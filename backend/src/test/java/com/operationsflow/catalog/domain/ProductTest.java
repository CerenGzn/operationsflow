package com.operationsflow.catalog.domain;

import org.junit.jupiter.api.Test;
import com.operationsflow.shared.domain.*;
import static org.assertj.core.api.Assertions.*;

class ProductTest {
    @Test void deactivationIsIdempotentAndBlocksEditsAndNewSkus() {
        var product = new Product("Desk", "Synthetic catalog record");
        product.deactivate(0);
        product.deactivate(99);
        assertThat(product.getStatus()).isEqualTo(RecordStatus.INACTIVE);
        assertThatThrownBy(() -> product.update("Changed", "", 0)).isInstanceOf(BusinessException.class).hasMessageContaining("Inactive");
        assertThatThrownBy(product::touch).isInstanceOf(BusinessException.class);
    }
    @Test void staleChangesDoNotMutateProduct() {
        var product = new Product("Desk", "");
        assertThatThrownBy(() -> product.update("Changed", "", 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> product.deactivate(1)).isInstanceOf(BusinessException.class);
        assertThat(product.getName()).isEqualTo("Desk");
        assertThat(product.getStatus()).isEqualTo(RecordStatus.ACTIVE);
    }
}
