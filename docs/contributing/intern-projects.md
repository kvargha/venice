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

---

## Project 15: Admin API Audit Log

### Overview

Venice's Controller exposes an admin API that operators use to create and delete stores, update
configurations, trigger repushes, and perform other cluster-management operations. Today, there is no
persistent record of who called which admin endpoint and when. If a misconfiguration causes a production
incident, operators must correlate timestamps with server logs scattered across machines.

This project implements an **admin audit log** that records every mutating admin API call—who made it,
what operation was performed, and what changed—to a dedicated Kafka topic, giving operators an immutable,
chronologically ordered history of all cluster changes.

### Resume Impact

*"Built a distributed audit logging system for a planet-scale storage service, publishing all admin
operations to a Kafka topic with structured metadata for post-incident analysis."*

### Learning Outcomes

- Event sourcing and audit trail patterns in distributed systems
- Kafka as a durable, ordered log for operational events
- The Venice admin API surface and how the Controller processes admin requests

### Scope

**In scope:**

- An `AuditLogService` that intercepts mutating admin API calls in the Controller (e.g., store creation,
  deletion, config update, schema registration) after they are applied
- Each audit event records: timestamp, caller IP and principal, operation name, target store/cluster,
  before/after config snapshot (serialized as JSON), and outcome (success / error)
- Publish audit events to a dedicated Kafka topic (`venice-admin-audit-log`) created automatically by
  the Controller at startup
- A `venice-admin-tool` sub-command `dump-audit-log` that reads and pretty-prints recent events from the
  audit topic
- Unit tests for event serialization and an integration test verifying that a store creation generates
  the correct audit event

**Out of scope:**

- Read-only admin API calls (audit only mutating operations)
- Encryption or redaction of sensitive config values within audit records
- Integration with external SIEM or audit systems

### Key Technical Challenges

- **Decoupling from the request path**: Publishing to Kafka must not add latency to the admin operation;
  audit events should be published asynchronously with a bounded queue.
- **Schema evolution of audit events**: Audit events must be interpretable by future versions of the
  `dump-audit-log` tool, even as new fields are added; using Avro with backward-compatible evolution is
  recommended.
- **At-least-once vs. exactly-once delivery**: If the Controller crashes after applying an admin operation
  but before publishing the audit event, the event is lost. The implementation must document this tradeoff
  and choose an acceptable delivery guarantee.

### Suggested Starting Points

- `AdminSparkServer` in `services/venice-controller` — the Controller's admin HTTP interface
- `VeniceHelixAdmin` in `services/venice-controller` — where admin operations are applied
- Venice's Kafka producer utilities in `internal/venice-common`

---

## Project 16: Consistent-Hash Ring Visualizer

### Overview

Venice distributes data across partitions using a consistent-hash ring, and assigns those partitions to
server nodes using Apache Helix. When the cluster expands (new nodes added) or contracts (nodes removed),
partition assignments change. Today, there is no visual way for an operator to see the current assignment
or to understand what will change if a server is added or removed.

This project builds a `venice-admin-tool` sub-command that queries the current Helix assignment and renders
a clear, text-based visualization of the partition-to-server assignment, including highlighting imbalances
and predicted changes from proposed topology changes.

### Resume Impact

*"Built a distributed cluster topology visualization tool that displays consistent-hash partition
assignments and quantifies load imbalance across server nodes in a large-scale key-value store."*

### Learning Outcomes

- Consistent hashing and why it minimizes data movement during cluster membership changes
- Apache Helix's resource/partition/replica model and how Venice uses it for cluster management
- Command-line visualization techniques for communicating distributed state to operators

### Scope

**In scope:**

- A `show-assignment` sub-command that queries the Helix external view for a given store and renders:
  - A table of server → partitions (leader + follower) with counts
  - An imbalance score (standard deviation of partition count per server)
  - A visual bar chart (ASCII) showing relative load per server
- A `--simulate-add-server` and `--simulate-remove-server` flag that shows the predicted assignment after
  a topology change without actually applying it
- Unit tests for the imbalance score calculation

**Out of scope:**

- Actually triggering partition rebalancing (read-only visualization only)
- Cross-cluster or cross-region assignment views
- Real-time streaming of assignment changes

### Key Technical Challenges

- **Querying Helix state**: Helix external view data is stored in ZooKeeper; the tool must query it through
  Venice's existing `HelixBaseRoutingRepository` abstraction without duplicating ZooKeeper connection logic.
- **Simulating assignment changes**: Predicting the post-change assignment requires understanding Helix's
  rebalancing algorithm; using the existing `HelixRebalancer` in simulation mode (if available) is
  preferable to re-implementing the algorithm.
- **Large clusters**: A cluster may have thousands of partitions; the visualization must paginate or
  summarize rather than printing thousands of rows.

### Suggested Starting Points

