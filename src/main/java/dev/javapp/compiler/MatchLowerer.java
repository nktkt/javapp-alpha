package dev.javapp.compiler;

import java.util.List;

final class MatchLowerer {
    private MatchLowerer() {
    }

    static String lower(String source) {
        return lower(source, VariantConstructorLowerer.Registry.empty());
    }

    static String lower(String source, VariantConstructorLowerer.Registry variantRegistry) {
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
                    .append(lowerCases(body, selector, indent + "    ", variantRegistry))
                    .append(indent)
                    .append("}");
            cursor = braceEnd + 1;
        }
    }

    private static String lowerCases(
            String body,
            String selector,
            String indent,
            VariantConstructorLowerer.Registry variantRegistry
    ) {
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
            out.append(indent).append(lowerPattern(pattern, selector, expression, variantRegistry)).append('\n');
        }

        return out.toString();
    }

    private static String lowerPattern(
            String pattern,
            String selector,
            String expression,
            VariantConstructorLowerer.Registry variantRegistry
    ) {
        GuardedPattern guarded = splitGuard(pattern);
        pattern = guarded.pattern();
        String guard = guarded.guard() == null ? "" : " when " + guarded.guard();

        if (pattern.equals("_") || pattern.equals("else") || pattern.equals("default")) {
            if (!guard.isBlank()) {
                return "case var __jppAny" + guard + " -> " + expression + ";";
            }
            return "default -> " + expression + ";";
        }

        if (pattern.equals("null")) {
            return "case null -> " + expression + ";";
        }

        if (pattern.startsWith("var ")) {
            String name = pattern.substring("var ".length()).strip();
            if (!guard.isBlank()) {
                return "case var " + name + guard + " -> " + expression + ";";
            }
            return "default -> { var " + name + " = " + selector + "; yield " + expression + "; }";
        }

        String noArgVariant = noArgVariantName(pattern, variantRegistry);
        if (noArgVariant != null) {
            String typePattern = noArgVariant + (variantRegistry.isGeneric(noArgVariant) ? "<?> " : " ");
            return "case " + typePattern + "__jpp" + noArgVariant + guard + " -> " + expression + ";";
        }

        if (isBareVariant(pattern)) {
            return "case " + pattern + "()" + guard + " -> " + expression + ";";
        }

        return "case " + pattern + guard + " -> " + expression + ";";
    }

    private static boolean isBareVariant(String pattern) {
        if (pattern.isEmpty() || pattern.indexOf('(') >= 0 || pattern.indexOf(' ') >= 0) {
            return false;
        }
        return Character.isUpperCase(pattern.charAt(0));
    }

    private static String noArgVariantName(String pattern, VariantConstructorLowerer.Registry variantRegistry) {
        if (pattern.isEmpty()) {
            return null;
        }
        if (isBareVariant(pattern) && variantRegistry.isNoArg(pattern)) {
            return pattern;
        }
        int paren = pattern.indexOf('(');
        if (paren <= 0 || !pattern.endsWith(")")) {
            return null;
        }
        String name = pattern.substring(0, paren).strip();
        String arguments = pattern.substring(paren + 1, pattern.length() - 1).strip();
        if (arguments.isBlank() && variantRegistry.isNoArg(name)) {
            return name;
        }
        return null;
    }

    static GuardedPattern splitGuard(String pattern) {
        int when = findTopLevelWhen(pattern);
        if (when < 0) {
            return new GuardedPattern(pattern.strip(), null);
        }
        return new GuardedPattern(
                pattern.substring(0, when).strip(),
                pattern.substring(when + "when".length()).strip()
        );
    }

    private static int findTopLevelWhen(String source) {
        SourceScanner.State state = SourceScanner.State.CODE;
        int paren = 0;
        int bracket = 0;
        int brace = 0;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            switch (state) {
                case CODE -> {
                    if (c == '/' && n == '/') {
                        state = SourceScanner.State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && n == '*') {
                        state = SourceScanner.State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        if (i + 2 < source.length()
                                && source.charAt(i + 1) == '"'
                                && source.charAt(i + 2) == '"') {
                            state = SourceScanner.State.TEXT_BLOCK;
                            i += 2;
                        } else {
                            state = SourceScanner.State.STRING;
                        }
                    } else if (c == '\'') {
                        state = SourceScanner.State.CHAR;
                    } else if (c == '(') {
                        paren++;
                    } else if (c == ')') {
                        paren = Math.max(0, paren - 1);
                    } else if (c == '[') {
                        bracket++;
                    } else if (c == ']') {
                        bracket = Math.max(0, bracket - 1);
                    } else if (c == '{') {
                        brace++;
                    } else if (c == '}') {
                        brace = Math.max(0, brace - 1);
                    } else if (paren == 0 && bracket == 0 && brace == 0 && startsWithKeyword(source, "when", i)) {
                        return i;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        state = SourceScanner.State.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && n == '/') {
                        state = SourceScanner.State.CODE;
                        i++;
                    }
                }
                case STRING -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = SourceScanner.State.CODE;
                    }
                }
                case CHAR -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '\'') {
                        state = SourceScanner.State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '"' && i + 2 < source.length()
                            && source.charAt(i + 1) == '"'
                            && source.charAt(i + 2) == '"') {
                        state = SourceScanner.State.CODE;
                        i += 2;
                    }
                }
            }
        }
        return -1;
    }

    private static boolean startsWithKeyword(String source, String keyword, int index) {
        if (!source.startsWith(keyword, index)) {
            return false;
        }
        int before = index - 1;
        int after = index + keyword.length();
        boolean cleanBefore = before < 0 || !Character.isJavaIdentifierPart(source.charAt(before));
        boolean cleanAfter = after >= source.length() || !Character.isJavaIdentifierPart(source.charAt(after));
        return cleanBefore && cleanAfter;
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

    record GuardedPattern(String pattern, String guard) {
    }
}
