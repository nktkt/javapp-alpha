package dev.javapp.compiler;

import java.util.List;

final class MatchLowerer {
    private MatchLowerer() {
    }

    static String lower(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int cursor = 0;

        while (true) {
            int match = SourceScanner.findKeyword(source, "match", cursor);
            if (match < 0) {
                out.append(source, cursor, source.length());
                return out.toString();
            }

            int parenStart = SourceScanner.skipWhitespace(source, match + "match".length());
            if (parenStart >= source.length() || source.charAt(parenStart) != '(') {
                out.append(source, cursor, match + "match".length());
                cursor = match + "match".length();
                continue;
            }

            int parenEnd = SourceScanner.findMatching(source, parenStart, '(', ')');
            if (parenEnd < 0) {
                out.append(source, cursor, source.length());
                return out.toString();
            }

            int braceStart = SourceScanner.skipWhitespace(source, parenEnd + 1);
            if (braceStart >= source.length() || source.charAt(braceStart) != '{') {
                out.append(source, cursor, parenEnd + 1);
                cursor = parenEnd + 1;
                continue;
            }

            int braceEnd = SourceScanner.findMatching(source, braceStart, '{', '}');
            if (braceEnd < 0) {
                out.append(source, cursor, source.length());
                return out.toString();
            }

            String selector = source.substring(parenStart + 1, parenEnd).strip();
            String body = source.substring(braceStart + 1, braceEnd);
            String indent = lineIndent(source, match);
            out.append(source, cursor, match)
                    .append("switch (")
                    .append(selector)
                    .append(") {\n")
                    .append(lowerCases(body, selector, indent + "    "))
                    .append(indent)
                    .append("}");
            cursor = braceEnd + 1;
        }
    }

    private static String lowerCases(String body, String selector, String indent) {
        StringBuilder out = new StringBuilder();
        List<String> cases = SourceScanner.splitTopLevel(body, ';');

        for (String rawCase : cases) {
            String part = rawCase.strip();
            if (part.isEmpty()) {
                continue;
            }

            int arrow = SourceScanner.findTopLevelArrow(part);
            if (arrow < 0) {
                out.append(indent).append("// javapp could not lower match case: ").append(part).append('\n');
                continue;
            }

            String pattern = part.substring(0, arrow).strip();
            String expression = part.substring(arrow + 2).strip();
            out.append(indent).append(lowerPattern(pattern, selector, expression)).append('\n');
        }

        return out.toString();
    }

    private static String lowerPattern(String pattern, String selector, String expression) {
        if (pattern.equals("_") || pattern.equals("else") || pattern.equals("default")) {
            return "default -> " + expression + ";";
        }

        if (pattern.equals("null")) {
            return "case null -> " + expression + ";";
        }

        if (pattern.startsWith("var ")) {
            String name = pattern.substring("var ".length()).strip();
            return "default -> { var " + name + " = " + selector + "; yield " + expression + "; }";
        }

        if (isBareVariant(pattern)) {
            return "case " + pattern + "() -> " + expression + ";";
        }

        return "case " + pattern + " -> " + expression + ";";
    }

    private static boolean isBareVariant(String pattern) {
        if (pattern.isEmpty() || pattern.indexOf('(') >= 0 || pattern.indexOf(' ') >= 0) {
            return false;
        }
        return Character.isUpperCase(pattern.charAt(0));
    }

    private static String lineIndent(String source, int index) {
        int lineStart = index;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }

        int cursor = lineStart;
        while (cursor < source.length()) {
            char c = source.charAt(cursor);
            if (c != ' ' && c != '\t') {
                break;
            }
            cursor++;
        }
        return source.substring(lineStart, cursor);
    }
}
