package com.operationsflow.orders.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.operationsflow.orders.domain.OrderStatus;

/** Deliberately excludes customers, destinations, tracking and audit actor/detail fields. */
public record OrderTimeline(UUID orderId, OrderStatus status, List<Milestone> milestones) {
    public enum Progress { COMPLETED, IN_PROGRESS, PENDING, NOT_REACHED }
    public record Milestone(String code, String label, Progress progress, Instant occurredAt, String detail) {}
}
