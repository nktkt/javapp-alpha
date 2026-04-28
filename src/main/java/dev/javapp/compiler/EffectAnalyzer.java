package dev.javapp.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EffectAnalyzer {
    private static final Pattern METHOD_HEADER = Pattern.compile(
            "(?:(pure)\\s+)?(?:[A-Za-z_$][A-Za-z0-9_$.<>?!,\\[\\]\\s]*\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;{}]*\\)\\s*(?:effect\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\s*,\\s*[A-Za-z_$][A-Za-z0-9_$]*)*))?(?:\\s+throws\\s+[^{};]+)?\\s*\\{");

    private static final List<KnownEffectCall> KNOWN_EFFECT_CALLS = List.of(
            new KnownEffectCall("Files.readString(", "IO"),
            new KnownEffectCall("Files.writeString(", "IO"),
            new KnownEffectCall("Files.newBufferedReader(", "IO"),
            new KnownEffectCall("Files.newBufferedWriter(", "IO"),
            new KnownEffectCall("Files.readAllBytes(", "IO"),
            new KnownEffectCall("Files.write(", "IO"),
            new KnownEffectCall("Thread.sleep(", "Blocking"),
            new KnownEffectCall("System.out.", "IO"),
            new KnownEffectCall("System.err.", "IO"),
            new KnownEffectCall("System.in.", "IO"),
            new KnownEffectCall("Runtime.getRuntime(", "Native"),
            new KnownEffectCall("System.load(", "Native"),
            new KnownEffectCall("System.loadLibrary(", "Native")
    );

    private EffectAnalyzer() {
    }

    static Map<String, Set<String>> collectEffectfulMethods(Iterable<String> sources) {
        Map<String, Set<String>> methods = new LinkedHashMap<>();
        for (String source : sources) {
            for (MethodBlock method : methodBlocks(source)) {
                if (!method.effects().isEmpty()) {
                    methods.put(method.name(), method.effects());
                }
            }
        }
        return methods;
    }

    static List<Diagnostic> analyze(
            Path file,
            String source,
            Map<String, Set<String>> effectfulMethods,
            EffectMode mode
    ) {
        if (mode == EffectMode.OFF) {
            return List.of();
        }

        Diagnostic.Severity severity = mode == EffectMode.STRICT
                ? Diagnostic.Severity.ERROR
                : Diagnostic.Severity.WARN;
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (MethodBlock method : methodBlocks(source)) {
            if (!method.pure()) {
                continue;
            }

            for (KnownEffectCall knownCall : KNOWN_EFFECT_CALLS) {
                int index = method.body().indexOf(knownCall.token());
                if (index >= 0) {
                    diagnostics.add(new Diagnostic(
                            severity,
                            file,
                            SourceScanner.lineNumber(source, method.bodyStart() + index),
                            "JPP_EFFECT_001",
                            "pure method '" + method.name() + "' calls " + knownCall.token()
                                    + " requiring effect " + knownCall.effect()
                    ));
                }
            }

            for (Map.Entry<String, Set<String>> entry : effectfulMethods.entrySet()) {
                if (entry.getKey().equals(method.name())) {
                    continue;
                }
                int index = findMethodCall(method.body(), entry.getKey());
                if (index >= 0) {
                    diagnostics.add(new Diagnostic(
                            severity,
                            file,
                            SourceScanner.lineNumber(source, method.bodyStart() + index),
                            "JPP_EFFECT_002",
                            "pure method '" + method.name() + "' calls effectful method '"
                                    + entry.getKey() + "' requiring effect "
                                    + String.join(", ", entry.getValue())
                    ));
                }
            }
        }

        return List.copyOf(diagnostics);
    }

    private static List<MethodBlock> methodBlocks(String source) {
        List<MethodBlock> methods = new ArrayList<>();
        Matcher matcher = METHOD_HEADER.matcher(source);
        int cursor = 0;
        while (matcher.find(cursor)) {
            int openBrace = matcher.end() - 1;
            int closeBrace = SourceScanner.findMatching(source, openBrace, '{', '}');
            if (closeBrace < 0) {
                return methods;
            }

            String name = matcher.group(2);
            if (isControlKeyword(name)) {
                cursor = closeBrace + 1;
                continue;
            }

            boolean pure = matcher.group(1) != null;
            Set<String> effects = parseEffects(matcher.group(3));
            methods.add(new MethodBlock(
                    name,
                    pure,
                    effects,
                    openBrace + 1,
                    source.substring(openBrace + 1, closeBrace)
            ));
            cursor = closeBrace + 1;
        }
        return List.copyOf(methods);
    }

    private static Set<String> parseEffects(String rawEffects) {
        if (rawEffects == null || rawEffects.isBlank()) {
            return Set.of();
        }
        Set<String> effects = new LinkedHashSet<>();
        for (String effect : rawEffects.split(",")) {
            String trimmed = effect.strip();
            if (!trimmed.isEmpty()) {
                effects.add(trimmed);
            }
        }
        return Set.copyOf(effects);
    }

    private static int findMethodCall(String source, String methodName) {
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(methodName) + "\\s*\\(");
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.start() : -1;
    }

    private static boolean isControlKeyword(String name) {
        return name.equals("if")
                || name.equals("for")
                || name.equals("while")
                || name.equals("switch")
                || name.equals("catch");
    }

    private record MethodBlock(String name, boolean pure, Set<String> effects, int bodyStart, String body) {
    }

    private record KnownEffectCall(String token, String effect) {
    }
}
