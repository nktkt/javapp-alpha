package dev.javapp.compiler;

import dev.javapp.cli.Main;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public final class TranspilerSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("javapp-smoke-");
        Path sourceRoot = root.resolve("src/main/jpp");
        Path packageRoot = sourceRoot.resolve("app/demo");
        Files.createDirectories(packageRoot);

        Files.writeString(packageRoot.resolve("Result.jpp"), """
                package app.demo;

                data enum Result<T, E> {
                    Ok(T value),
                    Err(E error)
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("Greeter.jpp"), """
                package app.demo;

                class Greeter {
                    static String greet(String? name) {
                        return match (name) {
                            null -> "Hello";
                            var n -> "Hello, {n}";
                        };
                    }

                    static String ternary(boolean flag) {
                        return flag ? "yes" : "no";
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("NullCoalesceDemo.jpp"), """
                package app.demo;

                class NullCoalesceDemo {
                    static int calls = 0;

                    static String fallback(String? name) {
                        return name ?? "guest";
                    }

                    static String methodFallback() {
                        return maybeName() ?? "guest";
                    }

                    static String maybeName() {
                        calls++;
                        return null;
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("ResultView.jpp"), """
                package app.demo;

                class ResultView {
                    static Result<String, String> ok(String value) {
                        return Ok(value);
                    }

                    static Result<String, String> err(String error) {
                        return Err(error);
                    }

                    static String render(Result<String, String> result) {
                        return match (result) {
                            Ok(var value) -> "ok: {value}";
                            Err(var error) -> "err: {error}";
                        };
                    }

                    static String renderGuarded(Result<String, String> result) {
                        return match (result) {
                            Ok(var value) when !value.isBlank() -> "ok: {value}";
                            Ok(var value) -> "blank";
                            Err(var error) -> "err: {error}";
                        };
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("Maybe.jpp"), """
                package app.demo;

                data enum Maybe<T> {
                    Some(T value),
                    None
                }

                class MaybeFactory {
                    static Maybe<String> some(String value) {
                        return Some(value);
                    }

                    static Maybe<String> none() {
                        return None();
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("ResourceDemo.jpp"), """
                package app.demo;

                import java.io.ByteArrayInputStream;

                class ResourceDemo {
                    static int readFirst(byte[] bytes) throws java.io.IOException {
                        use in = new ByteArrayInputStream(bytes);
                        defer ResourceDemo.afterRead();
                        return in.read();
                    }

                    static void afterRead() {
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("AsyncDemo.jpp"), """
                package app.demo;

                class AsyncDemo {
                    static int sum(int left, int right) {
                        scoped {
                            var a = async { left + 1 };
                            var b = async { right + 1 };
                            return a.await() + b.await();
                        }
                    }

                    static int timedSum(int left, int right) {
                        scoped timeout 1.seconds {
                            var a = async { left + 1 };
                            var b = async { right + 1 };
                            return a.await() + b.await();
                        }
                    }

                    static int timeout() {
                        scoped timeout 1.millis {
                            var slow = async {
                                Thread.sleep(200);
                                return 1;
                            };
                            return slow.await();
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("User.jpp"), """
                package app.demo;

                data class User {
                    UserId id;
                    String name;
                    String? nickname;
                }

                value record UserId(long value) {
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("Profile.jpp"), """
                package app.demo;

                data class Profile {
                    String name;
                    int age;

                    String label() {
                        return "{name}:{age}";
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("StringExtensions.jpp"), """
                package app.demo;

                extension String {
                    boolean hasText() {
                        return this != null && !this.isBlank();
                    }

                    String surround(String prefix, String suffix) {
                        return prefix + this + suffix;
                    }
                }

                class ExtensionUse {
                    static boolean hasText(String value) {
                        return value.hasText();
                    }

                    static String tagged(String value) {
                        return value.surround("[", "]");
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("RuntimeDemo.jpp"), """
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
                """, StandardCharsets.UTF_8);

        Files.writeString(packageRoot.resolve("CliMain.jpp"), """
                package app.demo;

                public class CliMain {
                    public static void main(String[] args) {
                        String name = args.length > 0 ? args[0] : null;
                        String fallback = "Java++";
                        System.out.println("cli:{name ?? fallback}");
                    }
                }
                """, StandardCharsets.UTF_8);

        JavappCompiler compiler = new JavappCompiler();
        Path generated = root.resolve("build/generated");
        Path classes = root.resolve("build/classes");
        BuildResult result = compiler.build(sourceRoot, root.resolve("src/main/java"), generated, classes, NullMode.STRICT);

        require(result.success(), "generated Java should compile:\n" + result.compilerOutput());

        String resultJava = Files.readString(generated.resolve("app/demo/Result.java"), StandardCharsets.UTF_8);
        require(resultJava.contains("sealed interface Result<T, E> permits Ok, Err"), "data enum should become sealed interface");
        require(resultJava.contains("record Ok<T, E>(T value) implements Result<T, E>"), "Ok should become record");

        String greeterJava = Files.readString(generated.resolve("app/demo/Greeter.java"), StandardCharsets.UTF_8);
        require(greeterJava.contains("switch (name)"), "match should become switch");
        require(greeterJava.contains("String.valueOf(n)"), "string interpolation should lower");
        require(greeterJava.contains("flag ? \"yes\" : \"no\""), "ternary operator should remain intact");

        String nullCoalesceJava = Files.readString(generated.resolve("app/demo/NullCoalesceDemo.java"), StandardCharsets.UTF_8);
        require(nullCoalesceJava.contains("return (name != null ? name : \"guest\");"),
                "stable null coalesce left operand should lower to ternary");
        require(nullCoalesceJava.contains("return java.util.Optional.ofNullable(maybeName()).orElse(\"guest\");"),
                "effectful null coalesce left operand should lower without double evaluation");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> nullCoalesceDemo = Class.forName("app.demo.NullCoalesceDemo", true, loader);
            var fallback = nullCoalesceDemo.getDeclaredMethod("fallback", String.class);
            fallback.setAccessible(true);
            require("guest".equals(fallback.invoke(null, new Object[]{null})), "null coalesce should return fallback for null");
            require("java".equals(fallback.invoke(null, "java")), "null coalesce should return present value");
            var methodFallback = nullCoalesceDemo.getDeclaredMethod("methodFallback");
            methodFallback.setAccessible(true);
            require("guest".equals(methodFallback.invoke(null)), "method null coalesce should return fallback");
            var calls = nullCoalesceDemo.getDeclaredField("calls");
            calls.setAccessible(true);
            require((int) calls.get(null) == 1, "method null coalesce should evaluate the left expression once");
        }

        String resultViewJava = Files.readString(generated.resolve("app/demo/ResultView.java"), StandardCharsets.UTF_8);
        require(resultViewJava.contains("return new Ok<>(value);"),
                "bare data enum variant constructor should lower to record construction");
        require(resultViewJava.contains("return new Err<>(error);"),
                "bare data enum variant constructor should lower generic err construction");
        require(resultViewJava.contains("case Ok(var value) when !value.isBlank()"),
                "guarded match case should lower to Java pattern switch guard");
        String maybeJava = Files.readString(generated.resolve("app/demo/Maybe.java"), StandardCharsets.UTF_8);
        require(maybeJava.contains("return new Some<>(value);"),
                "bare generic data enum variant constructor should lower with diamond");
        require(maybeJava.contains("return new None<>();"),
                "bare no-arg generic data enum variant constructor should lower with diamond");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> resultView = Class.forName("app.demo.ResultView", true, loader);
            var okFactory = resultView.getDeclaredMethod("ok", String.class);
            okFactory.setAccessible(true);
            require(okFactory.invoke(null, "value").getClass().getSimpleName().equals("Ok"),
                    "bare Ok(value) constructor should run");
            var errFactory = resultView.getDeclaredMethod("err", String.class);
            errFactory.setAccessible(true);
            require(errFactory.invoke(null, "error").getClass().getSimpleName().equals("Err"),
                    "bare Err(error) constructor should run");
            var renderGuarded = resultView.getDeclaredMethod("renderGuarded", Class.forName("app.demo.Result", true, loader));
            renderGuarded.setAccessible(true);
            require("ok: value".equals(renderGuarded.invoke(null, okFactory.invoke(null, "value"))),
                    "guarded match should take guarded branch when guard passes");
            require("blank".equals(renderGuarded.invoke(null, okFactory.invoke(null, ""))),
                    "guarded match should fall through when guard fails");

            Class<?> maybeFactory = Class.forName("app.demo.MaybeFactory", true, loader);
            var someFactory = maybeFactory.getDeclaredMethod("some", String.class);
            someFactory.setAccessible(true);
            require(someFactory.invoke(null, "value").getClass().getSimpleName().equals("Some"),
                    "bare Some(value) constructor should run");
            var noneFactory = maybeFactory.getDeclaredMethod("none");
            noneFactory.setAccessible(true);
            require(noneFactory.invoke(null).getClass().getSimpleName().equals("None"),
                    "bare None() constructor should run");
        }

        String resourceJava = Files.readString(generated.resolve("app/demo/ResourceDemo.java"), StandardCharsets.UTF_8);
        require(resourceJava.contains("try (var in = new ByteArrayInputStream(bytes))"), "use should lower to try-with-resources");
        require(resourceJava.contains("} finally {"), "defer should lower to finally");
        require(resourceJava.contains("ResourceDemo.afterRead();"), "defer finalizer should be preserved");

        String asyncJava = Files.readString(generated.resolve("app/demo/AsyncDemo.java"), StandardCharsets.UTF_8);
        require(asyncJava.contains("try (var __jppScope"), "scoped should lower to try-with-resources");
        require(asyncJava.contains(".fork(() -> left + 1)"), "async expression should lower to scope fork");
        require(asyncJava.contains("StructuredScope.open(java.time.Duration.ofSeconds(1))"),
                "scoped timeout seconds should lower to Duration");
        require(asyncJava.contains("StructuredScope.open(java.time.Duration.ofMillis(1))"),
                "scoped timeout millis should lower to Duration");
        require(asyncJava.contains("a.await() + b.await()"), "await calls should remain on task handles");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> asyncDemo = Class.forName("app.demo.AsyncDemo", true, loader);
            var sum = asyncDemo.getDeclaredMethod("sum", int.class, int.class);
            sum.setAccessible(true);
            require((int) sum.invoke(null, 2, 3) == 7, "generated structured async code should run");
            var timedSum = asyncDemo.getDeclaredMethod("timedSum", int.class, int.class);
            timedSum.setAccessible(true);
            require((int) timedSum.invoke(null, 2, 3) == 7, "generated structured async timeout code should run before deadline");
            var timeout = asyncDemo.getDeclaredMethod("timeout");
            timeout.setAccessible(true);
            try {
                timeout.invoke(null);
                throw new AssertionError("generated structured async timeout should fail after deadline");
            } catch (InvocationTargetException ex) {
                require(ex.getCause() instanceof dev.javapp.runtime.StructuredTaskException,
                        "timeout should throw StructuredTaskException");
            }
        }

        String userJava = Files.readString(generated.resolve("app/demo/User.java"), StandardCharsets.UTF_8);
        require(userJava.contains("record User(UserId id, String name, String nickname)"),
                "field-only data class should lower to record");
        require(userJava.contains("public User copy(UserId id, String name, String nickname)"),
                "field-only data class should generate full copy method");
        require(userJava.contains("public User withName(String name)"),
                "field-only data class should generate per-field with methods");
        require(userJava.contains("record UserId(long value)"),
                "value record should still lower to record");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> userId = Class.forName("app.demo.UserId", true, loader);
            var userIdConstructor = userId.getDeclaredConstructor(long.class);
            userIdConstructor.setAccessible(true);
            Object id = userIdConstructor.newInstance(1L);
            Class<?> user = Class.forName("app.demo.User", true, loader);
            var userConstructor = user.getDeclaredConstructor(userId, String.class, String.class);
            userConstructor.setAccessible(true);
            Object original = userConstructor.newInstance(id, "Aki", null);
            var withName = user.getDeclaredMethod("withName", String.class);
            withName.setAccessible(true);
            Object renamed = withName.invoke(original, "Mika");
            var nameAccessor = user.getDeclaredMethod("name");
            nameAccessor.setAccessible(true);
            require("Mika".equals(nameAccessor.invoke(renamed)),
                    "field-only data class with-field method should run");
        }

        String profileJava = Files.readString(generated.resolve("app/demo/Profile.java"), StandardCharsets.UTF_8);
        require(profileJava.contains("final class Profile"), "data class with methods should lower to final class");
        require(profileJava.contains("private final String name;"), "data class class lowering should create final fields");
        require(profileJava.contains("public Profile(String name, int age)"), "data class class lowering should create constructor");
        require(profileJava.contains("public String name()"), "data class class lowering should create accessors");
        require(profileJava.contains("public Profile copy(String name, int age)"),
                "data class class lowering should create full copy method");
        require(profileJava.contains("public Profile withAge(int age)"),
                "data class class lowering should create per-field with methods");
        require(profileJava.contains("public boolean equals(Object other)"), "data class class lowering should create equals");
        require(profileJava.contains("public int hashCode()"), "data class class lowering should create hashCode");
        require(profileJava.contains("public String toString()"), "data class class lowering should create toString");
        require(profileJava.contains("String label()"), "data class class lowering should preserve methods");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> profile = Class.forName("app.demo.Profile", true, loader);
            var constructor = profile.getDeclaredConstructor(String.class, int.class);
            constructor.setAccessible(true);
            Object left = constructor.newInstance("Aki", 30);
            Object right = constructor.newInstance("Aki", 30);
            Object other = constructor.newInstance("Nao", 31);
            var label = profile.getDeclaredMethod("label");
            label.setAccessible(true);
            require("Aki:30".equals(label.invoke(left)), "data class preserved method should run");
            var nameAccessor = profile.getDeclaredMethod("name");
            nameAccessor.setAccessible(true);
            require("Aki".equals(nameAccessor.invoke(left)), "data class accessor should run");
            require(left.equals(right), "data class generated equals should compare fields");
            require(!left.equals(other), "data class generated equals should reject different fields");
            require(left.hashCode() == right.hashCode(), "data class generated hashCode should match equal fields");
            require(left.toString().contains("Profile[name=Aki, age=30"),
                    "data class generated toString should include field names");
            var withAge = profile.getDeclaredMethod("withAge", int.class);
            withAge.setAccessible(true);
            Object older = withAge.invoke(left, 31);
            require("Aki:31".equals(label.invoke(older)), "data class with-field method should copy with one replacement");
            var copy = profile.getDeclaredMethod("copy", String.class, int.class);
            copy.setAccessible(true);
            Object copied = copy.invoke(left, "Mika", 20);
            require("Mika:20".equals(label.invoke(copied)), "data class copy should replace all fields");
        }

        String stringExtensionsJava = Files.readString(generated.resolve("app/demo/StringExtensions.java"), StandardCharsets.UTF_8);
        require(stringExtensionsJava.contains("final class StringExtensions"), "extension should lower to utility class");
        require(stringExtensionsJava.contains("static boolean hasText(String self)"), "extension method should receive self");
        require(stringExtensionsJava.contains("self != null && !self.isBlank()"), "extension method should rewrite this to self");
        require(stringExtensionsJava.contains("return StringExtensions.hasText(value);"),
                "extension call with no args should rewrite to static call");
        require(stringExtensionsJava.contains("return StringExtensions.surround(value, \"[\", \"]\");"),
                "extension call with args should rewrite to static call");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> stringExtensions = Class.forName("app.demo.StringExtensions", true, loader);
            var hasText = stringExtensions.getDeclaredMethod("hasText", String.class);
            hasText.setAccessible(true);
            require((boolean) hasText.invoke(null, "hello"), "generated extension method should run");
            require(!(boolean) hasText.invoke(null, "   "), "generated extension method should preserve behavior");
            var surround = stringExtensions.getDeclaredMethod("surround", String.class, String.class, String.class);
            surround.setAccessible(true);
            require("[x]".equals(surround.invoke(null, "x", "[", "]")),
                    "generated extension method with parameters should run");
            Class<?> extensionUse = Class.forName("app.demo.ExtensionUse", true, loader);
            var useHasText = extensionUse.getDeclaredMethod("hasText", String.class);
            useHasText.setAccessible(true);
            require((boolean) useHasText.invoke(null, "hello"), "rewritten extension call should run");
            var tagged = extensionUse.getDeclaredMethod("tagged", String.class);
            tagged.setAccessible(true);
            require("[x]".equals(tagged.invoke(null, "x")), "rewritten extension call with args should run");
        }

        var some = dev.javapp.runtime.Option.some("java");
        require(some.isSome(), "runtime Option.some should be some");
        require("JAVA".equals(some.map(String::toUpperCase).orElse("")), "runtime Option.map should transform values");
        require(dev.javapp.runtime.Option.none().isNone(), "runtime Option.none should be none");
        require("fallback".equals(dev.javapp.runtime.Option.<String>none().orElse("fallback")),
                "runtime Option.orElse should return fallback for none");
        var ok = dev.javapp.runtime.Result.<Integer, String>ok(41).map(value -> value + 1);
        require(ok.isOk() && ok.okOptional().orElseThrow() == 42, "runtime Result.map should transform ok values");
        var err = dev.javapp.runtime.Result.<Integer, String>err("bad").map(value -> value + 1);
        require(err.isErr() && "bad".equals(err.errOptional().orElseThrow()),
                "runtime Result.map should preserve err values");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> runtimeDemo = Class.forName("app.demo.RuntimeDemo", true, loader);
            var maybeName = runtimeDemo.getDeclaredMethod("maybeName", String.class);
            maybeName.setAccessible(true);
            Object maybe = maybeName.invoke(null, "java");
            require(maybe instanceof dev.javapp.runtime.Option<?> option && option.isSome(),
                    "generated code should return runtime Option");
            var parseInt = runtimeDemo.getDeclaredMethod("parseInt", String.class);
            parseInt.setAccessible(true);
            Object parsed = parseInt.invoke(null, "42");
            require(parsed instanceof dev.javapp.runtime.Result<?, ?> resultValue && resultValue.isOk(),
                    "generated code should return runtime Result ok");
            Object failed = parseInt.invoke(null, "x");
            require(failed instanceof dev.javapp.runtime.Result<?, ?> failedResult && failedResult.isErr(),
                    "generated code should return runtime Result err");
        }

        var nullDiagnostics = new NullSafetyAnalyzer().analyze(
                Path.of("UnsafeNull.jpp"),
                "class UnsafeNull { int size(String? name) { return name.length(); } }",
                NullMode.STRICT
        );
        require(nullDiagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR),
                "strict null mode should reject direct nullable dereference");
        require(nullDiagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("JPP_NULL_001")),
                "null diagnostic should include stable code");

        var guardedDiagnostics = new NullSafetyAnalyzer().analyze(
                Path.of("GuardedNull.jpp"),
                "class GuardedNull { int size(String? name) { return name != null ? name.length() : 0; } }",
                NullMode.STRICT
        );
        require(guardedDiagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR),
                "same-line null guard should suppress direct nullable dereference error");

        var guardClauseDiagnostics = new NullSafetyAnalyzer().analyze(
                Path.of("GuardClause.jpp"),
                """
                class GuardClause {
                    int size(String? name) {
                        if (name == null) {
                            return 0;
                        }
                        return name.length();
                    }
                }
                """,
                NullMode.STRICT
        );
        require(guardClauseDiagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR),
                "null-exit guard clause should narrow nullable values after the guard");

        var notNullBlockDiagnostics = new NullSafetyAnalyzer().analyze(
                Path.of("NotNullBlock.jpp"),
                """
                class NotNullBlock {
                    int size(String? name) {
                        if (name != null) {
                            return name.length();
                        }
                        return 0;
                    }
                }
                """,
                NullMode.STRICT
        );
        require(notNullBlockDiagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR),
                "not-null block should narrow nullable values inside the block");

        var unguardedAfterBlockDiagnostics = new NullSafetyAnalyzer().analyze(
                Path.of("UnguardedAfterBlock.jpp"),
                """
                class UnguardedAfterBlock {
                    int size(String? name) {
                        if (name != null) {
                            System.out.println(name.length());
                        }
                        return name.length();
                    }
                }
                """,
                NullMode.STRICT
        );
        require(unguardedAfterBlockDiagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR),
                "not-null block should not narrow nullable values after the block");

        var requireNonNullDiagnostics = new NullSafetyAnalyzer().analyze(
                Path.of("RequireNonNull.jpp"),
                """
                class RequireNonNull {
                    int size(String? name) {
                        java.util.Objects.requireNonNull(name);
                        return name.length();
                    }
                }
                """,
                NullMode.STRICT
        );
        require(requireNonNullDiagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR),
                "requireNonNull should narrow nullable values after the call");

        var enumRegistry = MatchExhaustivenessAnalyzer.collectDataEnums(List.of("""
                data enum Maybe<T> {
                    Some(T value),
                    None
                }
                """));
        var nonExhaustive = MatchExhaustivenessAnalyzer.analyze(
                Path.of("MaybeView.jpp"),
                """
                class MaybeView {
                    static String render(Maybe<String> maybe) {
                        return match (maybe) {
                            Some(var value) -> value;
                        };
                    }
                }
                """,
                enumRegistry
        );
        require(nonExhaustive.stream().anyMatch(diagnostic -> diagnostic.message().contains("missing variants: None")),
                "data enum match should report missing variants");
        require(nonExhaustive.stream().anyMatch(diagnostic -> diagnostic.code().equals("JPP_MATCH_001")),
                "match diagnostic should include stable code");
        var exhaustive = MatchExhaustivenessAnalyzer.analyze(
                Path.of("MaybeView.jpp"),
                """
                class MaybeView {
                    static String render(Maybe<String> maybe) {
                        return match (maybe) {
                            Some(var value) -> value;
                            None -> "";
                        };
                    }
                }
                """,
                enumRegistry
        );
        require(exhaustive.isEmpty(), "data enum match should accept exhaustive cases");

        var guardedNonExhaustive = MatchExhaustivenessAnalyzer.analyze(
                Path.of("MaybeView.jpp"),
                """
                class MaybeView {
                    static String render(Maybe<String> maybe) {
                        return match (maybe) {
                            Some(var value) when !value.isBlank() -> value;
                            None -> "";
                        };
                    }
                }
                """,
                enumRegistry
        );
        require(guardedNonExhaustive.stream().anyMatch(diagnostic -> diagnostic.message().contains("missing variants: Some")),
                "guarded data enum cases should not count as exhaustive unless the guard is true");

        var guardedTrueExhaustive = MatchExhaustivenessAnalyzer.analyze(
                Path.of("MaybeView.jpp"),
                """
                class MaybeView {
                    static String render(Maybe<String> maybe) {
                        return match (maybe) {
                            Some(var value) when true -> value;
                            None -> "";
                        };
                    }
                }
                """,
                enumRegistry
        );
        require(guardedTrueExhaustive.isEmpty(), "when true should count as exhaustive");

        var effectRegistry = EffectAnalyzer.collectEffectfulMethods(List.of("""
                class Effects {
                    static String readConfig() effect IO {
                        return "config";
                    }

                    pure static String badLocal() {
                        return readConfig();
                    }

                    pure static void badJdk() throws Exception {
                        Thread.sleep(1);
                    }
                }
                """));
        var effectDiagnostics = EffectAnalyzer.analyze(
                Path.of("Effects.jpp"),
                """
                class Effects {
                    static String readConfig() effect IO {
                        return "config";
                    }

                    pure static String badLocal() {
                        return readConfig();
                    }

                    pure static void badJdk() throws Exception {
                        Thread.sleep(1);
                    }
                }
                """,
                effectRegistry,
                EffectMode.STRICT
        );
        require(effectDiagnostics.stream().anyMatch(diagnostic ->
                        diagnostic.severity() == Diagnostic.Severity.ERROR
                                && diagnostic.message().contains("effectful method 'readConfig'")),
                "pure method should reject calls to effectful same-project methods in strict mode");
        require(effectDiagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("JPP_EFFECT_002")),
                "same-project effect diagnostic should include stable code");
        require(effectDiagnostics.stream().anyMatch(diagnostic ->
                        diagnostic.severity() == Diagnostic.Severity.ERROR
                                && diagnostic.message().contains("Thread.sleep(")),
                "pure method should reject known blocking JDK calls in strict mode");
        require(effectDiagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("JPP_EFFECT_001")),
                "known effect diagnostic should include stable code");

        Path effectRoot = root.resolve("effect-src");
        Files.createDirectories(effectRoot.resolve("app/effects"));
        Files.writeString(effectRoot.resolve("app/effects/Effects.jpp"), """
                package app.effects;

                class Effects {
                    static String readConfig() effect IO {
                        return "config";
                    }

                    pure static String bad() {
                        return readConfig();
                    }
                }
                """, StandardCharsets.UTF_8);
        List<TranspileResult> effectResults = compiler.transpile(
                effectRoot,
                root.resolve("effect-generated"),
                NullMode.WARN,
                EffectMode.STRICT
        );
        require(effectResults.stream()
                        .flatMap(transpileResult -> transpileResult.diagnostics().stream())
                        .anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR
                                && diagnostic.message().contains("readConfig")),
                "compiler pipeline should expose strict effect diagnostics");

        ByteArrayOutputStream checkOutput = new ByteArrayOutputStream();
        PrintStream previousCheckOut = System.out;
        try {
            System.setOut(new PrintStream(checkOutput, true, StandardCharsets.UTF_8));
            int exit = new Main().run(new String[]{
                    "check",
                    "--source", effectRoot.toString(),
                    "--effects", "strict",
                    "--format", "json"
            });
            require(exit == 1, "check should fail when strict diagnostics contain errors");
        } finally {
            System.setOut(previousCheckOut);
        }
        String checkJson = checkOutput.toString(StandardCharsets.UTF_8);
        require(checkJson.startsWith("{\"diagnostics\":["), "check json should produce a diagnostics object");
        require(checkJson.contains("\"code\":\"JPP_EFFECT_002\""),
                "check json should include same-project effect diagnostics");

        Path migrationRoot = root.resolve("migration-src");
        Files.createDirectories(migrationRoot);
        Files.writeString(migrationRoot.resolve("Legacy.java"), """
                import java.util.Optional;
                import java.util.concurrent.CompletableFuture;

                class Legacy {
                    Optional<String> maybe() {
                        return Optional.empty();
                    }

                    String nullable() {
                        return null;
                    }

                    CompletableFuture<String> future() {
                        return CompletableFuture.completedFuture("ok");
                    }
                }
                """, StandardCharsets.UTF_8);
        ByteArrayOutputStream migrationOutput = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        try {
            System.setOut(new PrintStream(migrationOutput, true, StandardCharsets.UTF_8));
            int exit = new Main().run(new String[]{"migrate", migrationRoot.toString(), "--format", "json"});
            require(exit == 0, "migrate json should exit successfully");
        } finally {
            System.setOut(previousOut);
        }
        String migrationJson = migrationOutput.toString(StandardCharsets.UTF_8);
        require(migrationJson.startsWith("{\"diagnostics\":["), "migrate json should produce a diagnostics object");
        require(migrationJson.contains("\"code\":\"JPP_MIGRATE_001\""),
                "migrate json should include diagnostic code");
        require(migrationJson.contains("\"message\":\"possible null return; consider T? or Result/Option\""),
                "migrate json should include null return hint");
        require(migrationJson.contains("\"message\":\"CompletableFuture chain candidate for structured async\""),
                "migrate json should include structured async hint");

        Path formatRoot = root.resolve("format-src");
        Files.createDirectories(formatRoot);
        Path messy = formatRoot.resolve("Messy.jpp");
        Files.writeString(messy,
                "package app.format;   \r\n\r\n\r\nclass Messy {    \r\n    String value() { return \"x\"; }    \r\n}",
                StandardCharsets.UTF_8);
        ByteArrayOutputStream formatCheckError = new ByteArrayOutputStream();
        PrintStream previousErr = System.err;
        try {
            System.setErr(new PrintStream(formatCheckError, true, StandardCharsets.UTF_8));
            int exit = new Main().run(new String[]{"fmt", formatRoot.toString(), "--check"});
            require(exit == 1, "fmt --check should fail on unformatted sources");
        } finally {
            System.setErr(previousErr);
        }
        require(formatCheckError.toString(StandardCharsets.UTF_8).contains("not formatted"),
                "fmt --check should list unformatted files");

        int formatExit = new Main().run(new String[]{"fmt", formatRoot.toString()});
        require(formatExit == 0, "fmt should format sources successfully");
        String formatted = Files.readString(messy, StandardCharsets.UTF_8);
        require(formatted.equals("""
                package app.format;


                class Messy {
                    String value() { return "x"; }
                }
                """), "fmt should trim trailing whitespace, normalize line endings, and ensure final newline");

        int cleanFormatCheck = new Main().run(new String[]{"fmt", "--check", "--source", formatRoot.toString()});
        require(cleanFormatCheck == 0, "fmt --check should pass after formatting");

        ByteArrayOutputStream runOutput = new ByteArrayOutputStream();
        PrintStream previousRunOut = System.out;
        try {
            System.setOut(new PrintStream(runOutput, true, StandardCharsets.UTF_8));
            int exit = new Main().run(new String[]{
                    "run",
                    "app.demo.CliMain",
                    "--source", sourceRoot.toString(),
                    "--java-source", root.resolve("src/main/java").toString(),
                    "--generated", root.resolve("run-generated").toString(),
                    "--classes", root.resolve("run-classes").toString(),
                    "--null", "strict",
                    "--",
                    "runner"
            });
            require(exit == 0, "run should build and execute the selected main class");
        } finally {
            System.setOut(previousRunOut);
        }
        require(runOutput.toString(StandardCharsets.UTF_8).contains("cli:runner"),
                "run should pass application arguments after --");

        System.out.println("Transpiler smoke test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
