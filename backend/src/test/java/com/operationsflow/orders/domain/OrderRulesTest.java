package com.operationsflow.orders.domain;

import java.util.*;
import org.junit.jupiter.api.Test;
import com.operationsflow.inventory.domain.OrderAllocator;
import com.operationsflow.inventory.domain.StockLine;
import com.operationsflow.inventory.domain.OrderAllocator.*;
import com.operationsflow.shared.domain.BusinessException;
import static org.assertj.core.api.Assertions.*;

class OrderRulesTest {
    private final UUID sku = UUID.randomUUID();
    private final Warehouse first = new Warehouse(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),"A");
    private final Warehouse second = new Warehouse(UUID.fromString("00000000-0000-0000-0000-000000000001"),"B");
    @Test void singleWarehouseWinsEvenWhenEarlierWarehouseHasPartialStock() {
        var result = OrderAllocator.allocate(List.of(new Demand(sku,5)),List.of(second,first),List.of(new StockLine(first.id(),sku,2),new StockLine(second.id(),sku,8)));
        assertThat(result).containsExactly(new StockLine(second.id(),sku,5));
    }
    @Test void splitUsesBusinessCodePriorityAndReturnsUuidLockOrder() {
        var result = OrderAllocator.allocate(List.of(new Demand(sku,5)),List.of(second,first),List.of(new StockLine(second.id(),sku,4),new StockLine(first.id(),sku,3)));
        assertThat(result).containsExactly(new StockLine(second.id(),sku,2),new StockLine(first.id(),sku,3));
    }
    @Test void firstCompleteWarehouseIsDeterministic() {
        assertThat(OrderAllocator.allocate(List.of(new Demand(sku,5)),List.of(second,first),List.of(new StockLine(second.id(),sku,5),new StockLine(first.id(),sku,5))))
            .containsExactly(new StockLine(first.id(),sku,5));
    }
    @Test void missingBalancesAreZeroAndInsufficientDemandFails() {
        assertThatThrownBy(() -> OrderAllocator.allocate(List.of(new Demand(sku,1)),List.of(first),List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OrderAllocator.allocate(List.of(new Demand(sku,1)),List.of(),List.of())).isInstanceOf(BusinessException.class);
    }
    @Test void cancellationAllowsUnshippedWorkButNeverShippedStock() {
        assertThat(OrderStatus.RESERVED.cancel()).isTrue(); assertThat(OrderStatus.CANCELLED.cancel()).isFalse();
        assertThat(OrderStatus.PROCESSING.cancel()).isTrue(); assertThat(OrderStatus.READY_TO_SHIP.cancel()).isTrue();
        for (var state : List.of(OrderStatus.PARTIALLY_SHIPPED,OrderStatus.SHIPPED))
            assertThatThrownBy(state::cancel).isInstanceOf(BusinessException.class);
    }
}
