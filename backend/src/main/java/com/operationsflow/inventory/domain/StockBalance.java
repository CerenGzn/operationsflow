package com.operationsflow.inventory.domain;

import com.operationsflow.shared.domain.BusinessException;

/** Unit quantities; all mutations run on a locked persisted balance. */
public record StockBalance(long onHand, long reserved) {
    public static final long MAX_STOCK = 1_000_000_000_000L;
    public StockBalance {
        if (onHand < 0 || reserved < 0 || reserved > onHand || onHand > MAX_STOCK) {
            throw new BusinessException("INVALID_STOCK", "Stock must satisfy 0 <= reserved <= on-hand <= 1,000,000,000,000.");
        }
    }
    public long available() { return onHand - reserved; }
    public StockBalance receive(long quantity) { positive(quantity); return new StockBalance(onHand + quantity, reserved); }
    public StockBalance adjust(long delta) {
        if (delta == 0) throw new BusinessException("INVALID_STOCK", "An adjustment must change stock.");
        return new StockBalance(onHand + delta, reserved);
    }
    public StockBalance reserve(long quantity) {
        positive(quantity);
        if (quantity > available()) throw new BusinessException("INSUFFICIENT_STOCK", "Not enough available stock for the reservation.");
        return new StockBalance(onHand, reserved + quantity);
    }
    public StockBalance release(long quantity) { positive(quantity); return new StockBalance(onHand, reserved - quantity); }
    public StockBalance consume(long quantity) { positive(quantity); return new StockBalance(onHand - quantity, reserved - quantity); }
    private static void positive(long quantity) {
        if (quantity <= 0 || quantity > 1_000_000_000L) throw new BusinessException("INVALID_STOCK", "Quantity must be between 1 and 1,000,000,000.");
    }
}
