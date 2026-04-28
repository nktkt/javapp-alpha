package dev.javapp.compiler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StructuredAsyncLowerer {
    private static final Pattern DURATION_LITERAL = Pattern.compile("([0-9]+)\\.([A-Za-z_][A-Za-z0-9_]*)");

    private int nextScopeId = 1;

    static String lower(String source) {
        return new StructuredAsyncLowerer().lowerScopedBlocks(source);
    }

    private String lowerScopedBlocks(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int cursor = 0;

        while (true) {
            int scoped = SourceScanner.findKeyword(source, "scoped", cursor);
            if (scoped < 0) {
                out.append(source, cursor, source.length());
                return out.toString();
            }

            int headerStart = SourceScanner.skipWhitespace(source, scoped + "scoped".length());
            int braceStart = findScopedBrace(source, headerStart);
            if (braceStart >= source.length() || source.charAt(braceStart) != '{') {
                out.append(source, cursor, scoped + "scoped".length());
                cursor = scoped + "scoped".length();
                continue;
            }

            int braceEnd = SourceScanner.findMatching(source, braceStart, '{', '}');
            if (braceEnd < 0) {
                out.append(source, cursor, source.length());
                return out.toString();
            }

            String indent = lineIndent(source, scoped);
            String scopeName = "__jppScope" + nextScopeId++;
            String opener = scopeOpener(source.substring(headerStart, braceStart));
            String body = source.substring(braceStart + 1, braceEnd);
            String loweredBody = lowerAsyncBlocks(lowerScopedBlocks(body), scopeName);

            out.append(source, cursor, scoped)
                    .append("try (var ")
                    .append(scopeName)
                    .append(" = ")
                    .append(opener)
                    .append(") {")
                    .append(indentBody(loweredBody, "    "))
                    .append("\n")
                    .append(indent)
                    .append("}");

            cursor = braceEnd + 1;
        }
    }

    private static int findScopedBrace(String source, int from) {
        SourceScanner.State state = SourceScanner.State.CODE;
        for (int i = from; i < source.length(); i++) {
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
                        state = SourceScanner.State.STRING;
                    } else if (c == '\'') {
                        state = SourceScanner.State.CHAR;
                    } else if (c == '{') {
                        return i;
                    } else if (c == ';') {
                        return source.length();
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
        return source.length();
    }

    private static String scopeOpener(String header) {
        String trimmed = header.strip();
        if (trimmed.isEmpty()) {
            return "dev.javapp.runtime.StructuredScope.open()";
        }
        if (trimmed.startsWith("timeout ")) {
            String duration = trimmed.substring("timeout ".length()).strip();
            return "dev.javapp.runtime.StructuredScope.open(" + lowerDuration(duration) + ")";
        }
        return "dev.javapp.runtime.StructuredScope.open()";
    }

    private static String lowerDuration(String expression) {
        Matcher matcher = DURATION_LITERAL.matcher(expression);
        if (!matcher.matches()) {
            return expression;
        }

        String value = matcher.group(1);
        return switch (matcher.group(2)) {
            case "ms", "milli", "millis", "millisecond", "milliseconds" ->
                    "java.time.Duration.ofMillis(" + value + ")";
            case "s", "sec", "second", "seconds" ->
                    "java.time.Duration.ofSeconds(" + value + ")";
            case "m", "min", "minute", "minutes" ->
                    "java.time.Duration.ofMinutes(" + value + ")";
            default -> expression;
        };
    }

    private String lowerAsyncBlocks(String source, String scopeName) {
        StringBuilder out = new StringBuilder(source.length());
        int cursor = 0;

        while (true) {
            int async = SourceScanner.findKeyword(source, "async", cursor);
            if (async < 0) {
                out.append(source, cursor, source.length());
                return out.toString();
            }

            int braceStart = SourceScanner.skipWhitespace(source, async + "async".length());
            if (braceStart >= source.length() || source.charAt(braceStart) != '{') {
                out.append(source, cursor, async + "async".length());
                cursor = async + "async".length();
                continue;
            }

            int braceEnd = SourceScanner.findMatching(source, braceStart, '{', '}');
            if (braceEnd < 0) {
                out.append(source, cursor, source.length());
                return out.toString();
            }

            String body = source.substring(braceStart + 1, braceEnd).strip();
            boolean voidBlock = isStatementBlock(body) && !hasReturn(body);
            out.append(source, cursor, async)
                    .append(scopeName)
                    .append(voidBlock ? ".forkVoid(() -> " : ".fork(() -> ")
                    .append(toLambdaBody(body))
                    .append(")");
            cursor = braceEnd + 1;
        }
    }

    private static boolean hasReturn(String body) {
        return SourceScanner.findKeyword(body, "return", 0) >= 0;
    }

    private static boolean isStatementBlock(String body) {
        return body.indexOf(';') >= 0 || body.indexOf('\n') >= 0;
    }

    private static String toLambdaBody(String body) {
        if (isStatementBlock(body)) {
            return "{\n" + body + "\n}";
        }
        return body;
    }

    private static String indentBody(String body, String extra) {
        if (body.isEmpty()) {
            return body;
        }

        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder(body.length() + lines.length * extra.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (lines[i].isBlank()) {
                out.append(lines[i]);
            } else {
                out.append(extra).append(lines[i]);
            }
        }
        return out.toString();
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
