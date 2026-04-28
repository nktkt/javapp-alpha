# Java++ Charter

Java++ is a JVM-first language layer for teams that want Java's ecosystem, tooling, and operational model while gaining stronger defaults around safety, concurrency, data modeling, and performance-sensitive code.

## Principles

| Principle | Meaning |
| --- | --- |
| Java interop first | Existing Java classes, JARs, Maven artifacts, Gradle builds, agents, debuggers, and profilers remain usable. |
| JVM first | The default backend emits JVM bytecode through Java source or direct bytecode generation. No new VM is required. |
| Safe by default | Non-null types, scoped concurrency, auditable native access, and resource lifetimes are language concepts. |
| Performance is opt-in | Value records, primitive collections, AOT profiles, SIMD, and native access are explicit choices. |
| Low-level power is isolated | Unsafe memory, reflection, native linking, and final-field mutation require visible capability boundaries. |
| Java remains recognizable | Java++ should feel like Java with sharper types, not like an unrelated JVM language. |

## Compatibility Model

Java++ uses `.jpp` files. Java uses `.java` files.

This keeps the Java compatibility story simple:

- `.java` is compiled by `javac`
- `.jpp` is compiled by `javapp`
- Java++ can call Java directly
- Java can call generated Java++ bytecode through ordinary classes, records, and interfaces

The project does not require Java++ to be a complete source-level superset of Java. That choice avoids the hardest grammar conflicts and gives Java++ room for features like `match`, `data enum`, `use`, `defer`, and effect markers.

## Initial Feature Set

The first useful Java++ layer is intentionally small:

1. null safety
2. `data enum` and `match`
3. `use` and `defer`
4. structured async
5. Java migration diagnostics

Performance features follow after the daily-development features prove stable:

1. `value record`
2. specialized generics
3. FFM/native capability wrappers
4. ownership and borrowing for off-heap resources
5. AOT and startup profiles

