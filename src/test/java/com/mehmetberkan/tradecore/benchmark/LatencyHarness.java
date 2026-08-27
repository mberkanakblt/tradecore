package com.mehmetberkan.tradecore.benchmark;

import com.mehmetberkan.tradecore.domain.Order;
import com.mehmetberkan.tradecore.domain.OrderBook;
import com.mehmetberkan.tradecore.domain.TradeBuffer;
import com.mehmetberkan.tradecore.domain.enums.Side;
import org.HdrHistogram.Histogram;

import java.util.Random;

/**
 * Sabit hızda yük üreten latency ölçüm harness'ı.
 *
 * JMH'den farkı: JMH "olabildiğince hızlı" çağırır, sistem yavaşladığında kendisi de
 * yavaşlar ve kötü anları eksik ölçer (coordinated omission). Burada emirlerin sabit
 * bir hızda geldiğini varsayıyoruz; gecikme, emrin GELMESİ GEREKEN andan işlenmesinin
 * bittiği ana kadar geçen süredir. Bir duraklamanın arkasında biriken emirlerin
 * gecikmesi de böylece ölçüme girer.
 *
 * Emirlerin bir kısmı iptal edilir, aksi halde defter sınırsız büyür ve ölçülen şey
 * motor değil, şişen bir heap'in GC davranışı olur.
 */
public final class LatencyHarness {

    private static final int PRELOAD = 10_000;

    private static final int PARAM_COUNT = 1 << 18;      // 262_144
    private static final int PARAM_MASK = PARAM_COUNT - 1;

    /** Deftere yazılan emirlerin sequence'lerini tutan halka tampon. */
    private static final int LIVE_CAPACITY = 1 << 20;    // 1_048_576
    private static final int LIVE_MASK = LIVE_CAPACITY - 1;

    /** İptal edilecek emri seçerken bakılacak geçmiş penceresi. */
    private static final int CANCEL_WINDOW = 1 << 16;    // 65_536
    private static final int CANCEL_WINDOW_MASK = CANCEL_WINDOW - 1;

    private static final int WARMUP_ORDERS = 5_000_000;
    private static final int MEASURED_ORDERS = 10_000_000;

    public static void main(String[] args) {
        long targetRate = args.length > 0 ? Long.parseLong(args[0]) : 1_000_000L;
        new LatencyHarness().run(targetRate);
    }

    private OrderBook book;
    private TradeBuffer tradeBuffer;

    // Önceden üretilmiş emir parametreleri
    private boolean[] isBuy;
    private long[] prices;
    private long[] quantities;
    private boolean[] isCancel;

    private long[] liveSequences;
    private int liveWriteIndex;

    private long sequence;

    private void run(long targetRatePerSecond) {
        long intervalNanos = 1_000_000_000L / targetRatePerSecond;

        System.out.printf("Target rate: %,d orders/sec (%d ns interval)%n",
                targetRatePerSecond, intervalNanos);

        setup();

        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_ORDERS; i++) {
            submitNext(i);
        }
        System.out.printf("Warmup done, book size %,d%n", book.activeOrderCount());

        System.out.println("Measuring...");
        Histogram histogram = measure(intervalNanos);

        report(histogram, targetRatePerSecond);
    }

    private void setup() {
        Random random = new Random(42);

        book = new OrderBook();
        tradeBuffer = new TradeBuffer();
        sequence = 0;

        liveSequences = new long[LIVE_CAPACITY];
        liveWriteIndex = 0;

        for (int i = 0; i < PRELOAD; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long price = (side == Side.BUY)
                    ? 449_000 - random.nextInt(20) * 100
                    : 451_000 + random.nextInt(20) * 100;
            long seq = ++sequence;
            book.submit(seq, side, price, 1 + random.nextInt(100), tradeBuffer);
            if (book.isResting(seq)) {
                recordLive(seq);
            }
        }

        isBuy = new boolean[PARAM_COUNT];
        prices = new long[PARAM_COUNT];
        quantities = new long[PARAM_COUNT];
        isCancel = new boolean[PARAM_COUNT];

        for (int i = 0; i < PARAM_COUNT; i++) {
            isBuy[i] = random.nextBoolean();
            prices[i] = 450_000 + (random.nextInt(40) - 20) * 100;
            quantities[i] = 1 + random.nextInt(100);
            isCancel[i] = random.nextInt(100) < 45;        }
    }

    private Histogram measure(long intervalNanos) {
        // 1 ns – 10 s, 3 anlamlı basamak. Kayıt sırasında allocation yapmaz.
        Histogram histogram = new Histogram(1, 10_000_000_000L, 3);

        long start = System.nanoTime();

        for (int i = 0; i < MEASURED_ORDERS; i++) {
            long expectedArrival = start + i * intervalNanos;

            while (System.nanoTime() < expectedArrival) {
                Thread.onSpinWait();
            }

            submitNext(i);

            histogram.recordValue(System.nanoTime() - expectedArrival);

            if (i % 1_000_000 == 0 && i > 0) {
                System.out.printf("  %,d orders, book size %,d, behind by %,d ms%n",
                        i, book.activeOrderCount(),
                        (System.nanoTime() - (start + i * intervalNanos)) / 1_000_000);
            }
        }

        return histogram;
    }

    private void submitNext(int i) {
        int p = i & PARAM_MASK;

        if (isCancel[p] && liveWriteIndex > 0) {
            book.cancel(pickVictim(p));
            return;
        }

        long seq = ++sequence;
        book.submit(
                seq,
                isBuy[p] ? Side.BUY : Side.SELL,
                prices[p],
                quantities[p],
                tradeBuffer
        );

        if (book.isResting(seq)) {
            recordLive(seq);
        }
    }

    private long pickVictim(int p) {
        int offset = p & CANCEL_WINDOW_MASK;
        int slot = (liveWriteIndex - 1 - offset) & LIVE_MASK;
        return liveSequences[slot];
    }

    private void recordLive(long seq) {
        liveSequences[liveWriteIndex & LIVE_MASK] = seq;
        liveWriteIndex++;
    }

    private void report(Histogram h, long targetRate) {
        System.out.println();
        System.out.printf("Orders measured: %,d at %,d/sec%n", h.getTotalCount(), targetRate);
        System.out.printf("Final book size: %,d%n", book.activeOrderCount());
        System.out.println("----------------------------------------");
        System.out.printf("  mean     %,12.1f ns%n", h.getMean());
        System.out.printf("  p50      %,12d ns%n", h.getValueAtPercentile(50.0));
        System.out.printf("  p90      %,12d ns%n", h.getValueAtPercentile(90.0));
        System.out.printf("  p99      %,12d ns%n", h.getValueAtPercentile(99.0));
        System.out.printf("  p99.9    %,12d ns%n", h.getValueAtPercentile(99.9));
        System.out.printf("  p99.99   %,12d ns%n", h.getValueAtPercentile(99.99));
        System.out.printf("  max      %,12d ns%n", h.getMaxValue());
        System.out.println("----------------------------------------");
    }
}