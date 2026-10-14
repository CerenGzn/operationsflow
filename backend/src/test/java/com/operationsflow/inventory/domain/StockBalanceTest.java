package com.operationsflow.inventory.domain;

import org.junit.jupiter.api.Test;
import com.operationsflow.shared.domain.BusinessException;
import static org.assertj.core.api.Assertions.*;

class StockBalanceTest {
    @Test void availableIsDerivedAndReleaseDoesNotCreateStock() {
        var stock = new StockBalance(10, 0).reserve(7);
        assertThat(stock.available()).isEqualTo(3);
        assertThat(stock.release(7)).isEqualTo(new StockBalance(10, 0));
    }
    @Test void consumptionDeductsBothCounters() {
        assertThat(new StockBalance(10, 7).consume(7)).isEqualTo(new StockBalance(3, 0));
    }
    @Test void adjustmentsCannotDamageReservationsOrOverflowStockLimit() {
        assertThatThrownBy(() -> new StockBalance(10, 7).adjust(-4)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new StockBalance(10, 0).adjust(0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new StockBalance(StockBalance.MAX_STOCK, 0).receive(1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new StockBalance(10, 0).reserve(11)).isInstanceOf(BusinessException.class);
    }
    @Test void invalidPersistedCountersAreRejected() {
        assertThatThrownBy(() -> new StockBalance(-1, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new StockBalance(1, -1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new StockBalance(1, 2)).isInstanceOf(BusinessException.class);
    }
    @Test void terminalTransitionsAreIdempotentButCannotBeReversed() {
        assertThat(ReservationState.ACTIVE.transitionTo(ReservationState.RELEASED)).isTrue();
        assertThat(ReservationState.RELEASED.transitionTo(ReservationState.RELEASED)).isFalse();
        assertThat(ReservationState.CONSUMED.transitionTo(ReservationState.CONSUMED)).isFalse();
        assertThatThrownBy(() -> ReservationState.CONSUMED.transitionTo(ReservationState.RELEASED)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReservationState.RELEASED.transitionTo(ReservationState.CONSUMED)).isInstanceOf(BusinessException.class);
    }
}
