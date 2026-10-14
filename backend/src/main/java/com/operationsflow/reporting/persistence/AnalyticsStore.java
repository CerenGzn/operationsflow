package com.operationsflow.reporting.persistence;

import java.util.*;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.operationsflow.reporting.domain.DateWindow;
import com.operationsflow.reporting.api.ReportingContracts.*;

@Repository
public class AnalyticsStore {
    private final NamedParameterJdbcTemplate jdbc;
    public AnalyticsStore(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public void limit(){jdbc.getJdbcTemplate().execute("set local statement_timeout='5s'");}
    public Instant asOf(){return jdbc.getJdbcTemplate().queryForObject("select transaction_timestamp()",Timestamp.class).toInstant();}
    public static Map<String,Object> parameters(DateWindow w){var p=new HashMap<String,Object>();p.put("start",Timestamp.from(w.start()));p.put("end",Timestamp.from(w.end()));p.put("from",w.from().toString());p.put("to",w.to().toString());p.put("zone",w.zone());return p;}
    public Map<String,String> kpis(int threshold){
        return jdbc.queryForObject("""
            select * from
            (select count(*) total_orders,count(*) filter(where status in ('RESERVED','PROCESSING','READY_TO_SHIP')) open_orders,
              count(*) filter(where status='CANCELLED') cancelled_orders,count(*) filter(where status='SHIPPED') shipped_orders,
              count(*) filter(where status='RESERVED') awaiting_fulfillment from operationsflow.sales_order) o
            cross join (select coalesce(sum(on_hand),0) inventory_units,coalesce(sum(reserved),0) reserved_units,coalesce(sum(on_hand-reserved),0) available_units from operationsflow.inventory_balance) i
            cross join (select count(*) active_warehouses from operationsflow.warehouse where status='ACTIVE') w
            cross join (select count(*) active_products from operationsflow.product where status='ACTIVE') p
            cross join (select count(*) shipments_awaiting_dispatch from operationsflow.shipment where state='READY') s
            cross join (select count(*) low_stock from operationsflow.inventory_balance b join operationsflow.sku k on k.id=b.sku_id join operationsflow.product p on p.id=k.product_id join operationsflow.warehouse w on w.id=b.warehouse_id where p.status='ACTIVE' and w.status='ACTIVE' and b.on_hand-b.reserved<=:threshold) l
            """,Map.of("threshold",threshold),(r,n)->{var result=new LinkedHashMap<String,String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)result.put(r.getMetaData().getColumnLabel(i),r.getString(i));return result;});
    }
    private List<Point> points(String sql,Map<String,Object> p){return jdbc.query(sql,p,(r,n)->new Point(r.getString("label"),r.getString("value"),r.getString("secondary")));}
    private static final String DAYS="with days as (select generate_series(cast(:from as timestamp),cast(:to as timestamp),interval '1 day')::date day), values_by_day as (";
    private static final String DAILY_END=") select d.day::text label,coalesce(v.value,0)::text value,coalesce(v.secondary,0)::text secondary from days d left join values_by_day v on v.day=d.day order by d.day";
    public List<Point> orderVolume(Map<String,Object> p){return points(DAYS+"select timezone(:zone,created_at)::date day,count(*) value,0 secondary from operationsflow.sales_order where created_at>=:start and created_at<:end group by 1"+DAILY_END,p);}
    public List<Point> orderStatus(Map<String,Object> p){return points("""
        select labels.state label,count(o.id)::text value,'0' secondary from
        (values ('RESERVED',1),('PROCESSING',2),('READY_TO_SHIP',3),('SHIPPED',4),('CANCELLED',5)) labels(state,position)
        left join operationsflow.sales_order o on o.status=labels.state and o.created_at>=:start and o.created_at<:end
        group by labels.state,labels.position order by labels.position
        """,p);}
    public List<Point> inventoryByWarehouse(){return points("""
        with amounts as (select w.id,w.code,coalesce(sum(b.on_hand),0) on_hand,coalesce(sum(b.reserved),0) reserved
          from operationsflow.warehouse w left join operationsflow.inventory_balance b on b.warehouse_id=w.id group by w.id,w.code),
        ranked as (select *,row_number() over(order by on_hand desc,code,id) position from amounts)
        select case when position<=12 then code else 'Other warehouses' end label,sum(on_hand)::text value,sum(reserved)::text secondary
        from ranked group by case when position<=12 then code else 'Other warehouses' end order by min(position)
        """,Map.of());}
    public List<Point> movements(Map<String,Object> p){return points(DAYS+"select timezone(:zone,created_at)::date day,sum(on_hand_delta) value,sum(reserved_delta) secondary from operationsflow.inventory_movement where created_at>=:start and created_at<:end group by 1"+DAILY_END,p);}
    public List<Point> throughput(Map<String,Object> p){return points(DAYS+"select timezone(:zone,dispatched_at)::date day,count(*) value,0 secondary from operationsflow.shipment where dispatched_at>=:start and dispatched_at<:end group by 1"+DAILY_END,p);}
    public List<Point> fulfillmentStatus(Map<String,Object> p){return points("""
        select labels.state label,count(t.id)::text value,'0' secondary from
        (values ('PENDING',1),('PICKING',2),('PICKED',3),('PACKED',4),('READY_TO_SHIP',5),('DISPATCHED',6),('CANCELLED',7)) labels(state,position)
        left join operationsflow.fulfillment_task t on t.state=labels.state and t.created_at>=:start and t.created_at<:end
        group by labels.state,labels.position order by labels.position
        """,p);}
    public List<Point> topSkus(Map<String,Object> p){return points("""
        select l.sku_code label,count(*)::text value,sum(l.quantity)::text secondary from operationsflow.order_line l
        join operationsflow.sales_order o on o.id=l.order_id where o.created_at>=:start and o.created_at<:end and o.status<>'CANCELLED'
        group by l.sku_id,l.sku_code order by count(*) desc,l.sku_code,l.sku_id limit 10
        """,p);}
    public List<Activity> activity(Map<String,Object> p){return jdbc.query("""
        select type,reference,occurred_at from (
          select 'ORDER_CREATED' type,order_number reference,created_at occurred_at,id from operationsflow.sales_order where created_at>=:start and created_at<:end
          union all select 'ORDER_CANCELLED',order_number,updated_at,id from operationsflow.sales_order where status='CANCELLED' and updated_at>=:start and updated_at<:end
          union all select 'SHIPMENT_PREPARED',o.order_number||' / '||w.code,s.created_at,s.id from operationsflow.shipment s join operationsflow.fulfillment_task t on t.id=s.task_id join operationsflow.sales_order o on o.id=t.order_id join operationsflow.warehouse w on w.id=t.warehouse_id where s.created_at>=:start and s.created_at<:end
          union all select 'SHIPMENT_DISPATCHED',o.order_number||' / '||w.code,s.dispatched_at,s.id from operationsflow.shipment s join operationsflow.fulfillment_task t on t.id=s.task_id join operationsflow.sales_order o on o.id=t.order_id join operationsflow.warehouse w on w.id=t.warehouse_id where s.dispatched_at>=:start and s.dispatched_at<:end
        ) events order by occurred_at desc,id,type limit 10
        """,p,(r,n)->new Activity(r.getString("type"),r.getString("reference"),r.getTimestamp("occurred_at").toInstant()));}
}
