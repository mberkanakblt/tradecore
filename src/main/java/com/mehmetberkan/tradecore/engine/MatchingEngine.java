package com.mehmetberkan.tradecore.engine;

import com.mehmetberkan.tradecore.domain.OrderBook;
import com.mehmetberkan.tradecore.domain.Trade;
import com.mehmetberkan.tradecore.domain.enums.Side;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class MatchingEngine {

    private final Map<String, OrderBook> books = new HashMap<>();
    private final AtomicLong sequenceGenerator = new AtomicLong();

    public List<Trade> submit(String symbol, Side side, long price, long quantity) {
        long sequence = sequenceGenerator.incrementAndGet();
        return book(symbol).submit(sequence, side, price, quantity);
    }

    public boolean cancel(String symbol, long sequence) {
        OrderBook orderBook = books.get(symbol);
        if (orderBook == null) {
            return false;
        }
        return orderBook.cancel(sequence);
    }

    public OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, k -> new OrderBook());
    }
}