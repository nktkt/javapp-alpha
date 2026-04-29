package dev.javapp.cli;

import dev.javapp.compiler.BuildResult;
import dev.javapp.compiler.Diagnostic;
import dev.javapp.compiler.EffectMode;
import dev.javapp.compiler.JavappCompiler;
import dev.javapp.compiler.JppFormatter;
import dev.javapp.compiler.MigrationAnalyzer;
import dev.javapp.compiler.NullMode;
import dev.javapp.compiler.TranspileResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    private final JavappCompiler compiler = new JavappCompiler();

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = new Main().run(args);
        } catch (Exception ex) {
            System.err.println("javapp: " + ex.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    public int run(String[] args) throws IOException, InterruptedException {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp();
            return 0;
        }

        String command = args[0];
        Map<String, String> options = parseOptions(Arrays.copyOfRange(args, 1, args.length));

        return switch (command) {
            case "transpile" -> transpile(options);
            case "build" -> build(options);
            case "run" -> runProgram(args);
            case "check" -> check(options);
            case "migrate" -> migrate(args);
            case "fmt" -> format(args);
            case "test" -> {
                System.out.println("Use ./scripts/test.sh for the alpha smoke test.");
                yield 0;
            }
            default -> {
                System.err.println("Unknown command: " + command);
                printHelp();
                yield 1;
            }
        };
    }

    private int transpile(Map<String, String> options) throws IOException {
        Path sourceRoot = pathOption(options, "source", "src/main/jpp");
        Path generatedRoot = pathOption(options, "generated", "build/generated/sources/javapp");
        NullMode nullMode = NullMode.parse(options.getOrDefault("null", "warn"));
        EffectMode effectMode = EffectMode.parse(options.getOrDefault("effects", "warn"));

        List<TranspileResult> results = compiler.transpile(sourceRoot, generatedRoot, nullMode, effectMode);
        printTranspileResults(results);
        System.out.println("Transpiled " + results.size() + " .jpp file(s) into " + generatedRoot);
        return hasErrors(results) ? 1 : 0;
    }

    private int build(Map<String, String> options) throws IOException {
        Path sourceRoot = pathOption(options, "source", "src/main/jpp");
        Path javaSourceRoot = pathOption(options, "java-source", "src/main/java");
        Path generatedRoot = pathOption(options, "generated", "build/generated/sources/javapp");
        Path classesRoot = pathOption(options, "classes", "build/classes");
        NullMode nullMode = NullMode.parse(options.getOrDefault("null", "warn"));
        EffectMode effectMode = EffectMode.parse(options.getOrDefault("effects", "warn"));

        BuildResult result = compiler.build(sourceRoot, javaSourceRoot, generatedRoot, classesRoot, nullMode, effectMode);
        printTranspileResults(result.transpiled());

        if (!result.compilerOutput().isBlank()) {
            System.err.print(result.compilerOutput());
        }

        if (result.success()) {
            System.out.println("Built " + result.javaSources().size() + " Java source file(s) into " + classesRoot);
            return hasErrors(result.transpiled()) ? 1 : 0;
        }

        System.err.println("Build failed.");
        return 1;
    }

    private int runProgram(String[] args) throws IOException, InterruptedException {
        RunOptions options = parseRunOptions(args);
        if (options == null) {
            return 1;
        }

        BuildResult result = compiler.build(
                options.sourceRoot(),
                options.javaSourceRoot(),
                options.generatedRoot(),
                options.classesRoot(),
                options.nullMode(),
                options.effectMode()
        );
        printTranspileResults(result.transpiled());

        if (!result.compilerOutput().isBlank()) {
            System.err.print(result.compilerOutput());
        }
        if (!result.success()) {
            System.err.println("Build failed.");
            return 1;
        }
        if (hasErrors(result.transpiled())) {
            return 1;
        }

        return launchMain(options.classesRoot(), options.mainClass(), options.programArgs());
    }

    private int check(Map<String, String> options) throws IOException {
        Path sourceRoot = pathOption(options, "source", "src/main/jpp");
        NullMode nullMode = NullMode.parse(options.getOrDefault("null", "warn"));
        EffectMode effectMode = EffectMode.parse(options.getOrDefault("effects", "warn"));
        String format = options.getOrDefault("format", "text");

        List<Diagnostic> diagnostics = compiler.check(sourceRoot, nullMode, effectMode);
        if ("json".equals(format)) {
            System.out.println(toJson(diagnostics));
            return hasDiagnosticErrors(diagnostics) ? 1 : 0;
        }
        if (!"text".equals(format)) {
            System.err.println("Unknown check format: " + format);
            return 1;
        }
        if (diagnostics.isEmpty()) {
            System.out.println("No diagnostics found in " + sourceRoot);
            return 0;
        }
        for (Diagnostic diagnostic : diagnostics) {
            System.err.println(diagnostic.format());
        }
        return hasDiagnosticErrors(diagnostics) ? 1 : 0;
    }

    private int migrate(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: javapp migrate <java-source-root> [--format text|json]");
            return 1;
        }

        Path root = Path.of(args[1]);
        Map<String, String> options = parseOptions(Arrays.copyOfRange(args, 2, args.length));
        String format = options.getOrDefault("format", "text");
        List<Diagnostic> diagnostics = new MigrationAnalyzer().analyze(root);
        if ("json".equals(format)) {
            System.out.println(toJson(diagnostics));
            return 0;
        }
        if (!"text".equals(format)) {
            System.err.println("Unknown migrate format: " + format);
            return 1;
        }
        if (diagnostics.isEmpty()) {
            System.out.println("No migration hints found in " + root);
            return 0;
        }

        for (Diagnostic diagnostic : diagnostics) {
            System.out.println(diagnostic.format());
        }
        return 0;
    }

    private int format(String[] args) throws IOException {
        FormatOptions options = parseFormatOptions(args);
        if (options == null) {
            return 1;
        }
        if (!Files.exists(options.root())) {
            System.err.println("Format root not found: " + options.root());
            return 1;
        }

        List<JppFormatter.FormatResult> results = new JppFormatter().formatTree(options.root(), options.checkOnly());
        long changed = results.stream().filter(JppFormatter.FormatResult::changed).count();
        if (options.checkOnly()) {
            if (changed == 0) {
                System.out.println("Format check passed for " + results.size() + " .jpp file(s).");
                return 0;
            }
            for (JppFormatter.FormatResult result : results) {
                if (result.changed()) {
                    System.err.println(result.file() + ": not formatted");
                }
            }
            return 1;
        }

        System.out.println("Formatted " + changed + " of " + results.size() + " .jpp file(s).");
        return 0;
    }

    private static FormatOptions parseFormatOptions(String[] args) {
        Path root = Path.of("src/main/jpp");
        boolean rootSet = false;
        boolean checkOnly = false;

        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if ("--check".equals(token)) {
                checkOnly = true;
                continue;
            }
            if (token.startsWith("--source=")) {
                root = Path.of(token.substring("--source=".length()));
                rootSet = true;
                continue;
            }
            if ("--source".equals(token)) {
                if (i + 1 >= args.length) {
                    System.err.println("Usage: javapp fmt [source-root] [--source DIR] [--check]");
                    return null;
                }
                root = Path.of(args[++i]);
                rootSet = true;
                continue;
            }
            if (token.startsWith("--")) {
                System.err.println("Unknown fmt option: " + token);
                return null;
            }
            if (rootSet) {
                System.err.println("Usage: javapp fmt [source-root] [--source DIR] [--check]");
                return null;
            }
            root = Path.of(token);
            rootSet = true;
        }

        return new FormatOptions(root, checkOnly);
    }

    private static RunOptions parseRunOptions(String[] args) {
        Path sourceRoot = Path.of("src/main/jpp");
        Path javaSourceRoot = Path.of("src/main/java");
        Path generatedRoot = Path.of("build/generated/sources/javapp");
        Path classesRoot = Path.of("build/classes");
        NullMode nullMode = NullMode.WARN;
        EffectMode effectMode = EffectMode.WARN;
        String mainClass = null;
        List<String> programArgs = new ArrayList<>();
        boolean afterSeparator = false;

        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (afterSeparator) {
                programArgs.add(token);
                continue;
            }
            if ("--".equals(token)) {
                afterSeparator = true;
                continue;
            }
            if (token.startsWith("--source=")) {
                sourceRoot = Path.of(token.substring("--source=".length()));
                continue;
            }
            if ("--source".equals(token)) {
                if (i + 1 >= args.length) {
                    System.err.println("Usage: javapp run <main-class> [build-options] [-- program-args...]");
                    return null;
                }
                sourceRoot = Path.of(args[++i]);
                continue;
            }
            if (token.startsWith("--java-source=")) {
                javaSourceRoot = Path.of(token.substring("--java-source=".length()));
                continue;
            }
            if ("--java-source".equals(token)) {
                if (i + 1 >= args.length) {
                    System.err.println("Usage: javapp run <main-class> [build-options] [-- program-args...]");
                    return null;
                }
                javaSourceRoot = Path.of(args[++i]);
                continue;
            }
            if (token.startsWith("--generated=")) {
                generatedRoot = Path.of(token.substring("--generated=".length()));
                continue;
            }
            if ("--generated".equals(token)) {
                if (i + 1 >= args.length) {
                    System.err.println("Usage: javapp run <main-class> [build-options] [-- program-args...]");
                    return null;
                }
                generatedRoot = Path.of(args[++i]);
                continue;
            }
            if (token.startsWith("--classes=")) {
                classesRoot = Path.of(token.substring("--classes=".length()));
                continue;
            }
            if ("--classes".equals(token)) {
                if (i + 1 >= args.length) {
                    System.err.println("Usage: javapp run <main-class> [build-options] [-- program-args...]");
                    return null;
                }
                classesRoot = Path.of(args[++i]);
                continue;
            }
            if (token.startsWith("--null=")) {
                nullMode = NullMode.parse(token.substring("--null=".length()));
                continue;
            }
            if ("--null".equals(token)) {
                if (i + 1 >= args.length) {
                    System.err.println("Usage: javapp run <main-class> [build-options] [-- program-args...]");
                    return null;
                }
                nullMode = NullMode.parse(args[++i]);
                continue;
            }
            if (token.startsWith("--effects=")) {
                effectMode = EffectMode.parse(token.substring("--effects=".length()));
                continue;
            }
            if ("--effects".equals(token)) {
                if (i + 1 >= args.length) {
                    System.err.println("Usage: javapp run <main-class> [build-options] [-- program-args...]");
                    return null;
                }
                effectMode = EffectMode.parse(args[++i]);
                continue;
            }
            if (token.startsWith("--")) {
                System.err.println("Unknown run option: " + token + ". Use '--' before application arguments.");
                return null;
            }
            if (mainClass != null) {
                System.err.println("Unexpected run argument: " + token + ". Use '--' before application arguments.");
                return null;
            }
            mainClass = token;
        }

        if (mainClass == null || mainClass.isBlank()) {
            System.err.println("Usage: javapp run <main-class> [build-options] [-- program-args...]");
            return null;
        }

        return new RunOptions(
                sourceRoot,
                javaSourceRoot,
                generatedRoot,
                classesRoot,
                nullMode,
                effectMode,
                mainClass,
                List.copyOf(programArgs)
        );
    }

    private static int launchMain(Path classesRoot, String mainClass, List<String> programArgs)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(classesRoot.toString());
        command.add(mainClass);
        command.addAll(programArgs);

        Process process = new ProcessBuilder(command).start();
        Thread stdout = pipe(process.getInputStream(), System.out);
        Thread stderr = pipe(process.getErrorStream(), System.err);
        int exitCode = process.waitFor();
        stdout.join();
        stderr.join();
        return exitCode;
    }

    private static Thread pipe(InputStream input, OutputStream output) {
        Thread thread = Thread.ofVirtual().start(() -> {
            try (input) {
                input.transferTo(output);
                output.flush();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
        return thread;
    }

    private static String javaExecutable() {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        return executable.toString();
    }

    private static String toJson(List<Diagnostic> diagnostics) {
        StringBuilder out = new StringBuilder();
        out.append("{\"diagnostics\":[");
        for (int i = 0; i < diagnostics.size(); i++) {
            Diagnostic diagnostic = diagnostics.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append('{')
                    .append("\"severity\":\"").append(jsonEscape(diagnostic.severity().name().toLowerCase())).append("\",")
                    .append("\"code\":\"").append(jsonEscape(diagnostic.code())).append("\",")
                    .append("\"file\":\"").append(jsonEscape(diagnostic.file() == null ? "" : diagnostic.file().toString())).append("\",")
                    .append("\"line\":").append(diagnostic.line()).append(',')
                    .append("\"message\":\"").append(jsonEscape(diagnostic.message())).append("\"")
                    .append('}');
        }
        out.append("]}");
        return out.toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void printTranspileResults(List<TranspileResult> results) {
        for (TranspileResult result : results) {
            for (Diagnostic diagnostic : result.diagnostics()) {
                System.err.println(diagnostic.format());
            }
        }
    }

    private static boolean hasErrors(List<TranspileResult> results) {
        for (TranspileResult result : results) {
            for (Diagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.severity() == Diagnostic.Severity.ERROR) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasDiagnosticErrors(List<Diagnostic> diagnostics) {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == Diagnostic.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    private static Path pathOption(Map<String, String> options, String name, String defaultValue) {
        return Path.of(options.getOrDefault(name, defaultValue));
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> list = new ArrayList<>(Arrays.asList(args));
        for (int i = 0; i < list.size(); i++) {
            String token = list.get(i);
            if (!token.startsWith("--")) {
                continue;
            }
            int equals = token.indexOf('=');
            if (equals > 0) {
                options.put(token.substring(2, equals), token.substring(equals + 1));
                continue;
            }
            String key = token.substring(2);
            if (i + 1 < list.size() && !list.get(i + 1).startsWith("--")) {
                options.put(key, list.get(++i));
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    private static void printHelp() {
        System.out.println("""
                javapp - Java++ alpha CLI

                Commands:
                  javapp transpile [--source DIR] [--generated DIR] [--null off|warn|strict] [--effects off|warn|strict]
                  javapp build     [--source DIR] [--java-source DIR] [--generated DIR] [--classes DIR] [--effects off|warn|strict]
                  javapp run       <main-class> [build-options] [-- program-args...]
                  javapp check     [--source DIR] [--null off|warn|strict] [--effects off|warn|strict] [--format text|json]
                  javapp migrate   <java-source-root> [--format text|json]
                  javapp fmt       [source-root] [--source DIR] [--check]
                  javapp test

                Defaults:
                  --source      src/main/jpp
                  --java-source src/main/java
                  --generated   build/generated/sources/javapp
                  --classes     build/classes
                """);
    }

    private record FormatOptions(Path root, boolean checkOnly) {
    }

    private record RunOptions(
            Path sourceRoot,
            Path javaSourceRoot,
            Path generatedRoot,
            Path classesRoot,
            NullMode nullMode,
            EffectMode effectMode,
            String mainClass,
            List<String> programArgs
    ) {
    }
}
