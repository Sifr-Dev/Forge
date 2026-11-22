# Engineering log

Product development started 2026-11-20. Initial implementation evidence is appended only after commands execute.

## 2026-11-22 — bounded parallel scheduling and adapter verification

### Scheduling

Execution was strictly sequential. `ForgeRunner` now takes a job count and
schedules by dependency indegree: a target becomes eligible when all
dependencies have finished successfully, and eligible targets are taken in
lexicographic id order. Results are emitted in plan order regardless of
completion order, so a given job count always produces the same log sequence.
The CLI default is `min(cores, 8)`; `--jobs N` overrides it.

Each worker is a platform thread, because the work is process supervision and
blocking stream drains rather than CPU. Concurrency is bounded by the worker
count, not by virtual threads, so a wide graph cannot fork hundreds of
compilers. Cache publication was made concurrency-safe: a blob or entry
written by another worker is replaced atomically instead of failing the move.

### Why not structured concurrency

`StructuredTaskScope` remains a preview API in JDK 25/27. Introducing a preview
dependency into a tool that must build on both LTS and current JDKs was not
worth it for a fixed-size worker pool; the pool is cancelled in a `finally`
block on interruption or failure.

### Adapter verification findings

Running every adapter against its real toolchain (see the benchmark report
`forge-v0.1.0.md`) found two defects:

1. The Maven adapter executed `maven`; the executable is `mvn`.
2. Clearing the child environment to `PATH` broke Go and Zig, which need a
   resolvable user cache. `HOME`, `XDG_CACHE_HOME`, `XDG_CONFIG_HOME` and
   `TMPDIR` are now adapter-required passthrough variables and are included in
   the fingerprint.

Ten of eleven adapters now pass a cold-build-plus-warm-cache-hit check. The
Gradle adapter remains unverified and is labelled as such.

### Measured effect

48-target, four-level graph, 20 ms work per target: 1,328 ms cold at one
worker, 295 ms at eight (4.5×), bounded by the four-level critical path. Warm
runs are flat (~140 ms) because all 48 lookups hit. A failing root with 39
dependents exits 1 in 109 ms and skips all 39 without executing them.
