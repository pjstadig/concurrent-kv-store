# concurrent-kv-store

## Requirements

Implement a concurrent, persistent key-value store with a slightly write-heavy workload. Expect a few operations per millisecond and values up to a few hundred kilobytes.

### Operations

`get` :: takes a string key and returns the corresponding value

`put` :: take a string key and an arbitrary value storing the value under key such that all subsequent calls to `get` for the same key return the value.

### Concurrency

The operations should have strong consistency meaning for each `get` it should see the `put`s of all threads that wrote to the kv store before it.

### Durability

Each `put` operation should be stored such that all `get`s return the latest value across process crashes but not necessarily node crashes.

## Assumptions

Some particulars are not described by the requirements, so I’m making assumptions.

1. If a client performs a `get` operation for a key that does not exist they will receive an exception.
2. There’s no restriction specified for the length of keys or values nor is there a `delete` operation. I will place limits on the size of keys and values, but will allow the store to grow to an arbitrary size in memory and on disk.
3. In the requirements, there is no restriction on the types of values that can be stored. However, not every value can be persisted (for example, a network connection). I will not restrict the types of values that can be stored, but an exception may occur if a value cannot be marshaled to and from disk.
4. There’s no specification for fairness. If two threads are in contention to `put`, which thread wins? I’m assuming thread starvation is undesirable so the longest waiting thread should be chosen.
5. There’s no explicit definition in the requirements of what “before” means. I’m going to lean on the Java Memory Model which defines a happens-before relation.
6. I’m assuming no other processes are reading/writing the store files. I am not verifying their integrity (other than attempting to read them), nor am I using any file locks.

## Commentary

### Concurrency

Concurrent programming can be difficult even for experienced programmers. There many things to consider writing concurrent programs, but the things that immediately come to mind are: preventing deadlocks and preventing starvation.

Deadlocks can occur when multiple threads use shared resources but none can make progress because each is holding a lock that another wants and wants a lock that another holds. There are some basic conditions that are necessary for deadlocking, and they can mostly be avoided by: not holding a lock while acquiring another lock, barging a lock forcefully, always acquiring locks in the same order.

Starvation is when one thread cannot make progress because it keeps getting preempted by other threads. A compare-and-swap or Java’s wait/notify can create situations where a thread can fail to make progress because other threads keep barging ahead of it.

An additional difficulty of lock-based concurrent programming is the lack of composability. You can get one concurrent algorithm correct, but if you combine multiple concurrent algorithms into a larger operation you can still create deadlock or starvation or other undesirable situations. For example, you can correctly program a concurrent key-value store, but if you combine it with some other concurrent algorithm and common shared resources are involved you can create a deadlock or starve a thread.

Even with a single concurrent kv store unless you have some broader coordination you cannot guarantee that a thread performing a write dependent on an value read will not end up writing an invalid value because before it could write its read was invalidated by another thread.

Because of all this, I would generally prefer to use some existing well-tested component rather than writing my own. Rather than locks, I would also prefer transactions, because they compose better.

I’m writing this in Clojure on the JVM and that gives me several tools:

1. Clojure has reference types (`atom`, `ref`, `agent`). In particular I would probably use a `ref` in this case since they participate in Clojure’s Software Transactional Memory. I would have expectations that I would not have deadlocks or starvation and my concurrent kv store could be used with other concurrent algorithms in a composable way.
2. If I wanted to avoid Clojure’s reference types, I could use `java.util.concurrent.ConcurrentHashMap` or some similar component.
3. If I wanted to program locks directly, I could use `java.util.concurrent.locks.*`. Because Java has a well defined memory model, I can reason about what “before” means and whether there are appropriate memory fences so threads can see the results of operations that happened “before.”

### Durability

Apart from the concurrency issues, the durability requirements can add their own complexity. There are no specific requirements about performance.

Writing to a backing file with each `put` will greatly increase the latency of `put` operations. Especially if the kv store is writing out all of the key-value pairs to disk with every `put`. There come to mind a couple of ways to improve this:
1. Use a write ahead log. There would be a persistent “current” version of all the values in the kv store and a log of changes that have happened since. An append only write ahead log would be relatively cheaper to write to, but you would have to merge the log into the “current” version either upon initializing the kv store or with some background thread.
2. Batch writes to disk. This could be done by gathering pending writes but parking the writer threads until the writes can be flushed to both the in-memory and file store, then releasing them.
2. Partition the kv store. This is not mutually exclusive with the first two. The kv store would be partitioned (for example, modulo of a hash of the value) so only one partition of values would have to be written at a time, and multiple partitions could make progress simultaneously. This brings with it complications about how a re-partition process would work and whether the kv store should automatically re-partition. Also if the partition algorithm is changed all the data must be repartitioned after reading from disk.

The decisions you make here are driven by whether latency or throughput is more important, and the requirements are not specific about that.

### Testing

A final difficulty that is rolling around it my head is testing. This kind of component can be difficult to test. My first thought is to use generative testing, but the question is what are the properties of the component and how can you adequately test them without reimplementing the component in the tests. For example, you can spin up a bunch of threads to bang on the kv store, but you would need some way to coordinate them so you could guarantee an ordering about which you can make assertions. This is the same problem that the kv store is trying to solve, and you’d have to be careful that your test doesn’t introduce timing delays that hide race conditions.

Another approach would be to write example or scenario based tests where you come up with certain scenarios, contrive to have some threads enact that scenario, then verify the state is what you expect. The problem is your tests are constrained by your imagination.

## Approach

Since I think the point of this is to demonstrate experience and proficiency with low-level concurrency primitives, I think it would be cheating to use Clojure’s reference types, so I will opt to use `java.util.concurrent.locks*`.

I usually would go for the simplest thing that could work first, but instead of writing the entire store to disk with every `put` I will partition the store. However, I will side-step any complexity about re-partitioning.

## Results

The performance of the kv store (particularly `put`) depends on how many partitions it has. I ran several tests with different partitions and here is the best out of three for each of 1 partition, 10 partitions, and 100 partitions, where there are 100 threads writing to the store. Each thread performs a `put` then a `get` 100 times in succession (see `t-concurrent-operations`).

```
partition-count 1
thread-count 100
op-count 100
total-time 2254 msecs
avg-put-latency 22.379000 msecs
avg-get-latency 0.001400 msecs
throughput 2.218279 ops/msec

partition-count 10
thread-count 100
op-count 100
total-time 263 msecs
avg-put-latency 1.854500 msecs
avg-get-latency 0.003900 msecs
throughput 19.011407 ops/msec

partition-count 100
thread-count 100
op-count 100
total-time 173 msecs
avg-put-latency 0.373700 msecs
avg-get-latency 0.004500 msecs
throughput 28.901734 ops/msec
```