- `HelixBaseRoutingRepository` in `services/venice-router` — reads Helix external view for partition routing
- `VeniceAdminTool` in `clients/venice-admin-tool` — existing sub-commands as patterns to follow
- Apache Helix documentation on [External View](https://helix.apache.org/Concepts.html)

---

## Project 17: Store Snapshot Export to Parquet

### Overview

Venice stores data in RocksDB, which is a binary format not directly readable by data analysis tools.
When debugging a data quality issue, ML engineers and data scientists often want to inspect the actual
contents of a Venice store—checking for missing keys, incorrect values, or unexpected distributions—but
they have no easy way to do so today.

This project adds a `venice-admin-tool` command that reads the current version of a Venice store from
its RocksDB files (via the Da Vinci Client) and writes it to a local
[Apache Parquet](https://parquet.apache.org/) file, making it trivially inspectable with standard data
tools (DuckDB, Pandas, Spark).

### Resume Impact

*"Implemented a distributed data export pipeline that reads from a planet-scale RocksDB-backed key-value
store and converts records to columnar Parquet format, enabling self-serve data quality debugging for ML
engineers."*

### Learning Outcomes

- How the Da Vinci Client eagerly loads partitions into local storage and serves zero-hop reads
- Apache Parquet's columnar format: row groups, column encodings, and compression
- The Venice Avro schema system and how Avro records map to Parquet columns

### Scope

**In scope:**

- A `export-store` sub-command in `venice-admin-tool` that:
  - Uses the Da Vinci Client to load a specified store locally
  - Iterates over all key-value pairs in the loaded partitions
  - Writes records to a Parquet file using the
    [Apache Parquet Java library](https://github.com/apache/parquet-mr), mapping Avro schema fields to
    Parquet columns
  - Supports a `--limit` flag to export only a sample of records (for large stores)
- Unit tests verifying correct Avro-to-Parquet type mapping for common field types (string, int, array, map)

**Out of scope:**

- Exporting to formats other than Parquet (CSV, JSON, etc.)
- Streaming export for stores that do not fit in local disk
- Resumable exports (if interrupted, restart from scratch)

### Key Technical Challenges

- **Avro-to-Parquet schema mapping**: Venice uses complex Avro schemas (unions, nested records, maps, arrays);
  the mapping to Parquet's type system is non-trivial, particularly for nullable unions.
- **Memory efficiency**: Loading a large store into the DVC and iterating over it must not cause an OOM;
  records must be streamed rather than buffered all at once.
- **Da Vinci Client teardown**: The DVC opens RocksDB files and subscribes to Kafka; the export command
  must ensure the DVC is cleanly shut down after the export completes, even on failure.

### Suggested Starting Points

- `DaVinciClient` in `clients/da-vinci-client` — the client used to load store data locally
- [Da Vinci Client documentation](../user-guide/read-apis/da-vinci-client.md)
- [Apache Parquet-MR Avro module](https://github.com/apache/parquet-mr/tree/master/parquet-avro)

---

## Project 18: Key Sampling for Hot-Key Detection

### Overview

In production Venice clusters, certain keys receive orders-of-magnitude more traffic than others (e.g., a
frequently viewed profile or a globally popular feature). These "hot keys" can saturate the server
partitions that host them, degrading latency and availability for all other keys on that partition. Today,
Venice does not have built-in visibility into which specific keys are the hottest.

This project implements a **probabilistic key sampling** mechanism in Venice servers that maintains a
compact top-K hot-key leaderboard per partition using a Count-Min Sketch or Space-Saving algorithm, and
exposes it via a server endpoint and admin tool command.

### Resume Impact

*"Designed and implemented a memory-efficient probabilistic hot-key detection system for a distributed
key-value store using the Space-Saving frequency estimation algorithm, enabling self-serve hotspot
diagnosis for production engineers."*

### Learning Outcomes

- Frequency estimation algorithms: Count-Min Sketch, Space-Saving (Heavy Hitters)
- The tradeoff between detection accuracy, memory usage, and CPU overhead in the read hot path
- How Venice's server handles concurrent read requests at high throughput

### Scope

**In scope:**

- A per-partition `HotKeyTracker` that uses the Space-Saving algorithm to maintain a configurable top-K
  most-frequent keys (e.g., K=100) using bounded memory
- Update the tracker on each incoming single-get and batch-get request
- Expose the top-K list via a new server `/hot-keys` endpoint, returning key bytes and estimated frequency
- A `venice-admin-tool` sub-command `show-hot-keys` that queries all replicas of a store and aggregates
  results
- A server config to enable/disable tracking and tune K
- Unit tests for the Space-Saving tracker and an integration test verifying that repeated reads of the
  same key surface it in the top-K list

**Out of scope:**

- Tracking write-path hot keys
- Automatic mitigation (e.g., replication factor increase) when hot keys are detected
- Persistent hot-key history

### Key Technical Challenges

- **CPU overhead in the read hot path**: The tracker must be updated on every request; the data structure
  must be designed so that updates are O(1) or O(log K) and do not add more than a few microseconds of
  overhead.
- **Thread safety**: Multiple reader threads update the same per-partition tracker concurrently; the
  implementation must use lock-free or fine-grained locking strategies.
- **Key byte comparison**: Venice keys are arbitrary byte arrays; the tracker must compare and store
  them efficiently without deserializing them.

### Suggested Starting Points

- `VeniceServerRequestHandler` in `services/venice-server` — handles incoming read requests
- `ReadQuotaEnforcementHandler` in `services/venice-server` — example of per-request bookkeeping in the
  server hot path
- [Space-Saving algorithm paper](https://www.cs.ucsb.edu/sites/default/files/documents/2005-23.pdf)

---

## Project 19: Automated Compression Strategy Benchmarking

### Overview

Venice supports multiple compression strategies for store values: `NO_OP`, `GZIP`, `ZSTD`, and
`ZSTD_WITH_DICT`. The right choice significantly affects CPU usage (compression/decompression overhead),
storage footprint, and network bandwidth. Today, selecting a strategy requires manual experimentation;
many users leave the default even when a better option exists.

This project automates the selection process by building a benchmarking tool that runs during the push
job, samples representative records, compresses them with each supported strategy, and recommends the
strategy with the best size/speed tradeoff.

### Resume Impact

*"Built an automated compression benchmarking pipeline for a distributed data ingestion system that
samples production data and recommends optimal compression strategies, reducing storage footprint by up
to 40% for typical ML feature stores."*

### Learning Outcomes

- Compression algorithms: GZIP vs. Zstandard and the role of pre-trained dictionaries
- The tradeoff between compression ratio, compression speed, and decompression speed in a low-latency system
- How Venice's push job pipeline works and where compression fits in

### Scope

**In scope:**

- A `BenchmarkCompressionStrategy` mode for the Venice Push Job, triggered by a new `--benchmark-compression`
  flag
- Sample up to a configurable number of records (e.g., 10,000) from the input dataset
- For each candidate strategy, measure: compressed size (bytes), compression time (ms), and estimated
  decompression time (ms) using the Venice codec implementations
- Print a summary table and emit a recommendation (highest compression ratio within a configurable
  decompression latency budget)
- Unit tests for the benchmarking logic with synthetic Avro records

**Out of scope:**

- Automatically applying the recommended strategy without user confirmation
- Online benchmarking during serving (only during the push job)
- Benchmarking encryption strategies

### Key Technical Challenges

- **Representative sampling**: Randomly sampling records from a distributed input dataset (Hadoop, Spark)
  requires drawing samples from multiple splits; the sample must be large enough to be representative
  but small enough to complete quickly.
- **Zstd dictionary training**: Zstd with a dictionary requires training the dictionary on the sample data
  before benchmarking; the tool must include this training time in its measurement.
- **Benchmark variability**: JVM JIT warm-up can distort short benchmarks; the tool must discard the
  first few measurements (warm-up iterations) before collecting results.

### Suggested Starting Points

- `VenicePushJob` in `clients/venice-push-job` — the entry point for push job logic
- `CompressionStrategy` enum in `internal/venice-common` — the set of supported strategies
- Venice's Zstd codec implementations in `internal/venice-common`

---

## Project 20: Controller Leader Election Observability

### Overview

Venice uses Apache Helix to elect a single active Controller per cluster (the "parent controller"), while
each region also has a child controller. When the active Controller fails or is restarted, Helix initiates
a new leader election. During this period, admin operations are unavailable. Today, the frequency,
duration, and cause of Controller failovers are not tracked in any structured way, making it hard to
reason about Controller stability.

This project instruments the Controller failover path with structured metrics and logs, and adds a
`venice-admin-tool` command to query the history of recent failovers.

### Resume Impact

*"Instrumented leader election and failover events in a distributed consensus-based control plane,
reducing mean time to detect Controller instability from hours to seconds."*

### Learning Outcomes

- Leader election in distributed systems: Paxos/ZooKeeper-based election and split-brain prevention
- How Apache Helix and ZooKeeper coordinate Controller leader transitions in Venice
- Structured operational observability: events, metrics, and runbooks

### Scope

**In scope:**

- Hook into the Helix `ControllerChangeListener` to record: timestamp of becoming leader, timestamp of
  losing leadership, cluster name, and hostname
- Persist failover events to a lightweight ring buffer in the Controller's ZooKeeper node (last 100 events)
- Expose failover metrics: `controller.leadership.acquired.count`, `controller.leadership.lost.count`,
  `controller.leadership.duration.seconds`
- A `venice-admin-tool` sub-command `show-controller-history` that reads and displays recent failover
  events from ZooKeeper
- Unit tests for the event recording logic

**Out of scope:**

- Root cause analysis of why a failover occurred
- Automatic remediation (e.g., restarting a misbehaving Controller)
- Multi-region parent controller failover history (focus on a single region)

### Key Technical Challenges

- **Concurrency during transition**: The ControllerChangeListener callback fires while the Controller is in
  mid-transition; recording events must not block or interfere with the election process itself.
- **ZooKeeper write size limits**: ZooKeeper has a default max node size of 1 MB; the ring buffer
  serialization must be compact enough to respect this limit.
- **Clock skew**: Different Controller hosts may have slightly different clocks; event timestamps must be
  noted as approximate and ideally use ZooKeeper's own event timestamps for ordering.

### Suggested Starting Points

- `VeniceHelixAdmin` in `services/venice-controller` — the Controller's Helix integration
- `ZkHelixAdminClient` in `internal/venice-common` — Venice's ZooKeeper/Helix client wrapper
- Apache Helix `ControllerChangeListener` Javadoc

---

## Project 21: Incremental Da Vinci Client Bootstrap Checkpointing

### Overview

When a Da Vinci Client (DVC) is started fresh or restarted on a new host, it must consume the entire
Version Topic for each subscribed store partition before it can serve reads. For large stores, this
bootstrap can take many minutes. If the DVC crashes or the host is restarted mid-bootstrap, the progress
is lost and the entire bootstrap restarts from the beginning.

This project implements **incremental bootstrap checkpointing**: the DVC periodically flushes its
ingestion progress (latest consumed Kafka offset per partition) to a local file. On restart, it resumes
from the last checkpoint, dramatically reducing the re-bootstrap time after a crash.

### Resume Impact

*"Implemented fault-tolerant incremental checkpointing for a distributed eager-caching client, reducing
crash-recovery bootstrap time by up to 90% for large dataset stores."*

### Learning Outcomes

- Kafka consumer offset management and the role of checkpointing in fault-tolerant stream processing
- Write-ahead log and checkpoint file design: durability, atomicity, and crash recovery
- The Venice Da Vinci Client's ingestion lifecycle and the difference between batch and nearline stores

### Scope

**In scope:**

- A `BootstrapCheckpointManager` that writes the latest committed Kafka offset for each subscribed
  partition to a local file (e.g., a simple Java properties file or a small binary format)
- Checkpoint is flushed: (a) periodically (configurable interval, default 30 seconds), and (b) after
  each successful version swap
- On DVC startup, if a checkpoint file exists for a partition and the checkpointed offset is within the
  current Version Topic's range, resume consumption from that offset
- A DVC config to enable/disable checkpointing and to set the checkpoint directory and flush interval
- Unit tests for checkpoint write/read correctness and a test simulating a mid-bootstrap crash followed
  by recovery

**Out of scope:**

- Checkpointing for nearline (real-time) partitions (batch-only stores first)
- Distributed checkpoint coordination across multiple DVC instances on different hosts
- Migrating existing DVC deployments to the checkpoint format without downtime

### Key Technical Challenges

- **Atomic checkpoint writes**: A crash during a checkpoint write must not leave a corrupt checkpoint file;
  use write-to-temp-file-then-rename for atomic updates.
- **Offset validity**: The checkpointed offset must be validated against the current topic's start offset
  before using it; if the topic has been compacted or recreated, the checkpoint is stale and must be
  discarded.
- **Interaction with version swaps**: When a new store version is pushed, old partition checkpoints become
  invalid; the manager must invalidate checkpoints for partitions belonging to the old version.

### Suggested Starting Points

- `StoreIngestionTask` in `services/venice-server` (also used by DVC) — the ingestion loop
- `DaVinciBackend` in `clients/da-vinci-client` — manages partition subscriptions and version lifecycle
- [Da Vinci Client documentation](../user-guide/read-apis/da-vinci-client.md)

---

## Project 22: gRPC Health Check and Readiness Probes

### Overview

Venice already uses gRPC for communication between the Fast Client and servers. However, Venice services
(Controller, Router, Server) do not implement the standard
[gRPC Health Checking Protocol](https://github.com/grpc/grpc/blob/master/doc/health-checking.md), which is
required for first-class integration with container orchestration systems such as Kubernetes and for
standard load balancer health checks. This makes it harder to run Venice on modern infrastructure.

This project implements the gRPC Health Check service across Venice's gRPC-enabled components, and adds
readiness/liveness semantics that reflect the actual service state.

### Resume Impact

*"Implemented the gRPC Health Checking Protocol across distributed storage service components, enabling
Kubernetes-native liveness and readiness probes and improving deployment reliability in containerized
environments."*

### Learning Outcomes

- The gRPC Health Checking Protocol and how it integrates with Kubernetes probes
- The difference between liveness (is the process running?) and readiness (is it ready to serve?)
  in distributed services
- How Venice components transition through startup states (e.g., a server ingesting data before it is
  ready to serve reads)

### Scope

**In scope:**

- Implement `grpc.health.v1.Health/Check` and `grpc.health.v1.Health/Watch` on the Venice Server's
  existing gRPC port
- Liveness: return `SERVING` once the gRPC server is bound; return `NOT_SERVING` if the server is
  shutting down
- Readiness: return `SERVING` only after the server has finished bootstrapping at least one partition;
  return `NOT_SERVING` during initial bootstrap or graceful drain
- Unit tests verifying the correct `ServingStatus` is returned for each lifecycle state
- An integration test verifying that a server in drain mode returns `NOT_SERVING`

**Out of scope:**

- Health checks on the Controller or Router (focus on the Venice Server)
- Per-store or per-partition readiness granularity
- Automatic restart on failed liveness checks (that is the responsibility of the orchestration system)

### Key Technical Challenges

- **Accurate readiness semantics**: "Ready to serve" is nuanced in Venice; a server can serve requests
  for partitions it has already loaded, even if other partitions are still bootstrapping. The health
  check must reflect the intended semantics clearly.
- **gRPC service registration**: The health service must be registered on the existing gRPC server
  without disrupting other registered services.
- **Watch streaming**: The `Health/Watch` RPC requires streaming updates whenever status changes; this
  requires a publish-subscribe mechanism between the server's lifecycle state machine and the health
  service implementation.

### Suggested Starting Points

- `VeniceGrpcServer` in `services/venice-server` — the existing gRPC server setup
- [grpc-java health proto and stub](https://github.com/grpc/grpc-java/tree/master/services/src/main/java/io/grpc/services)
- [VIP-6](proposals/vip-6.md) — Venice on Kubernetes context

---

## Project 23: Store-Level Circuit Breaker

### Overview

When a Venice backend service (server or router) is degraded—returning errors or extremely high latencies—a
well-behaved client should stop hammering it and give it time to recover. The **circuit breaker** pattern
addresses this: after observing a configurable number of consecutive errors, the client "opens the circuit"
and fast-fails requests to that backend for a short window before retrying with a probe request.

This project implements a circuit breaker in the Venice Fast Client, protecting both the client application
from cascading failures and the backend from being overwhelmed during a degraded period.

### Resume Impact

*"Implemented the circuit breaker pattern in a distributed storage client library, reducing cascading
failure propagation and improving application resilience during partial backend outages."*

### Learning Outcomes

- The circuit breaker pattern: closed, open, and half-open states and the state machine transitions
- Cascading failure in distributed systems and how failure isolation prevents system-wide degradation
- Concurrent state machine implementation in a high-throughput Java client library

### Scope

**In scope:**

- A per-server-instance circuit breaker state machine with three states: CLOSED (normal), OPEN
  (fast-failing), and HALF-OPEN (probing)
- Configurable thresholds: error count to open, timeout while open, success count to close from half-open
- When OPEN, return a `ServiceUnavailableException` immediately without making a network call
- Metrics for circuit breaker state transitions and rejection rate per server instance
- Unit tests for all state transitions and an integration test verifying that a failing server causes the
  circuit to open and that recovery causes it to close

**Out of scope:**

- Circuit breaking at the store granularity (instance-level only)
- Bulkhead pattern (separate thread pools per server)
- Integration with external circuit breaker libraries (e.g., Resilience4j) — implement from scratch

### Key Technical Challenges

- **Concurrent state transitions**: Multiple threads may observe errors simultaneously and try to
  transition the circuit to OPEN; only one transition should succeed (use atomic compare-and-swap).
- **Interaction with the existing retry logic**: A circuit-broken server must not be retried; the retry
  logic must distinguish circuit-breaker rejections from transient errors that should be retried.
- **Half-open probe correctness**: During HALF-OPEN, only one probe request should be issued (not all
  waiting requests); additional requests must continue to fast-fail until the probe succeeds or fails.

### Suggested Starting Points

- `RetriableAvroGenericStoreClient` in `clients/venice-client` — the existing retry logic
- `InstanceHealthMonitor` in `clients/venice-client` — per-instance health tracking
- [Martin Fowler's Circuit Breaker pattern](https://martinfowler.com/bliki/CircuitBreaker.html)

---

## Project 24: Streaming Change Data Capture (CDC) Lag Dashboard

### Overview

Venice's [Change Data Capture (CDC)](../user-guide/read-apis/cdc.md) feature allows consumers to subscribe
to all data changes in a store. In production, CDC consumers can fall behind the stream of changes—either
because the consumer is too slow or because Venice is ingesting data faster than the consumer can process
it. Today, operators have no easy way to see CDC consumer lag across all active consumers in a cluster.

This project builds a CDC lag monitoring dashboard: a CLI tool that queries consumer group offsets from
the Venice RT/VT topics and reports per-store, per-consumer-group lag in real time.

### Resume Impact

*"Built a real-time consumer lag monitoring tool for a distributed change data capture system, reducing
mean time to detect data pipeline delays from hours to under a minute."*

### Learning Outcomes

- Kafka consumer groups, committed offsets, and lag computation
- Venice's change data capture architecture: how changes flow from the RT topic to CDC consumers
- Building a polling-based monitoring tool that aggregates data across many Kafka topics and partitions

### Scope

**In scope:**

- A `venice-admin-tool` sub-command `watch-cdc-lag` that, for each Venice store with CDC enabled:
  - Lists all consumer groups subscribed to its RT/VT topics
  - Computes per-partition lag (topic end offset − committed offset) for each consumer group
  - Renders a live-updating table (refreshed every few seconds) with: store name, consumer group, max
    partition lag, and total lag (sum across partitions)
- A `--store` filter flag and a `--threshold` flag that highlights consumer groups exceeding the threshold
- Unit tests for the lag calculation logic

**Out of scope:**

- Alerting or automated remediation when lag exceeds a threshold
- Lag tracking for the write path (only CDC consumer groups)
- Estimating the time-to-catch-up (ETA)

### Key Technical Challenges

- **Many topics and consumer groups**: A large Venice cluster may have hundreds of stores and dozens of
  CDC consumer groups; the tool must query Kafka's admin API efficiently using batch requests.
- **Offset freshness**: Consumer group committed offsets may be stale if the consumer is not currently
  running; the tool must indicate when a consumer group's last commit was and flag inactive groups.
- **Venice topic naming conventions**: Venice uses specific naming conventions for RT and VT topics; the
  tool must map from store name to topic name correctly.

### Suggested Starting Points

- `TopicManager` in `internal/venice-common` — Venice's interface to Kafka topic and offset management
- `VeniceAdminTool` in `clients/venice-admin-tool` — existing sub-commands as patterns
- [Change Data Capture documentation](../user-guide/read-apis/cdc.md)

---

## Project 25: Read Compute Performance Benchmark Suite

### Overview

Venice's Read Compute feature allows clients to push down field projections, dot products, cosine
similarity, and Hadamard product computations to the server, reducing network bandwidth. The performance
characteristics of these operations—latency, throughput, and CPU cost—are not systematically documented.
Operators and users have no data-driven guidance on when to use server-side compute vs. fetching the
full record and computing client-side.

This project builds a reusable, parameterized benchmark suite using [JMH](https://github.com/openjdk/jmh)
(Java Microbenchmark Harness) that measures server-side read compute operations under controlled
conditions and generates a performance report.

### Resume Impact

*"Designed and executed a systematic performance benchmark suite for distributed server-side compute
operations, producing data-driven recommendations that reduced client network bandwidth by 60% for
vector similarity search workloads."*

### Learning Outcomes

- JMH benchmarking methodology: avoiding common pitfalls (JIT warm-up, dead code elimination, benchmarking
  overhead)
- Venice's Read Compute wire protocol and how computation requests are serialized and dispatched
- Performance analysis: how to interpret throughput/latency tradeoffs and produce actionable recommendations

### Scope

**In scope:**

- JMH benchmarks for the following server-side operations across a range of vector/record sizes:
  - Field projection (single field, multiple fields)
  - Dot product on float and double arrays
  - Cosine similarity on float arrays
  - Count on arrays and maps
- Each benchmark measures: p50, p99, and p999 latency, and throughput (ops/sec)
- A benchmark runner that spins up a local Venice cluster (using `VeniceClusterWrapper`) and drives
  requests through the Full Stack (client → router → server)
- A Markdown report generated automatically by the benchmark runner summarizing results

**Out of scope:**

- Benchmarking multi-get read compute (single-key only for the initial scope)
- Benchmarking client-side (Da Vinci) compute
- Continuous performance regression tracking (one-shot benchmark only)

### Key Technical Challenges

- **JMH in an integration test context**: JMH benchmarks typically run standalone; running them against
  a real Venice cluster requires a careful setup/teardown lifecycle that does not interfere with JMH's
  measurement methodology.
- **Result reproducibility**: Benchmark results vary with hardware; the report must document the test
  environment (JVM version, CPU, record count, vector dimensionality) so results can be compared across
  runs.
- **Meaningful workload parameters**: Vector dimensionality, batch size, and record schema affect results
  significantly; the benchmark must cover a representative range and document the rationale for chosen
  parameters.

### Suggested Starting Points

- `ReadComputeRouterRequestHandler` in `services/venice-router` — routes compute requests to servers
- Existing Read Compute integration tests in `tests/venice-integration-tests` — setup patterns
- [JMH tutorials](https://jenkov.com/tutorials/java-performance/jmh.html)

---

## Project 26: Metadata Cache Warm-Up for Fast Client

### Overview

Venice's Fast Client avoids the router tier by maintaining a local routing table that maps each partition
to the server instances that host it. This metadata is fetched from the router at startup and refreshed
periodically. On a cold start, the Fast Client cannot serve requests until this metadata has been
fetched—typically a few hundred milliseconds. For latency-sensitive applications that restart frequently
(e.g., during rolling deployments), this cold-start delay is noticeable.

This project implements a **metadata cache persistence** mechanism: the Fast Client serializes its routing
table to a local file on shutdown and loads it on startup, enabling it to begin serving requests within
milliseconds of starting.

### Resume Impact

*"Implemented a persistent metadata cache for a distributed storage client, reducing cold-start latency
by 90% and enabling zero-downtime rolling restarts for latency-sensitive microservices."*

### Learning Outcomes

- Cluster discovery and routing metadata management in a distributed storage system
- Cache warm-up strategies: the tradeoff between serving stale metadata and blocking on a fresh fetch
- Serialization formats for operational data that must be readable across software versions

### Scope

**In scope:**

- Serialize the Fast Client's routing metadata (partition-to-server mapping, schema versions) to a
  local file (JSON or a lightweight binary format) on graceful shutdown
- On startup, load the cached metadata and begin serving requests immediately
- Issue an asynchronous metadata refresh in the background; replace the cached metadata once the fresh
  data arrives
- If the cached metadata is older than a configurable max-age (default: 5 minutes), block on a
  synchronous refresh before serving
- Unit tests for serialization/deserialization correctness and a test verifying that stale cache triggers
  a blocking refresh

**Out of scope:**

- Shared metadata cache across multiple Fast Client instances in the same JVM
- Cache warm-up for the Thin Client or Da Vinci Client
- Cache invalidation pushed from the server (pull-based refresh only)

### Key Technical Challenges

- **Backward compatibility**: The serialized metadata format must be readable by future versions of the
  Fast Client; a versioned format with a schema is needed to allow graceful upgrades.
- **Stale metadata risk**: Serving with a cached routing table that is significantly out of date could
  route requests to decommissioned servers; the max-age check and background refresh must be implemented
  correctly to bound staleness.
- **Atomic file writes**: Writing the cache file must be atomic (write-to-temp-then-rename) to avoid
  leaving a corrupt file on crash.

### Suggested Starting Points

- `D2BasedClusterInfoProvider` in `clients/venice-client` — how metadata is fetched from the router
- `ClientConfig` in `clients/venice-client` — Fast Client configuration
- [Fast Client documentation](../user-guide/read-apis/fast-client.md)

---

## Project 27: Batch Get Splitting for Oversized Requests

### Overview

Venice's batch-get API allows clients to fetch values for multiple keys in a single request. However, if
a request contains too many keys, it can exceed the server's configured request size limit or overwhelm
a single server with disproportionate work. Today, clients that issue oversized batch-get requests
receive an error rather than having the request automatically split and retried.

This project implements **automatic batch splitting** in the Venice client: when a batch-get request
exceeds a configurable key count threshold, the client transparently splits it into multiple smaller
sub-batches, dispatches them in parallel, and merges the results before returning to the caller.

### Resume Impact

*"Built an automatic request splitting and result merging layer in a distributed storage client,
eliminating user-facing batch size limit errors and improving throughput for large-scale ML feature
retrieval workloads."*

### Learning Outcomes

- The fan-out pattern in distributed systems: splitting a logical request into parallel sub-requests
  and merging the results
- `CompletableFuture` composition in Java: `allOf`, `thenApply`, and exception propagation across
  parallel futures
- The tradeoff between batch size, parallelism, and server-side load in a key-value store

### Scope

**In scope:**

- A `SplittingBatchGetStoreClient` wrapper that can be layered over the Thin or Fast Client
- Configurable `max-keys-per-sub-batch` (default: 150, matching the server-side limit)
- Sub-batches are dispatched in parallel; results are merged into a single `Map<K, V>` response
- If any sub-batch fails, the merged future fails with the first encountered error
- Metrics tracking: split rate, average number of sub-batches per original request
- Unit tests for splitting logic (edge cases: exactly max keys, one key, empty batch) and result merging

**Out of scope:**

- Adaptive batch size selection based on observed server latency
- Splitting read-compute requests (batch-get only)
- Splitting writes

### Key Technical Challenges

- **Partial failure semantics**: If one sub-batch fails and others succeed, the caller receives a failure.
  The implementation must document this behavior and cancel any remaining in-flight sub-batches on failure.
- **Key ordering**: The merged result map must contain all keys from the original request regardless of
  sub-batch boundaries; missing keys (not found in the store) must be surfaced consistently.
- **Overhead for small batches**: The splitting wrapper must add negligible overhead for batches that do
  not exceed the threshold (the common case); it must not allocate unnecessarily.

### Suggested Starting Points

- `StatTrackingStoreClient` in `clients/venice-client` — example of a transparent client wrapper
- `DispatchingAvroGenericStoreClient` in `clients/venice-client` — the batch-get dispatch path
- Venice's `ComputeUtils` and related request builder classes in `clients/venice-client`

---

## Project 28: Versioned Configuration History in the Controller

### Overview

Venice stores per-store configuration in ZooKeeper (partition count, replication factor, compression
strategy, hybrid store settings, etc.). When a misconfiguration causes a production incident, operators
need to know what the configuration was before and after the change, and when it changed. Today, only the
current configuration is stored; previous values are silently overwritten.

This project implements **versioned configuration history**: every time a store's configuration is
updated, the previous configuration is appended to a history log in ZooKeeper, giving operators a full
audit trail of store config changes.

### Resume Impact

*"Designed and implemented a versioned configuration history system for a distributed storage control
plane, enabling post-incident root cause analysis by preserving the complete change history of store
configurations."*

### Learning Outcomes

- ZooKeeper's data model: znodes, versioned writes, and the limitations of ZooKeeper for log-structured
  data
- The Venice Controller's configuration management and how store configs are applied
- Configuration change tracking as a distributed systems reliability practice

### Scope

**In scope:**

- A `StoreConfigHistory` ZooKeeper path per store that holds an ordered list of the last N (configurable,
  default 20) store config snapshots, each annotated with: timestamp, caller, and a human-readable
  change summary (diffing old vs. new config)
- Update the history on every `updateStore` Controller operation
- A `venice-admin-tool` sub-command `show-config-history` that fetches and displays the history for a
  given store
- Unit tests for config diff generation and history rotation (when N entries are exceeded, the oldest is
  dropped)

**Out of scope:**

- Full config rollback (display only, no automated rollback)
- Cross-region config history synchronization
- History for cluster-level (non-store) configs

### Key Technical Challenges

- **ZooKeeper node size limits**: ZooKeeper's 1 MB node size limit constrains how many history entries
  can be stored; the entry format must be compact (binary or compressed JSON) and the rotation policy
  must be enforced strictly.
- **Atomic read-modify-write**: Updating the history requires reading the current list, appending an
  entry, and writing back—all atomically to avoid history corruption under concurrent admin operations.
  ZooKeeper's conditional write (`version` parameter) must be used.
- **Change summary accuracy**: The diff between old and new store config must highlight only fields that
  actually changed, not all fields; a generic config diff utility is needed.

### Suggested Starting Points

- `ZkStoreConfigAccessor` in `services/venice-controller` — reads and writes store configs in ZooKeeper
- `VeniceHelixAdmin` in `services/venice-controller` — where `updateStore` operations are applied
- Apache Curator's `CuratorFramework` for ZooKeeper operations (already used by Venice)

---

## Project 29: Kafka Topic Retention Policy Enforcer

### Overview

Venice stores data in Kafka topics during the ingestion process. Version Topics (VTs) are deleted once the
corresponding store version is retired. Real-Time (RT) topics persist indefinitely. In practice, Venice
clusters accumulate stale topics—topics for deleted stores, topics whose retention settings drift from
the configured policy—consuming unnecessary Kafka broker storage and confusing operators.

This project builds a **Kafka topic retention policy enforcer**: a Controller background task that
periodically audits all Venice-related Kafka topics, identifies policy violations (stale topics, wrong
retention settings), and either fixes them automatically or reports them to the operator.

### Resume Impact

*"Built an automated Kafka topic lifecycle management system that reclaims broker storage by detecting and
removing stale topics and correcting misconfigured retention policies across a large distributed cluster."*

### Learning Outcomes

- Kafka topic lifecycle management: creation, retention settings, and deletion
- How Venice's Controller tracks the mapping between stores, versions, and Kafka topics
- Distributed housekeeping tasks: idempotent background workers that converge toward a desired state

### Scope

**In scope:**

- A background `TopicRetentionEnforcer` task in the Controller (runs every configurable interval, default
  1 hour) that:
  - Queries the Kafka broker for all topics in the Venice namespace (by prefix)
  - Cross-references with the Controller's known stores and versions
  - Reports stale topics (topics for deleted stores or retired versions still present in Kafka) as a
    Controller metric and log warning
  - For topics that exist and have incorrect retention settings (vs. the configured policy), issue a
    Kafka admin API call to correct the retention
- A dry-run mode that logs what would be changed without making any modifications
- Unit tests for the staleness detection logic

**Out of scope:**

- Automatically deleting stale topics (report only; deletion requires operator confirmation)
- Managing non-Venice topics on shared Kafka clusters
- Policy enforcement for schema registry or other ancillary topics

### Key Technical Challenges

- **Topic naming conventions**: Venice topics follow a naming convention that encodes store name and
  version; the enforcer must parse these names reliably, including edge cases like store names containing
  special characters.
- **Kafka admin API rate limiting**: Issuing many `alterConfigs` calls rapidly can overwhelm the Kafka
  broker; calls must be batched and rate-limited.
- **Eventual consistency**: The Controller may have committed a version deletion to ZooKeeper but not yet
  sent the Kafka delete command; the enforcer must tolerate this window and not double-delete.

### Suggested Starting Points

- `TopicManager` in `internal/venice-common` — Venice's interface to Kafka admin operations
- `VeniceHelixAdmin` in `services/venice-controller` — the source of truth for store/version state
- Existing topic cleanup logic in `services/venice-controller`

---

## Project 30: Server-Side Request Size Histogram Metrics

### Overview

Venice servers receive both single-get and batch-get requests. The size distribution of these requests
(number of keys in a batch, estimated value size returned, total response bytes) directly impacts
CPU usage, memory allocation, and network bandwidth. Today, Venice tracks request counts and aggregate
latency, but does not expose a histogram of request sizes. This makes it hard to detect when client
behavior changes (e.g., batch sizes growing due to a new use case) before it causes a capacity problem.

This project adds **request size histograms** to the Venice server metrics: for each store, track the
distribution of batch sizes, response sizes, and value sizes as histograms.

### Resume Impact

*"Instrumented a distributed storage service with request size histogram metrics, enabling proactive
capacity planning and reducing the time to detect anomalous traffic patterns from days to minutes."*

### Learning Outcomes

- Histogram design for high-throughput systems: reservoir sampling, HDR histograms, and bucket choice
- The Venice server's request handling pipeline and where metrics are collected
- Capacity planning using percentile metrics: why mean is misleading and p99 is actionable

### Scope

**In scope:**

- For each store, expose the following histograms (using the existing Venice metrics framework):
  - Batch-get key count distribution (p50, p99, max)
  - Response total bytes distribution per request (p50, p99, max)
  - Single value bytes distribution (p50, p99, max) for single-get and per-key within batch-get
- Metrics are tagged by store name and request type (single-get vs. batch-get)
- A configurable sampling rate to limit overhead for very high throughput stores (default: 100%)
- Unit tests verifying histogram population and a microbenchmark verifying that histogram recording adds
  less than 1 microsecond of overhead per request

**Out of scope:**

- Histograms for write requests (read path only)
- Per-partition granularity (store-level aggregation is sufficient)
- Client-side histograms (server-side only)

### Key Technical Challenges

- **Overhead in the hot path**: Recording a histogram observation on every request must be extremely fast
  (nanoseconds); using HDR Histogram's lock-free `Recorder` class is strongly recommended over
  synchronized alternatives.
- **Metric cardinality**: Exposing per-store histograms for hundreds of stores can create a large number
  of metric time series; a configurable allow-list for stores to track (or a default top-N by request
  volume) may be needed.
- **Byte size estimation**: Computing response size precisely would require serializing the full response;
  an accurate estimate (summing RocksDB value sizes without full serialization) must be used instead.

### Suggested Starting Points

- `VeniceServerRequestHandler` in `services/venice-server` — processes incoming read requests
- Venice's `ServerStats` class in `services/venice-server` — where existing server metrics are defined
- [HdrHistogram Java library](https://github.com/HdrHistogram/HdrHistogram)

---

## Project 31: Store Dependency Graph

### Overview

In large Venice deployments, stores are often built on top of other stores (e.g., an ML feature store
whose values are derived from a raw data store via a Spark job). Today, there is no way to capture or
query these **store dependency relationships** within Venice itself. When a source store is deleted or
its schema is changed, downstream consumers may silently break.

This project implements a lightweight dependency graph registry in the Venice Controller: operators
can declare that store B depends on store A, and the admin tool can traverse the graph to assess the
blast radius of changes to a given store.

### Resume Impact

*"Built a distributed store dependency graph registry for a planet-scale storage service, enabling
blast-radius analysis and preventing accidental deletion of stores that have active downstream consumers."*

### Learning Outcomes

- Dependency graph data structures and graph traversal algorithms (BFS, topological sort)
- ZooKeeper as a metadata store for operational dependency relationships
- How distributed systems can enforce soft constraints (warnings) vs. hard constraints (blocking
  operations) based on dependency information

### Scope

**In scope:**

- A new per-store metadata field `upstreamStores: List<String>` stored in ZooKeeper alongside existing
  store config
- Controller admin API endpoints:
  - `PUT /stores/{store}/dependencies` — set upstream store dependencies
  - `GET /stores/{store}/dependencies?direction=upstream|downstream` — list upstream or downstream
    stores
- When a store delete is requested, the Controller checks if any other store declares it as a dependency
  and returns a warning (non-blocking) in the admin API response
- `venice-admin-tool` sub-commands: `set-dependencies`, `show-dependencies`, `show-dependency-graph`
  (renders an ASCII DAG)
- Unit tests for graph traversal and cycle detection

**Out of scope:**

- Automatic enforcement of schema compatibility between dependent stores
- Versioned dependency declarations (all or nothing — no per-version granularity)
- Integration with external lineage systems (e.g., Datahub, OpenLineage)

### Key Technical Challenges

- **Cycle detection**: Dependency declarations must not form a cycle (store A depends on B, B depends on
  A); the Controller must reject such declarations.
- **Consistency of dependency metadata**: The dependency graph is stored alongside store config in
  ZooKeeper; updates must be atomic with respect to other store config changes.
- **ASCII graph rendering**: Rendering a dependency DAG in the terminal for large graphs (many stores)
  requires a layout algorithm; a simple top-down BFS rendering is sufficient.

### Suggested Starting Points

- `ZkStoreConfigAccessor` in `services/venice-controller` — reads/writes store metadata
- `AdminSparkServer` in `services/venice-controller` — the admin HTTP API
- `VeniceAdminTool` in `clients/venice-admin-tool` — CLI sub-command patterns

---

## Project 32: Write-Path Latency Decomposition

### Overview

When a Venice nearline write (via the Online Producer or Samza) takes longer than expected to be visible
to readers, it is difficult to determine which component of the write path caused the delay. The write
travels through: the producer client → Kafka RT topic → Venice Server leader → RocksDB write → follower
replication. Each hop adds latency, but today there are no structured metrics that decompose end-to-end
write-visible latency into per-hop contributions.

This project instruments the Venice write path with **per-hop latency timing**, attaching timestamps to
messages as they flow through each component and computing decomposed latency metrics at the server.

### Resume Impact

*"Instrumented the end-to-end write path of a distributed storage system with per-hop latency
decomposition, reducing mean time to root-cause write latency regressions from hours to minutes."*

### Learning Outcomes

- Write path tracing in a distributed system: attaching causally ordered timestamps to messages
- Kafka message header propagation and how to preserve metadata through a distributed pipeline
- The Venice write path: RT topic → leader ingestion → RocksDB write → follower replication

### Scope

**In scope:**

- Attach a `write-path-timestamp-map` header to Venice producer messages containing:
  - `t_produced`: timestamp when the Online Producer sends the message
  - `t_leader_received`: timestamp when the leader server dequeues the message from Kafka
  - `t_rocksdb_written`: timestamp when the leader writes the record to RocksDB
- At each stage, compute and record as a metric the delta from the previous stage (e.g.,
  `kafka_queue_latency_ms = t_leader_received - t_produced`)
- Metrics tagged by store name; p50, p99 histograms for each delta
- Unit tests for timestamp header serialization and delta computation

**Out of scope:**

- Follower replication hop timing (leader-side only for the initial scope)
- Cross-region write latency decomposition
- Write-path tracing for batch push jobs (nearline writes only)

### Key Technical Challenges

- **Clock skew between hosts**: Producer and server run on different machines whose clocks may differ by
  milliseconds; absolute timestamps are approximate, and the implementation must document this limitation.
- **Header size overhead**: Kafka messages have size constraints; the timestamp map header must be
  compact (e.g., three 8-byte longs = 24 bytes) to avoid meaningfully increasing message size.
- **Sampling**: Attaching headers and computing deltas on every message adds CPU overhead; a configurable
  sampling rate (e.g., 1% of messages) must be supported.

### Suggested Starting Points

- `VeniceWriter` in `internal/venice-common` — writes messages to Kafka with headers
- `StoreIngestionTask` in `services/venice-server` — the server-side ingestion loop
- Existing write path documentation in [Write Path Architecture](architecture/write-path.md)

---

## Project 33: Partition Leader Stickiness Analysis

### Overview

In Venice's leader-follower replication model, each partition has exactly one leader server at a time.
Leaders handle all writes and then replicate to followers. Ideally, leadership is evenly distributed
across servers so that write load is balanced. In practice, a server restart can cause many partitions to
elect the same server as leader simultaneously (a "leader stickiness" problem), creating write hotspots.

This project builds tooling to measure and visualize the distribution of partition leadership across
servers, detect imbalance, and trigger a Helix-assisted rebalance if requested by the operator.

### Resume Impact

*"Built distributed partition leadership imbalance detection tooling for a large-scale storage service,
enabling operators to diagnose and correct write hotspots caused by uneven leader distribution."*

### Learning Outcomes

- Leader-follower replication and why balanced leader distribution matters for write throughput
- Apache Helix leader election: how leaders are assigned and how re-election is triggered
- Statistical imbalance detection: when is a distribution "imbalanced enough" to warrant corrective action?

### Scope

**In scope:**

- A `venice-admin-tool` sub-command `show-leader-distribution` that queries the Helix external view and
  reports:
  - Number of leader partitions per server, across all stores in the cluster
  - Imbalance score (standard deviation / mean)
  - A bar chart showing relative leader load per server
- A `--rebalance` flag that triggers a Helix leader rebalance for stores where imbalance exceeds a
  configurable threshold
- Unit tests for the imbalance score calculation

**Out of scope:**

- Automatic scheduled rebalancing (operator-triggered only)
- Per-store granularity rebalancing (cluster-wide only)
- Cross-region leader distribution analysis

### Key Technical Challenges

- **Distinguishing leader vs. follower**: The Helix external view contains both leader and follower
  state; the tool must correctly filter for `LEADER` state partitions only.
- **Rebalance safety**: Triggering a Helix rebalance moves leader assignments and incurs a brief
  re-election period during which writes to affected partitions may be delayed; the tool must display a
  warning before executing the rebalance.
- **Aggregating across stores**: Leadership is tracked per store per partition; aggregating across all
  stores requires merging many Helix resource views efficiently.

### Suggested Starting Points

- `HelixBaseRoutingRepository` in `services/venice-router` — reads leader/follower assignments
- `VeniceAdminTool` in `clients/venice-admin-tool` — CLI patterns
- Apache Helix `HelixAdmin` for triggering rebalance operations

---

## Project 34: Schema Registry UI (Read-Only Web View)

### Overview

Venice's schema registry stores every version of every Avro schema for every store. Operators and
developers frequently need to look up a schema—to understand the data shape, to check field names before
writing a query, or to debug a deserialization error. Today, the only way to inspect schemas is via the
Controller's REST API or the `venice-admin-tool` CLI, both of which require terminal access and knowledge
of the right commands.

This project builds a simple **read-only web UI** that connects to the Venice Controller's schema API
and renders schemas in a browsable, human-friendly format.

### Resume Impact

*"Built a single-page web application for browsing and searching Avro schemas in a distributed storage
service's schema registry, reducing the time for data engineers to look up schema information from
minutes to seconds."*

### Learning Outcomes

- Avro schema rendering and the challenges of presenting nested schemas accessibly
- REST API design for schema registry queries
- Frontend development (React or plain HTML/JS) integrated with a distributed backend API

### Scope

**In scope:**

- A lightweight single-page application (vanilla HTML/CSS/JS or React) that:
  - On load, fetches the list of all stores from the Controller's `/stores` endpoint
  - Displays a searchable store list
  - On selecting a store, fetches and displays all registered value schema versions with:
    - Schema version number, registration timestamp, and the full Avro schema rendered as a collapsible
      JSON tree
    - A diff view highlighting fields added/removed between consecutive schema versions
- The app can be served from a standalone HTML file or as a new endpoint on the Controller
- Unit tests for the diff logic (pure JavaScript or Java, depending on implementation choice)

**Out of scope:**

- Schema write operations (read-only browsing only)
- Authentication or access control for the UI
- Embedding in an existing observability platform

### Key Technical Challenges

- **Recursive schema rendering**: Avro schemas can be deeply nested (records within records, arrays of
  maps, union types); the JSON tree renderer must handle arbitrary nesting without stack overflow.
- **Schema diff**: Computing a human-readable diff between two Avro schemas (added fields, removed
  fields, changed types) requires a field-level comparison, not a raw text diff.
- **Cross-origin requests**: If the UI is served from a different origin than the Controller API, CORS
  headers must be configured; alternatively, a simple proxy endpoint can be added to the Controller.

### Suggested Starting Points

- `SchemaAccessor` REST endpoints in `services/venice-controller` — existing schema query APIs
- Venice's `ControllerClient` — lists stores and retrieves schemas
- [Avro Schema Specification](https://avro.apache.org/docs/current/spec.html)

---

## Project 35: Zero-Downtime Schema Migration Dry-Run Mode

### Overview

Schema migrations in Venice (adding a new required field, changing a field type) are irreversible once the
first record with the new schema is written. Today, an operator who wants to understand the impact of a
schema change before committing to it has no tooling for this. A failed migration may require a full repush
of the entire dataset—a costly multi-hour operation.

This project implements a **dry-run mode for schema registration**: before persisting a new schema, the
Controller simulates the migration end-to-end, verifies backward compatibility, and reports exactly which
in-flight push jobs would be affected—without touching any production state.

### Impact

- **For Venice clusters**: Eliminates costly repushes caused by accidental incompatible schema changes, which
  at LinkedIn scale represent multi-hour delays and significant compute cost.
- **For the intern**: Shipped a safety feature that demonstrably reduced production incidents in a widely used
  data platform; quantifiable as "reduced schema-related repushes by X% on Y stores."

### Resume Impact

*"Implemented a dry-run schema migration safety feature for a distributed storage service, preventing
irreversible schema changes that previously caused multi-hour repushes on production datasets."*

### Learning Outcomes

- Avro schema compatibility rules and why they matter for long-running production systems
- The Venice Controller's schema registration flow and how ZooKeeper stores schema history
- Risk-free "what-if" tooling patterns in distributed systems

### Scope

**In scope:**

- A `?dryRun=true` query parameter on the Controller's `POST /schema/{store}` endpoint
- Dry-run mode runs all compatibility checks and returns a detailed JSON response:
  - Compatibility result per existing schema version
  - List of in-flight push jobs that would be affected
  - Whether the schema would be accepted or rejected in production mode
- No ZooKeeper writes occur during dry-run; results are computed in-memory only
- Unit tests verifying that dry-run mode never persists state, and that the compatibility report is accurate

**Out of scope:**

- Dry-run for key schema changes
- Simulating the actual data re-encoding (only schema-level compatibility is checked)

### Key Technical Challenges

- **Idempotency guarantee**: Dry-run must never have side effects even under concurrent real registrations
  happening on other Controller threads.
- **Reporting clarity**: The JSON response must be human-readable enough for an operator to act on without
  reading source code.

### Suggested Starting Points

- `SchemaAdminHandler` in `services/venice-controller` — handles schema registration requests
- `SchemaEntry` and `SchemaRepository` in `internal/venice-common` — schema persistence
- Avro `SchemaCompatibilityValidator` utilities already used in Venice

---

## Project 36: Read Path Latency Budget Alerting

### Overview

Venice's SLA for p99 read latency is well-defined per client type (e.g., < 2 ms for the Fast Client). Today,
when latency degrades, operators find out reactively—after users report the issue or after an on-call alert
fires on an aggregate metric. There is no proactive, per-store alerting that fires early, before the SLA is
breached, based on a configurable latency budget.

This project implements **per-store latency budget alerting** in the Router and Server: each store can declare
its latency budget, and a background evaluator emits a warning metric and log event when recent p99 latency
exceeds the budget.

### Impact

- **For Venice clusters**: Reduces mean time to detect latency regressions from minutes to seconds, directly
  shortening the window during which user-facing applications experience degraded performance.
- **For the intern**: Shipped proactive alerting infrastructure used in production; quantifiable as "reduced
  mean time to detect latency regressions from N minutes to M seconds."

### Resume Impact

*"Built per-store latency budget alerting for a distributed storage service, reducing mean time to detect
SLA violations from minutes to under 30 seconds across hundreds of production stores."*

### Learning Outcomes

- SLA definition and latency percentile metrics (p50, p99, p999) in high-throughput systems
- Rolling time-window statistics and their tradeoffs (accuracy vs. memory)
- Proactive vs. reactive monitoring in distributed systems

### Scope

**In scope:**

- A per-store config field `latencyBudgetMs` (nullable; no alerting if unset)
- A background evaluator thread in the Router that checks the rolling p99 latency for each store
  every 30 seconds against the budget
- Emit a `latency_budget_exceeded` metric (counter + last-exceeded-at timestamp) and a structured
  log warning when the budget is breached
- A `venice-admin-tool` sub-command `set-latency-budget` that updates the per-store config
- Unit tests for the budget evaluation logic and threshold comparison

**Out of scope:**

- Automatic throttling or traffic shedding when the budget is exceeded
- Per-key-range granularity (store-level only)
- Integration with external alerting systems (PagerDuty, Slack, etc.)

### Key Technical Challenges

- **Rolling window accuracy**: A simple ring buffer of the last N latency samples gives approximate
  percentiles; the window size and sample rate must be tuned so that the p99 estimate converges quickly
  after a degradation event.
- **Config propagation**: The latency budget is a per-store config stored in ZooKeeper; the Router must
  subscribe to config change events and update its evaluator without restarting.

### Suggested Starting Points

- `RouterStats` in `services/venice-router` — where existing router latency metrics are collected
- `StoreConfig` in `internal/venice-common` — per-store configuration schema
- Venice's config update notification path in `services/venice-router`

---

## Project 37: Automatic Replication Factor Repair

### Overview

Venice ensures that each partition is replicated to a configured number of replicas (e.g., 3). When servers
are removed from a cluster or crash permanently, the actual replication factor of some partitions may drop
below the target—a condition called **under-replication**. Today, under-replication is detected passively
via metrics, but recovery requires manual operator intervention: adding a new server and waiting for Helix
to rebalance.

This project implements an **auto-repair background task** in the Controller that detects under-replicated
partitions and automatically triggers a Helix rebalance to restore the configured replication factor.

### Impact

- **For Venice clusters**: Eliminates manual toil for the most common under-replication scenarios, reducing
  mean time to recover data availability from hours (waiting for an operator) to minutes (automatic repair).
- **For the intern**: Contributed to Venice's core reliability story; quantifiable as "automated detection
  and repair of under-replicated partitions, reducing MTTR from X hours to Y minutes."

### Resume Impact

*"Designed and implemented an automated under-replication detection and self-healing system for a
distributed storage platform, reducing mean time to recover full replication factor from hours to minutes."*

### Learning Outcomes

- Replication factor and quorum in distributed storage systems
- Apache Helix resource balancing: how rebalance commands propagate and how replicas are assigned
- Self-healing systems: idempotent background workers that converge toward a desired replication state

### Scope

**In scope:**

- A `ReplicationFactorRepairTask` background thread in the Controller (runs every configurable interval,
  default 5 minutes)
- Queries the Helix external view to count the actual number of healthy replicas per partition per store
- When actual replicas < configured `replicationFactor` for a partition, trigger a targeted Helix
  rebalance for that resource
- Emit a `under_replicated_partitions` gauge metric and a log warning per affected store
- A dry-run mode that reports without triggering rebalance
- Unit tests for the under-replication detection logic

**Out of scope:**

- Automatically provisioning new servers to satisfy the replication factor (only rebalancing among
  existing servers)
- Cross-region replication repair
- Repair during active push jobs (defer to after the push completes)

### Key Technical Challenges

- **Helix rebalance triggering**: Incorrectly triggering a rebalance during an active ingestion can cause
  unnecessary partition movements; the task must check for active push jobs before issuing the rebalance.
- **Thundering herd**: If many stores become under-replicated simultaneously (e.g., after a multi-server
  outage), triggering rebalances for all of them at once could overwhelm the cluster. The task must
  rate-limit rebalance triggers.

### Suggested Starting Points

- `VeniceHelixAdmin` in `services/venice-controller` — the Controller's Helix integration
- `HelixBaseRoutingRepository` in `services/venice-router` — reads replica state from Helix
- Apache Helix `HelixAdmin.rebalance()` API

---

## Project 38: Push Job Throughput Estimator

### Overview

When a Venice push job starts, operators and users have no estimate of how long it will take to complete.
For large datasets, a push can take hours, and users must monitor log output manually to gauge progress.
A throughput estimator that projects the expected completion time from the first few minutes of actual
ingestion rates would significantly reduce uncertainty and help operators schedule maintenance windows.

### Impact

- **For Venice users**: Provides actionable completion time estimates for multi-hour push jobs, reducing
  the need for manual monitoring and enabling better scheduling of downstream pipeline steps.
- **For the intern**: Built user-facing productivity tooling used daily by data engineers; quantifiable
  as "reduced push job monitoring overhead by providing completion time estimates within 10% accuracy
  after the first 5 minutes of ingestion."

### Resume Impact

*"Built a push job completion time estimator for a distributed data ingestion pipeline, providing
within-10%-accuracy ETA predictions that reduced engineer monitoring overhead across hundreds of
daily push jobs."*

### Learning Outcomes

- Throughput estimation and extrapolation in distributed systems
- The Venice push job lifecycle and what metrics are available at each stage
- Statistical forecasting: exponential smoothing and confidence intervals for rate-based estimates

### Scope

**In scope:**

- A `ThroughputEstimator` component in the Venice Push Job that:
  - Samples ingestion throughput (records/sec, bytes/sec) every 30 seconds using a sliding window
  - Applies exponential smoothing to reduce noise
  - Projects completion time as `remaining_records / smoothed_throughput`
- Log the estimated completion time at each sample interval, including a confidence range (±20%)
- Expose the estimate via the push job's progress reporter so it appears in the Controller's job status API
- Unit tests for the smoothing algorithm and ETA calculation

**Out of scope:**

- Adaptive throttling based on the estimate
- Multi-region ETA aggregation (estimate per-region only)
- Machine learning–based estimation (statistical smoothing only)

### Key Technical Challenges

- **Estimation accuracy in early stages**: Ingestion often accelerates as RocksDB warms up caches; early
  samples are biased low. The estimator must apply a warm-up discount to early readings.
- **Handling pauses**: If the push job stalls (e.g., waiting for quota), throughput drops to zero; the
  estimator must detect stalls and suspend ETA reporting rather than projecting an infinite completion time.

### Suggested Starting Points

- `VenicePushJob` in `clients/venice-push-job` — the push job entry point
- `PushJobDetails` Avro schema in `internal/venice-common` — the push job progress record structure
- [Push Job documentation](../user-guide/write-apis/batch-push.md)

---

## Project 39: Automatic Dead Letter Queue for Failed Nearline Writes

### Overview

Venice nearline (streaming) writes arrive via Kafka. When a message cannot be applied—because it is
malformed, violates the current schema, or triggers an unexpected processing error—the Venice server
currently logs the error and skips the message. The message is silently lost with no way for the producer
to know their write failed or to recover the data.

This project implements an **automatic dead letter queue (DLQ)**: failed messages are forwarded to a
dedicated Kafka topic alongside a structured error record describing why they failed. Producers can
subscribe to the DLQ topic to detect and reprocess failures.

### Impact

- **For Venice users**: Eliminates the current silent data loss scenario for failed nearline writes,
  giving producers a reliable feedback loop and a recovery path—a critical correctness improvement
  for high-value streaming pipelines.
- **For the intern**: Shipped a reliability feature that closes a known data integrity gap; quantifiable
  as "eliminated silent data loss for X% of error-prone nearline write workloads."

### Resume Impact

*"Implemented an automatic dead letter queue for failed streaming writes in a distributed storage
service, eliminating silent data loss and providing producers a structured error recovery path."*

### Learning Outcomes

- The dead letter queue pattern: why it is necessary in at-least-once messaging systems
- Venice's nearline write ingestion path: how messages flow from Kafka to RocksDB
- Error classification: transient errors that should be retried vs. permanent errors that should be DLQ'd

### Scope

**In scope:**

- A `DeadLetterQueueWriter` component in `StoreIngestionTask` that, on a permanent ingestion failure:
  - Creates a DLQ topic named `<store>_dlq` if it does not exist
  - Writes the original failed message bytes alongside a structured error record (timestamp, error
    type, error message, store name, partition)
- Configuration to enable/disable DLQ per store (`dead.letter.queue.enabled`, default: false)
- A `venice-admin-tool` sub-command `inspect-dlq` that reads and displays recent DLQ entries for a store
- Unit tests for error classification (transient vs. permanent) and DLQ write logic

**Out of scope:**

- Automatic reprocessing of DLQ messages
- DLQ for batch push job failures
- Cross-region DLQ replication

### Key Technical Challenges

- **Error classification**: Not all ingestion errors should go to the DLQ; transient errors (e.g., a
  temporary RocksDB I/O error) should be retried, while permanent errors (schema violation, corrupt
  message) should be DLQ'd immediately. Defining and testing this boundary is the core challenge.
- **DLQ topic creation**: Creating a new Kafka topic at ingestion time adds latency to the failure path
  and may fail if the Kafka cluster is unavailable; topic creation must be pre-provisioned or retried
  asynchronously.

### Suggested Starting Points

- `StoreIngestionTask` in `services/venice-server` — the ingestion loop where errors occur
- `TopicManager` in `internal/venice-common` — Kafka topic creation and management
- `VeniceWriter` in `internal/venice-common` — writes messages to Kafka topics

---

## Project 40: Per-Store Read SLA Dashboard

### Overview

Venice serves hundreds of stores simultaneously, each with different read latency profiles. Today, a
single aggregate latency dashboard makes it difficult to identify which specific stores are violating their
SLA at any given moment. An operator investigating a user-reported latency complaint must manually cross-
reference store names, metrics, and logs across multiple systems.

This project builds a **per-store read SLA dashboard** as a `venice-admin-tool` command that queries the
Router's metrics endpoint, aggregates per-store p99 latency, and renders a ranked list of stores sorted
by SLA violation severity.

### Impact

- **For Venice operators**: Reduces the time to identify which store is causing a user-facing latency
  issue from 10–30 minutes (manual log spelunking) to under 60 seconds (a single CLI command).
- **For the intern**: Shipped operator tooling with direct, measurable impact on incident response time;
  quantifiable as "reduced mean time to identify latency-offending store from N minutes to under 1 minute."

### Resume Impact

*"Built a per-store SLA monitoring CLI for a distributed storage service, reducing mean time to identify
latency-offending stores during incidents from 20 minutes to under 60 seconds."*

### Learning Outcomes

- How Venice exposes per-store metrics via its Router metrics endpoint
- Latency percentile interpretation: why p50 is fine but p99 is what users feel
- Incident-response tooling: designing CLIs for speed and clarity under pressure

### Scope

**In scope:**

- A `venice-admin-tool` sub-command `show-store-sla` that:
  - Queries the Router's `/metrics` (or equivalent) endpoint for all stores
  - Extracts p50, p99, and p999 single-get latency per store
  - Renders a table sorted by p99 descending, with a visual indicator (🟢/🟡/🔴) based on a
    configurable SLA threshold
  - Supports a `--top N` flag to show only the N worst-performing stores
- Unit tests for the metric parsing and ranking logic

**Out of scope:**

- Historical latency trending (point-in-time snapshot only)
- Automatic alerting from the CLI command
- Per-region breakdown

### Key Technical Challenges

- **Metric scraping reliability**: The Router's metrics endpoint may be temporarily unavailable; the
  tool must handle partial failures gracefully and report which routers could not be reached.
- **Metric naming consistency**: Venice's metric naming conventions may vary across Router versions; the
  parser must handle minor naming variations without failing hard.

### Suggested Starting Points

- `RouterStats` in `services/venice-router` — where per-store latency metrics are emitted
- `VeniceAdminTool` in `clients/venice-admin-tool` — CLI patterns
- Venice's metrics endpoint in `services/venice-router`

---

## Project 41: Ingestion Backpressure Metrics

### Overview

Venice servers ingest data from Kafka at a configurable rate. When ingestion is slower than the rate at
which the producer writes (e.g., due to RocksDB write stalls, compaction, or CPU saturation), Kafka
consumer lag builds up. Today, consumer lag is visible, but the root cause—which component is the
bottleneck—is not. Is it RocksDB write speed? Deserialization? Write Compute? Schema validation?

This project adds **per-stage ingestion backpressure metrics**: detailed timing instrumentation for each
stage of the ingestion pipeline so that the slowest stage can be identified at a glance.

### Impact

- **For Venice clusters**: Enables data-driven optimization of the ingestion pipeline by pinpointing
  bottlenecks, potentially unlocking significant throughput improvements for the entire Venice fleet.
- **For the intern**: Produced actionable performance insights for a critical production system; quantifiable
  as "identified and helped resolve ingestion bottleneck that increased ingestion throughput by X%."

### Resume Impact

*"Instrumented the ingestion pipeline of a distributed storage service with per-stage latency metrics,
enabling data-driven bottleneck detection and contributing to a measurable throughput improvement."*

### Learning Outcomes

- Pipeline instrumentation and bottleneck analysis in distributed systems
- The Venice ingestion pipeline stages: Kafka deserialization, schema validation, Write Compute,
  RocksDB write, and follower replication notification
- Performance profiling methodology: measuring without perturbing

### Scope

**In scope:**

- Add per-stage timing metrics (nanosecond-resolution stopwatch) to `StoreIngestionTask` for:
  - Kafka record deserialization
  - Schema validation
  - Write Compute (if enabled)
  - RocksDB put operation
  - Leader-to-follower notification
- Expose each stage's p50, p99 latency and throughput (records/sec) as server metrics tagged by store
- A `venice-admin-tool` sub-command `show-ingestion-breakdown` that queries all servers for a given store
  and displays a per-stage breakdown sorted by mean latency contribution
- Unit tests for the timing instrumentation (verify non-zero values after writes)

**Out of scope:**

- Trace-level per-record timing (aggregate histograms only)
- Cross-region ingestion breakdown
- Adaptive throttling based on stage latency

### Key Technical Challenges

- **Nanosecond timing overhead**: Recording timestamps at each stage of a high-throughput pipeline adds
  CPU overhead; the instrumentation must use `System.nanoTime()` efficiently and batch metric updates.
- **Stage boundary definition**: Some stages (e.g., RocksDB write) are asynchronous; accurately
  attributing time to the right stage requires careful use of timestamps at enqueue and completion.

### Suggested Starting Points

- `StoreIngestionTask` in `services/venice-server` — the ingestion loop
- Venice's `ServerStats` class — existing server-side metrics
- `RocksDBStoragePartition` in `services/venice-server` — the RocksDB write layer

---

## Project 42: Automatic Schema Documentation Generator

### Overview

Every Venice store has an Avro schema that describes the shape of its data. Keeping human-readable
documentation of these schemas current is tedious: today it is either missing entirely or maintained
manually in separate wikis that quickly fall out of sync.

This project builds an **automatic schema documentation generator** that reads schemas from the Venice
schema registry and produces well-formatted Markdown (or HTML) documentation pages—one per store—that
are always up to date with the actual registered schemas.

### Impact

- **For Venice users**: Eliminates the need for manual schema documentation maintenance; data engineers
  can navigate store schemas as easily as browsing a API reference, reducing integration time for new
  consumers.
- **For the intern**: Shipped developer productivity infrastructure used by the entire organization;
  quantifiable as "automated schema documentation for X stores, eliminating Y hours/week of manual
  documentation work."

### Resume Impact

*"Built an automatic schema documentation generator for a distributed storage service's schema registry,
producing always-current Markdown reference pages for hundreds of stores and eliminating manual
documentation maintenance."*

### Learning Outcomes

- Avro schema structure: records, fields, unions, arrays, maps, enums, and their documentation features
- Documentation-as-code: generating structured docs from machine-readable schemas
- How Venice's schema registry stores and versions schemas

### Scope

**In scope:**

- A `generate-schema-docs` sub-command in `venice-admin-tool` that:
  - Accepts a cluster and optional store filter
  - For each matching store, fetches all schema versions from the Controller
  - Renders a Markdown page per store containing: store description, a table of schema versions
    (version, registration date), and for the latest version a full field-level reference table
    (field name, type, default, docstring)
  - Writes pages to a configurable output directory
- Support for Avro `doc` field strings as field-level descriptions in the rendered output
- Unit tests for the Markdown rendering logic (field table generation, nested record expansion)

**Out of scope:**

- Generating docs for key schemas (value schemas only)
- Hosting or serving the generated docs (file output only)
- Diff pages between schema versions

### Key Technical Challenges

- **Nested schema expansion**: Avro allows nested records and named type references; the renderer
  must expand nested records inline (with proper indentation) to produce a readable field table.
- **Union type rendering**: Avro unions (nullable fields, typed unions) must be rendered clearly;
  `["null", "string"]` should render as `string (nullable)`, not as raw JSON.

### Suggested Starting Points

- `ControllerClient` in `internal/venice-common` — fetches registered schemas
- Avro `Schema.Field` and `Schema.Type` — the Java API for inspecting schema structure
- `VeniceAdminTool` in `clients/venice-admin-tool` — CLI patterns

---

## Project 43: Multi-Store Batch Get Client

### Overview

Some applications need to retrieve related data from multiple Venice stores in a single logical operation—
for example, fetching a user's profile from store A and their preferences from store B for the same user
ID. Today, clients must issue separate, sequential requests to each store, paying full latency for each hop.

This project implements a **multi-store batch get client** that issues parallel requests to multiple stores
for a shared set of keys and aggregates the results, reducing total latency to a single network round-trip.

### Impact

- **For Venice users**: Reduces ML inference latency for applications that join multiple feature stores,
  potentially cutting feature retrieval time by 50% for two-store joins and proportionally more for
  larger joins.
- **For the intern**: Shipped a user-facing API improvement with directly measurable latency impact on
  ML serving pipelines; quantifiable as "reduced multi-store feature retrieval latency by X ms for Y
  use cases."

### Resume Impact

*"Designed and implemented a parallel multi-store batch get API for a distributed feature store, reducing
ML inference feature retrieval latency by up to 50% for applications that join data from multiple stores."*

### Learning Outcomes

- Fan-out parallelism in distributed systems: issuing and joining multiple concurrent requests
- API design for composable distributed data access patterns
- Error handling semantics for partial results in a multi-store operation

### Scope

**In scope:**

- A `MultiStoreClient` wrapper that accepts a `Map<StoreName, Set<Key>>` and returns a
  `Map<StoreName, Map<Key, Value>>` response
- All per-store requests are issued in parallel using `CompletableFuture.allOf`
- A configurable per-store client map injected at construction time
- Partial failure semantics: if one store's request fails, the response for that store contains the
  error while other stores' results are still returned
- Unit tests for parallel dispatch, result merging, and partial failure handling

**Out of scope:**

- Automatically discovering which stores to query (caller specifies store names explicitly)
- Cross-datacenter multi-store requests
- Write operations

### Key Technical Challenges

- **Error isolation**: One store's failure must not cancel in-flight requests to other stores; the
  multi-store client must collect results independently and allow partial success.
- **Key type heterogeneity**: Different stores may have different key types; the API must handle
  type-safe keys per store without requiring a common supertype.
- **Timeout semantics**: The caller specifies one timeout for the entire multi-store operation; the
  implementation must set per-store deadlines accordingly and cancel any outstanding requests on timeout.

### Suggested Starting Points

- `StatTrackingStoreClient` in `clients/venice-client` — client wrapper pattern
- `DispatchingAvroGenericStoreClient` in `clients/venice-client` — the Fast Client dispatch layer
- `VeniceClientFactory` in `clients/venice-client` — client construction patterns

---

## Project 44: Controller REST API OpenAPI Specification

### Overview

Venice's Controller exposes a rich REST API used by the `venice-admin-tool`, the push job, and monitoring
systems. Today, this API is undocumented in any machine-readable format: there is no OpenAPI/Swagger
specification, no generated client code, and no interactive documentation. New contributors must read
source code to understand how to call the API.

This project produces an **OpenAPI 3.0 specification** for the Venice Controller's REST API, enabling
auto-generated client SDKs, interactive documentation (Swagger UI), and API contract testing.

### Impact

- **For the Venice community**: Lowers the barrier to building integrations with Venice's control plane;
  any language can generate a typed client SDK from the spec, expanding Venice's ecosystem reach.
- **For the intern**: Published the authoritative API specification for a widely used distributed system's
  control plane; quantifiable as "documented X endpoints covering Y% of the Controller API surface."

### Resume Impact

*"Authored the complete OpenAPI 3.0 specification for a distributed storage service's control plane API,
enabling auto-generated client SDKs in multiple languages and interactive documentation used by the open
source community."*

### Learning Outcomes

- OpenAPI 3.0 specification format: paths, operations, request/response schemas, and authentication
- REST API design review: documenting an existing API forces a clear-eyed assessment of its consistency
- Documentation tooling: Swagger UI, code generation with `openapi-generator`

### Scope

**In scope:**

- An `openapi.yaml` file at the repository root documenting the Controller's primary admin endpoints:
  - Store CRUD operations (`/stores`, `/stores/{store}`)
  - Schema registration and retrieval (`/schema/{store}`)
  - Push job status (`/job`)
  - Cluster health and version info
- Inline `description` fields for every endpoint, parameter, and response field
- A CI check (using `spectral` or similar) that validates the spec is syntactically correct
- A Swagger UI page served by the Controller at `/api-docs` (optional stretch goal)

**Out of scope:**

- Documenting internal or experimental endpoints
- Generating a Java client from the spec (documenting the existing client is out of scope)
- Authentication-related endpoints

### Key Technical Challenges

- **Keeping spec in sync**: The spec must not drift from the actual Controller implementation; a CI
  check that runs a basic smoke-test against a local cluster using the spec-generated client would
  catch drift early.
- **Response schema accuracy**: The Controller's responses use Venice-specific Avro-derived types;
  these must be accurately represented in the OpenAPI JSON Schema format, including nullable fields
  and enums.

### Suggested Starting Points

- `AdminSparkServer` in `services/venice-controller` — the full list of registered routes
- Existing `ControllerClient` in `internal/venice-common` — documents expected request/response shapes
- [OpenAPI 3.0 Specification](https://spec.openapis.org/oas/v3.0.3)

---

## Project 45: Partition Hot-Spot Auto-Rebalancing

### Overview

Venice distributes data across partitions using a hash function. Some key distributions are inherently
uneven (e.g., a store whose keys are customer IDs where a small number of large customers generate the
majority of lookups). Even if partition sizes are balanced, request rates per partition can be severely
skewed. Today, there is no mechanism to detect and respond to per-partition request rate skew.

This project implements **request-rate-aware partition hot-spot detection** and recommends (or automatically
executes) a partition count increase to dilute the hot partition's load.

### Impact

- **For Venice clusters**: Eliminates per-partition request rate bottlenecks that today cause tail latency
  degradation for all keys on the hot partition, improving p99 latency for affected stores.
- **For the intern**: Addressed a well-known distributed systems scaling challenge with a measurable
  production impact; quantifiable as "reduced p99 latency for hot-partition stores by X% through
  automated load rebalancing."

### Resume Impact

*"Built hot-spot detection and automated partition rebalancing for a distributed key-value store, reducing
p99 latency for skewed-access stores by detecting and diluting overloaded partitions."*

### Learning Outcomes

- Data skew and hot-spot problems in distributed hash-partitioned systems
- The tradeoff between partition granularity and per-partition overhead
- Automated remediation workflows: detecting a problem, proposing a solution, and applying it safely

### Scope

**In scope:**

- Track per-partition request rate (requests/sec) as a rolling gauge metric on Venice servers
- A Controller background task that queries servers for per-partition request rates and computes a
  skew score (max partition rate / mean partition rate)
- When skew score exceeds a configurable threshold (default: 10×), log a warning and emit a
  `hot_partition_detected` metric, including the store name and partition ID
- A `venice-admin-tool` sub-command `show-hot-partitions` that displays current hot partitions
- Unit tests for the skew score calculation

**Out of scope:**

- Automatically triggering a partition count increase (detection and reporting only)
- Adjusting the hash function to reduce skew
- Cross-region hot-spot detection

### Key Technical Challenges

- **Per-partition metric granularity**: Venice may have millions of partitions across all stores; tracking
  request rate per partition requires careful memory bounding (use a top-K structure rather than a full
  histogram per partition).
- **Aggregation across replicas**: Each partition has multiple replicas on different servers; the
  Controller must aggregate request rates from all replicas to compute a total rate per partition.

### Suggested Starting Points

- `ServerStats` in `services/venice-server` — existing server-side request metrics
- `VeniceHelixAdmin` in `services/venice-controller` — queries server state for admin tasks
- Project 18 (Key Sampling) in this document — similar per-partition tracking techniques

---

## Project 46: gRPC Streaming Batch Get

### Overview

Venice's current batch-get protocol sends all keys in a single request and receives all values in a
single response. For very large batches (thousands of keys), this creates a head-of-line blocking
problem: the response cannot be processed until all values have been retrieved and serialized. gRPC
bidirectional streaming provides a natural way to pipeline keys and values, allowing the client to begin
processing early values while the server is still fetching later ones.

This project implements **gRPC bidirectional streaming batch get** in Venice's gRPC server path,
enabling clients to stream keys and receive values as they are ready.

### Impact

- **For Venice users**: Reduces time-to-first-value for large batch gets from O(total_batch_latency) to
  O(single_key_latency), a significant improvement for real-time ML inference pipelines that process
  values as they arrive.
- **For the intern**: Shipped a next-generation API used in production; quantifiable as "reduced time-
  to-first-value for large batch gets by X ms, improving ML inference throughput by Y%."

### Resume Impact

*"Implemented gRPC bidirectional streaming for large batch reads in a distributed storage service,
reducing time-to-first-value from O(batch size) to O(single key latency) for ML inference workloads."*

### Learning Outcomes

- gRPC bidirectional streaming: the difference between unary, server-streaming, client-streaming, and
  bidirectional RPCs
- Pipelining and head-of-line blocking in distributed data retrieval
- Backpressure in streaming APIs: how the client signals it is ready for more data

### Scope

**In scope:**

- A new gRPC service method `StreamingBatchGet(stream KeyRequest) returns (stream ValueResponse)` in
  the Venice gRPC proto definition
- Server implementation: read each incoming key, look it up in RocksDB, and stream the response
  immediately without waiting for all keys to arrive
- Fast Client integration: a `streamingBatchGet` API that opens the streaming RPC, sends keys, and
  returns a `Flow` or `AsyncIterator` of values
- Unit tests verifying that values are returned before all keys are sent

**Out of scope:**

- Streaming batch get in the Thin Client
- Read Compute on streaming batch get requests
- Backpressure flow control (accept keys as fast as the client sends them)

### Key Technical Challenges

- **Ordering semantics**: The streaming API does not guarantee that values are returned in the same
  order as keys were sent; clients must match values to keys using a key identifier in the response.
- **Error mid-stream**: If one key's lookup fails, the stream must decide whether to send an error
  response for that key and continue, or to terminate the entire stream.
- **gRPC flow control**: The gRPC library has built-in flow control; the server must use it correctly
  to avoid overwhelming the client with responses faster than it can process them.

### Suggested Starting Points

- Venice's existing gRPC proto definitions in `services/venice-server`
- `VeniceGrpcServer` in `services/venice-server` — existing gRPC server setup
- [grpc-java bidirectional streaming tutorial](https://grpc.io/docs/languages/java/basics/#bidirectional-streaming-rpc)

---

## Project 47: Continuous Write Amplification Benchmarking

### Overview

Write amplification (the ratio of bytes written to disk vs. bytes logically written by the application) is
a critical metric for RocksDB-backed systems. High write amplification accelerates SSD wear, increases
compaction CPU usage, and reduces ingestion throughput. Venice's current RocksDB configuration was tuned
empirically; there is no automated benchmark that validates whether configuration changes improve or worsen
write amplification.

This project builds a **continuous write amplification benchmark** that runs as a nightly CI job, measures
write amplification under a standardized workload, and posts a regression comment on any PR that degrades
it.

### Impact

- **For Venice clusters**: Provides a safety net against configuration regressions that would increase
  SSD wear and reduce ingestion throughput; measurable as "caught X write amplification regressions
  before they reached production."
- **For the intern**: Built automated performance regression detection infrastructure integrated into CI;
  a concrete example of responsible performance engineering.

### Resume Impact

*"Built a continuous write amplification benchmark integrated into CI for a distributed storage service,
catching configuration regressions before production and reducing SSD wear events by establishing a
measurable baseline."*

### Learning Outcomes

- Write amplification in LSM trees: why it happens and how RocksDB configuration affects it
- CI/CD integration of performance benchmarks: preventing regressions without blocking developer velocity
- RocksDB statistics API and how to measure write amplification programmatically

### Scope

**In scope:**

- A JMH-based write amplification benchmark that:
  - Writes a fixed number of records to a local RocksDB instance with Venice's production configuration
  - Reads `rocksdb.compact.write.bytes` and `rocksdb.bytes.written` statistics to compute write amplification
  - Records and asserts that write amplification is below a configurable threshold (default: 10×)
- A GitHub Actions workflow step that runs the benchmark on PRs that touch Venice server or RocksDB
  configuration
- A README section documenting the expected write amplification baseline and how to interpret results
- Unit tests for the amplification calculation logic

**Out of scope:**

- Benchmarking on different hardware profiles (single hardware configuration only)
- Automated RocksDB tuning based on benchmark results
- Read amplification benchmarking

### Key Technical Challenges

- **Benchmark stability**: Write amplification varies with compaction timing; the benchmark must run
  long enough (sufficient records) for compaction to stabilize, and must flush and compact to a
  deterministic state before measuring.
- **CI resource limits**: The benchmark must complete within a reasonable CI time budget (under 5 minutes);
  record count and compaction parameters must be tuned accordingly.

### Suggested Starting Points

- `RocksDBStoragePartition` in `services/venice-server` — the RocksDB wrapper with statistics access
- Venice's existing JMH benchmarks (if any) in `tests/` — for CI integration patterns
- [RocksDB Tuning Guide](https://github.com/facebook/rocksdb/wiki/RocksDB-Tuning-Guide)

---

## Project 48: Incremental Schema Registry Sync Across Regions

### Overview

In Venice's multi-region deployment, schemas are registered in a "source of truth" region and must
be propagated to all other regions before data encoded with that schema can be served. Today, schema
propagation happens lazily—schemas are replicated as part of the admin channel flow, which can have
variable delays. If a schema is not yet present in a region when a server tries to deserialize a record
encoded with it, the server logs an error and drops the record.

This project implements **proactive, incremental schema sync**: a Controller background task that
detects schema gaps across regions and immediately initiates propagation to fill them.

### Impact

- **For Venice clusters**: Eliminates deserialization errors and dropped records caused by schema
  propagation delays in multi-region deployments, directly improving data availability for
  globally distributed stores.
- **For the intern**: Solved a reliability problem that manifested as silent data loss in production;
  quantifiable as "eliminated schema-gap-caused deserialization errors across X multi-region stores."

### Resume Impact

*"Built proactive schema synchronization across regions for a multi-region distributed storage service,
eliminating deserialization errors caused by schema propagation delays and improving cross-region data
availability."*

### Learning Outcomes

- Multi-region distributed systems: consistency challenges when state must be replicated across
  geographically separated datacenters
- Venice's admin channel: how admin operations (including schema registrations) propagate to child
  controllers in each region
- Incremental sync patterns: comparing two states and propagating only the delta

### Scope

**In scope:**

- A `SchemaSyncTask` background thread in the parent Controller (runs every configurable interval,
  default 2 minutes) that:
  - For each store, queries each child controller for its known schema versions
  - Identifies schemas present in the parent but missing in one or more child regions
  - Proactively pushes missing schemas to lagging regions via the admin channel
- A metric `schema_sync_lag` (count of missing schemas per region) and a log warning per gap detected
- Unit tests for schema gap detection logic

**Out of scope:**

- Conflict resolution for schemas registered independently in different regions
- Schema deletion sync
- Cross-fabric (inter-organization) schema sync

### Key Technical Challenges

- **Querying child controllers**: The parent must call each child controller's schema endpoint; this
  must be done with bounded parallelism and timeouts to avoid blocking on unresponsive regions.
- **Admin channel ordering**: Schema pushes through the admin channel are ordered; the task must not
  push a schema version out of order (e.g., push v3 before v2 is confirmed applied).

### Suggested Starting Points

- `ParentHelixAdmin` in `services/venice-controller` — the parent Controller's admin operations
- `ControllerClient` in `internal/venice-common` — client for calling child controllers
- The admin channel protocol in `internal/venice-common`

---

## Project 49: Configurable Read Request Timeout Policies

### Overview

Venice clients have a single, global read timeout applied to all requests. In practice, different stores
have very different latency profiles: a small in-memory store should time out in 1 ms, while a large SSD-
backed store may legitimately take 10 ms for a cache-miss read. A global timeout set conservatively for the
slowest store causes unnecessary latency overhead for fast stores; set aggressively, it causes false
timeouts on legitimate slow stores.

This project implements **per-store configurable read timeout policies** in the Venice Fast Client, allowing
each store to declare its own timeout, fallback timeout, and retry budget.

### Impact

- **For Venice users**: Reduces false timeout errors for latency-sensitive stores while allowing generous
  timeouts for stores with high cache-miss rates, directly improving application reliability and reducing
  unnecessary retry traffic.
- **For the intern**: Shipped a user-facing reliability improvement with measurable impact on timeout error
  rates; quantifiable as "reduced false timeout errors by X% for Y stores through per-store timeout tuning."

### Resume Impact

*"Implemented per-store configurable read timeout policies for a distributed storage client library,
reducing false timeout errors by enabling store-appropriate timeout values instead of a one-size-fits-all
global setting."*

### Learning Outcomes

- Timeout design in distributed systems: the difference between soft timeouts (fail fast), hard timeouts
  (cancel in-flight), and retry budgets
- Venice's request lifecycle in the Fast Client and where timeouts are enforced
- Per-entity configuration management in a distributed system

### Scope

**In scope:**

- A per-store config field `readTimeoutMs` (nullable; falls back to the global client timeout if unset)
- Fast Client reads the per-store timeout from the store's metadata at request time
- A fallback timeout (`readTimeoutFallbackMs`) that applies if the store config cannot be fetched
- A `venice-admin-tool` sub-command `set-read-timeout` that updates the per-store timeout config
- Unit tests verifying that the per-store timeout is used when set and falls back correctly when unset

**Out of scope:**

- Per-operation timeouts (single-get vs. batch-get)
- Dynamic timeout adjustment based on observed latency (static config only)
- Timeout policies for the Thin Client or Da Vinci Client

### Key Technical Challenges

- **Config propagation latency**: Per-store timeout configs are stored in ZooKeeper; the Fast Client
  must refresh its local cache periodically and use the latest value without blocking in the hot path.
- **Thread safety of timeout lookup**: The per-store timeout map is read on every request; it must be
  read without locks (use a `volatile` reference to an immutable map that is swapped atomically on update).

### Suggested Starting Points

- `ClientConfig` in `clients/venice-client` — Fast Client configuration
- `D2BasedClusterInfoProvider` in `clients/venice-client` — how the Fast Client fetches store metadata
- `StoreConfig` in `internal/venice-common` — per-store configuration schema

---

## Project 50: Self-Describing Admin Tool Help System

### Overview

The `venice-admin-tool` has dozens of sub-commands with many flags each. Today, the help text for each
command is hand-written in the source code and must be updated manually when command behavior changes.
Many commands have outdated or missing documentation, and there is no way for a user to discover commands
by searching for a keyword.

This project redesigns the `venice-admin-tool` help system to be **self-describing and searchable**:
help text is generated from annotations on command classes, all flag descriptions include examples, and
a `search` sub-command lets users find commands by keyword.

### Impact

- **For Venice operators**: Dramatically reduces the learning curve for new operators; a `venice-admin-tool
  search repush` immediately finds the relevant commands rather than requiring knowledge of the exact
  command name. Measurable as "reduced new operator onboarding time from X hours to Y hours."
- **For the intern**: Shipped infrastructure that directly improves the daily experience of every Venice
  operator; a visible, user-facing contribution to an open source project used at LinkedIn scale.

### Resume Impact

*"Redesigned the CLI help system for a distributed storage service's admin tool, adding annotation-driven
documentation generation and keyword search across dozens of commands, reducing operator onboarding time
by an estimated 40%."*

### Learning Outcomes

- CLI framework design: annotation-based configuration, help generation, and command discovery
- Reflection and annotation processing in Java
- Developer experience (DX) as a product: how tooling quality directly affects team productivity

### Scope

**In scope:**

- A `@CommandDescription(summary="...", examples={...})` annotation applied to every command class
- A `@FlagDescription(description="...", example="...")` annotation applied to every flag field
- A help formatter that generates the help text from annotations (replacing hand-written strings)
- A `search` sub-command that performs case-insensitive keyword matching across all command and flag
  descriptions and prints matching commands with their summaries
- Unit tests verifying that all commands have non-empty `@CommandDescription` annotations (enforced by
  a test that fails the build if missing)

**Out of scope:**

- Generating a web-based or man-page documentation format from the annotations
- Versioned help (help text reflects the current binary only)
- Auto-completion (shell tab completion) beyond what the current CLI framework provides

### Key Technical Challenges

- **Annotation completeness enforcement**: The build must fail if a new command is added without the
  required annotation; a reflection-based test that discovers all command classes at test time is needed.
- **Backward compatibility**: Existing users may have scripts that parse the current help text format;
  the new format must be at least as machine-parseable as the old one.

### Suggested Starting Points

- `VeniceAdminTool` in `clients/venice-admin-tool` — the main CLI entry point and existing command
  registration patterns
- Venice's existing command flag framework (likely JCommander or similar) — annotation patterns
- Existing help text strings throughout `clients/venice-admin-tool` — the content to migrate

---

## Project 51: TLA+ Specification for Partition Leader Election

### Overview

Venice uses Apache Helix to elect a leader replica for each partition. The correctness of leader
election—specifically, that at no point two replicas both believe they are the leader
(**split-brain**)—is critical to data consistency. While Venice relies on Helix for this guarantee, there
is no formal specification that captures Venice's specific assumptions and usage of Helix.

This project writes a **TLA+ (or FizzBee) specification** for Venice's partition leader election
protocol, verifies the safety property (at-most-one-leader), and adds it to the `specs/` directory
alongside Venice's existing formal specifications.

### Impact

- **For Venice's reliability story**: Provides a formal, machine-verified proof that Venice's leader
  election is free from split-brain under the modeled assumptions, strengthening confidence in the
  design and serving as a reference for future changes.
- **For the intern**: Produced a formal methods artifact for a production distributed system—a rare and
  highly valued skill; demonstrates rigorous distributed systems reasoning on a resume.

### Resume Impact

*"Authored and model-checked a TLA+ specification for the partition leader election protocol of a
distributed storage service, formally verifying the split-brain safety property and contributing to the
project's growing corpus of formal specifications."*

### Learning Outcomes

- TLA+ (or FizzBee): the Temporal Logic of Actions specification language and TLC model checker
- Formal verification of safety and liveness properties in distributed protocols
- Venice's Helix-based leader election mechanism

### Scope

**In scope:**

- A TLA+ (or FizzBee) specification in `specs/` modeling:
  - A fixed set of server nodes, each potentially holding a partition replica
  - The leader election process: a node proposes leadership, Helix verifies the candidate is healthy,
    and grants the role exclusively
  - Server failure (crash) and recovery transitions
- Safety invariant: `AtMostOneLeader` — at all reachable states, at most one node holds the LEADER
  role for a given partition
- TLC model check results documented in a README within the spec directory
- A brief written explanation of the modeling assumptions and limitations

**Out of scope:**

- Specifying the full Venice ingestion protocol (scope to leader election only)
- Liveness verification (focus on safety only)
- Specifying multi-region replication

### Key Technical Challenges

- **Abstracting Helix**: TLA+ models are abstractions; faithfully representing Helix's ZooKeeper-based
  election requires careful decisions about which details to include and which to abstract away.
- **State space explosion**: A naive model with many nodes and partitions will produce a state space too
  large to check exhaustively; the spec must use symmetry sets and constants to keep the state space
  manageable.

### Suggested Starting Points

- Existing FizzBee/TLA+ specs in `specs/` — style and structure to follow
- [TLA+ learning resources](https://learntla.com/)
- [FizzBee documentation](https://fizzbee.io/)
- Apache Helix leader election documentation

---

## Project 52: Backfill Support for Partial Update Stores

### Overview

Venice supports partial updates (Write Compute), where only certain fields of a record are updated
without rewriting the entire record. This is powerful for nearline pipelines that update different
fields independently. However, there is currently no built-in way to **backfill** a store that uses
partial updates: running a full batch push overwrites partial update history and loses all accumulated
nearline writes.

This project implements a **safe backfill workflow** for partial update stores: a push job mode that
merges the new batch data with the existing partial updates rather than replacing them.

### Impact

- **For Venice users**: Unlocks backfill operations for the growing class of stores using partial updates,
  which today cannot be refreshed from batch without losing nearline data—a critical operational gap.
- **For the intern**: Solved a real production problem that blocked multiple use cases; quantifiable as
  "enabled safe backfill for X partial-update stores, unblocking Y downstream data pipelines."

### Resume Impact

*"Designed and implemented a merge-based backfill workflow for partial-update stores in a distributed
storage service, enabling dataset refreshes without losing accumulated nearline writes."*

### Learning Outcomes

- Write Compute and partial updates in Venice: how field-level updates are stored and merged
- The Venice push job lifecycle and the difference between a full push and a repush
- Conflict resolution strategies for merging batch and nearline data in a distributed store

### Scope

**In scope:**

- A new push job option `--mergeWithExisting` for stores with `writeComputeEnabled = true`
- In merge mode, the push job reads the current version's data, merges each incoming record's fields
  with the existing record using the same Write Compute merge semantics, and writes the merged result
- A Controller-side validation step that rejects `--mergeWithExisting` for stores that do not have
  Write Compute enabled
- Unit tests for the merge logic and an integration test with a simple partial-update store

**Out of scope:**

- Merge semantics for stores without Write Compute (full-replacement push is unchanged)
- Incremental push with merge (full push only)
- Cross-region merge coordination

### Key Technical Challenges

- **Merge correctness**: The merge must produce the same result as if the nearline writes had been
  applied on top of the new batch data, not the other way around; the ordering of merge operations
  must be carefully specified.
- **Performance of read-then-write**: Reading the existing record before writing each incoming record
  doubles the I/O per key; this is acceptable for a batch job but must be bounded (e.g., by parallelism)
  to keep job duration reasonable.

### Suggested Starting Points

- `VenicePushJob` in `clients/venice-push-job` — push job entry point
- Write Compute merge logic in `internal/venice-common` — the existing field-level merge implementation
- [Batch Push documentation](../user-guide/write-apis/batch-push.md)

---

## Project 53: Dynamic Partition Count Expansion

### Overview

Venice requires specifying the partition count for a store at creation time. This count cannot currently
be changed without deleting and recreating the store (and re-pushing all data). As a store grows over time,
an under-partitioned store becomes a bottleneck: each partition is larger, ingestion is slower, and hot
partitions have fewer neighbors to dilute load.

This project implements **online partition count expansion**: the ability to double the partition count of
a store in place, using a consistent-hash rehashing step that moves data to the new partitions without
a full repush.

### Impact

- **For Venice operators**: Eliminates the need to delete-and-repush stores that have outgrown their
  original partition count—an operation that for large stores takes hours and blocks all writes. This
  is one of the most frequently requested operational capabilities.
- **For the intern**: Contributed a high-complexity, high-impact distributed systems feature; quantifiable
  as "enabled online partition expansion for X stores, saving Y person-hours of repush operations."

### Resume Impact

*"Designed and prototyped online partition count expansion for a distributed key-value store, eliminating
the need for costly full-repush operations when stores outgrow their initial partitioning."*

### Learning Outcomes

- Consistent hashing and how partition count changes affect key-to-partition mapping
- Online data migration in distributed storage: how to move data while continuing to serve reads
- Distributed coordination: using the Venice Controller and Helix to orchestrate a cluster-wide resharding

### Scope

**In scope:**

- A design document outlining the proposed expansion algorithm, including: how the new partition count
  is announced, how existing servers ingest the rehashed data, and how the cutover from old to new
  partitions is coordinated
- A proof-of-concept implementation that demonstrates doubling the partition count for a single-region,
  batch-only store in an integration test environment
- Unit tests for the consistent-hash rehashing logic (verifying that every key maps correctly to its
  new partition)

**Out of scope:**

- Non-power-of-two partition count changes (doubling only, for simplicity)
- Expansion for hybrid (nearline-write) stores
- Cross-region coordinated expansion

### Key Technical Challenges

- **Cutover atomicity**: The moment when routing switches from old partitions to new partitions must
  be atomic cluster-wide to prevent a window where some servers serve from old partitions and others
  from new ones.
- **Rollback**: If the expansion fails mid-way, the cluster must be able to roll back to the original
  partition count without data loss.
- **This is a hard problem**: Partition expansion is one of the hardest problems in distributed storage;
  the intern is expected to produce a working prototype and design document, not a production-ready
  implementation.

### Suggested Starting Points

- `VeniceHelixAdmin` in `services/venice-controller` — store version and partition management
- Venice's partition assignment logic in `internal/venice-common`
- Academic reference: [Consistent Hashing and Random Trees (Karger et al., 1997)](https://dl.acm.org/doi/10.1145/258533.258660)

---

## Project 54: End-to-End Data Freshness Tracking

### Overview

For Venice hybrid stores, data freshness is critical: ML models must serve predictions based on data
that is no more than N minutes old. Today, there is no first-class way to track how fresh the data
served by a Venice store actually is—operators must infer it from Kafka consumer lag, which is a proxy
at best.

This project implements **end-to-end data freshness tracking**: a mechanism that embeds producer-side
timestamps into records at write time, extracts them at serve time, and exposes a "data age" metric per
store—the age of the oldest data currently being served.

### Impact

- **For Venice users**: Provides a first-class, directly measurable freshness SLA for hybrid stores,
  enabling ML teams to make data-driven decisions about acceptable freshness tradeoffs and alerting
  immediately when data goes stale.
- **For the intern**: Built a cross-cutting observability feature spanning the write path, serve path,
  and metrics layer; quantifiable as "enabled freshness SLA monitoring for X hybrid stores, reducing
  time to detect stale data from hours to under 2 minutes."

### Resume Impact

*"Implemented end-to-end data freshness tracking for a distributed hybrid storage service, embedding
producer timestamps into records and exposing a real-time 'data age' metric that reduced stale data
detection time from hours to under 2 minutes."*

### Learning Outcomes

- Data freshness and staleness in distributed systems: how timestamps propagate through a pipeline
- Venice's hybrid store architecture: how batch and nearline data coexist in the same store version
- Cross-cutting observability: instrumenting a feature that spans the write path, serve path, and
  metrics infrastructure

### Scope

**In scope:**

- A `producerTimestampMs` field added to the Venice message envelope (in the Avro schema used by
  `VeniceWriter`) populated with `System.currentTimeMillis()` at write time
- At read time, the Venice server extracts the `producerTimestampMs` from the stored record and
  updates a per-store `data_age_ms` gauge metric (age = current time − max producer timestamp seen
  in the last 60 seconds)
- A `venice-admin-tool` sub-command `show-data-age` that queries all servers for a store and reports
  min/max/mean data age per partition
- Unit tests for timestamp embedding and extraction, and a test verifying that the metric is
  populated after a write

**Out of scope:**

- Per-key freshness tracking (store-level aggregate only)
- Freshness for batch-only stores (hybrid stores only)
- Automated enforcement of freshness SLAs (reporting only)

### Key Technical Challenges

- **Backward compatibility**: Adding `producerTimestampMs` to the message envelope must not break
  existing servers that do not know about this field; the field must be optional with a sensible
  default (0 or -1 indicating "not available").
- **Clock skew between producers**: Different producer instances may have slightly different clocks;
  the `data_age_ms` metric must document that it reflects the producer clock, not an absolute measure
  of system time.
- **High-frequency metric updates**: The server may receive thousands of records per second; updating
  the `data_age_ms` gauge on every record would be too expensive. The metric must be updated via a
  background sampler or on a per-batch basis.

### Suggested Starting Points

- `VeniceWriter` in `internal/venice-common` — writes messages to Kafka with envelope metadata
- `StoreIngestionTask` in `services/venice-server` — where ingested records are processed at read time
- Venice's message envelope Avro schema in `internal/venice-common`
