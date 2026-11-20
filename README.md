# Forge

Forge is a Java 25 CLI for deterministic local execution of polyglot build tasks. It coordinates the actual installed toolchain; it does not replace Cargo, Ninja, Gradle, Maven or a compiler.

## Working product surface

- Strict version-1 JSON manifests with typed targets, relative working directories, declared inputs/outputs, dependencies, environment allowlists, explicit argv and timeouts.
- Lexicographic DAG planning, missing-dependency/cycle errors and dependency failure propagation.
- SHA-256, length-prefixed, domain-separated target fingerprints covering recipe, file contents, environment and transitive dependency hashes.
- Direct `ProcessBuilder` execution, bounded output capture, virtual-thread stdout/stderr drains and timeout termination.
- Success-only local output caching, verified restore and explicit corruption rejection.
- `plan`, `run`, `graph`, and `why` commands for human-readable inspection.

**Current limits:** execution is sequential. There is no remote cache, distributed worker, process-group guarantee, security sandbox or closed-world toolchain dependency inference. Environment and toolchain contents not explicitly declared by a target are not magically reproducible. Symbolic-link inputs are rejected. A failed or timed-out action is never cached.

Adapters build actual tool invocations for `cmake`, `ninja`, `cargo`, `zig`, `go`, `gradle`, `maven`, `dotnet`, `uv`, `node`, and `dart`; `exec` supports arbitrary argv. An adapter does not emulate the underlying engine's complete dependency graph. CMake configuration, lockfiles and native tool inputs must be declared accurately. Adapter availability does not mean every toolchain was exercised on every operating system.

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
