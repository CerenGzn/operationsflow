package com.operationsflow.shared.application;

import java.util.Set;
import org.junit.jupiter.api.Test;
import com.operationsflow.shared.domain.BusinessException;
import static org.assertj.core.api.Assertions.*;

class ListQueryTest {
    @Test void rejectsUnboundedPagesAndUnapprovedSorting() {
        assertThatThrownBy(() -> ListQuery.page(0, 101, "name", "asc", Set.of("name"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ListQuery.page(-1, 20, "name", "asc", Set.of("name"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ListQuery.page(0, 20, "password", "asc", Set.of("name"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ListQuery.page(0, 20, "name", "invalid", Set.of("name"))).isInstanceOf(BusinessException.class);
        assertThat(ListQuery.page(0, 20, "name", "asc", Set.of("name")).getSort().getOrderFor("id")).isNotNull();
    }
}
