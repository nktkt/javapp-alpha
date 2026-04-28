# Java++ Alpha

Java++ Alpha is an experimental JVM language layer that keeps Java interop first while adding safer and more expressive syntax in front of ordinary Java bytecode.

It is not a new VM and it is not a replacement for the Java standard library. The current prototype transpiles `.jpp` files into Java source and then relies on the ordinary JDK toolchain.

## Status

This is an early research scaffold. It is useful for exploring syntax, lowering rules, CLI shape, and migration diagnostics, but it is not production-ready.

## What's Included

- language charter and non-goals in `spec/`
- a small `javapp` CLI
- a source-to-source `.jpp -> .java` transpiler
- runtime helpers for `Option`, `Result`, and structured async
- examples for `data enum`, `match`, nullable markers, `??`, `use`, `defer`, `effect`, and string interpolation

The compiler is intentionally small. It targets JDK 25+ Java source and lowers Java++ syntax into Java that can be compiled by `javac`.

## Prerequisites

- JDK 25 or newer
- Maven 3.9+ for packaging
- A POSIX shell for `bin/javapp` and `scripts/test.sh`

## Quick Start

```bash
./bin/javapp transpile --source examples/basic/src/main/jpp --generated build/generated/sources/javapp
./bin/javapp build --source examples/basic/src/main/jpp --classes build/classes
./bin/javapp check --source examples/basic/src/main/jpp --null strict --effects strict
./bin/javapp fmt examples/basic/src/main/jpp --check
./bin/javapp migrate src/main/java
./bin/javapp migrate src/main/java --format json
```

Run the smoke test and package the CLI:

```bash
./scripts/test.sh
mvn -q -DskipTests package
```

## Repository Layout

```text
bin/          CLI launcher
examples/     sample .jpp programs
scripts/      smoke-test helpers
spec/         language charter, non-goals, and sketch
src/main/     compiler, runtime, and CLI source
src/test/     executable smoke test
```

## Implemented In This Alpha

- `data enum` lowered to sealed interfaces plus records
- field-only `data class` lowered to Java `record`
- `match (...) { ... }` lowered to Java `switch`
- same-project `data enum` match exhaustiveness warnings
- nullable type markers `T?` and platform markers `T!` parsed and stripped for Java output
- direct nullable dereference diagnostics for `--null warn|strict`, including guard clauses and not-null blocks
- null coalescing with `left ?? fallback`
- `effect IO, DB` markers parsed and stripped for Java output
- `pure` diagnostics for known side-effecting calls and same-project `effect` methods via `--effects warn|strict`
- `value record` lowered to Java `record`
- `extension Type { ... }` lowered to static utility methods with `self`
- simple extension call rewriting for typed variables, such as `name.hasText()`
- `use` lowered to Java try-with-resources for block-scoped resources
- `defer` lowered to Java `try/finally` for block-scoped cleanup
- `scoped { ... }`, `scoped timeout N.unit { ... }`, and `async { ... }` lowered to a virtual-thread runtime scope
- runtime `Option<T>` and `Result<T,E>` sealed types with common map/flatMap/orElse helpers
- simple string interpolation such as `"Hello, {name}"`
- diagnostics with stable codes such as `JPP_NULL_001` and `JPP_MIGRATE_001`
- `javapp check` for no-output diagnostics in `text` and `json` formats
- migration report hints for existing Java sources with `text` and `json` output
- `javapp fmt` for minimal `.jpp` formatting and `--check` CI mode

## Not Implemented Yet

- full flow-sensitive null checking beyond local guard clauses and simple not-null blocks
- full effect inference and effect propagation
- extension overload resolution and chained receiver rewriting
- strict structured async cancellation policies beyond timeout cancellation
- ownership and borrow checking
- specialized generics backend
- FFM/native capability checks
- macro and derive system
- Gradle/Maven plugins

The goal of this scaffold is to make the concept concrete enough to iterate on: a tiny language front-end, a stable CLI shape, and specs that define what Java++ should avoid as much as what it should add.
