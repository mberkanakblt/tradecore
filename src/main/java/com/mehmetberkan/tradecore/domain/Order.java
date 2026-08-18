package com.mehmetberkan.tradecore.domain;

import com.mehmetberkan.tradecore.domain.enums.OrderStatus;
import com.mehmetberkan.tradecore.domain.enums.Side;

public class Order {

    private final long sequence;
    private final Side side;
    private final long price;
    private final long quantity;
    private final long timestampNanos;

    private long remainingQuantity;
    private OrderStatus status;

    public Order(long sequence, Side side, long price, long quantity, long timestampNanos) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be greater than 0");
        if (price <= 0) throw new IllegalArgumentException("Price must be greater than 0");
        this.sequence = sequence;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.timestampNanos = timestampNanos;
        this.remainingQuantity = quantity;
        this.status = OrderStatus.NEW;
    }

    public void fill(long quantity) {
        if (quantity <= 0 || quantity > remainingQuantity)
            throw new IllegalArgumentException("invalid fill: requested=" + quantity + ", remaining=" + remainingQuantity);
        remainingQuantity -= quantity;
        status = (remainingQuantity == 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel() {
        if (status == OrderStatus.FILLED) {
            throw new IllegalStateException("cannot cancel a filled order");
        }
        status = OrderStatus.CANCELLED;
    }

    public boolean isActive() {
        return remainingQuantity > 0
                && status != OrderStatus.CANCELLED;
    }

    public long getSequence() {
        return sequence;
    }

    public Side getSide() {
        return side;
    }

    public long getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public OrderStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return sequence == other.sequence;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(sequence);
    }
}
