# TradeCore

A low-latency order matching engine written in Java 21.

TradeCore implements the core of an exchange: it accepts limit orders, matches
them under **price-time priority**, and emits trades. The focus is correctness
under randomized load and measurable latency — not feature count.

## Design decisions

**Fixed-point `long` prices and quantities.** `BigDecimal` allocates on every
arithmetic operation and scatters objects across the heap. Prices are stored as
scaled integers (45.50 → 455000), so the matching path uses primitive arithmetic
only.

**No framework in the core.** `OrderBook` is a plain POJO with no JPA, no Spring,
no annotations. The engine never touches a proxy, a persistence context, or a DI
container. This also keeps the benchmark measuring the engine and nothing else.

**Flat array order book.** Price levels are indexed directly:

```
index = (price - basePrice) / tickSize
```

A `TreeMap` means a tree walk on every `firstKey()`, with each node landing
somewhere else on the heap — a chain of cache misses per lookup, plus `Long`
boxing on every key. A flat array turns that into one subtraction and one
division, and keeps levels contiguous in memory so the prefetcher does useful
work. Best bid and best ask are kept as `int` indices, updated on insert and
walked outward when a level empties, which is amortised O(1).

The cost is a fixed price range decided up front. Real venues already impose
price bands, so a bounded book is closer to how exchanges work than an unbounded
one. Prices outside the range, or off the tick grid, are rejected with
`IllegalArgumentException` rather than silently rounded.

**Intrusive doubly-linked list per level.** Orders at the same price are linked
through `Order.prev` / `Order.next` — the list nodes *are* the orders, so no node
objects are allocated. Because a cancel already holds the order, unlinking it is
genuinely O(1): fix up two neighbours and it's out. This removes the need for
lazy deletion and the ghost price levels that came with it.

The trade-off is that `Order` is no longer just data — it carries its membership
in whatever list it belongs to, and can only be in one. The link fields are
package-private so nothing outside `domain` can corrupt the book.

**Primitive-keyed order index.** `Long2ObjectHashMap` from Agrona replaces
`HashMap<Long, Order>`. The JDK map allocated twice per resting order: a `Long`
for the key (sequence numbers are well outside the boxing cache) and a `Node` for
the entry. Agrona uses open addressing over parallel arrays — no nodes, no
boxing, and the probe sequence stays in cache.

**Trades are written into a reusable buffer.** `submit(Order, TradeBuffer)`
writes trade fields into parallel primitive arrays (struct-of-arrays) instead of
allocating a `Trade` per match. A `List<Trade>` overload is kept for tests and
non-hot-path callers, and is documented as such.

**Trades execute at the resting order's price.** A buy limit at 46.00 matching a
resting sell at 45.50 trades at 45.50. The resting order provided liquidity and
set the price — this is price improvement, and real venues treat it as a
regulatory requirement.

## Correctness

JUnit 5 tests cover full fills, walking the book across price levels, partial
fills, time priority within a level, price improvement, cancelled orders, and
rejection of prices outside the book range or off the tick grid.

The important one is a randomised invariant test: 100,000 orders with a 20%
cancel rate against a fixed seed, verifying after **every** operation that

- the book is never crossed (`bestBidIndex < bestAskIndex`)
- the best-bid and best-ask indices point at non-empty levels
- no level holds an order belonging to another price or side
- every level's `head`/`tail` and `prev` links are consistent
- every level's stored quantity and order count match the linked list
- the number of live orders in the book equals the index size
- quantity is conserved: `submitted = matched × 2 + resting + cancelled`

The last one is what proves no liquidity is created or lost. The link and counter
checks exist because the flat book keeps several structures in sync by hand;
each is a place where a silent bug could hide.

## Benchmark

Throughput and allocation with JMH (3 forks × 10 iterations, order parameters
pre-generated so the measured path contains only the engine). Percentiles come
from a separate fixed-rate harness described below.

Environment: WSL2 (Ubuntu), OpenJDK 21, single thread, `-Xms2g -Xmx2g
-XX:+AlwaysPreTouch`.

| Step | Change                           | Latency  | Allocation | Throughput |
|------|----------------------------------|----------|------------|------------|
| 0    | Baseline (TreeMap + ArrayDeque)  | 175.1 ns | 296.2 B/op | 5.84M/s    |
| 1    | Lazy trade list allocation       | 167.6 ns | 272.4 B/op | 5.95M/s    |
| 2    | Trades into reusable buffer      | 165.4 ns | 197.8 B/op | 6.11M/s    |
| 3    | Flat array book + intrusive list | 131.4 ns | 121.5 B/op | 7.43M/s    |
| 4    | Agrona primitive-keyed index     | 145.9 ns |  86.5 B/op | 7.05M/s    |

