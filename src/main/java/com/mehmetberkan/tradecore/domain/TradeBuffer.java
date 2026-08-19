package com.mehmetberkan.tradecore.domain;

/**
 * Bir submit çağrısında üretilen trade'leri tutan, yeniden kullanılabilir buffer.
 *
 * Trade nesnesi üretmek yerine alanları paralel primitive dizilere yazar
 * (struct-of-arrays). Böylece eşleşme başına allocation olmaz ve okuma sırasında
 * bellek erişimi ardışık kalır.
 *
 * Tek thread'lik kullanım içindir. Çağıran her submit öncesi reset() çağırmalıdır.
 */
public final class TradeBuffer {

    private static final int DEFAULT_CAPACITY = 16;

    private long[] tradeIds;
    private long[] buySequences;
    private long[] sellSequences;
    private long[] prices;
    private long[] quantities;
    private long timestampNanos;

    private int count;

    public TradeBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public TradeBuffer(int capacity) {
        tradeIds      = new long[capacity];
        buySequences  = new long[capacity];
        sellSequences = new long[capacity];
        prices        = new long[capacity];
        quantities    = new long[capacity];
        count = 0;
    }

    void add(long tradeId, long buySequence, long sellSequence, long price, long quantity) {
        if (count == tradeIds.length) {
            grow();
        }
        tradeIds[count]      = tradeId;
        buySequences[count]  = buySequence;
        sellSequences[count] = sellSequence;
        prices[count]        = price;
        quantities[count]    = quantity;
        count++;
    }

    void timestamp(long nanos) {
        this.timestampNanos = nanos;
    }

    public void reset() {
        count = 0;
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public long tradeId(int i)      { return tradeIds[check(i)]; }
    public long buySequence(int i)  { return buySequences[check(i)]; }
    public long sellSequence(int i) { return sellSequences[check(i)]; }
    public long price(int i)        { return prices[check(i)]; }
    public long quantity(int i)     { return quantities[check(i)]; }
    public long timestampNanos()    { return timestampNanos; }

    public Trade toTrade(int i) {
        check(i);
        return new Trade(tradeIds[i], buySequences[i], sellSequences[i],
                prices[i], quantities[i], timestampNanos);
    }

    private int check(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("index " + i + ", count " + count);
        }
        return i;
    }

    private void grow() {
        int newCapacity = tradeIds.length * 2;
        tradeIds      = java.util.Arrays.copyOf(tradeIds, newCapacity);
        buySequences  = java.util.Arrays.copyOf(buySequences, newCapacity);
        sellSequences = java.util.Arrays.copyOf(sellSequences, newCapacity);
        prices        = java.util.Arrays.copyOf(prices, newCapacity);
        quantities    = java.util.Arrays.copyOf(quantities, newCapacity);
    }
}