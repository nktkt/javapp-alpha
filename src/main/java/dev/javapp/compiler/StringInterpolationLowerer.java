package dev.javapp.compiler;

import java.util.ArrayList;
import java.util.List;

final class StringInterpolationLowerer {
    private StringInterpolationLowerer() {
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
                        if (i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                            out.append("\"\"\"");
                            state = SourceScanner.State.TEXT_BLOCK;
                            i += 2;
                        } else {
                            int end = readStringEnd(source, i);
                            if (end < 0) {
                                out.append(c);
                            } else {
                                String raw = source.substring(i + 1, end);
                                out.append(lowerLiteral(raw));
                                i = end;
                            }
                        }
                    } else if (c == '\'') {
                        out.append(c);
                        state = SourceScanner.State.CHAR;
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
                case STRING -> throw new IllegalStateException("string state is handled by readStringEnd");
            }
        }

        return out.toString();
    }

    private static int readStringEnd(String source, int quoteIndex) {
        for (int i = quoteIndex + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String lowerLiteral(String raw) {
        List<String> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\') {
                text.append(c);
                if (i + 1 < raw.length()) {
                    text.append(raw.charAt(++i));
                }
                continue;
            }

            if (c == '{') {
                int close = raw.indexOf('}', i + 1);
                if (close > i) {
                    flushText(parts, text);
                    String expression = raw.substring(i + 1, close).strip();
                    parts.add("String.valueOf(" + expression + ")");
                    i = close;
                    continue;
                }
            }

            text.append(c);
        }

        if (parts.isEmpty()) {
            return "\"" + raw + "\"";
        }

        flushText(parts, text);
        return String.join(" + ", parts);
    }

    private static void flushText(List<String> parts, StringBuilder text) {
        if (text.length() == 0) {
            return;
        }
        parts.add("\"" + text + "\"");
        text.setLength(0);
    }
}

