package com.mehmetberkan.tradecore.domain;

import com.mehmetberkan.tradecore.domain.enums.Side;

import java.util.*;

public final class OrderBook {

    private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> orderIndex = new HashMap<>();

    private long tradeIdSequence = 0;
    private long cancelledQuantity = 0;

    public List<Trade> submit(Order order) {
        List<Trade> trades = null;

        boolean isBuy = (order.getSide() == Side.BUY);
        TreeMap<Long, ArrayDeque<Order>> counterBook = isBuy ? asks : bids;

        long now = System.nanoTime();

        while (order.getRemainingQuantity() > 0 && !counterBook.isEmpty()) {

            long bestPrice = counterBook.firstKey();

            if (isBuy ? bestPrice > order.getPrice() : bestPrice < order.getPrice()) {
                break;
            }

            ArrayDeque<Order> queue = counterBook.get(bestPrice);

            while (!queue.isEmpty() && order.getRemainingQuantity() > 0) {
                Order resting = queue.peekFirst();

                if (!resting.isActive()) {
                    queue.pollFirst();
                    continue;
                }

                long qty = Math.min(order.getRemainingQuantity(), resting.getRemainingQuantity());

                order.fill(qty);
                resting.fill(qty);

                if(trades == null) {
                    trades = new ArrayList<>(4);
                }

                trades.add(new Trade(
                        ++tradeIdSequence,
                        isBuy ? order.getSequence()   : resting.getSequence(),
                        isBuy ? resting.getSequence() : order.getSequence(),
                        bestPrice,
                        qty,
                        now
                ));

                if (resting.getRemainingQuantity() == 0) {
                    queue.pollFirst();
                    orderIndex.remove(resting.getSequence());
                }
            }

            if (queue.isEmpty()) {
                counterBook.remove(bestPrice);
            }
        }

        if (order.getRemainingQuantity() > 0) {
            addOrder(order);
        }

        return trades == null ? Collections.emptyList() : trades;
    }

    public boolean cancel(long sequence) {
        Order order = orderIndex.remove(sequence);
        if (order == null) return false;
        cancelledQuantity += order.getRemainingQuantity();
        order.cancel();
        return true;
    }

    public long bestBid() {
        return bids.isEmpty() ? Long.MIN_VALUE : bids.firstKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? Long.MAX_VALUE : asks.firstKey();
    }

    /** Verilen fiyat seviyesindeki toplam aktif miktar. Test ve market data için. */
    public long quantityAt(Side side, long price) {
        ArrayDeque<Order> queue = (side == Side.BUY ? bids : asks).get(price);
        if (queue == null) return 0;
        long total = 0;
        for (Order o : queue) {
            if (o.isActive()) total += o.getRemainingQuantity();
        }
        return total;
    }

    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }

    public int activeOrderCount() {
        return orderIndex.size();
    }

    public long totalRestingQuantity() {
        long total = 0;
        for (Order o : orderIndex.values()) {
            total += o.getRemainingQuantity();
        }
        return total;
    }

    public long cancelledQuantity() {
        return cancelledQuantity;
    }

    public void validateInvariants() {
        if (bestBid() >= bestAsk()) {
            throw new IllegalStateException(
                    "book is crossed: bid=" + bestBid() + " ask=" + bestAsk());
        }

        long activeInBook = checkSide(bids) + checkSide(asks);
        if (activeInBook != orderIndex.size()) {
            throw new IllegalStateException(
                    "book/index mismatch: book=" + activeInBook + " index=" + orderIndex.size());
        }

        for (Order o : orderIndex.values()) {
            if (!o.isActive()) {
                throw new IllegalStateException("inactive order in index: " + o.getSequence());
            }
        }
    }

    private void addOrder(Order order) {
        TreeMap<Long, ArrayDeque<Order>> book = (order.getSide() == Side.BUY) ? bids : asks;
        book.computeIfAbsent(order.getPrice(), p -> new ArrayDeque<>()).addLast(order);
        orderIndex.put(order.getSequence(), order);
    }

    /** Bir tarafı doğrular ve içindeki aktif emir sayısını döner. */
    private long checkSide(TreeMap<Long, ArrayDeque<Order>> book) {
        long active = 0;
        for (Map.Entry<Long, ArrayDeque<Order>> e : book.entrySet()) {
            if (e.getValue().isEmpty()) {
                throw new IllegalStateException("empty price level: " + e.getKey());
            }
            for (Order o : e.getValue()) {
                if (o.getPrice() != e.getKey()) {
                    throw new IllegalStateException("order at wrong price level: " + o.getSequence());
                }
                if (o.isActive()) active++;
            }
        }
        return active;
    }
}