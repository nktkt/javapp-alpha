package dev.javapp.compiler;

import java.util.ArrayList;
import java.util.List;

final class NullCoalescingLowerer {
    private NullCoalescingLowerer() {
    }

    static String lower(String source) {
        String current = source;
        int searchFrom = 0;
        while (true) {
            int operator = findOperator(current, searchFrom);
            if (operator < 0) {
                return current;
            }

            Operand left = findLeftOperand(current, operator);
            Operand right = findRightOperand(current, operator + 2);
            if (left == null || right == null || left.start() >= left.end() || right.start() >= right.end()) {
                searchFrom = operator + 2;
                continue;
            }

            String leftExpression = current.substring(left.start(), left.end()).strip();
            String rightExpression = lower(current.substring(right.start(), right.end()).strip());
            String replacement = replacement(leftExpression, rightExpression);
            current = current.substring(0, left.start()) + replacement + current.substring(right.end());
            searchFrom = left.start() + replacement.length();
        }
    }

    private static String replacement(String left, String right) {
        if (isStableExpression(left)) {
            return "(" + left + " != null ? " + left + " : " + right + ")";
        }
        return "java.util.Optional.ofNullable(" + left + ").orElse(" + right + ")";
    }

    private static int findOperator(String source, int from) {
        SourceScanner.State state = SourceScanner.State.CODE;
        for (int i = Math.max(0, from); i + 1 < source.length(); i++) {
            char c = source.charAt(i);
            char n = source.charAt(i + 1);

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
                    } else if (c == '?' && n == '?') {
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

    private static Operand findLeftOperand(String source, int operator) {
        int end = skipWhitespaceLeft(source, operator);
        if (end <= 0) {
            return null;
        }
        int start = expressionStart(source, end);
        return start < end ? new Operand(start, end) : null;
    }

    private static Operand findRightOperand(String source, int from) {
        int start = SourceScanner.skipWhitespace(source, from);
        if (start >= source.length()) {
            return null;
        }

        SourceScanner.State state = SourceScanner.State.CODE;
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        for (int i = start; i < source.length(); i++) {
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
                        if (paren == 0) {
                            return new Operand(start, trimRight(source, start, i));
                        }
                        paren--;
                    } else if (c == '{') {
                        brace++;
                    } else if (c == '}') {
                        if (brace == 0 && paren == 0 && bracket == 0) {
                            return new Operand(start, trimRight(source, start, i));
                        }
                        brace = Math.max(0, brace - 1);
                    } else if (c == '[') {
                        bracket++;
                    } else if (c == ']') {
                        bracket = Math.max(0, bracket - 1);
                    } else if ((c == ';' || c == ',' || c == ':') && paren == 0 && brace == 0 && bracket == 0) {
                        return new Operand(start, trimRight(source, start, i));
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
        return new Operand(start, trimRight(source, start, source.length()));
    }

    private static int expressionStart(String source, int endExclusive) {
        int i = skipWhitespaceLeft(source, endExclusive) - 1;
        int start = i + 1;
        while (i >= 0) {
            char c = source.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                int tokenStart = i;
                while (tokenStart > 0 && Character.isJavaIdentifierPart(source.charAt(tokenStart - 1))) {
                    tokenStart--;
                }
                start = tokenStart;
                i = skipWhitespaceLeft(source, tokenStart) - 1;
                if (i >= 0 && source.charAt(i) == '.') {
                    start = i;
                    i = skipWhitespaceLeft(source, i) - 1;
                    continue;
                }
                return start;
            }

            if (c == ')' || c == ']') {
                char open = c == ')' ? '(' : '[';
                int match = findMatchingOpen(source, i, open, c);
                if (match < 0) {
                    return start;
                }
                start = match;
                i = skipWhitespaceLeft(source, match) - 1;
                if (i >= 0 && source.charAt(i) == '.') {
                    start = i;
                    i = skipWhitespaceLeft(source, i) - 1;
                    continue;
                }
                continue;
            }

            if (c == '"') {
                int stringStart = findStringStart(source, i);
                return stringStart < 0 ? start : stringStart;
            }

            if (c == '.') {
                start = i;
                i = skipWhitespaceLeft(source, i) - 1;
                continue;
            }

            return start;
        }
        return start;
    }

    private static boolean isStableExpression(String expression) {
        String trimmed = expression.strip();
        if (trimmed.isEmpty() || trimmed.indexOf('(') >= 0 || trimmed.indexOf(')') >= 0) {
            return false;
        }
        if (trimmed.startsWith("new ") || trimmed.contains("++") || trimmed.contains("--")) {
            return false;
        }
        return trimmed.matches("(?:this|super|[A-Za-z_$][A-Za-z0-9_$]*)(?:\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*|\\s*\\[[^\\]]+])*");
    }

    private static int findMatchingOpen(String source, int closeIndex, char open, char close) {
        List<Integer> stack = new ArrayList<>();
        SourceScanner.State state = SourceScanner.State.CODE;
        for (int i = 0; i <= closeIndex && i < source.length(); i++) {
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
                    } else if (c == open) {
                        stack.add(i);
                    } else if (c == close) {
                        if (stack.isEmpty()) {
                            return -1;
                        }
                        int match = stack.remove(stack.size() - 1);
                        if (i == closeIndex) {
                            return match;
                        }
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

    private static int findStringStart(String source, int quoteIndex) {
        for (int i = quoteIndex - 1; i >= 0; i--) {
            if (source.charAt(i) != '"') {
                continue;
            }
            int slashes = 0;
            for (int j = i - 1; j >= 0 && source.charAt(j) == '\\'; j--) {
                slashes++;
            }
            if (slashes % 2 == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespaceLeft(String source, int fromExclusive) {
        int i = fromExclusive;
        while (i > 0 && Character.isWhitespace(source.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static int trimRight(String source, int start, int endExclusive) {
        int end = endExclusive;
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private record Operand(int start, int end) {
    }
}
