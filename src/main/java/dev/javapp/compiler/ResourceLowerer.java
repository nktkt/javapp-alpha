package dev.javapp.compiler;

final class ResourceLowerer {
    private ResourceLowerer() {
    }

    static String lower(String source) {
        StringBuilder out = new StringBuilder(source.length());
        SourceScanner.State state = SourceScanner.State.CODE;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            switch (state) {
                case CODE -> {
                    if (c == '/' && n == '/') {
                        out.append(c).append(n);
                        state = SourceScanner.State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && n == '*') {
                        out.append(c).append(n);
                        state = SourceScanner.State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        out.append(c);
                        if (i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                            out.append("\"\"");
                            state = SourceScanner.State.TEXT_BLOCK;
                            i += 2;
                        } else {
                            state = SourceScanner.State.STRING;
                        }
                    } else if (c == '\'') {
                        out.append(c);
                        state = SourceScanner.State.CHAR;
                    } else if (c == '{') {
                        int close = SourceScanner.findMatching(source, i, '{', '}');
                        if (close < 0) {
                            out.append(c);
                        } else {
                            out.append('{')
                                    .append(lowerBlock(source.substring(i + 1, close)))
                                    .append('}');
                            i = close;
                        }
                    } else {
                        out.append(c);
                    }
                }
                case LINE_COMMENT -> {
                    out.append(c);
                    if (c == '\n') {
                        state = SourceScanner.State.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    out.append(c);
                    if (c == '*' && n == '/') {
                        out.append(n);
                        state = SourceScanner.State.CODE;
                        i++;
                    }
                }
                case STRING -> {
                    out.append(c);
                    if (c == '\\') {
                        if (i + 1 < source.length()) {
                            out.append(source.charAt(++i));
                        }
                    } else if (c == '"') {
                        state = SourceScanner.State.CODE;
                    }
                }
                case CHAR -> {
                    out.append(c);
                    if (c == '\\') {
                        if (i + 1 < source.length()) {
                            out.append(source.charAt(++i));
                        }
                    } else if (c == '\'') {
                        state = SourceScanner.State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    out.append(c);
                    if (c == '"' && i + 2 < source.length()
                            && source.charAt(i + 1) == '"'
                            && source.charAt(i + 2) == '"') {
                        out.append("\"\"");
                        state = SourceScanner.State.CODE;
                        i += 2;
                    }
                }
            }
        }

        return out.toString();
    }

    private static String lowerBlock(String body) {
        return lowerResourceStatements(lower(body));
    }

    private static String lowerResourceStatements(String body) {
        int use = findTopLevelKeyword(body, "use", 0);
        int defer = findTopLevelKeyword(body, "defer", 0);

        if (use < 0 && defer < 0) {
            return body;
        }
        if (use >= 0 && (defer < 0 || use < defer)) {
            return lowerUse(body, use);
        }
        return lowerDefer(body, defer);
    }

    private static String lowerUse(String body, int index) {
        int statementEnd = findTopLevelSemicolon(body, index);
        if (statementEnd < 0) {
            return body;
        }

        String statement = body.substring(index + "use".length(), statementEnd).strip();
        String resourceHeader = toResourceHeader(statement);
        if (resourceHeader.isBlank()) {
            return body;
        }

        String prefix = body.substring(0, index);
        String rest = lowerResourceStatements(body.substring(statementEnd + 1));
        TrailingSplit split = splitTrailingWhitespace(rest);
        String indent = lineIndent(body, index);

        return prefix
                + "try (" + resourceHeader + ") {"
                + indentBody(split.content(), "    ")
                + "\n" + indent + "}"
                + split.trailing();
    }

    private static String lowerDefer(String body, int index) {
        int statementEnd = findTopLevelSemicolon(body, index);
        if (statementEnd < 0) {
            return body;
        }

        String finalizer = body.substring(index + "defer".length(), statementEnd).strip();
        if (finalizer.isBlank()) {
            return body;
        }

        String prefix = body.substring(0, index);
        String rest = lowerResourceStatements(body.substring(statementEnd + 1));
        TrailingSplit split = splitTrailingWhitespace(rest);
        String indent = lineIndent(body, index);

        return prefix
                + "try {"
                + indentBody(split.content(), "    ")
                + "\n" + indent + "} finally {\n"
                + indent + "    " + finalizer + ";\n"
                + indent + "}"
                + split.trailing();
    }

    private static String toResourceHeader(String statement) {
        int equals = findTopLevelEquals(statement);
        if (equals < 0) {
            return statement;
        }

        String left = statement.substring(0, equals).strip();
        String right = statement.substring(equals + 1).strip();
        if (left.isBlank() || right.isBlank()) {
            return "";
        }

        if (left.contains(" ")) {
            return left + " = " + right;
        }
        return "var " + left + " = " + right;
    }

    private static int findTopLevelKeyword(String source, String keyword, int from) {
        SourceScanner.State state = SourceScanner.State.CODE;
        int paren = 0;
        int brace = 0;
        int bracket = 0;

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
                    } else if (paren == 0 && brace == 0 && bracket == 0
                            && startsWithKeyword(source, keyword, i)) {
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

    private static int findTopLevelSemicolon(String source, int from) {
        return findTopLevelChar(source, from, ';');
    }

    private static int findTopLevelEquals(String source) {
        return findTopLevelChar(source, 0, '=');
    }

    private static int findTopLevelChar(String source, int from, char target) {
        SourceScanner.State state = SourceScanner.State.CODE;
        int paren = 0;
        int brace = 0;
        int bracket = 0;

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
                    } else if (c == target && paren == 0 && brace == 0 && bracket == 0) {
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

    private static TrailingSplit splitTrailingWhitespace(String value) {
        int index = value.length();
        while (index > 0 && Character.isWhitespace(value.charAt(index - 1))) {
            index--;
        }
        return new TrailingSplit(value.substring(0, index), value.substring(index));
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

    private record TrailingSplit(String content, String trailing) {
    }
}
