---
layout: default
title: Venice
nav_order: 1
---

<div class="home-hero">
  <img src="assets/style/venice_full_lion_logo.svg" alt="Venice logo" class="hero-logo" />
  <h1 class="hero-title">Venice</h1>
  <p class="hero-tagline">Derived Data Platform for Planet-Scale Workloads</p>
  <div class="hero-buttons">
    <a href="quickstart/quickstart" class="btn btn-primary">Quickstart</a>
    <a href="dev_guide/dev_guide" class="btn">Developer Docs</a>
    <a href="dev_guide/how_to/how_to" class="btn">Contribute</a>
  </div>
</div>

Venice is a derived data storage platform, providing the following characteristics:

1. High throughput asynchronous ingestion from batch and streaming sources (e.g. [Hadoop](https://github.com/apache/hadoop) and [Samza](https://github.com/apache/samza)).
2. Low latency online reads via remote queries or in-process caching.
3. Active-active replication between regions with CRDT-based conflict resolution.
4. Multi-cluster support within each region with operator-driven cluster assignment.
5. Multi-tenancy, horizontal scalability and elasticity within each cluster.

<div class="home-sections">
  <div class="card">
    <h2>Get Started</h2>
    <p>Spin up a Venice cluster and try features like creating a data store, batch push, incremental push, and single get.</p>
    <a href="quickstart/quickstart" class="btn btn-primary">Quickstart Guides</a>
  </div>
  <div class="card">
    <h2>Maintain</h2>
    <p>Learn about project internals and operations.</p>
    <a href="dev_guide/dev_guide" class="btn">Developer Guide</a>
    <a href="ops_guide/ops_guide" class="btn">Operator Guide</a>
  </div>
  <div class="card">
    <h2>Contribute</h2>
    <p>Join the community and help improve Venice.</p>
    <a href="dev_guide/how_to/how_to" class="btn">Contributor Guide</a>
  </div>
</div>

