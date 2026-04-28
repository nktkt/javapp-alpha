package dev.javapp.compiler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExtensionMethodTranspiler {
    private static final Pattern HEADER = Pattern.compile(
            "(?:(public)\\s+)?extension\\s+([^{}]+?)\\s*\\{");

    private static final Pattern METHOD_HEADER = Pattern.compile(
            "((?:(?:public|private|protected|static|final)\\s+)*)"
                    + "([A-Za-z_$][A-Za-z0-9_$.<>?!,\\[\\]\\s]*?)\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(([^()]*)\\)"
                    + "(\\s*(?:effect\\s+[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*,\\s*[A-Za-z_$][A-Za-z0-9_$]*)*)?"
                    + "(?:\\s+throws\\s+[^{};]+)?)\\s*\\{");

    private ExtensionMethodTranspiler() {
    }

    static ExtensionRegistry collectExtensions(Iterable<String> sources) {
        ExtensionRegistry registry = new ExtensionRegistry();
        for (String source : sources) {
            collectExtensions(source, registry);
        }
        return registry;
    }

    static String transpile(String source) {
        StringBuilder out = new StringBuilder(source.length());
        Matcher matcher = HEADER.matcher(source);
        int cursor = 0;

        while (matcher.find(cursor)) {
            int openBrace = matcher.end() - 1;
            int closeBrace = SourceScanner.findMatching(source, openBrace, '{', '}');
            if (closeBrace < 0) {
                break;
            }

            String access = matcher.group(1) == null ? "" : matcher.group(1) + " ";
            String targetType = matcher.group(2).strip();
            String body = source.substring(openBrace + 1, closeBrace);

            out.append(source, cursor, matcher.start())
                    .append(access)
                    .append("final class ")
                    .append(extensionClassName(targetType))
                    .append(" {\n")
                    .append("    private ")
                    .append(extensionClassName(targetType))
                    .append("() {}\n")
                    .append(lowerMethods(body, targetType))
                    .append("}\n");

            cursor = closeBrace + 1;
        }

        out.append(source, cursor, source.length());
        return out.toString();
    }

    private static void collectExtensions(String source, ExtensionRegistry registry) {
        Matcher matcher = HEADER.matcher(source);
        int cursor = 0;
        while (matcher.find(cursor)) {
            int openBrace = matcher.end() - 1;
            int closeBrace = SourceScanner.findMatching(source, openBrace, '{', '}');
            if (closeBrace < 0) {
                return;
            }

            String targetType = matcher.group(2).strip();
            String extensionClass = extensionClassName(targetType);
            String body = source.substring(openBrace + 1, closeBrace);
            Matcher methodMatcher = METHOD_HEADER.matcher(body);
            int methodCursor = 0;
            while (methodMatcher.find(methodCursor)) {
                int methodOpenBrace = methodMatcher.end() - 1;
                int methodCloseBrace = SourceScanner.findMatching(body, methodOpenBrace, '{', '}');
                if (methodCloseBrace < 0) {
                    break;
                }
                registry.add(targetType, methodMatcher.group(3), extensionClass);
                methodCursor = methodCloseBrace + 1;
            }
            cursor = closeBrace + 1;
        }
    }

    private static String lowerMethods(String body, String targetType) {
        StringBuilder out = new StringBuilder(body.length());
        Matcher matcher = METHOD_HEADER.matcher(body);
        int cursor = 0;

        while (matcher.find(cursor)) {
            int openBrace = matcher.end() - 1;
            int closeBrace = SourceScanner.findMatching(body, openBrace, '{', '}');
            if (closeBrace < 0) {
                break;
            }

            out.append(body, cursor, matcher.start());
            String modifiers = staticModifiers(matcher.group(1));
            String returnType = matcher.group(2).strip();
            String name = matcher.group(3);
            String parameters = matcher.group(4).strip();
            String suffix = matcher.group(5) == null ? "" : matcher.group(5);
            String methodBody = body.substring(openBrace + 1, closeBrace);

            out.append(modifiers)
                    .append(returnType)
                    .append(' ')
                    .append(name)
                    .append('(')
                    .append(targetType)
                    .append(" self");
            if (!parameters.isBlank()) {
                out.append(", ").append(parameters);
            }
            out.append(')')
                    .append(suffix)
                    .append(" {")
                    .append(rewriteThis(methodBody))
                    .append("}");

            cursor = closeBrace + 1;
        }

        out.append(body, cursor, body.length());
        return out.toString();
    }

    private static String staticModifiers(String modifiers) {
        String normalized = modifiers == null ? "" : modifiers;
        if (!Pattern.compile("\\bstatic\\b").matcher(normalized).find()) {
            normalized = normalized + "static ";
        }
        return normalized;
    }

    private static String rewriteThis(String source) {
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
                    } else if (startsWithThis(source, i)) {
                        out.append("self");
                        i += "this".length() - 1;
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

    private static boolean startsWithThis(String source, int index) {
        if (!source.startsWith("this", index)) {
            return false;
        }
        int before = index - 1;
        int after = index + "this".length();
        boolean cleanBefore = before < 0 || !Character.isJavaIdentifierPart(source.charAt(before));
        boolean cleanAfter = after >= source.length() || !Character.isJavaIdentifierPart(source.charAt(after));
        return cleanBefore && cleanAfter;
    }

    private static String extensionClassName(String targetType) {
        String stripped = targetType.replaceAll("<.*>", "").replace("[]", "").strip();
        int dot = stripped.lastIndexOf('.');
        String simple = dot >= 0 ? stripped.substring(dot + 1) : stripped;
        if (simple.isBlank()) {
            return "Extensions";
        }
        if (Character.isLowerCase(simple.charAt(0))) {
            simple = Character.toUpperCase(simple.charAt(0)) + simple.substring(1);
        }
        return simple + "Extensions";
    }
}
