package dev.javapp.compiler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExtensionCallRewriter {
    private ExtensionCallRewriter() {
    }

    static String rewrite(String source, ExtensionRegistry registry) {
        if (registry.isEmpty()) {
            return source;
        }
        return new ExtensionCallRewriterWithState(registry, collectVariableTypes(source, registry)).rewrite(source);
    }

    private static Map<String, String> collectVariableTypes(String source, ExtensionRegistry registry) {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        for (String targetType : registry.targetTypes()) {
            Pattern declaration = Pattern.compile(
                    "(?<![A-Za-z0-9_$])" + Pattern.quote(targetType)
                            + "(?:\\s*<[^;(){}=]*>)?(?:\\s*\\[\\s*])?\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
            Matcher matcher = declaration.matcher(source);
            while (matcher.find()) {
                variableTypes.put(matcher.group(1), targetType);
            }
        }
        return variableTypes;
    }

    private static final class ExtensionCallRewriterWithState {
        private final ExtensionRegistry registry;
        private final Map<String, String> variableTypes;

        private ExtensionCallRewriterWithState(ExtensionRegistry registry, Map<String, String> variableTypes) {
            this.registry = registry;
            this.variableTypes = variableTypes;
        }

        private String rewrite(String source) {
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
                            state = SourceScanner.State.STRING;
                        } else if (c == '\'') {
                            out.append(c);
                            state = SourceScanner.State.CHAR;
                        } else if (Character.isJavaIdentifierStart(c)) {
                            Rewrite rewrite = tryRewriteCall(source, i);
                            if (rewrite != null) {
                                out.append(rewrite.replacement());
                                i = rewrite.endIndex();
                            } else {
                                out.append(c);
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
                            state = SourceScanner.State.CODE;
                            i += 2;
                        }
                    }
                }
            }

            return out.toString();
        }

        private Rewrite tryRewriteCall(String source, int start) {
            int receiverEnd = readIdentifierEnd(source, start);
            String receiver = source.substring(start, receiverEnd);
            String receiverType = variableTypes.get(receiver);
            if (receiverType == null || receiverEnd >= source.length() || source.charAt(receiverEnd) != '.') {
                return null;
            }

            int methodStart = receiverEnd + 1;
            if (methodStart >= source.length() || !Character.isJavaIdentifierStart(source.charAt(methodStart))) {
                return null;
            }

            int methodEnd = readIdentifierEnd(source, methodStart);
            String method = source.substring(methodStart, methodEnd);
            String extensionClass = registry.extensionClass(receiverType, method);
            if (extensionClass == null || methodEnd >= source.length() || source.charAt(methodEnd) != '(') {
                return null;
            }

            int close = SourceScanner.findMatching(source, methodEnd, '(', ')');
            if (close < 0) {
                return null;
            }

            String args = source.substring(methodEnd + 1, close).strip();
            String replacement = extensionClass + "." + method + "(" + receiver
                    + (args.isBlank() ? "" : ", " + args)
                    + ")";
            return new Rewrite(replacement, close);
        }

        private static int readIdentifierEnd(String source, int start) {
            int cursor = start + 1;
            while (cursor < source.length() && Character.isJavaIdentifierPart(source.charAt(cursor))) {
                cursor++;
            }
            return cursor;
        }
    }

    private record Rewrite(String replacement, int endIndex) {
    }
}
