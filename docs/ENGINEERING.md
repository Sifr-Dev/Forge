# Engineering log

Product development started 2026-11-20. Initial implementation evidence is appended only after commands execute.

## 2026-11-26 — containment of target-local failures and cache permission fidelity

Both defects were reproduced against the built jar before any code changed,
and both new tests fail on the pre-fix sources.

### A missing toolchain aborted the whole build

`ForgeRunner.executeOne` let a `ForgeException` escape the worker task, and
`completion.take().get()` rethrew it out of `run()`. Anything raised before the
child started — an executable that cannot be spawned, a missing declared
input, an unusable output path — therefore destroyed the entire run. Reproduced
with three targets at `--jobs 3`, where `missing-toolchain` used a nonexistent
executable:

- before: `sifr-forge: cannot execute /nonexistent/compiler-xyz`, CLI exit `2`;
  the independent `sibling` and the dependent's `SKIPPED` status were lost.
- after: `missing-toolchain FAILED`, `sibling SUCCEEDED`,
  `dependent SKIPPED`, CLI exit `1`.

This matters because "the installed toolchain is absent" is the ordinary
adapter failure, not an exceptional one. A target-local `ForgeException` now
becomes a `FAILED` result. Cancellation is excluded by checking that the worker
thread is already interrupted, and unexpected runtime exceptions still abort
because they indicate an engine defect rather than a target failure.

### Cache restore dropped file permissions

Blobs are addressed by content SHA-256, so any two outputs with identical bytes
share one blob, while POSIX permission bits are not part of the hash. `restore`
copied bytes with `Files.copy`, which yields the default mode. Verified with a
real project where a 644 data file and a 755 copy held identical bytes:

- before: the blob was created as `644`, the restored tool came back `644`, and
  the dependent target failed with `./bin/tool: Permission denied` while the
  cache reported `CACHE_HIT restored verified outputs`.
- after: the same run restores `755`, the tool executes and reports
  `tool-ran`; the 644 output stays 644 rather than inheriting the blob's mode.

Entries now carry `mode.<output>` and a `format=2` marker; entries without the
marker are a miss so old caches rebuild rather than restore wrong permissions.
Filesystems without POSIX support record `n/a` and skip the step. Content
verification is unchanged: a corrupted blob is still rejected.

### Validation

- `mvn clean verify`: 12 tests, 0 failures. The three new tests were confirmed
  to fail on the pre-fix sources (2 errors, 1 assertion failure).
- Real toolchains, cold build plus warm cache hit with the output deleted:
  `cargo` (restored 2.1 MB release binary executes), `go`, and `cmake`+`ninja`
  (restored `build/token` is `755` and prints `cmake-ok`).
- Scheduler containment with a real Go target plus an absent `zig`: Go
  `SUCCEEDED`, `zig` `FAILED`, dependent `SKIPPED`, exit `1`.
- Example CI smoke sequence (`plan`, `run`, `run` again, grep `CACHE_HIT`)
  passes.
- `bench/forge-benchmark.sh 12 4` shows no scheduling regression: 48 targets
  cold 1636 ms at 1 worker and 367 ms at 8 (4.5×), warm ~190 ms with 48 hits,
  and the failing-root case skips 39 dependents in 151 ms with exit `1`.

### Limits of this evidence

`gradle`, `uv`, `dart` and `node` were not exercised here and were not
available to exercise; the permission fix is filesystem-level and independent
of the adapter, but it is only demonstrated on the adapters named above. The
cached entry still does not restore ownership, timestamps, extended attributes
or symlinks. A cache hit for a target with no declared outputs reports
`CACHE_HIT` with nothing restored; that is intentional because such a target's
declared inputs fully determine its action, but it does mean such a target
never re-executes once cached.

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
