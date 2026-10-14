package com.operationsflow.inventory.domain;

import org.junit.jupiter.api.Test;
import com.operationsflow.shared.domain.*;
import static org.assertj.core.api.Assertions.*;

class WarehouseTest {
    @Test void inactiveWarehouseIsReadOnlyAndCodeSurvivesMetadataChanges() {
        var warehouse = new Warehouse("BER-01", "Berlin", "Example street 1", "Berlin", "10115", "DE");
        warehouse.update("Berlin North", "Example street 2", "Berlin", "10115", "DE", 0);
        assertThat(warehouse.getCode()).isEqualTo("BER-01");
        warehouse.deactivate(0);
        warehouse.deactivate(99);
        assertThat(warehouse.getStatus()).isEqualTo(RecordStatus.INACTIVE);
        assertThatThrownBy(() -> warehouse.update("Changed", "A", "B", "C", "DE", 0)).isInstanceOf(BusinessException.class);
    }
    @Test void staleDeactivationDoesNotChangeStatus() {
        var warehouse = new Warehouse("BER-02", "Berlin", "Example street 1", "Berlin", "10115", "DE");
        assertThatThrownBy(() -> warehouse.deactivate(5)).isInstanceOf(BusinessException.class);
        assertThat(warehouse.getStatus()).isEqualTo(RecordStatus.ACTIVE);
    }
}
