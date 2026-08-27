# TradeCore

A low-latency order matching engine written in Java 21.

TradeCore implements the core of an exchange: it accepts limit orders, matches
them under **price-time priority**, and emits trades. The focus is correctness
under randomized load and measurable latency — not feature count.

The matching path allocates nothing. That claim is verified by running the
engine under Epsilon GC, a collector that never reclaims memory: 15 million
orders complete without the heap filling.

## Design decisions

**Fixed-point `long` prices and quantities.** `BigDecimal` allocates on every
arithmetic operation and scatters objects across the heap. Prices are stored as
scaled integers (45.50 → 455000), so matching uses primitive arithmetic only.

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
division, and keeps levels contiguous so the prefetcher does useful work. Best
bid and best ask are held as `int` indices, updated on insert and walked outward
when a level empties, which is amortised O(1).

The cost is a price range fixed at construction. Real venues already impose
price bands, so a bounded book is closer to how exchanges work than an unbounded
one. Prices outside the range, or off the tick grid, are rejected with
`IllegalArgumentException` rather than silently rounded.

**Intrusive doubly-linked list per level.** Orders at the same price are linked
through `Order.prev` / `Order.next` — the list nodes *are* the orders, so no node
objects are allocated. Because a cancel already holds the order, unlinking is
genuinely O(1). This removed the need for lazy deletion and the ghost price
levels that came with it.

The trade-off is that `Order` is no longer just data: it carries its membership
in whatever list it belongs to, and can only belong to one. The link fields are
package-private so nothing outside `domain` can corrupt the book.

**Primitive-keyed order index.** Agrona's `Long2ObjectHashMap` replaces
`HashMap<Long, Order>`. The JDK map allocated twice per resting order — a `Long`
for the key (sequence numbers sit well outside the boxing cache) and a `Node` for
the entry. Agrona uses open addressing over parallel arrays: no nodes, no boxing,
and the probe sequence stays in cache. The index is sized up front so it never
rehashes during matching.

**Trades are written into a reusable buffer.** `submit(..., TradeBuffer)` writes
trade fields into parallel primitive arrays (struct-of-arrays) instead of
allocating a `Trade` per match. A `List<Trade>` overload is kept for tests and
non-hot-path callers, and is documented as such.

**Orders come from a pool.** `OrderBook` owns an `OrderPool` of pre-allocated
`Order` objects and takes order fields directly rather than a constructed object,
so an `Order` reference never leaves the book. Orders return to the pool at the
three points where their last reference drops: matched to zero, cancelled, or
never rested at all. Price validation happens *before* acquiring, so a rejected
order cannot leak a pooled object.

The pool fails fast when exhausted rather than falling back to `new`. A silent
fallback would start allocating exactly when the system is under load, degrading
latency at the worst possible moment and hiding the capacity problem that caused
it.

Pooling costs real safety. `Order` is no longer immutable, and a released object
may already represent a different order — the Java equivalent of a dangling
pointer. Keeping the type inside the book is what makes this tolerable.

**Trades execute at the resting order's price.** A buy limit at 46.00 matching a
resting sell at 45.50 trades at 45.50. The resting order provided liquidity and
set the price — this is price improvement, and real venues treat it as a
regulatory requirement.

## Correctness

JUnit 5 tests cover full fills, walking the book across price levels, partial
fills, time priority within a level, price improvement, cancellation, pool
accounting, and rejection of prices outside the book range or off the tick grid.

The important one is a randomised invariant test: 100,000 orders with a 20%
cancel rate against a fixed seed, verifying after **every** operation that

- the book is never crossed (`bestBidIndex < bestAskIndex`)
- the best-bid and best-ask indices point at non-empty levels
- no level holds an order belonging to another price or side
- every level's `head`/`tail` and `prev` links are consistent
- every level's stored quantity and order count match the linked list
- the number of live orders in the book equals the index size
- the number of pooled objects in use equals the number of resting orders
- quantity is conserved: `submitted = matched × 2 + resting + cancelled`

The conservation check proves no liquidity is created or lost. The link, counter
and pool checks exist because the flat book keeps several structures in sync by
hand; each is a place where a silent bug could hide. The pool check in particular
catches a missed `release`, which would otherwise drain the pool slowly and fail
somewhere unrelated.

## Benchmark

Throughput and allocation with JMH: 3 forks × 10 iterations, order parameters
pre-generated so the measured path contains only the engine, 45% of operations
are cancels so the book approaches steady state.

Environment: WSL2 (Ubuntu), OpenJDK 21, single thread, `-Xms2g -Xmx2g
-XX:+AlwaysPreTouch`.

| Step | Change                            | Latency  | Allocation | Throughput |
|------|-----------------------------------|----------|------------|------------|
| 0    | Baseline (TreeMap + ArrayDeque)   | 175.1 ns | 296.2 B/op | 5.84M/s    |
| 1    | Lazy trade list allocation        | 167.6 ns | 272.4 B/op | 5.95M/s    |
| 2    | Trades into reusable buffer       | 165.4 ns | 197.8 B/op | 6.11M/s    |
| 3    | Flat array book + intrusive list  | 131.4 ns | 121.5 B/op | 7.43M/s    |
| 4    | Agrona primitive-keyed index      | 145.9 ns |  86.5 B/op | 7.05M/s    |
| 5    | Order pooling                     |    — *   |  19.2 B/op |     — *    |
| 6    | Pre-sized index, right-sized pool |  84.3 ns |   3.4 B/op | 11.9M/s    |