Step 4 looks like a regression in the JMH column, and it isn't. Per-iteration GC
data splits the runs into two groups: iterations with no collection averaged
around 125 ns, iterations with one averaged around 160 ns. The reported mean sits
between two modes and describes neither. The percentile harness, measuring the
same code, shows the tail improving 2.5x at that step.

Three forks are used because JIT compilation decisions vary between JVM runs;
single-fork numbers hid a spread of roughly 15%, larger than most of the changes
above.

## Latency distribution

Fixed-rate harness: 1M orders/sec, 5M warm-up orders, 10M measured orders, ~210k
resting orders, 30% cancels.

Latency is measured from each order's *expected* arrival time rather than from
the call itself. A benchmark that simply loops stops issuing work while the
system is paused and records one bad sample where reality would have queued
thousands — coordinated omission. Anchoring to the expected arrival makes every
order stuck behind a pause count individually.

| Configuration                        | p50    | p90    | p99    | p99.9   | max     |
|--------------------------------------|--------|--------|--------|---------|---------|
| TreeMap + ArrayDeque, G1             | 160 ns | 380 ns | 954 µs | 12.2 ms | 17.6 ms |
| TreeMap + ArrayDeque, ZGC            | 170 ns | 420 ns | 653 µs | 4.25 ms | 10.3 ms |
| Flat book + intrusive list, G1       | 130 ns | 280 ns |  79 µs | 4.75 ms | 10.5 ms |
| Flat book + intrusive list, ZGC      | 133 ns | 317 ns |  92 µs | 2.75 ms | 7.73 ms |
| + Agrona primitive index, G1         | 134 ns | 275 ns |  36 µs | 1.79 ms | 6.57 ms |

Switching collectors improved p99 by 1.5x. Changing the data structure improved
it by 12x — and the flat book on G1 beats the TreeMap on ZGC at every
percentile. GC tuning is a workaround; not producing the garbage is the fix.

G1 logs show why the old numbers were so bad: pause length tracked *survivors*,
not garbage. As the book grew from 106 MB to 246 MB of live orders, young pauses
grew from 15.6 ms to 33.0 ms, because a copying collector pays for what stays
alive rather than what dies.

The tail is still in the millisecond range because the hot path allocates 86.5 B
per order, nearly all of it the `Order` object itself.

## Known limitations

- **Single-threaded.** No synchronisation anywhere; concurrent use will corrupt
  the book. A single-writer thread fed by a ring buffer is the intended fix.
- **Bounded price range.** The book covers `basePrice` to
  `basePrice + levelCount × tickSize`, with a single fixed tick size. Real venues
  vary tick size by price band.
- **Symbols are created on demand.** Submitting to an unknown symbol opens a new
  book; real venues define instruments up front.
- **Limit orders only.** No market, stop, or iceberg orders. No self-trade
  prevention, no circuit breakers.
- **In-memory only.** No persistence, no recovery, no journaling.
- **The book grows under the harness.** Cancels do not keep pace with new
  resting orders, so the measured configuration is a book that is still filling.
  Numbers should be read as relative comparisons under identical load, not as
  steady-state figures.

## Roadmap

**Remaining in Phase 2 — reaching 0 B/op**
Object pooling for `Order`, so the last significant allocation leaves the hot
path; verification under Epsilon GC, where any allocation at all eventually
kills the process; then a single-writer thread fed by a ring buffer.

**Phase 3 — surrounding system**
Market data publication, persistence off the hot path, and a REST or FIX gateway.

## Build

```bash
mvn clean test

mvn clean test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt

# throughput and allocation
java -Xms2g -Xmx2g -XX:+AlwaysPreTouch \
     -cp "target/classes:target/test-classes:$(cat cp.txt)" \
     com.mehmetberkan.tradecore.benchmark.MatchingEngineBenchmark -prof gc

# latency percentiles, argument is target orders/sec
java -Xms2g -Xmx2g -XX:+AlwaysPreTouch -Xlog:gc \
     -cp "target/classes:target/test-classes:$(cat cp.txt)" \
     com.mehmetberkan.tradecore.benchmark.LatencyHarness 1000000
```