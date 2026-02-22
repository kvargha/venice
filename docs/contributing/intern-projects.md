# Intern Projects

This page lists potential intern projects for Venice. These projects are designed to be achievable within an
internship timeframe (~3 months), while providing meaningful learning in distributed systems. They are distinct
from [Venice Improvement Proposals (VIPs)](proposals/index.md), which are reserved for larger architectural changes.

If you are interested in one of these projects, please open a GitHub issue and mention this page so the community can
connect you with a mentor.

---

## Project 1: Distributed Tracing with OpenTelemetry

### Overview

Venice serves requests across multiple distributed components: clients contact the Router, which fans requests to
Servers, while the Controller orchestrates cluster metadata. Diagnosing latency problems across these tiers currently
requires manually correlating logs from multiple services.

This project adds end-to-end distributed tracing to Venice using the
[OpenTelemetry](https://opentelemetry.io/) standard, enabling engineers to see how a single request flows through
each component with precise timing information at every hop.

### Learning Outcomes

- The OpenTelemetry data model: traces, spans, context propagation, and exporters
- How Venice's multi-tier read path works (client → router → server)
- Instrumentation of a real-world distributed Java system with async I/O

### Scope

**In scope:**

- Add the OpenTelemetry Java SDK as an optional dependency
- Instrument the Thin Client → Router → Server read path with trace spans
- Propagate trace context via the W3C `traceparent` HTTP header across Venice components
- Configuration options to enable/disable tracing and to point at an OTLP exporter endpoint
- Unit and integration tests demonstrating correct context propagation across hops

**Out of scope:**

- Write-path tracing (Venice Push Job, Kafka ingestion, Controller admin channel)
- Automatic instrumentation of RocksDB internal operations
- Custom sampling policies or tail-based sampling

### Key Technical Challenges

- **Context propagation across async boundaries**: Venice's read path uses `CompletableFuture`; spans must
  be carried across thread-pool boundaries without being lost.
- **Zero overhead when disabled**: Tracing must add no measurable latency when not configured, as Venice
  operates in extremely latency-sensitive environments (sub-millisecond p99 targets).
- **Backward compatibility**: Adding new HTTP headers must not break existing clients or servers that do not
  recognize them.

### Suggested Starting Points

- `RouterRequestHandler` in `services/venice-router` — handles incoming client requests and forwards to servers
- `DispatchingAvroGenericStoreClient` in `clients/venice-client` — issues outbound requests to servers
- Venice's existing metrics infrastructure in `internal/venice-common`

---

## Project 2: Chaos Engineering / Fault Injection Testing Framework

### Overview

Venice is designed to tolerate node failures, slow replicas, and transient network issues in production.
However, verifying these fault-tolerance properties systematically requires deliberately inducing failures during
testing. Today, Venice's integration tests exercise the happy path thoroughly, but coverage of failure scenarios
is more limited.

This project builds a lightweight fault injection framework that integrates with Venice's existing integration
test harness, allowing tests to simulate a range of distributed failure modes and assert that Venice recovers
correctly.

### Learning Outcomes

- How distributed systems fail: node crashes, slow replicas, leader election races, split-brain scenarios
- Venice's leader election and failover mechanisms, backed by Apache Helix and ZooKeeper
- Testing methodology for distributed systems: determinism, flakiness mitigation, and recovery assertions

### Scope

**In scope:**

- A `FaultInjector` API that integrates with `VeniceClusterWrapper` (the existing integration test harness)
- Support for the following fault types:
  - Pause and resume individual server or router processes (simulating a stalled node)
  - Kill and restart individual server or router processes (simulating a crash and recovery)
  - Inject artificial latency into client calls (simulating a slow replica)
- Example integration tests that use the framework and validate recovery behavior:
  - Server crash during active ingestion: remaining replicas complete ingestion and the store becomes fully
    available after the killed server rejoins
  - Router failure with client failover: client transparently retries against a healthy router
  - Slow replica detection: the Fast Client avoids the slow replica and meets its latency SLA

**Out of scope:**

- Network-level fault injection requiring OS-level tools (e.g., `tc netem` or `iptables`)
- Automated chaos testing in production environments
- Fault injection targeting the Controller

### Key Technical Challenges

- **Test determinism**: Fault injection tests can be flaky if recovery timing is unpredictable; the framework
  must provide primitives (e.g., "wait until replica count is back to target") to make assertions deterministic.
- **Isolation**: Injected faults in one test must not leak into subsequent tests sharing the same cluster.
- **Integration with the existing test harness**: The framework should extend `VeniceClusterWrapper` cleanly
  rather than requiring tests to be rewritten.

### Suggested Starting Points

- `VeniceClusterWrapper` in `tests/venice-test-common` — the main integration test harness
- `ProcessWrapper` subclasses in `tests/venice-test-common` — wrappers around server/router processes
- Existing integration tests in `tests/venice-integration-tests` for patterns to follow

---

## Project 3: Adaptive Client-Side Request Routing

### Overview

Venice's Fast Client is partition-aware: it knows which servers host each partition and routes requests
directly to them, bypassing the router tier entirely. Currently, when multiple healthy replicas are available
for a partition, replica selection is essentially static (e.g., round-robin). This means a temporarily slow
or error-prone replica continues to receive traffic, degrading p99 tail latency.

This project enhances the Fast Client with health-aware, adaptive replica selection that shifts traffic away
from slow or error-prone replicas at runtime, improving tail latency for latency-sensitive use cases.

### Learning Outcomes

- Load balancing and adaptive routing strategies in distributed systems (Weighted Round Robin, EWMA,
  power-of-two-choices)
- Tail latency mitigation techniques (outlier detection, hedged requests)
- Concurrency and memory efficiency in a high-throughput Java client library

### Scope

**In scope:**

- Track per-replica latency (EWMA) and error rate statistics inside the Fast Client
- Implement a weighted replica selection strategy that scores replicas based on recent latency and error rate
- Decay statistics over time so that a replica that recovers from transient degradation is eventually
  preferred again
- Configuration flags to enable/disable adaptive routing and to tune scoring parameters
- Microbenchmarks comparing adaptive vs. static routing under simulated server degradation
- Unit tests for the scoring and selection logic

**Out of scope:**

- Router-side adaptive routing (only the Fast Client is in scope)
- Cross-datacenter traffic steering
- Integration with external service mesh systems (e.g., Envoy, Istio)

### Key Technical Challenges

- **Thread safety at high throughput**: The Fast Client issues requests from many threads concurrently;
  per-replica statistics must be updated atomically without becoming a scalability bottleneck.
- **Cold start**: On initialization, no latency data has been collected; the strategy must handle unknown
  replicas gracefully (e.g., optimistic equal weighting until sufficient samples exist).
- **Avoiding oscillation**: Overreacting to brief latency spikes can cause all traffic to pile onto a single
  replica, making things worse; the decay rate and scoring function must be tuned carefully.

### Suggested Starting Points

- `InstanceHealthMonitor` in `clients/venice-client` — existing per-instance health tracking
- `RetriableAvroGenericStoreClient` in `clients/venice-client` — retry and routing logic
- [Fast Client documentation](../user-guide/read-apis/fast-client.md)

---

## Project 4: Store Partition Skew Detection and Reporting

### Overview

Venice distributes data across many partitions, each hosted on one or more server nodes. If the data is
heavily skewed—some partitions are much larger than others—certain servers become hotspots while others are
underutilized. Skew can also significantly impact ingestion time because the push job only completes after
the largest partition finishes loading.

This project builds observability tooling to detect, report, and alert on partition size skew across Venice
stores. It is a concrete introduction to how distributed data systems manage load distribution, and how
operational tools are built on top of a distributed system's admin APIs.

### Learning Outcomes

- Data partitioning and consistent hashing in distributed key-value stores
- How Venice exposes per-partition metrics and how the admin protocol works
- Building operational tooling on top of a distributed system's admin interface

### Scope

**In scope:**

- Expose per-partition record count and raw byte size metrics from Venice servers via an existing or new
  server endpoint
- Implement an admin API endpoint (Controller) that aggregates per-partition stats for a given store across
  all replicas and returns a summary
- Add a `venice-admin-tool` CLI command that calls the new endpoint and displays:
  - Partition size distribution (min, max, mean, standard deviation, and p99)
  - A skew score (e.g., coefficient of variation: standard deviation / mean)
- Log a controller-side warning when the skew score for a store exceeds a configurable threshold at the end
  of a push job
- Unit tests for the skew calculation logic and an integration test for the end-to-end flow

**Out of scope:**

- Automatic repartitioning or data rebalancing triggered by skew detection
- Cross-region skew aggregation (single-region scope only)
- Real-time streaming of skew metrics (polling-based only)

### Key Technical Challenges

- **Aggregating across replicas**: Each partition has multiple replicas; the tool must decide which replica's
  stats to use (e.g., leader replica) or how to aggregate them.
- **Impact on serving latency**: Collecting per-partition stats from all servers for a large store must not
  stall foreground read requests. Stats should be collected asynchronously.
- **Protocol backward compatibility**: Extending the admin API protocol or adding a new server endpoint must
  be backward-compatible with older clients and servers.

### Suggested Starting Points

- `AdminSparkServer` in `services/venice-controller` — the controller's admin HTTP interface
- `VeniceAdminTool` in `clients/venice-admin-tool` — the CLI tool for operators
- Existing system store instrumentation in `internal/venice-common` for patterns to follow

---

## Project 5: Hedged Requests for Tail Latency Reduction

### Overview

Tail latency (p99, p999) in distributed storage systems is often caused by a small number of slow replicas: a
garbage collection pause, a compaction stall in RocksDB, or transient network congestion can make one replica
take 10× longer than usual to respond. One proven technique for reducing tail latency is **hedged requests**
(also called speculative execution): after a configurable delay, send the same request to a second replica in
parallel. Whichever replica responds first wins; the other is cancelled.

This project implements hedged requests in Venice's Fast Client, teaching the intern how tail latency arises
and how it can be mitigated without sacrificing correctness.

### Learning Outcomes

- The mechanics of tail latency in distributed storage systems and why p99/p999 diverge from median
- Hedging strategies and the tradeoffs between latency reduction and increased server load
- Cancellation and resource cleanup patterns for in-flight async requests in Java

### Scope

**In scope:**

- A per-store configurable hedge delay (e.g., issue hedge after 2 ms) controlled by a client config
- Hedge the Fast Client's single-get and batch-get paths; the first response wins and the other is cancelled
- Metrics to track hedge rate, hedge benefit (latency improvement), and hedge overhead (extra server load)
- Unit tests for the hedging logic and an integration test validating latency improvement under a simulated
  slow replica

**Out of scope:**

- Hedging in the Thin Client or Da Vinci Client
- Adaptive hedge delay that adjusts automatically based on observed latency distribution
- Cross-datacenter hedging

### Key Technical Challenges

- **Cancellation**: When the first response arrives, the hedged request must be cancelled promptly to avoid
  wasting server resources; Venice's async I/O layer must be inspected to find the right cancellation hook.
- **Avoiding amplification on overload**: If the cluster is slow because it is overloaded, hedging will add
  more load and make things worse. A hedge budget (maximum hedge rate) must be enforced.
- **Idempotency**: Hedged read requests are safe because reads are idempotent, but the implementation must
  verify there are no side effects (e.g., quota accounting) that would be double-counted.

### Suggested Starting Points

- `DispatchingAvroGenericStoreClient` in `clients/venice-client` — issues outbound requests to servers
- `InstanceHealthMonitor` in `clients/venice-client` — per-replica health and latency tracking
- [Fast Client documentation](../user-guide/read-apis/fast-client.md)

---

## Project 6: Request Coalescing for Hot Keys

### Overview

In workloads where a small number of keys receive a disproportionately large share of read traffic (e.g., a
popular product listing or a trending user profile), the same key may be fetched hundreds of times per second
from the same server. Today, each in-flight request is handled independently, meaning the server must perform
the same RocksDB lookup many times concurrently.

**Request coalescing** (also called request deduplication or request collapsing) allows the client or router
to detect when a request for a key is already in-flight, attach the new caller to the existing request, and
return a single shared response to all callers when the one in-flight request completes.

### Learning Outcomes

- The hot key / thundering herd problem in distributed caches and key-value stores
- Concurrent programming patterns in Java: `CompletableFuture` chaining, atomic maps, and callbacks
- The tradeoff between deduplication correctness and added complexity in a client library

### Scope

**In scope:**

- A `CoalescingStoreClient` wrapper that can be layered over the existing Fast Client
- Coalesce concurrent single-get requests for the same key within a configurable time window (e.g., 1 ms)
- Metrics exposing the coalescing hit rate and estimated server load reduction
- Unit tests for coalescing correctness (all waiting callers receive the correct value; failure of the
  in-flight request is propagated to all waiting callers)

**Out of scope:**

- Batch-get coalescing
- Server-side or router-side coalescing
- Coalescing for write operations

### Key Technical Challenges

- **Error propagation**: If the single in-flight request fails, all coalesced callers must receive the
  failure; partial success (some callers getting a response while others get an error) must be avoided.
- **Memory bounding**: The in-flight key map must be bounded so that an extreme burst does not cause
  unbounded memory growth.
- **Correctness under TTL/invalidation**: If a key is deleted between the time the first request is issued
  and the time the response is shared, all coalesced callers must receive a consistent (empty) response.

### Suggested Starting Points

- `DispatchingAvroGenericStoreClient` in `clients/venice-client` — the Fast Client dispatch layer
- `StatTrackingStoreClient` in `clients/venice-client` — example of a client wrapper with metrics

---

## Project 7: Schema Compatibility Checker CLI

### Overview

Venice stores use [Apache Avro](https://avro.apache.org/) schemas for both keys and values. Schema evolution
is allowed, but only under strict compatibility rules (e.g., new fields must have defaults; fields cannot be
removed if they are required). Violating compatibility can silently corrupt data or crash readers.

Today, a Venice operator or user must manually reason about whether a new schema is compatible before
registering it. This project builds a `venice-admin-tool` sub-command that performs static compatibility
checks between an existing registered schema and a candidate new schema, reporting exactly which fields would
violate compatibility and why.

### Learning Outcomes

- Avro schema evolution rules: backward, forward, and full compatibility
- How Venice's schema registry works and the role of ZooKeeper in storing schemas
- Building a developer-friendly CLI diagnostic tool on top of a distributed system's admin API

### Scope

**In scope:**

- A `check-schema-compatibility` sub-command in `venice-admin-tool` that accepts:
  - A store name and cluster
  - A path to a candidate schema file (JSON)
  - A compatibility mode flag: `BACKWARD`, `FORWARD`, `FULL`, or `NONE`
- The command fetches all registered schemas for the store from the Controller, runs the Avro compatibility
  check against each, and outputs a human-readable report listing:
  - Compatible: a summary of compatible existing schema versions
  - Incompatible: the specific field-level violations for each incompatible existing schema version
- Unit tests covering all four compatibility modes and common violation types (removed required field,
  type change, missing default)

**Out of scope:**

- Automated schema registration based on compatibility check results
- Compatibility checks across key schemas and value schemas simultaneously
- Integration into the Venice Push Job as a pre-flight check

### Key Technical Challenges

- **Fetching schemas from a live cluster**: The tool must authenticate and call the Controller's existing
  schema endpoint, then deserialize the response reliably.
- **Actionable error messages**: Generic Avro error messages are difficult to parse; the tool must translate
  them into field-level, human-readable explanations that non-expert users can act on.
- **Handling many schema versions**: A store may have dozens of registered schemas; the compatibility check
  must scale to all of them without being prohibitively slow.

### Suggested Starting Points

- `ControllerClient` in `internal/venice-common` — client for calling Controller admin endpoints
- `VeniceAdminTool` in `clients/venice-admin-tool` — existing CLI sub-commands as patterns to follow
- [Apache Avro schema compatibility documentation](https://avro.apache.org/docs/current/spec.html#Schema+Resolution)

---

## Project 8: Client-Side Per-Store Rate Limiting

### Overview

Venice's backend enforces a read quota per store on the server side, but by the time a request is rejected
server-side, the client has already paid the cost of serialization, a network round-trip, and deserialization
of the error response. For clients that are misconfigured or suddenly experience a traffic spike, this
results in a stream of rejected requests and unnecessary load on the backend.

This project implements a **client-side token-bucket rate limiter** that prevents a client application from
sending more requests than its configured quota, short-circuiting quota violations locally before they
reach the network.

### Learning Outcomes

- Rate limiting algorithms: token bucket, leaky bucket, and sliding window
- The tradeoff between strict quota enforcement (server-side) and low-latency rejection (client-side)
- Designing a configurable, observable rate limiter in a high-throughput Java library

### Scope

**In scope:**

- A `RateLimitingStoreClient` wrapper that can be layered over any Venice client
- Token bucket algorithm with configurable tokens-per-second and burst size, set via client config or
  per-store store config fetched from the cluster
- When the bucket is exhausted, return a `QuotaExceededException` immediately without sending a network
  request
- Metrics exposing current bucket fill level, rejection rate, and pass-through rate
- Unit tests for the token bucket logic and burst behavior

**Out of scope:**

- Distributed rate limiting across multiple client instances sharing a quota
- Dynamic quota updates pushed from the server without client restart
- Write-path rate limiting

### Key Technical Challenges

- **Clock precision**: High-throughput clients issue thousands of requests per second; the rate limiter
  must refill the bucket accurately without calling `System.currentTimeMillis()` on every request (which
  can be expensive under contention).
- **Fairness across threads**: Multiple threads share the same client instance; the token bucket must be
  updated atomically without becoming a scalability bottleneck (e.g., using `AtomicLong` CAS operations).
- **Integration with retry logic**: The existing retry logic must be aware of rate limit rejections so it
  does not automatically retry a locally-rejected request and re-consume tokens.

### Suggested Starting Points

- `StatTrackingStoreClient` in `clients/venice-client` — example of a wrapper around a store client
- `VeniceClientConfig` in `clients/venice-thin-client` — per-client configuration patterns
- Venice's existing quota enforcement code in `services/venice-router`

---

## Project 9: Graceful Server Draining

### Overview

When a Venice server needs to be restarted for an upgrade or routine maintenance, it abruptly drops all
in-flight requests from the clients connected to it. Clients then see a spike of errors or timeouts, and
must retry against other replicas. For p99-sensitive use cases, this is a noticeable and avoidable source
of tail latency.

**Graceful draining** is a coordinated shutdown sequence: the server signals that it is about to stop
accepting new work, waits for all in-flight requests to complete (or a configurable deadline to pass),
and only then shuts down. During the drain window, the router and Fast Client route new requests to
other replicas.

### Learning Outcomes

- Graceful shutdown patterns in distributed services
- How Venice's router and Fast Client discover server health via Apache Helix and ZooKeeper
- The tradeoff between drain speed (fast restarts) and completeness (zero dropped requests)

### Scope

**In scope:**

- A drain mode triggered by a new admin endpoint (`POST /drain`) or a JVM shutdown hook
- While in drain mode, the server:
  - Stops announcing itself as healthy to Helix so the router and Fast Client stop routing new requests to it
  - Continues serving requests that are already in-flight
  - Waits until either in-flight request count reaches zero or a configurable `drain.timeout.seconds` elapses
- Metrics tracking drain progress (in-flight request count, drain duration)
- An integration test verifying that a draining server receives no new requests and that clients experience
  zero errors during a drain

**Out of scope:**

- Draining the ingestion path (ongoing RocksDB compactions, Kafka consumption)
- Coordinated rolling restarts across multiple servers simultaneously
- Drain support in the Controller

### Key Technical Challenges

- **In-flight request tracking**: The server must accurately count in-flight requests, including those that
  are currently waiting for RocksDB I/O, without introducing per-request overhead in the hot path.
- **Helix state transition timing**: Removing a server from Helix's external view must happen quickly enough
  that the router stops routing before new requests arrive, but the transition must not be so aggressive that
  it triggers unnecessary leader re-elections.
- **Drain timeout correctness**: If the drain deadline passes with requests still in-flight, the server
  must shut down cleanly rather than hanging indefinitely.

### Suggested Starting Points

- `VeniceServer` in `services/venice-server` — the server's main entry point and shutdown hooks
- `RouterServer` in `services/venice-router` — how the router reacts to Helix state changes
- `HelixParticipationService` in `services/venice-server` — how the server announces itself to Helix

---

## Project 10: Bloom Filter–Accelerated Negative Lookups

### Overview

A significant fraction of reads to any key-value store are for keys that do not exist. Today, Venice serves
these "negative lookups" by performing a full RocksDB point lookup, which involves seeking through several
levels of SST files before confirming that a key is absent. A **Bloom filter** is a space-efficient
probabilistic data structure that can answer "is this key definitely not in this partition?" in nanoseconds,
short-circuiting the RocksDB lookup entirely for the majority of negative cases.

This project builds per-partition Bloom filters for Venice servers, introduces an API to populate them
during the ingestion phase, and hooks them into the read path to accelerate negative lookups.

### Learning Outcomes

- Bloom filters: bit arrays, hash functions, false positive rate, and size/accuracy tradeoffs
- Venice's storage layer: how RocksDB stores data in SST files and why point lookups are expensive
- The interaction between the ingestion path and the read path in Venice servers

### Scope

**In scope:**

- A Bloom filter implementation (or use of an existing library such as Guava's `BloomFilter`) scoped to
  a single store partition in memory
- Populate the filter during batch push ingestion for batch-only stores: keys are streamed into the filter
  as records arrive
- On a negative RocksDB lookup, check the Bloom filter first and skip RocksDB entirely if the filter says
  the key is absent
- A store config option to enable or disable the Bloom filter
- Metrics tracking filter memory usage, false positive rate (estimated), and lookup short-circuit rate
- Unit tests verifying correctness (no false negatives) and an integration test measuring latency
  improvement for a workload with a high miss rate

**Out of scope:**

- Bloom filter support for hybrid (nearline-write) stores, where keys arrive continuously after ingestion
- Persistent Bloom filters that survive server restarts
- Per-partition Bloom filter sharing across replicas

### Key Technical Challenges

- **No false negatives**: A Bloom filter may have false positives (saying a key is absent when it is
  present), but must never have false negatives. The implementation must guarantee this property even
  under concurrent reads and writes.
- **Memory footprint**: For a store with billions of keys, a Bloom filter can consume significant memory.
  The filter size must be configurable, and the default sizing heuristic (based on expected key count and
  desired false positive rate) must be well-documented.
- **Invalidation on nearline writes**: For hybrid stores that receive nearline writes after the batch
  ingestion is complete, a new key might be written after the filter was built; the implementation must
  either update the filter or disable it for hybrid stores.

### Suggested Starting Points

- `RocksDBStoragePartition` in `services/venice-server` — the RocksDB wrapper used by Venice servers
- `StoreIngestionTask` in `services/venice-server` — the ingestion loop where records are written to RocksDB
- `StorageEngineRepository` in `services/venice-server` — how storage partitions are managed

---

## Project 11: Cross-Region Replication Lag Monitoring

### Overview

Venice supports active-active multi-region replication, where writes in one region are replicated to all
other regions via Kafka. When replication falls behind—due to a slow consumer, a Kafka topic backup, or a
network outage—serving applications in lagging regions may read stale data without any visibility into
how stale that data is.

This project builds tooling to measure, expose, and alert on **cross-region replication lag** (the delay
between a write landing in the source region and being confirmed visible in each destination region).

### Learning Outcomes

- How multi-region replication works in Venice: the role of the replication topic, leader servers, and
  the remote consumption path
- Kafka consumer lag as a proxy for replication lag and its limitations
- Building a monitoring sub-system that correlates metadata across regions

### Scope

**In scope:**

- A replication lag metric computed as: `current_time - timestamp_of_oldest_unconsumed_message` for
  each remote region consumer, exposed as a server-side gauge metric per store per source region
- A `venice-admin-tool` CLI command that queries each server, aggregates per-partition lag values, and
  reports a per-store lag summary (max, p99, mean) across all partitions and destination regions
- A controller-side config for a per-store lag alert threshold; log a warning when any partition exceeds it
- Unit tests for the lag calculation logic and an integration test with a simulated lagging consumer

**Out of scope:**

- Automatic remediation when lag exceeds a threshold (e.g., triggering a repush)
- Lag monitoring for the nearline real-time topic (focus on the version topic used for batch pushes)
- A persistent lag time series (metrics are point-in-time snapshots)

### Key Technical Challenges

- **Timestamp availability**: Kafka message timestamps can be producer-side (event time) or broker-side
  (log append time); the implementation must choose the right timestamp type and handle cases where
  timestamps are not available.
- **Aggregating across partitions and regions**: A store can have hundreds of partitions replicated to
  several regions; the CLI command must aggregate results efficiently without overwhelming the servers
  with concurrent requests.
- **Distinguishing lag from empty topics**: A consumer that is fully caught up reports zero lag; the
  metric must distinguish "all messages consumed" from "no messages ever written" to avoid false alerts.

### Suggested Starting Points

- `KafkaStoreIngestionService` in `services/venice-server` — the remote consumption path
- `ControllerClient` in `internal/venice-common` — admin API client
- Existing Kafka consumer lag utilities in `internal/venice-common`

---

## Project 12: Da Vinci Client Memory Pressure Handling

### Overview

The Da Vinci Client (DVC) eagerly loads store data into local RocksDB and RAM to serve sub-millisecond
lookups. When the host's available memory decreases (e.g., other processes on the same JVM consume more
heap, or additional stores are loaded), the DVC does not currently take any adaptive action: it may
consume more RAM than the host can provide, leading to GC pressure, OOM errors, or OS-level swap usage.

This project implements a **memory pressure handler** that monitors the DVC's RAM usage and triggers
configurable responses (reducing block cache size, pausing ingestion of non-critical stores, or emitting
eviction metrics) when usage crosses a threshold.

### Learning Outcomes

- How RocksDB manages memory: block cache, memtable, and index/filter blocks
- JVM memory management: heap vs. off-heap, GC pressure, and native memory tracking
- Feedback control loops in distributed system resource management

### Scope

**In scope:**

- A background thread that monitors total RocksDB memory usage (block cache + memtable + indexes) across
  all DVC-owned partitions using RocksDB's `MemoryUsage` API
- When usage exceeds a configurable high-watermark percentage of a configured memory budget:
  - Dynamically shrink the shared RocksDB block cache to a configured low-watermark level
  - Emit a metric and a log warning describing the memory pressure event
- When usage drops below the low-watermark, restore the block cache to its original size
- Unit tests for the threshold logic and a test verifying that the block cache is resized correctly

**Out of scope:**

- Evicting entire store partitions from memory
- Cross-store memory budget coordination when multiple DVCs run in the same JVM
- Integration with JVM heap pressure (focus on native/off-heap RocksDB memory only)

### Key Technical Challenges

- **Accuracy of memory estimates**: RocksDB's reported memory usage is an approximation; the implementation
  must account for this and avoid thrashing (repeatedly resizing the cache due to small fluctuations).
- **Thread safety of RocksDB resizing**: Changing the block cache size while reads are in-flight must not
  corrupt in-progress lookups; the RocksDB API must be used safely in a concurrent context.
- **Interaction with ingestion**: Shrinking the block cache may slow ingestion (more disk I/O needed); the
  implementation must not reduce the cache so aggressively that ingestion stalls.

### Suggested Starting Points

- `RocksDBStorageEngine` in `clients/da-vinci-client` — the DVC's RocksDB storage wrapper
- `DaVinciClient` in `clients/da-vinci-client` — the main DVC entry point
- [Da Vinci Client documentation](../user-guide/read-apis/da-vinci-client.md)

---

## Project 13: Push Job Progress Dashboard (CLI)

### Overview

Venice's push jobs can take minutes to hours to complete, ingesting data across many partitions and regions.
Today, an operator who wants to know how a push is progressing must read through log lines or call raw
admin API endpoints. This project builds an interactive CLI dashboard that provides a clear, real-time
view of push job progress for all active push jobs in a cluster.

### Learning Outcomes

- How Venice tracks push job state: push job status records, partition ingestion offsets, and the
  push job details system store
- Polling-based distributed monitoring: how to aggregate state from many sources efficiently
- Terminal UI development in Java or Python for operator tooling

### Scope

**In scope:**

- A `venice-admin-tool` sub-command `watch-pushes` that polls the Controller every few seconds and
  renders a live table showing, for each active push job:
  - Store name, version number, and push start time
  - Per-region ingestion progress as a percentage (partitions completed / total partitions)
  - Estimated time to completion (extrapolated from the current ingestion rate)
  - Current push status (STARTED, COMPLETED, ERROR, etc.)
- The dashboard refreshes in-place (using ANSI escape codes) rather than scrolling new lines
- A `--store` flag to filter the view to a single store
- Unit tests for the progress percentage and ETA calculations

**Out of scope:**

- A web-based or graphical dashboard
- Historical push job history (only active jobs are shown)
- Displaying per-partition (rather than per-region aggregate) progress

### Key Technical Challenges

- **Rate of API calls**: A cluster may have many active push jobs and many regions; the polling loop must
  batch requests to avoid overwhelming the Controller with concurrent admin API calls.
- **ETA accuracy**: Ingestion is not always linear (it accelerates as data is cached); a naive linear
  extrapolation will be inaccurate during the early stages of a push. The ETA estimate must be clearly
  labeled as an approximation.
- **Terminal compatibility**: ANSI escape codes for in-place rendering are not supported on all terminals;
  the tool must degrade gracefully to plain scrolling output.

### Suggested Starting Points

- `ControllerClient` in `internal/venice-common` — provides `queryJobStatus` and related APIs
- `VeniceAdminTool` in `clients/venice-admin-tool` — existing CLI sub-commands as patterns to follow
- The Push Job Details [system store](../operations/data-management/system-stores.md)

---

## Project 14: Automated RocksDB Compaction Metrics Exposure

### Overview

Venice servers store all data in [RocksDB](https://rocksdb.org/), a log-structured merge tree (LSM)
key-value engine. RocksDB's compaction process—merging and garbage-collecting SST files across levels—has
a direct impact on read latency (compaction stalls), write amplification (disk wear), and storage space
usage. Currently, very few of RocksDB's rich internal statistics are surfaced to Venice's metrics system,
making it difficult for operators to detect and respond to compaction-related issues.

This project extracts a curated set of RocksDB compaction and LSM statistics and integrates them into
Venice's existing metrics pipeline, enabling operators to understand the health of the storage layer at
a glance.

### Learning Outcomes

- LSM tree internals: levels, memtables, SST files, compaction triggers, and write amplification
- How RocksDB exposes internal statistics via the Java API (`Statistics`, `ColumnFamilyMetaData`)
- Integrating a third-party engine's metrics with an existing application monitoring pipeline

### Scope

**In scope:**

- Expose the following RocksDB statistics as Venice server metrics, tagged by store and partition:
  - Total compaction bytes read and written (write amplification proxy)
  - Number of compactions in progress and in the compaction queue
  - Per-level SST file count and total size
  - Memtable flush count and total bytes flushed
  - Block cache hit rate and miss rate
- A background thread that samples these statistics periodically (e.g., every 60 seconds) and publishes
  them to Venice's metrics registry
- Unit tests verifying that each metric is populated with a non-zero value after data is written to RocksDB

**Out of scope:**

- Per-SST file granularity metrics (per-level aggregates are sufficient)
- Changes to RocksDB compaction policy or tuning parameters
- Dashboards or alerting rules (exposing the metrics is sufficient)

### Key Technical Challenges

- **Statistics overhead**: Enabling full RocksDB statistics collection adds measurable CPU overhead;
  the implementation must sample only the statistics needed and must be benchmarked to verify the overhead
  is acceptable.
- **Metric cardinality**: Tagging metrics by both store and partition can produce a large number of
  unique time series if a cluster has many stores and partitions. A configurable opt-in mode or
  aggregation strategy is needed.
- **API availability across RocksDB versions**: Venice pins a specific version of RocksJava; the
  statistics APIs used must be available in that version.

### Suggested Starting Points

- `RocksDBStoragePartition` in `services/venice-server` — the RocksDB Java wrapper used by Venice
- Venice's metrics registry in `internal/venice-common` — how metrics are registered and emitted
- [RocksDB Statistics documentation](https://github.com/facebook/rocksdb/wiki/Statistics)
