package dev.javapp.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NullSafetyAnalyzer {
    private static final Pattern NULLABLE_DECLARATION = Pattern.compile(
            "\\b[A-Za-z_$][A-Za-z0-9_$.]*(?:\\s*<[^;(){}=]*>)?(?:\\s*\\[\\s*])?\\s*\\?\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b"
    );
    private static final Pattern METHOD_HEADER = Pattern.compile(
            "(?:[A-Za-z_$][A-Za-z0-9_$.<>?!,\\[\\]\\s]*\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;{}]*\\)\\s*(?:effect\\s+[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*,\\s*[A-Za-z_$][A-Za-z0-9_$]*)*)?(?:\\s+throws\\s+[^{};]+)?\\s*\\{"
    );

    List<Diagnostic> analyze(Path file, String source, NullMode mode) {
        if (mode == NullMode.OFF) {
            return List.of();
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(detectNullableDereferences(file, source, mode));
        diagnostics.addAll(detectPlatformNullness(file, source));
        return diagnostics;
    }

    private static List<Diagnostic> detectNullableDereferences(Path file, String source, NullMode mode) {
        Diagnostic.Severity severity = mode == NullMode.STRICT
                ? Diagnostic.Severity.ERROR
                : Diagnostic.Severity.WARN;
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (MethodBlock method : methodBlocks(source)) {
            List<NullableVariable> variables = nullableVariables(method);
            if (variables.isEmpty()) {
                continue;
            }

            String[] bodyLines = method.body().split("\n", -1);
            for (int relativeLine = 0; relativeLine < bodyLines.length; relativeLine++) {
                int absoluteLine = method.bodyStartLine() + relativeLine;
                String code = stripLineComment(bodyLines[relativeLine]);
                for (NullableVariable variable : variables) {
                    if (absoluteLine < variable.declaredLine()) {
                        continue;
                    }
                    if (hasSameLineNullGuard(code, variable.name())
                            || isGuardedByPreviousCheck(method, variable.name(), absoluteLine)) {
                        continue;
                    }
                    if (hasDirectDereference(code, variable.name())) {
                        diagnostics.add(new Diagnostic(
                                severity,
                                file,
                                absoluteLine,
                                "JPP_NULL_001",
                                "nullable value '" + variable.name() + "' is dereferenced without a local null guard"
                        ));
                    }
                }
            }
        }

        return diagnostics;
    }

    private static List<Diagnostic> detectPlatformNullness(Path file, String source) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        SourceScanner.State state = SourceScanner.State.CODE;
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
                        state = SourceScanner.State.STRING;
                    } else if (c == '\'') {
                        state = SourceScanner.State.CHAR;
                    } else if (c == '!' && JavaMarkerSupport.isTypeMarker(source, i)) {
                        diagnostics.add(new Diagnostic(
                                Diagnostic.Severity.WARN,
                                file,
                                SourceScanner.lineNumber(source, i),
                                "JPP_NULL_002",
                                "platform nullness marker T! requires a boundary check before strict use"
                        ));
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
        return diagnostics;
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

            String name = matcher.group(1);
            if (!isControlKeyword(name)) {
                methods.add(new MethodBlock(
                        source.substring(matcher.start(), openBrace),
                        openBrace + 1,
                        SourceScanner.lineNumber(source, openBrace + 1),
                        source.substring(openBrace + 1, closeBrace)
                ));
            }
            cursor = closeBrace + 1;
        }
        return List.copyOf(methods);
    }

    private static List<NullableVariable> nullableVariables(MethodBlock method) {
        List<NullableVariable> variables = new ArrayList<>();

        Matcher headerMatcher = NULLABLE_DECLARATION.matcher(stripLineComment(method.header()));
        while (headerMatcher.find()) {
            variables.add(new NullableVariable(headerMatcher.group(1), method.bodyStartLine()));
        }

        String[] bodyLines = method.body().split("\n", -1);
        for (int i = 0; i < bodyLines.length; i++) {
            Matcher matcher = NULLABLE_DECLARATION.matcher(stripLineComment(bodyLines[i]));
            while (matcher.find()) {
                variables.add(new NullableVariable(matcher.group(1), method.bodyStartLine() + i));
            }
        }
        return variables;
    }

    private static boolean isGuardedByPreviousCheck(MethodBlock method, String name, int absoluteLine) {
        return isGuardedByNotNullBlock(method, name, absoluteLine)
                || isGuardedByNullExit(method, name, absoluteLine)
                || isGuardedByRequireNonNull(method, name, absoluteLine);
    }

    private static boolean isGuardedByNotNullBlock(MethodBlock method, String name, int absoluteLine) {
        Pattern pattern = Pattern.compile("if\\s*\\(\\s*" + Pattern.quote(name) + "\\s*!=\\s*null\\s*\\)");
        Matcher matcher = pattern.matcher(method.body());
        while (matcher.find()) {
            int brace = method.body().indexOf('{', matcher.end());
            if (brace < 0) {
                continue;
            }
            int close = SourceScanner.findMatching(method.body(), brace, '{', '}');
            if (close < 0) {
                continue;
            }
            int startLine = method.bodyStartLine() + SourceScanner.lineNumber(method.body(), brace) - 1;
            int endLine = method.bodyStartLine() + SourceScanner.lineNumber(method.body(), close) - 1;
            if (absoluteLine >= startLine && absoluteLine <= endLine) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGuardedByNullExit(MethodBlock method, String name, int absoluteLine) {
        String[] bodyLines = method.body().split("\n", -1);
        Pattern nullCheck = Pattern.compile("if\\s*\\(\\s*" + Pattern.quote(name) + "\\s*==\\s*null\\s*\\)");

        for (int i = 0; i < bodyLines.length; i++) {
            int checkLine = method.bodyStartLine() + i;
            if (checkLine >= absoluteLine) {
                break;
            }
            String code = stripLineComment(bodyLines[i]);
            Matcher matcher = nullCheck.matcher(code);
            if (!matcher.find()) {
                continue;
            }

            if (containsExit(code.substring(matcher.end()))) {
                return true;
            }

            int next = nextCodeLine(bodyLines, i + 1);
            if (next >= 0 && containsExit(stripLineComment(bodyLines[next]))) {
                return true;
            }

            int brace = firstBraceOnLine(bodyLines, i, method.body());
            if (brace >= 0) {
                int close = SourceScanner.findMatching(method.body(), brace, '{', '}');
                if (close >= 0) {
                    String guardedBody = method.body().substring(brace + 1, close);
                    int closeLine = method.bodyStartLine() + SourceScanner.lineNumber(method.body(), close) - 1;
                    if (closeLine < absoluteLine && containsExit(guardedBody)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean isGuardedByRequireNonNull(MethodBlock method, String name, int absoluteLine) {
        String[] bodyLines = method.body().split("\n", -1);
        Pattern requireNonNull = Pattern.compile("requireNonNull\\s*\\(\\s*" + Pattern.quote(name) + "\\s*\\)");
        for (int i = 0; i < bodyLines.length; i++) {
            int line = method.bodyStartLine() + i;
            if (line >= absoluteLine) {
                break;
            }
            if (requireNonNull.matcher(stripLineComment(bodyLines[i])).find()) {
                return true;
            }
        }
        return false;
    }

    private static int nextCodeLine(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (!stripLineComment(lines[i]).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static int firstBraceOnLine(String[] lines, int lineIndex, String body) {
        int lineOffset = lineStartOffset(lines, lineIndex);
        int lineEnd = lineOffset + lines[lineIndex].length();
        int brace = body.indexOf('{', lineOffset);
        if (brace >= 0 && brace <= lineEnd) {
            return brace;
        }
        int next = nextCodeLine(lines, lineIndex + 1);
        if (next >= 0) {
            String nextLine = stripLineComment(lines[next]).strip();
            if (nextLine.startsWith("{")) {
                return lineStartOffset(lines, next) + lines[next].indexOf('{');
            }
        }
        return -1;
    }

    private static int lineStartOffset(String[] lines, int lineIndex) {
        int offset = 0;
        for (int i = 0; i < lineIndex; i++) {
            offset += lines[i].length() + 1;
        }
        return offset;
    }

    private static boolean containsExit(String code) {
        return Pattern.compile("\\b(return|throw)\\b").matcher(code).find();
    }

    private static boolean hasDirectDereference(String line, String name) {
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "\\s*\\.");
        return pattern.matcher(line).find();
    }

    private static boolean hasSameLineNullGuard(String line, String name) {
        String quoted = Pattern.quote(name);
        return Pattern.compile("\\b" + quoted + "\\s*!=\\s*null\\b").matcher(line).find()
                || Pattern.compile("\\bnull\\s*!=\\s*" + quoted + "\\b").matcher(line).find();
    }

    private static boolean isControlKeyword(String name) {
        return name.equals("if")
                || name.equals("for")
                || name.equals("while")
                || name.equals("switch")
                || name.equals("catch");
    }

    private static String stripLineComment(String line) {
        int comment = line.indexOf("//");
        if (comment < 0) {
            return line;
        }
        return line.substring(0, comment);
    }

    private record MethodBlock(String header, int bodyStart, int bodyStartLine, String body) {
    }

    private record NullableVariable(String name, int declaredLine) {
    }
}