\* Step 5 exhausted the pool under the original benchmark, which had no cancels
and let the book grow without bound. Cancels were added at that point, so steps
5–6 run a different workload than steps 0–4 and the throughput column is not
directly comparable across that boundary.

Step 4 looks like a regression and isn't. Per-iteration GC data split the runs
into two groups: iterations with no collection averaged around 125 ns,
iterations with one averaged around 160 ns. The reported mean sat between two
modes and described neither. The percentile harness, measuring the same code,
showed the tail improving 2.5x at that step.

Three forks are used because JIT compilation decisions vary between JVM runs;
single-fork numbers hid a spread of roughly 15%, larger than most of the changes
above.

### What the allocation profile found

At 9.3 B/op the remaining allocation was small enough that guessing where it came
from would have wasted time, so it was profiled with async-profiler instead.

76% of it came from filling the object pool inside `@Setup` — not from the
measured path at all. JMH amortises setup cost across measured operations, so the
reported figure had been describing benchmark scaffolding as if it were engine
behaviour. Right-sizing the pool for the benchmark's actual book depth removed it.

The only allocation genuinely inside `submit` and `cancel` was Agrona's hash map
growing as the book filled — a one-off cost per resize, about 6% of the total.
Sizing the index up front eliminated it.

Nothing else in the matching path allocated: not matching, not `unlink`, not
`addLast`, not the trade buffer, not acquiring and releasing from the pool. The
remaining 3.4 B/op is entirely setup, spread across the measured operations.

### Zero-allocation verification

Epsilon GC never reclaims memory: any allocation in a loop will eventually fill
the heap and kill the process. Running the fixed-rate harness under it —
15 million orders, 5M warm-up and 10M measured — completes normally.

```bash
java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx4g \
     -cp "target/classes:target/test-classes:$(cat cp.txt)" \
     com.mehmetberkan.tradecore.benchmark.LatencyHarness 1000000
```

## Latency distribution

Fixed-rate harness: 1M orders/sec, 5M warm-up orders, 10M measured orders, ~210k
resting orders, 45% cancels.

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
| + Order pooling, Epsilon GC          | 147 ns | 296 ns |  31 µs |  716 µs | 1.95 ms |

Switching collectors improved p99 by 1.5x. Changing the data structure improved
it by 12x — and the flat book on G1 beat the TreeMap on ZGC at every percentile.
GC tuning is a workaround; not producing the garbage is the fix.

G1 logs show why the early numbers were so bad: pause length tracked *survivors*,
not garbage. As the book grew from 106 MB to 246 MB of live orders, young pauses
grew from 15.6 ms to 33.0 ms, because a copying collector pays for what stays
alive rather than what dies.

Two results are worth calling out because they are not what you would guess:

**Epsilon is slightly slower at the median** (134 → 147 ns). With nothing being
reclaimed the heap keeps growing into fresh pages, so TLB pressure rises. A
collector that recycles memory gives back a kind of locality.

**The tail is now bounded by the environment, not the engine.** With no GC and no
allocation, the remaining 716 µs at p99.9 comes from the hypervisor, the OS
scheduler, and page faults. Going below it needs CPU pinning, huge pages, and
core isolation — none of which WSL2 supports.

## Known limitations

- **Single-threaded.** No synchronisation anywhere; concurrent use will corrupt
  the book. A single-writer thread fed by a ring buffer is the intended shape.
- **Pooling weakens object safety.** `Order` is mutable and reused. A reference
  held after release points at a different order. Nothing outside `domain`
  receives one, but that guarantee is by convention, not by the type system.
- **Bounded price range, single tick size.** The book covers `basePrice` to
  `basePrice + levelCount × tickSize`. Real venues vary tick size by price band.
- **The pool must be sized correctly.** Exhaustion throws rather than degrading.
  Under the harness the book still grows slowly, so a long enough run will
  eventually exhaust any fixed pool.
- **Symbols are created on demand.** Submitting to an unknown symbol opens a new
  book; real venues define instruments up front.
- **Limit orders only.** No market, stop, or iceberg orders. No self-trade
  prevention, no circuit breakers.
- **In-memory only.** No persistence, no recovery, no journaling.
- **Measured on WSL2**, which is a virtual machine. Absolute numbers would differ
  on bare metal; the comparisons between rows were taken under identical
  conditions and are the part worth reading.

## Roadmap

**Phase 3 — surrounding system**
A single-writer thread fed by a ring buffer, market data publication, persistence
off the hot path, and a REST or FIX gateway.

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

# zero-allocation check
java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx4g \
     -cp "target/classes:target/test-classes:$(cat cp.txt)" \
     com.mehmetberkan.tradecore.benchmark.LatencyHarness 1000000
```