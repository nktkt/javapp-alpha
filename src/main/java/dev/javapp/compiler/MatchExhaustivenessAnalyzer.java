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

final class MatchExhaustivenessAnalyzer {
    private static final Pattern DATA_ENUM_HEADER = Pattern.compile(
            "\\bdata\\s+enum\\s+([A-Za-z_$][A-Za-z0-9_$]*)(?:\\s*<[^>{}]*>)?\\s*\\{");

    private MatchExhaustivenessAnalyzer() {
    }

    static Map<String, Set<String>> collectDataEnums(Iterable<String> sources) {
        Map<String, Set<String>> dataEnums = new LinkedHashMap<>();
        for (String source : sources) {
            collectDataEnums(source, dataEnums);
        }
        return dataEnums;
    }

    static List<Diagnostic> analyze(Path file, String source, Map<String, Set<String>> dataEnums) {
        if (dataEnums.isEmpty()) {
            return List.of();
        }

        Map<String, String> variableTypes = collectVariableTypes(source, dataEnums.keySet());
        if (variableTypes.isEmpty()) {
            return List.of();
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int match = SourceScanner.findKeyword(source, "match", cursor);
            if (match < 0) {
                return List.copyOf(diagnostics);
            }

            int parenStart = SourceScanner.skipWhitespace(source, match + "match".length());
            if (parenStart >= source.length() || source.charAt(parenStart) != '(') {
                cursor = match + "match".length();
                continue;
            }

            int parenEnd = SourceScanner.findMatching(source, parenStart, '(', ')');
            if (parenEnd < 0) {
                return List.copyOf(diagnostics);
            }

            int braceStart = SourceScanner.skipWhitespace(source, parenEnd + 1);
            if (braceStart >= source.length() || source.charAt(braceStart) != '{') {
                cursor = parenEnd + 1;
                continue;
            }

            int braceEnd = SourceScanner.findMatching(source, braceStart, '{', '}');
            if (braceEnd < 0) {
                return List.copyOf(diagnostics);
            }

            String selector = source.substring(parenStart + 1, parenEnd).strip();
            String type = variableTypes.get(selector);
            if (type != null) {
                Set<String> variants = dataEnums.get(type);
                Exhaustiveness exhaustiveness = checkExhaustiveness(source.substring(braceStart + 1, braceEnd), variants);
                if (!exhaustiveness.exhaustive()) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.WARN,
                            file,
                            SourceScanner.lineNumber(source, match),
                            "JPP_MATCH_001",
                            "non-exhaustive match on " + type + "; missing variants: "
                                    + String.join(", ", exhaustiveness.missing())
                    ));
                }
            }

            cursor = braceEnd + 1;
        }
    }

    private static void collectDataEnums(String source, Map<String, Set<String>> dataEnums) {
        Matcher matcher = DATA_ENUM_HEADER.matcher(source);
        int cursor = 0;
        while (matcher.find(cursor)) {
            int openBrace = matcher.end() - 1;
            int closeBrace = SourceScanner.findMatching(source, openBrace, '{', '}');
            if (closeBrace < 0) {
                return;
            }
            dataEnums.put(matcher.group(1), parseVariants(source.substring(openBrace + 1, closeBrace)));
            cursor = closeBrace + 1;
        }
    }

    private static Set<String> parseVariants(String body) {
        Set<String> variants = new LinkedHashSet<>();
        for (String rawPart : SourceScanner.splitTopLevel(body, ',')) {
            String part = rawPart.strip();
            if (part.isEmpty()) {
                continue;
            }
            int paren = part.indexOf('(');
            variants.add(paren < 0 ? part : part.substring(0, paren).strip());
        }
        return variants;
    }

    private static Map<String, String> collectVariableTypes(String source, Set<String> dataEnumNames) {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        for (String dataEnumName : dataEnumNames) {
            Pattern declaration = Pattern.compile(
                    "\\b" + Pattern.quote(dataEnumName)
                            + "(?:\\s*<[^;(){}=]*>)?\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
            Matcher matcher = declaration.matcher(source);
            while (matcher.find()) {
                variableTypes.put(matcher.group(1), dataEnumName);
            }
        }
        return variableTypes;
    }

    private static Exhaustiveness checkExhaustiveness(String body, Set<String> variants) {
        Set<String> seen = new LinkedHashSet<>();
        for (String rawCase : SourceScanner.splitTopLevel(body, ';')) {
            String part = rawCase.strip();
            if (part.isEmpty()) {
                continue;
            }

            int arrow = SourceScanner.findTopLevelArrow(part);
            if (arrow < 0) {
                continue;
            }

            String pattern = part.substring(0, arrow).strip();
            MatchLowerer.GuardedPattern guarded = MatchLowerer.splitGuard(pattern);
            if (guarded.guard() != null && !guarded.guard().equals("true")) {
                continue;
            }
            pattern = guarded.pattern();
            if (isWildcard(pattern)) {
                return new Exhaustiveness(true, Set.of());
            }
            String variant = variantName(pattern);
            if (variants.contains(variant)) {
                seen.add(variant);
            }
        }

        Set<String> missing = new LinkedHashSet<>(variants);
        missing.removeAll(seen);
        return new Exhaustiveness(missing.isEmpty(), missing);
    }

    private static boolean isWildcard(String pattern) {
        return pattern.equals("_")
                || pattern.equals("else")
                || pattern.equals("default")
                || pattern.startsWith("var ");
    }

    private static String variantName(String pattern) {
        int paren = pattern.indexOf('(');
        if (paren >= 0) {
            return pattern.substring(0, paren).strip();
        }
        return pattern.strip();
    }

    private record Exhaustiveness(boolean exhaustive, Set<String> missing) {
    }
}
