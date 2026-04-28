package dev.javapp.compiler;

import java.util.regex.Pattern;

final class JavaSourceNormalizer {
    private static final Pattern EFFECT =
            Pattern.compile("\\)\\s+effect\\s+[A-Za-z_][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*\\s*(?=[{;])");

    private JavaSourceNormalizer() {
    }

    static String normalize(String source) {
        String normalized = stripNullMarkers(source);
        normalized = normalized.replaceAll("\\bvalue\\s+record\\b", "record");
        normalized = normalized.replaceAll("\\bpure\\s+", "");
        normalized = EFFECT.matcher(normalized).replaceAll(")");
        return normalized;
    }

    private static String stripNullMarkers(String source) {
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
                    } else if ((c == '?' || c == '!') && JavaMarkerSupport.isTypeMarker(source, i)) {
                        // Drop Java++ nullness markers in generated Java.
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

}
