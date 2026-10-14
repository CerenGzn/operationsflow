package com.operationsflow.shared.application;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.operationsflow.shared.domain.BusinessException;

public final class ListQuery {
    private ListQuery() {}
    public static PageRequest page(int page, int size, String sort, String direction, Set<String> allowed) {
        if (page < 0 || size < 1 || size > 100 || !allowed.contains(sort) ||
                !(direction.equals("asc") || direction.equals("desc"))) {
            throw new BusinessException("INVALID_QUERY", "Use page >= 0, size 1–100, an allowed sort field, and direction asc or desc.");
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(direction), sort).and(Sort.by("id")));
    }
    public static String search(String value) {
        String term = value == null ? "" : value.strip();
        if (term.length() > 100) throw new BusinessException("INVALID_QUERY", "Search must be at most 100 characters.");
        return term;
    }
}
