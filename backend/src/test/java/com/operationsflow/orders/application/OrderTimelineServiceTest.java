package com.operationsflow.orders.application;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.operationsflow.orders.persistence.OrderTimelineStore.Facts;
import com.operationsflow.orders.domain.OrderStatus;
import static org.assertj.core.api.Assertions.*;

class OrderTimelineServiceTest {
    @Test void legacyCancellationHasNoInventedTimestampOrFulfillmentHistory() {
        var created=Instant.parse("2026-01-01T00:00:00Z");
        var result=OrderTimelineService.present(new Facts(UUID.randomUUID(),OrderStatus.CANCELLED,created,null,0,0,null,0,null,0,null,null));
        assertThat(result.milestones()).extracting(m->m.code()).containsExactly("CONFIRMED","PREPARING","PACKED","SHIPPED","CANCELLED");
        assertThat(result.milestones().getLast().occurredAt()).isNull();
        assertThat(result.milestones()).filteredOn(m->m.code().equals("SHIPPED")||m.code().equals("PACKED"))
            .allMatch(m->m.progress().name().equals("NOT_REACHED")&&m.occurredAt()==null);
    }
    @Test void cancelledPreparedShipmentIsHistoricalPreparationNotSuccessfulShipping() {
        var at=Instant.parse("2026-01-01T00:00:00Z");
        var result=OrderTimelineService.present(new Facts(UUID.randomUUID(),OrderStatus.CANCELLED,at,at,1,1,at,1,at,0,null,at));
        assertThat(result.milestones()).filteredOn(m->m.code().equals("SHIPMENT_PREPARED")).allMatch(m->m.progress().name().equals("COMPLETED"));
        assertThat(result.milestones()).filteredOn(m->m.code().equals("SHIPPED")).allMatch(m->m.occurredAt()==null&&m.progress().name().equals("NOT_REACHED"));
    }
}
