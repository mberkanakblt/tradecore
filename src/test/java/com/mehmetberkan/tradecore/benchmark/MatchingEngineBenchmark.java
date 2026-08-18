package com.mehmetberkan.tradecore.benchmark;

import com.mehmetberkan.tradecore.domain.OrderBook;
import com.mehmetberkan.tradecore.domain.Order;
import com.mehmetberkan.tradecore.domain.enums.Side;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class MatchingEngineBenchmark {

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }

    private OrderBook book;
    private long sequence;
    private Random random;

    @Setup(Level.Iteration)
    public void setup() {
        book = new OrderBook();
        sequence = 0;
        random = new Random(42);

        for (int i = 0; i < 10_000; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long price = side == Side.BUY
                    ? 449_000 - random.nextInt(20) * 100
                    : 451_000 + random.nextInt(20) * 100;
            book.submit(new Order(++sequence, side, price, 1 + random.nextInt(100), 0));
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void latency(Blackhole bh) {
        submitRandomOrder(bh);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughput(Blackhole bh) {
        submitRandomOrder(bh);
    }

    private void submitRandomOrder(Blackhole bh) {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long price = 450_000 + (random.nextInt(40) - 20) * 100;
        long qty = 1 + random.nextInt(100);

        bh.consume(book.submit(new Order(++sequence, side, price, qty, 0)));
    }
}