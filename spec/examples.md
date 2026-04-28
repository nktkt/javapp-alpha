# Examples

## Option

```java
package app.demo;

data enum Option<T> {
    Some(T value),
    None
}
```

## Result

```java
package app.demo;

data enum Result<T, E> {
    Ok(T value),
    Err(E error)
}
```

## Nullable Match

```java
package app.demo;

class Greeter {
    static String greet(String? name) {
        return match (name) {
            null -> "Hello";
            var n -> "Hello, {n}";
        };
    }
}
```

## Data Class

```java
package app.demo;

data class User {
    UserId id;
    String name;
    String? nickname;
}

value record UserId(long value) {}
```

## Runtime Option / Result

```java
package app.demo;

import dev.javapp.runtime.Option;
import dev.javapp.runtime.Result;

class RuntimeDemo {
    static Option<String> maybeName(String value) {
        return Option.ofNullable(value);
    }

    static Result<Integer, String> parseInt(String value) {
        try {
            return Result.ok(Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return Result.err("not an int: " + value);
        }
    }
}
```

## Value Record

```java
package app.demo;

value record Vec2(double x, double y) {
    double length() {
        return Math.sqrt(x * x + y * y);
    }
}
```

## Extension Method

```java
package app.demo;

extension String {
    boolean hasText() {
        return this != null && !this.isBlank();
    }
}
```
