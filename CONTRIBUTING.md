# Contributing

Run `mvn clean verify` on JDK 25 and 27. Add regression tests for malformed manifests, graph changes, cache invalidation and process failures. Keep the public execution model honest: sequential execution is the current contract; sandboxing, remote execution and parallel scheduling require separate implementations and tests.

Adapters must invoke the real installed toolchain. Do not add a language label without a command builder, a documented input contract and an executable example where that toolchain is available.
