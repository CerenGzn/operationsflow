package com.operationsflow.fulfillment.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.operationsflow.fulfillment.api.FulfillmentContracts.*;
import com.operationsflow.fulfillment.domain.TaskState;
import com.operationsflow.shared.domain.BusinessException;

/** Task mutations are serialized by the owning order row, acquired by the application boundary. */
@Repository
public class FulfillmentStore {
    private final JdbcTemplate jdbc;
    public FulfillmentStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public UUID orderId(UUID task){return jdbc.query("select order_id from operationsflow.fulfillment_task where id=?",(r,n)->r.getObject(1,UUID.class),task).stream().findFirst().orElseThrow(()->new BusinessException("NOT_FOUND","Fulfillment task not found."));}
    public void plan(UUID order){
        jdbc.update("insert into operationsflow.fulfillment_task(id,order_id,warehouse_id) select gen_random_uuid(),order_id,warehouse_id from operationsflow.order_allocation where order_id=? group by order_id,warehouse_id",order);
        jdbc.update("insert into operationsflow.fulfillment_line(task_id,order_id,warehouse_id,sku_id,quantity) select t.id,a.order_id,a.warehouse_id,a.sku_id,a.quantity from operationsflow.fulfillment_task t join operationsflow.order_allocation a on a.order_id=t.order_id and a.warehouse_id=t.warehouse_id where t.order_id=?",order);
    }
    public List<TaskView> tasks(UUID order){return jdbc.queryForList("select id from operationsflow.fulfillment_task where order_id=? order by warehouse_id,id",UUID.class,order).stream().map(this::task).toList();}
    public TaskView task(UUID id){
        var lines=jdbc.query("select f.*,l.sku_code,l.product_name from operationsflow.fulfillment_line f join operationsflow.order_line l on l.order_id=f.order_id and l.sku_id=f.sku_id where task_id=? order by l.sku_code,f.sku_id",(r,n)->new LineView(r.getObject("sku_id",UUID.class),r.getString("sku_code"),r.getString("product_name"),r.getLong("quantity"),r.getLong("picked_quantity")),id);
        var pack=jdbc.query("select * from operationsflow.fulfillment_package where task_id=?",(r,n)->new PackageView(r.getObject("id",UUID.class),r.getTimestamp("packed_at").toInstant()),id).stream().findFirst().orElse(null);
        var shipment=jdbc.query(SHIPMENT_SELECT+" where s.task_id=?",FulfillmentStore::shipment,id).stream().findFirst().orElse(null);
        return jdbc.query("select t.*,o.order_number,w.code from operationsflow.fulfillment_task t join operationsflow.sales_order o on o.id=t.order_id join operationsflow.warehouse w on w.id=t.warehouse_id where t.id=?",(r,n)->new TaskView(id,r.getObject("order_id",UUID.class),r.getString("order_number"),r.getObject("warehouse_id",UUID.class),r.getString("code"),TaskState.valueOf(r.getString("state")),r.getLong("version"),lines,pack,shipment),id).stream().findFirst().orElseThrow(()->new BusinessException("NOT_FOUND","Fulfillment task not found."));
    }
    public void pick(UUID task,UUID sku,long quantity){jdbc.update("update operationsflow.fulfillment_line set picked_quantity=? where task_id=? and sku_id=?",quantity,task,sku);}
    public void state(UUID task,TaskState state){jdbc.update("update operationsflow.fulfillment_task set state=?,version=version+1,updated_at=clock_timestamp() where id=?",state.name(),task);}
    public UUID pack(UUID task){UUID id=UUID.randomUUID();jdbc.update("insert into operationsflow.fulfillment_package(id,task_id) values (?,?)",id,task);return id;}
    public UUID shipment(UUID task,UUID pack,String carrier,String tracking){UUID id=UUID.randomUUID();jdbc.update("insert into operationsflow.shipment(id,task_id,package_id,carrier,tracking_reference) values (?,?,?,?,?)",id,task,pack,carrier,tracking);return id;}
    public void ship(UUID task){jdbc.update("update operationsflow.shipment set state='DISPATCHED',dispatched_at=clock_timestamp() where task_id=? and state='READY'",task);state(task,TaskState.DISPATCHED);}
    public void cancel(UUID task){jdbc.update("update operationsflow.shipment set state='CANCELLED' where task_id=? and state='READY'",task);state(task,TaskState.CANCELLED);}
    public static final String SHIPMENT_SELECT="select s.*,t.order_id,t.warehouse_id,o.order_number,w.code from operationsflow.shipment s join operationsflow.fulfillment_task t on t.id=s.task_id join operationsflow.sales_order o on o.id=t.order_id join operationsflow.warehouse w on w.id=t.warehouse_id";
    public static ShipmentView shipment(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new ShipmentView(r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getObject("order_id",UUID.class),r.getString("order_number"),r.getObject("warehouse_id",UUID.class),r.getString("code"),r.getObject("package_id",UUID.class),r.getString("carrier"),r.getString("tracking_reference"),r.getString("state"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("dispatched_at")==null?null:r.getTimestamp("dispatched_at").toInstant());}
}
