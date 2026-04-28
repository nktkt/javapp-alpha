package dev.javapp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DataClassTranspiler {
    private static final Pattern HEADER = Pattern.compile(
            "(?:(public)\\s+)?data\\s+class\\s+([A-Za-z_$][A-Za-z0-9_$]*)(\\s*<([^>{}]*)>)?\\s*\\{");

    private DataClassTranspiler() {
    }

    static String transpile(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int cursor = 0;
        Matcher matcher = HEADER.matcher(source);

        while (matcher.find(cursor)) {
            int openBrace = matcher.end() - 1;
            int closeBrace = SourceScanner.findMatching(source, openBrace, '{', '}');
            if (closeBrace < 0) {
                break;
            }

            out.append(source, cursor, matcher.start());
            String access = matcher.group(1) == null ? "" : matcher.group(1) + " ";
            String name = matcher.group(2);
            String typeParameters = matcher.group(3) == null ? "" : matcher.group(3).trim();
            String body = source.substring(openBrace + 1, closeBrace);
            List<Field> fields = parseFields(body);

            if (fields.isEmpty() && !body.isBlank()) {
                out.append(access)
                        .append("class ")
                        .append(name)
                        .append(typeParameters)
                        .append(" {")
                        .append(body)
                        .append("}");
            } else {
                out.append(access)
                        .append("record ")
                        .append(name)
                        .append(typeParameters)
                        .append('(')
                        .append(recordComponents(fields))
                        .append(") {}\n");
            }

            cursor = closeBrace + 1;
        }

        out.append(source, cursor, source.length());
        return out.toString();
    }

    private static List<Field> parseFields(String body) {
        List<Field> fields = new ArrayList<>();
        for (String rawMember : SourceScanner.splitTopLevel(body, ';')) {
            String member = rawMember.strip();
            if (member.isEmpty()) {
                continue;
            }
            Field field = parseField(member);
            if (field == null) {
                return List.of();
            }
            fields.add(field);
        }
        return List.copyOf(fields);
    }

    private static Field parseField(String member) {
        if (member.contains("(") || member.contains("{") || member.contains("}") || member.contains("=")) {
            return null;
        }

        String normalized = stripFieldModifiers(member);
        int split = lastWhitespace(normalized);
        if (split <= 0 || split + 1 >= normalized.length()) {
            return null;
        }

        String type = normalized.substring(0, split).strip();
        String name = normalized.substring(split + 1).strip();
        if (type.isBlank() || name.isBlank() || !isIdentifier(name)) {
            return null;
        }
        return new Field(type, name);
    }

    private static String stripFieldModifiers(String member) {
        String result = member.strip();
        boolean changed;
        do {
            changed = false;
            for (String modifier : List.of("public", "protected", "private", "final", "readonly")) {
                if (result.startsWith(modifier + " ")) {
                    result = result.substring(modifier.length()).stripLeading();
                    changed = true;
                }
            }
        } while (changed);
        return result;
    }

    private static String recordComponents(List<Field> fields) {
        List<String> components = new ArrayList<>();
        for (Field field : fields) {
            components.add(field.type() + " " + field.name());
        }
        return String.join(", ", components);
    }

    private static int lastWhitespace(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private record Field(String type, String name) {
    }
}

