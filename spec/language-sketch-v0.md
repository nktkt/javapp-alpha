# Language Sketch v0

## Nullable Types

```java
String name;     // non-null by default
String? alias;   // nullable
String! legacy;  // platform nullness from Java interop
```

Compiler modes:

```text
--null off
--null warn
--null strict
```

The alpha parser recognizes the markers and strips them for Java output. Flow-sensitive checks are not implemented yet.
It also emits a warning or error for simple direct dereferences of nullable locals and parameters:

```java
String? name = findName();
return name.length(); // strict error
```

Same-line guards are recognized in the alpha:

```java
return name != null ? name.length() : 0;
```

The alpha also recognizes local guard clauses and simple not-null blocks:

```java
if (name == null) {
    return 0;
}
return name.length();

if (name != null) {
    return name.length();
}
```

Calls to `requireNonNull(name)` also narrow `name` for following lines.

Null coalescing is supported in the alpha:

```java
return alias ?? "unknown";
```

Alpha lowering keeps simple locals and fields allocation-free:

```java
return alias != null ? alias : "unknown";
```

For left operands that may have side effects, such as method calls, the lowerer evaluates the left operand once:

```java
return java.util.Optional.ofNullable(findAlias()).orElse("unknown");
```

## Data Enum

```java
data enum Result<T, E> {
    Ok(T value),
    Err(E error)
}
```

Alpha lowering:

```java
sealed interface Result<T, E> permits Ok, Err {
    static <T, E> Result<T, E> Ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> Err(E error) {
        return new Err<>(error);
    }
}

record Ok<T, E>(T value) implements Result<T, E> {}
record Err<T, E>(E error) implements Result<T, E> {}
```

Java++ code may construct variants with bare constructor syntax:

```java
Result<User, ApiError> found(User user) {
    return Ok(user);
}
```

Alpha lowering:

```java
return new Ok<>(user);
```

## Data Class

```java
data class User {
    UserId id;
    String name;
    String? nickname;
}
```

Alpha lowering:

```java
record User(UserId id, String name, String nickname) {}
```

The alpha compiler lowers field-only data classes to records. If a data class has simple fields plus methods, it lowers to a final class with private final fields, a constructor, record-style accessors, and generated `equals`, `hashCode`, and `toString` methods:

```java
data class Profile {
    String name;
    int age;

    String label() {
        return "{name}:{age}";
    }
}
```

Both record-backed and class-backed data classes generate a full-field `copy(...)` method and per-field `withX(...)` helpers:

```java
Profile older = profile.withAge(profile.age() + 1);
Profile renamed = profile.copy("Mika", profile.age());
```

Field initializers and more complex class members still fall back to ordinary classes.

## Match

```java
String message = match (result) {
    Ok(var user) -> "Hello, {user.name}";
    Err(var err) -> "Error: {err.message}";
};
```

Guarded cases use Java-style `when` guards:

```java
String message = match (result) {
    Ok(var value) when !value.isBlank() -> "ok: {value}";
    Ok(var value) -> "blank";
    Err(var error) -> "err: {error}";
};
```

Alpha lowering:

```java
String message = switch (result) {
    case Ok(var user) -> "Hello, " + String.valueOf(user.name);
    case Err(var err) -> "Error: " + String.valueOf(err.message);
};
```

The alpha compiler warns when a `match` over a known same-project `data enum` misses a variant:

```java
return match (result) {
    Ok(var user) -> user.name();
}; // warning: missing variants: Err
```

Guarded cases do not count toward exhaustiveness unless the guard is literally `true`, because the guard may reject a value at runtime.

## Runtime Option / Result

Java++ also ships Java-usable runtime versions of common sum types:

```java
Option<String> name = Option.ofNullable(rawName);
String label = name.map(String::trim).orElse("unknown");

Result<Integer, String> parsed = parseInt(text);
int value = parsed.orElse(0);
```

`dev.javapp.runtime.Option<T>` and `dev.javapp.runtime.Result<T,E>` are sealed Java types with `map`, `flatMap`, `orElse`, and `Optional` conversion helpers. They coexist with user-defined `data enum` declarations.

## Effects

```java
User findUser(UserId id) effect IO, DB {
    return repository.find(id);
}
```

The alpha parser recognizes and removes `effect ...` markers for Java output. Later versions will support warning and strict checking.

