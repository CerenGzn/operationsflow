package com.operationsflow.fulfillment.application;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.operationsflow.fulfillment.api.FulfillmentContracts.*;
import com.operationsflow.fulfillment.domain.TaskState;
import com.operationsflow.fulfillment.persistence.FulfillmentStore;
import com.operationsflow.orders.application.OrderFulfillmentAccess;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.api.PageResponse;
import com.operationsflow.shared.domain.BusinessException;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class FulfillmentQueries {
    private final FulfillmentStore store;private final OrderFulfillmentAccess orders;private final NamedParameterJdbcTemplate jdbc;
    public FulfillmentQueries(FulfillmentStore store,OrderFulfillmentAccess orders,NamedParameterJdbcTemplate jdbc){this.store=store;this.orders=orders;this.jdbc=jdbc;}
    public OrderView order(UUID id){var order=orders.get(id);return new OrderView(id,order.orderNumber(),order.status(),store.tasks(id));}
    public PageResponse<TaskView> tasks(UUID warehouse,TaskState state,String query,int page,int size){
        ListQuery.page(page,size,"id","asc",Set.of("id"));String term=ListQuery.search(query).toLowerCase(Locale.ROOT);
        var p=new HashMap<String,Object>();p.put("warehouse",warehouse);p.put("state",state==null?null:state.name());p.put("term",term);p.put("size",size);p.put("offset",(long)page*size);
        String from=" from operationsflow.fulfillment_task t join operationsflow.sales_order o on o.id=t.order_id where strpos(lower(o.order_number),:term)>0"+(warehouse==null?"":" and t.warehouse_id=:warehouse")+(state==null?"":" and t.state=:state");
        long total=jdbc.queryForObject("select count(*)"+from,p,Long.class);
        var rows=jdbc.queryForList("select t.id"+from+" order by t.created_at,t.id limit :size offset :offset",p,UUID.class).stream().map(store::task).toList();
        return new PageResponse<>(rows,page,size,total,(int)Math.ceil((double)total/size));
    }
    public PageResponse<ShipmentView> shipments(UUID warehouse,String state,int page,int size){
        ListQuery.page(page,size,"id","asc",Set.of("id"));if(state!=null&&!Set.of("READY","DISPATCHED","CANCELLED").contains(state))throw new BusinessException("INVALID_QUERY","Unknown shipment state.");
        var p=new HashMap<String,Object>();p.put("warehouse",warehouse);p.put("state",state);p.put("size",size);p.put("offset",(long)page*size);
        String where=" where true"+(warehouse==null?"":" and t.warehouse_id=:warehouse")+(state==null?"":" and s.state=:state");
        long total=jdbc.queryForObject("select count(*) from operationsflow.shipment s join operationsflow.fulfillment_task t on t.id=s.task_id"+where,p,Long.class);
        var rows=jdbc.query(FulfillmentStore.SHIPMENT_SELECT+where+" order by s.created_at desc,s.id limit :size offset :offset",p,FulfillmentStore::shipment);
        return new PageResponse<>(rows,page,size,total,(int)Math.ceil((double)total/size));
    }
}
