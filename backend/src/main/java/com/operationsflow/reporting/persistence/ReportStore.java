package com.operationsflow.reporting.persistence;

import java.util.*;
import java.sql.Timestamp;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.operationsflow.reporting.api.ReportingContracts.Column;
import com.operationsflow.reporting.domain.*;

@Repository
public class ReportStore {
    private final NamedParameterJdbcTemplate jdbc;
    public ReportStore(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Spec(String select,String from,String where,String order,List<Column> columns,Map<String,Object> parameters,String dateBasis){}
    private Column text(String key,String label){return new Column(key,label,false);}
    private Column number(String key,String label){return new Column(key,label,true);}
    public Spec spec(ReportKind kind,ReportFilter f){
        f.validate(kind);var p=AnalyticsStore.parameters(f.range());p.put("warehouse",f.warehouseId());p.put("sku",f.skuId());p.put("status",f.status());p.put("query",f.query());p.put("threshold",f.threshold());
        String select,from,where,order,date="Created in selected date range; status is current";List<Column> columns;
        switch(kind){
            case ORDERS -> {
                select="o.id,o.order_number,o.status,o.total_units,o.created_at,o.updated_at";from="operationsflow.sales_order o";
                where="o.created_at>=:start and o.created_at<:end and strpos(lower(o.order_number),:query)>0"+status(f,"o.status")+
                    (f.warehouseId()==null?"":" and exists(select 1 from operationsflow.order_allocation a where a.order_id=o.id and a.warehouse_id=:warehouse)")+
                    (f.skuId()==null?"":" and exists(select 1 from operationsflow.order_line l where l.order_id=o.id and l.sku_id=:sku)");order="o.created_at desc,o.id";
                columns=List.of(text("id","Order ID"),text("order_number","Order"),text("status","Status"),number("total_units","Units"),text("created_at","Created (UTC)"),text("updated_at","Updated (UTC)"));
            }
            case INVENTORY,LOW_STOCK -> {
                select="b.warehouse_id,b.sku_id,w.code warehouse_code,s.code sku_code,p.name product_name,p.status product_status,w.status warehouse_status,b.on_hand,b.reserved,b.on_hand-b.reserved available,b.updated_at";
                from="operationsflow.inventory_balance b join operationsflow.warehouse w on w.id=b.warehouse_id join operationsflow.sku s on s.id=b.sku_id join operationsflow.product p on p.id=s.product_id";
                where="(strpos(lower(w.code),:query)>0 or strpos(lower(s.code),:query)>0 or strpos(lower(p.name),:query)>0)"+location(f,"b.warehouse_id","b.sku_id")+(kind==ReportKind.LOW_STOCK?" and p.status='ACTIVE' and w.status='ACTIVE' and b.on_hand-b.reserved<=:threshold":"");order="w.code,s.code,b.warehouse_id,b.sku_id";date="Current snapshot; date range does not apply";
                columns=List.of(text("warehouse_id","Warehouse ID"),text("sku_id","SKU ID"),text("warehouse_code","Warehouse"),text("sku_code","SKU"),text("product_name","Product"),text("product_status","Product status"),text("warehouse_status","Warehouse status"),number("on_hand","On hand"),number("reserved","Reserved"),number("available","Available"),text("updated_at","Updated (UTC)"));
            }
            case MOVEMENTS -> {
                select="m.id,w.code warehouse_code,s.code sku_code,m.type,m.on_hand_delta,m.reserved_delta,m.on_hand_after,m.reserved_after,m.created_at";
                from="operationsflow.inventory_movement m join operationsflow.warehouse w on w.id=m.warehouse_id join operationsflow.sku s on s.id=m.sku_id";
                where="m.created_at>=:start and m.created_at<:end and (strpos(lower(s.code),:query)>0 or strpos(lower(w.code),:query)>0)"+status(f,"m.type")+location(f,"m.warehouse_id","m.sku_id");order="m.created_at desc,m.id";date="Movement event date";
                columns=List.of(text("id","Movement ID"),text("warehouse_code","Warehouse"),text("sku_code","SKU"),text("type","Type"),number("on_hand_delta","On-hand delta"),number("reserved_delta","Reserved delta"),number("on_hand_after","On hand after"),number("reserved_after","Reserved after"),text("created_at","Occurred (UTC)"));
            }
            case FULFILLMENT -> {
                select="t.id,o.order_number,w.code warehouse_code,t.state,t.version,t.created_at,t.updated_at";
                from="operationsflow.fulfillment_task t join operationsflow.sales_order o on o.id=t.order_id join operationsflow.warehouse w on w.id=t.warehouse_id";
                where="t.created_at>=:start and t.created_at<:end and (strpos(lower(o.order_number),:query)>0 or strpos(lower(w.code),:query)>0)"+status(f,"t.state")+taskLocation(f);order="t.created_at desc,t.id";
                columns=List.of(text("id","Task ID"),text("order_number","Order"),text("warehouse_code","Warehouse"),text("state","State"),number("version","Version"),text("created_at","Created (UTC)"),text("updated_at","Updated (UTC)"));
            }
            case SHIPMENTS -> {
                select="s.id,o.order_number,w.code warehouse_code,s.state,s.carrier,s.tracking_reference,s.created_at,s.dispatched_at";
                from="operationsflow.shipment s join operationsflow.fulfillment_task t on t.id=s.task_id join operationsflow.sales_order o on o.id=t.order_id join operationsflow.warehouse w on w.id=t.warehouse_id";
                where="s.created_at>=:start and s.created_at<:end and (strpos(lower(o.order_number),:query)>0 or strpos(lower(s.carrier),:query)>0 or strpos(lower(s.tracking_reference),:query)>0)"+status(f,"s.state")+taskLocation(f);order="s.created_at desc,s.id";
                columns=List.of(text("id","Shipment ID"),text("order_number","Order"),text("warehouse_code","Warehouse"),text("state","State"),text("carrier","Carrier"),text("tracking_reference","Tracking reference"),text("created_at","Created (UTC)"),text("dispatched_at","Dispatched (UTC)"));
            }
            case AUDIT -> {
                select="a.id,a.actor_id,a.actor_kind,a.action,a.target_id,a.created_at";from="operationsflow.audit_event a";
                where="a.created_at>=:start and a.created_at<:end and strpos(lower(a.action),:query)>0"+status(f,"a.action");order="a.created_at desc,a.id";date="Audit event date; sensitive details excluded";
                columns=List.of(text("id","Event ID"),text("actor_id","Actor ID"),text("actor_kind","Actor kind"),text("action","Action"),text("target_id","Target ID"),text("created_at","Occurred (UTC)"));
            }
            default -> throw new IllegalStateException("Unhandled report kind");
        }
        return new Spec(select,from,where,order,columns,p,date);
    }
    private String status(ReportFilter f,String column){return f.status().isEmpty()?"":" and "+column+"=:status";}
    private String location(ReportFilter f,String warehouse,String sku){return (f.warehouseId()==null?"":" and "+warehouse+"=:warehouse")+(f.skuId()==null?"":" and "+sku+"=:sku");}
    private String taskLocation(ReportFilter f){return (f.warehouseId()==null?"":" and t.warehouse_id=:warehouse")+(f.skuId()==null?"":" and exists(select 1 from operationsflow.fulfillment_line l where l.task_id=t.id and l.sku_id=:sku)");}
    public long count(Spec s){return jdbc.queryForObject("select count(*) from "+s.from()+" where "+s.where(),s.parameters(),Long.class);}
    public List<Map<String,String>> rows(Spec s,int limit,long offset){
        var p=new HashMap<>(s.parameters());p.put("limit",limit);p.put("offset",offset);
        return jdbc.query("select "+s.select()+" from "+s.from()+" where "+s.where()+" order by "+s.order()+" limit :limit offset :offset",p,(r,n)->{
            Map<String,String> row=new LinkedHashMap<>();for(var col:s.columns()){Object value=r.getObject(col.key());row.put(col.key(),value==null?"":value instanceof Timestamp ts?ts.toInstant().toString():value instanceof java.time.OffsetDateTime time?time.toInstant().toString():value.toString());}return row;
        });
    }
}