The alpha analyzer supports `--effects off|warn|strict`. It diagnoses obvious side effects inside `pure` methods:

```java
String readConfig(Path path) effect IO {
    return Files.readString(path);
}

pure String bad(Path path) {
    return readConfig(path); // strict error
}
```

It also recognizes a small set of JDK calls such as `Files.readString(...)`, `Files.writeString(...)`, `Thread.sleep(...)`, `System.out.*`, `System.load(...)`, and `System.loadLibrary(...)`. This is not full effect inference yet.

## Value Record

```java
value record Vec2(double x, double y) {
    double length() {
        return Math.sqrt(x * x + y * y);
    }
}
```

The alpha lowering maps this to a Java `record`. Later versions can target Valhalla value-object features when they are available as a stable backend.

## Extension Methods

```java
extension String {
    boolean hasText() {
        return this != null && !this.isBlank();
    }
}
```

Alpha lowering:

```java
final class StringExtensions {
    private StringExtensions() {}

    static boolean hasText(String self) {
        return self != null && !self.isBlank();
    }
}
```

Simple call-site rewriting is supported when the receiver is a typed local or parameter:

```java
String name = "java";
return name.hasText();
```

Alpha lowering:

```java
String name = "java";
return StringExtensions.hasText(name);
```

Overload resolution and chained receivers such as `user.name().hasText()` are not implemented yet.

## Resource Management

```java
use file = Files.newBufferedReader(path);
defer mutex.unlock();
return file.readLine();
```

Alpha lowering:

```java
try (var file = Files.newBufferedReader(path)) {
    try {
        return file.readLine();
    } finally {
        mutex.unlock();
    }
}
```

`use name = expression;` is lowered as `try (var name = expression)`.
`use Type name = expression;` keeps the explicit resource type.
Both `use` and `defer` are scoped to the current block, and repeated cleanup statements are nested so later registrations run first.

## Planned Structured Async

```java
scoped timeout 2.seconds {
    var user = async { users.find(id) };
    return user.await();
}
```

Alpha lowering:

```java
try (var __jppScope1 = dev.javapp.runtime.StructuredScope.open(java.time.Duration.ofSeconds(2))) {
    var user = __jppScope1.fork(() -> users.find(id));
    return user.await();
}
```

The alpha runtime uses virtual threads. Leaving the `scoped` block cancels unfinished tasks and closes the backing executor. `await()` unwraps runtime exceptions and wraps checked failures in `StructuredTaskException`.

Supported timeout literal units are `millis`, `seconds`, and `minutes`, plus short forms such as `ms`, `s`, and `m`.

## CLI Run

```bash
javapp run app.demo.RunDemo --source examples/basic/src/main/jpp -- Naoki
```

`run` builds `.jpp` and `.java` sources into the configured classes directory, then launches the selected Java main class. Build options use the same names as `build`; arguments after `--` are passed to the application unchanged.

## Migration Report

```bash
javapp check --source src/main/jpp --null strict --effects strict
javapp check --source src/main/jpp --format json
```

`check` runs the Java++ diagnostic pipeline without writing generated Java files. It currently reports nullable dereference, platform nullness markers, non-exhaustive `data enum` matches, and strict `pure`/`effect` violations. The command exits non-zero when any diagnostic has error severity.

```bash
javapp migrate src/main/java
javapp migrate src/main/java --format json
```

The alpha migration report scans existing Java files for common Java++ adoption points such as null returns, `Optional<T>` API boundaries, `CompletableFuture`, manual executors, and resource cleanup. JSON output is intended for CI and editor integrations.

Diagnostics include stable codes:

```text
JPP_NULL_001     nullable dereference
JPP_NULL_002     platform nullness marker
JPP_MATCH_001    non-exhaustive data enum match
JPP_EFFECT_001   known side-effecting call in pure method
JPP_EFFECT_002   same-project effectful call in pure method
JPP_MIGRATE_*    migration report hints
```

## Formatter

```bash
javapp fmt src/main/jpp
javapp fmt src/main/jpp --check
```

The alpha formatter is intentionally conservative. It normalizes line endings, removes trailing whitespace, compresses long blank-line runs, and guarantees a final newline. `--check` is non-destructive and exits non-zero when any `.jpp` file would change, which makes it suitable for CI before the formatter grows into a syntax-aware pretty printer.
