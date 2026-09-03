package com.mehmetberkan.tradecore.domain;

import com.mehmetberkan.tradecore.domain.enums.Side;

final class OrderPool {

    private final Order[] pool;
    private int freeCount;

    OrderPool(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        pool = new Order[capacity];
        for (int i = 0; i < capacity; i++) {
            pool[i] = Order.blank();
        }
        freeCount = capacity;
    }


    Order acquire(long sequence, Side side, long price, long quantity, long timestampNanos) {
        if (freeCount == 0) {
            throw new IllegalStateException("order pool exhausted, capacity=" + pool.length);
        }
        Order order = pool[--freeCount];
        pool[freeCount] = null;                 // sızıntıyı önle
        order.reset(sequence, side, price, quantity, timestampNanos);
        return order;
    }


    void release(Order order) {
        if (freeCount == pool.length) {
            throw new IllegalStateException("releasing more orders than the pool holds");
        }
        pool[freeCount++] = order;
    }

    int available() {
        return freeCount;
    }

    int capacity() {
        return pool.length;
    }
}