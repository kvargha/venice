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
- [Fast Client documentation](../../user-guide/read-apis/fast-client.md)

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
