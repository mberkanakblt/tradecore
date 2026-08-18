# TradeCore

A low-latency order matching engine written in Java 21.

TradeCore implements the core of an exchange: it accepts limit orders, matches
them under **price-time priority**, and emits trades. The focus is correctness
under randomized load and measurable latency — not feature count.

## Design decisions

**Fixed-point `long` prices and quantities.** `BigDecimal` allocates on every
arithmetic operation and scatters objects across the heap. Prices are stored as
scaled integers (45.50 → 455000), so the matching path uses primitive arithmetic
with zero allocation.

**No framework in the core.** `OrderBook` is a plain POJO with no JPA, no Spring,
no annotations. The engine never touches a proxy, a persistence context, or a
DI container. This also keeps the benchmark measuring the engine and nothing else.

**`TreeMap<Long, ArrayDeque<Order>>` per side.** The bid side uses a reversed
comparator so `firstKey()` returns the best price on both sides. `ArrayDeque`
gives FIFO within a price level, which is what time priority requires.

**O(1) cancel via order index.** A `HashMap<Long, Order>` maps sequence numbers
to orders. Without it, cancelling would mean scanning both trees — unacceptable,
since in real markets most orders are cancelled rather than filled.

**Lazy deletion.** Cancelled orders are marked, not removed from the deque
(`ArrayDeque.remove()` is O(n)). The matching loop discards them when it reaches
them. Cancel becomes O(1); the cost is deferred to a path that would run anyway.

**Trades execute at the resting order's price.** A buy limit at 46.00 matching a
resting sell at 45.50 trades at 45.50. The resting order provided liquidity and
set the price — this is price improvement, and real venues treat it as a
regulatory requirement.

## Correctness

Six JUnit 5 tests cover full fills, walking the book across price levels, partial
fills, time priority within a level, price improvement, and skipping cancelled
orders.

The last one is a randomized invariant test: 100,000 orders with a 20% cancel
rate against a fixed seed, verifying after every operation that

- the book is never crossed (`bestBid < bestAsk`)
- no empty price levels remain in either tree
- every order sits at the price level matching its own price
- the number of live orders in the book equals the index size
- quantity is conserved: `submitted = matched × 2 + resting + cancelled`

The last invariant is what proves no liquidity is created or lost.

## Benchmark

Measured with JMH, in-process, against a book pre-loaded with 10,000 orders.

```
Environment: WSL2 (Ubuntu), OpenJDK 21, single thread
Latency:     204.3 ± 6.1 ns/op          (avgt, 3 forks × 10 iterations)
Throughput:  4.95M ± 0.17M ops/s
```


Three forks matter here: JIT compilation decisions vary between JVM runs, and
single-fork measurements hide that variance. The spread across forks was around
15%, which is larger than most of the optimizations Phase 2 will attempt.

## Known limitations

- **Single-threaded.** No synchronization anywhere; concurrent use will corrupt
  the book. Phase 2 moves to a single-writer thread fed by a ring buffer.
- **Ghost price levels.** A fully cancelled level stays visible in `bestBid`/
  `bestAsk` until matching sweeps it. A consequence of lazy deletion; Phase 2's
  per-level quantity counters remove it.
- **Symbols are created on demand.** Submitting to an unknown symbol opens a new
  book. Real venues define instruments up front.
- **Limit orders only.** No market, stop, or iceberg orders. No self-trade
  prevention, no circuit breakers.
- **In-memory only.** No persistence, no recovery, no journaling.

## Roadmap

**Phase 2 — latency**
Zero-allocation hot path with object pooling, flat array order book indexed by
`(price - base) / tick` to eliminate pointer chasing, intrusive linked lists
replacing `ArrayDeque`, single-writer thread with a ring buffer, and
HdrHistogram measurement with coordinated-omission correction.

**Phase 3 — surrounding system**
Market data publication, persistence off the hot path, and a REST/FIX gateway.

## Build

```bash
mvn clean test          # run the test suite
mvn clean test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:target/test-classes:$(cat cp.txt)" \
     com.mehmetberkan.tradecore.benchmark.MatchingEngineBenchmark
```