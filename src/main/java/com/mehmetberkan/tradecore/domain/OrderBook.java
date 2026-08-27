package com.mehmetberkan.tradecore.domain;

import com.mehmetberkan.tradecore.domain.enums.Side;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OrderBook {

    private static final long DEFAULT_BASE_PRICE = 0;
    private static final long DEFAULT_TICK_SIZE = 100;
    private static final int DEFAULT_LEVEL_COUNT = 10_000;
    private static final int DEFAULT_POOL_CAPACITY = 1 << 20;   // 1_048_576

    private final long basePrice;
    private final long tickSize;
    private final int levelCount;

    private final PriceLevel[] bidLevels;
    private final PriceLevel[] askLevels;

    private int bestBidIndex;
    private int bestAskIndex;

    private final Long2ObjectHashMap<Order> orderIndex = new Long2ObjectHashMap<>();
    private final OrderPool orderPool;

    private long tradeIdSequence;
    private long cancelledQuantity;

    public OrderBook() {
        this(DEFAULT_BASE_PRICE, DEFAULT_TICK_SIZE, DEFAULT_LEVEL_COUNT, DEFAULT_POOL_CAPACITY);
    }

    public OrderBook(long basePrice, long tickSize, int levelCount) {
        this(basePrice, tickSize, levelCount, DEFAULT_POOL_CAPACITY);
    }

    public OrderBook(long basePrice, long tickSize, int levelCount, int poolCapacity) {
        if (tickSize <= 0) throw new IllegalArgumentException("tickSize must be positive");
        if (levelCount <= 0) throw new IllegalArgumentException("levelCount must be positive");

        this.basePrice = basePrice;
        this.tickSize = tickSize;
        this.levelCount = levelCount;

        this.bidLevels = new PriceLevel[levelCount];
        this.askLevels = new PriceLevel[levelCount];
        for (int i = 0; i < levelCount; i++) {
            bidLevels[i] = new PriceLevel();
            askLevels[i] = new PriceLevel();
        }

        this.bestBidIndex = -1;
        this.bestAskIndex = levelCount;

        this.orderPool = new OrderPool(poolCapacity);
    }

    /**
     * Emri motora verir. Oluşan trade'ler out buffer'ına yazılır.
     * Order nesnesi havuzdan alınır ve gerektiğinde havuza iade edilir.
     */
    public void submit(long sequence, Side side, long price, long quantity, TradeBuffer out) {
        out.reset();
        out.timestamp(System.nanoTime());

        int limitIndex = toIndex(price);

        Order order = orderPool.acquire(sequence, side, price, quantity, 0L);

        if (side == Side.BUY) {
            matchAgainstAsks(order, limitIndex, out);
        } else {
            matchAgainstBids(order, limitIndex, out);
        }

        if (order.getRemainingQuantity() > 0) {
            addOrder(order, limitIndex);
        } else {
            orderPool.release(order);   // hiç deftere girmedi
        }
    }

    public boolean cancel(long sequence) {
        Order order = orderIndex.remove(sequence);
        if (order == null) return false;

        cancelledQuantity += order.getRemainingQuantity();

        int index = toIndex(order.getPrice());
        boolean isBuy = (order.getSide() == Side.BUY);
        PriceLevel level = isBuy ? bidLevels[index] : askLevels[index];

        level.unlink(order);
        order.cancel();

        if (level.isEmpty()) {
            if (isBuy && index == bestBidIndex) {
                advanceBestBid();
            } else if (!isBuy && index == bestAskIndex) {
                advanceBestAsk();
            }
        }

        orderPool.release(order);
        return true;
    }

    public long bestBid() {
        return bestBidIndex < 0 ? Long.MIN_VALUE : toPrice(bestBidIndex);
    }

    public long bestAsk() {
        return bestAskIndex >= levelCount ? Long.MAX_VALUE : toPrice(bestAskIndex);
    }

    /**
     * Bir fiyat seviyesindeki toplam kalan miktar. Seviye sayacı tutulduğu için O(1).
     */
    public long quantityAt(Side side, long price) {
        int index = toIndex(price);
        return (side == Side.BUY ? bidLevels[index] : askLevels[index]).totalQuantity;
    }

    /**
     * Bir fiyat seviyesindeki emir sayısı.
     */
    public int orderCountAt(Side side, long price) {
        int index = toIndex(price);
        return (side == Side.BUY ? bidLevels[index] : askLevels[index]).orderCount;
    }

    /**
     * Bir emrin defterde bekleyip beklemediği.
     */
    public boolean isResting(long sequence) {
        return orderIndex.containsKey(sequence);
    }

    /**
     * Defterde bekleyen bir emrin kalan miktarı, yoksa -1.
     */
    public long remainingQuantityOf(long sequence) {
        Order order = orderIndex.get(sequence);
        return order == null ? -1 : order.getRemainingQuantity();
    }

    public boolean isEmpty() {
        return bestBidIndex < 0 && bestAskIndex >= levelCount;
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

    public int poolAvailable() {
        return orderPool.available();
    }

    /**
     * Test ve dış kullanım için. Her çağrıda allocation yapar
     */
    public List<Trade> submit(long sequence, Side side, long price, long quantity) {
        TradeBuffer buffer = new TradeBuffer();
        submit(sequence, side, price, quantity, buffer);
        if (buffer.isEmpty()) return Collections.emptyList();
        List<Trade> trades = new ArrayList<>(buffer.count());
        for (int i = 0; i < buffer.count(); i++) {
            trades.add(buffer.toTrade(i));
        }
        return trades;
    }

    /**
     * Gelen BUY emrini satıcılarla eşleştirir: en ucuz satıcıdan başlayıp yukarı yürür.
     */
    private void matchAgainstAsks(Order order, int limitIndex, TradeBuffer out) {
        while (order.getRemainingQuantity() > 0
                && bestAskIndex < levelCount
                && bestAskIndex <= limitIndex) {

            PriceLevel level = askLevels[bestAskIndex];
            long price = toPrice(bestAskIndex);

            fillLevel(order, level, price, true, out);

            if (level.isEmpty()) {
                advanceBestAsk();
            }
        }
    }

    /**
     * Gelen SELL emrini alıcılarla eşleştirir: en yüksek alıcıdan başlayıp aşağı iner.
     */
    private void matchAgainstBids(Order order, int limitIndex, TradeBuffer out) {
        while (order.getRemainingQuantity() > 0
                && bestBidIndex >= 0
                && bestBidIndex >= limitIndex) {

            PriceLevel level = bidLevels[bestBidIndex];
            long price = toPrice(bestBidIndex);

            fillLevel(order, level, price, false, out);

            if (level.isEmpty()) {
                advanceBestBid();
            }
        }
    }

    private void fillLevel(Order order, PriceLevel level, long price,
                           boolean incomingIsBuy, TradeBuffer out) {

        while (order.getRemainingQuantity() > 0 && level.head != null) {
            Order resting = level.head;

            long qty = Math.min(order.getRemainingQuantity(), resting.getRemainingQuantity());

            order.fill(qty);
            resting.fill(qty);
            level.reduceQuantity(qty);

            out.add(
                    ++tradeIdSequence,
                    incomingIsBuy ? order.getSequence() : resting.getSequence(),
                    incomingIsBuy ? resting.getSequence() : order.getSequence(),
                    price,
                    qty
            );

            if (resting.getRemainingQuantity() == 0) {
                level.unlink(resting);
                orderIndex.remove(resting.getSequence());
                orderPool.release(resting);
            }
        }
    }

    private void addOrder(Order order, int index) {
        if (order.getSide() == Side.BUY) {
            bidLevels[index].addLast(order);
            if (index > bestBidIndex) bestBidIndex = index;
        } else {
            askLevels[index].addLast(order);
            if (index < bestAskIndex) bestAskIndex = index;
        }
        orderIndex.put(order.getSequence(), order);
    }

    private void advanceBestBid() {
        while (bestBidIndex >= 0 && bidLevels[bestBidIndex].isEmpty()) {
            bestBidIndex--;
        }
    }

    private void advanceBestAsk() {
        while (bestAskIndex < levelCount && askLevels[bestAskIndex].isEmpty()) {
            bestAskIndex++;
        }
    }

    private int toIndex(long price) {
        if (price < basePrice) {
            throw new IllegalArgumentException(
                    "price below book range: " + price + " < " + basePrice);
        }
        if ((price - basePrice) % tickSize != 0) {
            throw new IllegalArgumentException(
                    "price not aligned to tick size " + tickSize + ": " + price);
        }
        int index = (int) ((price - basePrice) / tickSize);
        if (index >= levelCount) {
            throw new IllegalArgumentException(
                    "price above book range: " + price + " > " + toPrice(levelCount - 1));
        }
        return index;
    }

    private long toPrice(int index) {
        return basePrice + (long) index * tickSize;
    }

    public void validateInvariants() {
        if (bestBidIndex >= bestAskIndex) {
            throw new IllegalStateException(
                    "book is crossed: bidIndex=" + bestBidIndex + " askIndex=" + bestAskIndex);
        }
        if (bestBidIndex >= 0 && bidLevels[bestBidIndex].isEmpty()) {
            throw new IllegalStateException("bestBidIndex points at an empty level");
        }
        if (bestAskIndex < levelCount && askLevels[bestAskIndex].isEmpty()) {
            throw new IllegalStateException("bestAskIndex points at an empty level");
        }

        long counted = checkSide(bidLevels, Side.BUY) + checkSide(askLevels, Side.SELL);
        if (counted != orderIndex.size()) {
            throw new IllegalStateException(
                    "book/index mismatch: book=" + counted + " index=" + orderIndex.size());
        }

        for (Order o : orderIndex.values()) {
            if (!o.isActive()) {
                throw new IllegalStateException("inactive order in index: " + o.getSequence());
            }
        }

        int inUse = orderPool.capacity() - orderPool.available();
        if (inUse != orderIndex.size()) {
            throw new IllegalStateException(
                    "pool leak: inUse=" + inUse + " resting=" + orderIndex.size());
        }
    }

    private long checkSide(PriceLevel[] levels, Side side) {
        long count = 0;
        for (int i = 0; i < levelCount; i++) {
            PriceLevel level = levels[i];
            if (level.isEmpty()) {
                if (level.totalQuantity != 0 || level.orderCount != 0) {
                    throw new IllegalStateException("empty level with non-zero counters at " + i);
                }
                continue;
            }

            if (side == Side.BUY && i > bestBidIndex) {
                throw new IllegalStateException("bid level above bestBidIndex at " + i);
            }
            if (side == Side.SELL && i < bestAskIndex) {
                throw new IllegalStateException("ask level below bestAskIndex at " + i);
            }

            long expectedPrice = toPrice(i);
            long sum = 0;
            int n = 0;
            Order prev = null;

            for (Order o = level.head; o != null; o = o.next) {
                if (o.getPrice() != expectedPrice) {
                    throw new IllegalStateException("order at wrong level: " + o.getSequence());
                }
                if (o.getSide() != side) {
                    throw new IllegalStateException("order on wrong side: " + o.getSequence());
                }
                if (!o.isActive()) {
                    throw new IllegalStateException("inactive order in book: " + o.getSequence());
                }
                if (o.prev != prev) {
                    throw new IllegalStateException("broken prev link at " + o.getSequence());
                }
                prev = o;
                sum += o.getRemainingQuantity();
                n++;
            }

            if (level.tail != prev) {
                throw new IllegalStateException("tail does not match last node at level " + i);
            }
            if (sum != level.totalQuantity) {
                throw new IllegalStateException(
                        "level quantity mismatch at " + i + ": counted=" + sum
                                + " stored=" + level.totalQuantity);
            }
            if (n != level.orderCount) {
                throw new IllegalStateException("level order count mismatch at " + i);
            }
            count += n;
        }
        return count;
    }
}