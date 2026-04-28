package dev.javapp.compiler;

import java.util.ArrayList;
import java.util.List;

final class SourceScanner {
    private SourceScanner() {
    }

    static int findMatching(String source, int openIndex, char open, char close) {
        int depth = 0;
        State state = State.CODE;
        for (int i = openIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            switch (state) {
                case CODE -> {
                    if (c == '/' && n == '/') {
                        state = State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && n == '*') {
                        state = State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        if (i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                            state = State.TEXT_BLOCK;
                            i += 2;
                        } else {
                            state = State.STRING;
                        }
                    } else if (c == '\'') {
                        state = State.CHAR;
                    } else if (c == open) {
                        depth++;
                    } else if (c == close) {
                        depth--;
                        if (depth == 0) {
                            return i;
                        }
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        state = State.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && n == '/') {
                        state = State.CODE;
                        i++;
                    }
                }
                case STRING -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = State.CODE;
                    }
                }
                case CHAR -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '\'') {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '"' && i + 2 < source.length()
                            && source.charAt(i + 1) == '"'
                            && source.charAt(i + 2) == '"') {
                        state = State.CODE;
                        i += 2;
                    }
                }
            }
        }
        return -1;
    }

    static int findKeyword(String source, String keyword, int from) {
        State state = State.CODE;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            switch (state) {
                case CODE -> {
                    if (c == '/' && n == '/') {
                        state = State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && n == '*') {
                        state = State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        if (i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                            state = State.TEXT_BLOCK;
                            i += 2;
                        } else {
                            state = State.STRING;
                        }
                    } else if (c == '\'') {
                        state = State.CHAR;
                    } else if (startsWithKeyword(source, keyword, i)) {
                        return i;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        state = State.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && n == '/') {
                        state = State.CODE;
                        i++;
                    }
                }
                case STRING -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = State.CODE;
                    }
                }
                case CHAR -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '\'') {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '"' && i + 2 < source.length()
                            && source.charAt(i + 1) == '"'
                            && source.charAt(i + 2) == '"') {
                        state = State.CODE;
                        i += 2;
                    }
                }
            }
        }
        return -1;
    }

    static List<String> splitTopLevel(String source, char delimiter) {
        List<String> parts = new ArrayList<>();
        State state = State.CODE;
        int start = 0;
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        int angle = 0;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            switch (state) {
                case CODE -> {
                    if (c == '/' && n == '/') {
                        state = State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && n == '*') {
                        state = State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        if (i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                            state = State.TEXT_BLOCK;
                            i += 2;
                        } else {
                            state = State.STRING;
                        }
                    } else if (c == '\'') {
                        state = State.CHAR;
                    } else if (c == '(') {
                        paren++;
                    } else if (c == ')') {
                        paren = Math.max(0, paren - 1);
                    } else if (c == '{') {
                        brace++;
                    } else if (c == '}') {
                        brace = Math.max(0, brace - 1);
                    } else if (c == '[') {
                        bracket++;
                    } else if (c == ']') {
                        bracket = Math.max(0, bracket - 1);
                    } else if (c == '<') {
                        angle++;
                    } else if (c == '>') {
                        angle = Math.max(0, angle - 1);
                    } else if (c == delimiter && paren == 0 && brace == 0 && bracket == 0 && angle == 0) {
                        parts.add(source.substring(start, i));
                        start = i + 1;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        state = State.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && n == '/') {
                        state = State.CODE;
                        i++;
                    }
                }
                case STRING -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = State.CODE;
                    }
                }
                case CHAR -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '\'') {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '"' && i + 2 < source.length()
                            && source.charAt(i + 1) == '"'
                            && source.charAt(i + 2) == '"') {
                        state = State.CODE;
                        i += 2;
                    }
                }
            }
        }

        parts.add(source.substring(start));
        return parts;
    }

    static int findTopLevelArrow(String source) {
        State state = State.CODE;
        int paren = 0;
        int brace = 0;
        int bracket = 0;

        for (int i = 0; i + 1 < source.length(); i++) {
            char c = source.charAt(i);
            char n = source.charAt(i + 1);

            switch (state) {
                case CODE -> {
                    if (c == '/' && n == '/') {
                        state = State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && n == '*') {
                        state = State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        state = State.STRING;
                    } else if (c == '\'') {
                        state = State.CHAR;
                    } else if (c == '(') {
                        paren++;
                    } else if (c == ')') {
                        paren = Math.max(0, paren - 1);
                    } else if (c == '{') {
                        brace++;
                    } else if (c == '}') {
                        brace = Math.max(0, brace - 1);
                    } else if (c == '[') {
                        bracket++;
                    } else if (c == ']') {
                        bracket = Math.max(0, bracket - 1);
                    } else if (c == '-' && n == '>' && paren == 0 && brace == 0 && bracket == 0) {
                        return i;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        state = State.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && n == '/') {
                        state = State.CODE;
                        i++;
                    }
                }
                case STRING -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = State.CODE;
                    }
                }
                case CHAR -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '\'') {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '"' && i + 2 < source.length()
                            && source.charAt(i + 1) == '"'
                            && source.charAt(i + 2) == '"') {
                        state = State.CODE;
                        i += 2;
                    }
                }
            }
        }
        return -1;
    }

    static int skipWhitespace(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    static int lineNumber(String source, int index) {
        int line = 1;
        int end = Math.min(index, source.length());
        for (int i = 0; i < end; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
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

    enum State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHAR,
        TEXT_BLOCK
    }
}

