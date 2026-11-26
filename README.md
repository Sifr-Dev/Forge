# Forge

Forge is a Java 25 CLI for deterministic local execution of polyglot build tasks. It coordinates the actual installed toolchain; it does not replace Cargo, Ninja, Gradle, Maven or a compiler.

## Working product surface

- Strict version-1 JSON manifests with typed targets, relative working directories, declared inputs/outputs, dependencies, environment allowlists, explicit argv and timeouts.
- Lexicographic DAG planning, missing-dependency/cycle errors and dependency failure propagation; a target that cannot start fails alone, with its dependents skipped and unrelated targets unaffected.
- SHA-256, length-prefixed, domain-separated target fingerprints covering recipe, file contents, environment and transitive dependency hashes.
- Direct `ProcessBuilder` execution, bounded output capture, virtual-thread stdout/stderr drains and timeout termination.
- Dependency-driven scheduling with bounded parallelism (`--jobs N`, default
  `min(cores, 8)`); results are always reported in plan order and the ready set
  is chosen lexicographically, so logs are deterministic for a given job count.
- Success-only local output caching, verified restore, recorded permission bits and explicit corruption rejection.
- `plan`, `run`, `graph`, and `why` commands for human-readable inspection.

**Current limits:** there is no remote cache, distributed worker, process-group guarantee, security sandbox or closed-world toolchain dependency inference. Environment and toolchain contents not explicitly declared by a target are not magically reproducible. Symbolic-link inputs are rejected. A failed or timed-out action is never cached.

Adapters build actual tool invocations for `cmake`, `ninja`, `cargo`, `zig`, `go`, `gradle`, `maven`, `dotnet`, `uv`, `node`, and `dart`; `exec` supports arbitrary argv. Ten of the eleven adapters were verified end to end against the real toolchains (cold build plus warm cache hit); **the `gradle` adapter is not verified** because a distribution Gradle differs from a project wrapper and no reproducible minimal project was exercised. See the verification report referenced in the release notes. An adapter does not emulate the underlying engine's complete dependency graph. CMake configuration, lockfiles and native tool inputs must be declared accurately. Adapter availability does not mean every toolchain was exercised on every operating system.

The environment seen by a child process is cleared and rebuilt from `PATH`,
the adapter's required toolchain variables (`HOME`, `XDG_CACHE_HOME`,
`XDG_CONFIG_HOME`, `TMPDIR` — needed by Go, Zig, Dart and .NET), the target's
`envAllowlist`, and its explicit `env` map. All of those values participate in
the fingerprint, so caches are machine-local.

## Build and verify

JDK 25+ and Maven:

```sh
mvn clean verify
java -jar target/sifr-forge.jar plan examples/exec/forge.json
java -jar target/sifr-forge.jar run examples/exec/forge.json
# Same fingerprint: second run reports CACHE_HIT.
java -jar target/sifr-forge.jar why examples/exec/forge.json
```

Use feature branches and Conventional Commits. Every semantic change requires executable tests. Do not describe a child process as a security sandbox, or sequential scheduling as parallel.

## License

MIT; see `LICENSE`.
