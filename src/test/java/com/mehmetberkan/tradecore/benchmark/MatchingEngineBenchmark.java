package com.mehmetberkan.tradecore.benchmark;

import com.mehmetberkan.tradecore.domain.OrderBook;
import com.mehmetberkan.tradecore.domain.TradeBuffer;
import com.mehmetberkan.tradecore.domain.enums.Side;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Matching engine throughput/allocation benchmark.
 *
 * Emir parametreleri @Setup'ta önceden üretilir; ölçülen yolda Random çağrısı yoktur.
 * Emirlerin bir kısmı iptal edilir — aksi halde defter sınırsız büyür, emir havuzu
 * tükenir ve ölçülen şey motor değil, şişen bir defterin GC davranışı olur.
 */
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
public class MatchingEngineBenchmark {

    private static final int PRELOAD = 10_000;

    /** 2'nin kuvveti olmalı — bit maskesi için. */
    private static final int PARAM_COUNT = 1 << 18;      // 262_144
    private static final int PARAM_MASK = PARAM_COUNT - 1;

    /** Deftere yerleşen emirlerin sequence'lerini tutan halka tampon. */
    private static final int LIVE_CAPACITY = 1 << 20;    // 1_048_576
    private static final int LIVE_MASK = LIVE_CAPACITY - 1;

    /** İptal edilecek emri seçerken bakılacak geçmiş penceresi. */
    private static final int CANCEL_WINDOW_MASK = (1 << 16) - 1;   // 65_535

    private static final int CANCEL_PERCENT = 45;

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }

    private OrderBook book;
    private TradeBuffer tradeBuffer;

    // Önceden üretilmiş emir parametreleri
    private boolean[] isBuy;
    private long[] prices;
    private long[] quantities;
    private boolean[] isCancel;

    // Halka tampon
    private long[] liveSequences;
    private int liveWriteIndex;

    private int index;
    private long sequence;

    @Setup(Level.Iteration)
    public void setup() {
        Random random = new Random(42);

        book = new OrderBook(0, 100, 10_000, 1 << 18);
        tradeBuffer = new TradeBuffer();
        sequence = 0;

        liveSequences = new long[LIVE_CAPACITY];
        liveWriteIndex = 0;

        // Gerçekçi defter derinliği: iki tarafa yayılmış, eşleşmeyen emirler
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

        // Hot path'te Random kalmasın diye her şey önceden
        isBuy = new boolean[PARAM_COUNT];
        prices = new long[PARAM_COUNT];
        quantities = new long[PARAM_COUNT];
        isCancel = new boolean[PARAM_COUNT];

        for (int i = 0; i < PARAM_COUNT; i++) {
            isBuy[i] = random.nextBoolean();
            prices[i] = 450_000 + (random.nextInt(40) - 20) * 100;
            quantities[i] = 1 + random.nextInt(100);
            isCancel[i] = random.nextInt(100) < CANCEL_PERCENT;
        }

        index = 0;
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void latency(Blackhole bh) {
        submitNext(bh);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughput(Blackhole bh) {
        submitNext(bh);
    }

    /** Hot path: dizi okuması + submit veya cancel. Order nesnesi havuzdan gelir. */
    private void submitNext(Blackhole bh) {
        int i = index++ & PARAM_MASK;

        if (isCancel[i] && liveWriteIndex > 0) {
            bh.consume(book.cancel(pickVictim(i)));
            return;
        }

        long seq = ++sequence;
        book.submit(
                seq,
                isBuy[i] ? Side.BUY : Side.SELL,
                prices[i],
                quantities[i],
                tradeBuffer
        );

        if (book.isResting(seq)) {
            recordLive(seq);
        }

        bh.consume(tradeBuffer.count());
    }

    /**
     * Son 65k emirden birini seçer. Üstüne yazılmış eski bir değere denk gelirse
     * cancel() zaten false döner — zararsız.
     */
    private long pickVictim(int i) {
        int slot = (liveWriteIndex - 1 - (i & CANCEL_WINDOW_MASK)) & LIVE_MASK;
        return liveSequences[slot];
    }

    private void recordLive(long seq) {
        liveSequences[liveWriteIndex & LIVE_MASK] = seq;
        liveWriteIndex++;
    }
}