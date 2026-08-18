package com.mehmetberkan.tradecore.benchmark;

import com.mehmetberkan.tradecore.domain.Order;
import com.mehmetberkan.tradecore.domain.OrderBook;
import com.mehmetberkan.tradecore.domain.enums.Side;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
public class MatchingEngineBenchmark {

    /** Ölçüm başlamadan önce deftere yerleştirilen emir sayısı. */
    private static final int PRELOAD = 10_000;

    /** Önceden üretilmiş emir parametrelerinin sayısı. 2'nin kuvveti olmalı (bit maskesi için). */
    private static final int PARAM_COUNT = 1 << 18;   // 262_144
    private static final int PARAM_MASK = PARAM_COUNT - 1;

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }

    private OrderBook book;

    private boolean[] isBuy;
    private long[] prices;
    private long[] quantities;

    private int index;
    private long sequence;

    @Setup(Level.Iteration)
    public void setup() {
        Random random = new Random(42);

        book = new OrderBook();
        sequence = 0;

        for (int i = 0; i < PRELOAD; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long price = (side == Side.BUY)
                    ? 449_000 - random.nextInt(20) * 100
                    : 451_000 + random.nextInt(20) * 100;
            book.submit(new Order(++sequence, side, price, 1 + random.nextInt(100), 0L));
        }


        isBuy = new boolean[PARAM_COUNT];
        prices = new long[PARAM_COUNT];
        quantities = new long[PARAM_COUNT];

        for (int i = 0; i < PARAM_COUNT; i++) {
            isBuy[i] = random.nextBoolean();
            prices[i] = 450_000 + (random.nextInt(40) - 20) * 100;
            quantities[i] = 1 + random.nextInt(100);
        }

        index = 0;
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void latency(Blackhole bh) {
        bh.consume(submitNext());
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughput(Blackhole bh) {
        bh.consume(submitNext());
    }

    private Object submitNext() {
        int i = index++ & PARAM_MASK;
        Order order = new Order(
                ++sequence,
                isBuy[i] ? Side.BUY : Side.SELL,
                prices[i],
                quantities[i],
                0L
        );
        return book.submit(order);
    }
}