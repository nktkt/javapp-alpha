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
            Body parsedBody = parseBody(body);

            if (parsedBody.fields().isEmpty() && !body.isBlank()) {
                out.append(access)
                        .append("class ")
                        .append(name)
                        .append(typeParameters)
                        .append(" {")
                        .append(body)
                        .append("}");
            } else if (!parsedBody.members().isEmpty()) {
                out.append(generateClass(access, name, typeParameters, parsedBody));
            } else {
                out.append(generateRecord(access, name, typeParameters, parsedBody.fields()));
            }

            cursor = closeBrace + 1;
        }

        out.append(source, cursor, source.length());
        return out.toString();
    }

    private static Body parseBody(String body) {
        List<Field> fields = new ArrayList<>();
        List<String> members = new ArrayList<>();
        for (String rawMember : topLevelMembers(body)) {
            String member = rawMember.strip();
            if (member.isEmpty()) {
                continue;
            }
            if (member.endsWith(";")) {
                Field field = parseField(member.substring(0, member.length() - 1));
                if (field != null) {
                    fields.add(field);
                    continue;
                }
            }
            members.add(member);
        }
        return new Body(List.copyOf(fields), List.copyOf(members));
    }

    private static List<String> topLevelMembers(String body) {
        List<String> members = new ArrayList<>();
        SourceScanner.State state = SourceScanner.State.CODE;
        int start = 0;
        int paren = 0;
        int bracket = 0;

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            char n = i + 1 < body.length() ? body.charAt(i + 1) : '\0';

            switch (state) {
                case CODE -> {
                    if (c == '/' && n == '/') {
                        state = SourceScanner.State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && n == '*') {
                        state = SourceScanner.State.BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        if (i + 2 < body.length() && body.charAt(i + 1) == '"' && body.charAt(i + 2) == '"') {
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
                        paren = Math.max(0, paren - 1);
                    } else if (c == '[') {
                        bracket++;
                    } else if (c == ']') {
                        bracket = Math.max(0, bracket - 1);
                    } else if (c == ';' && paren == 0 && bracket == 0) {
                        members.add(body.substring(start, i + 1));
                        start = i + 1;
                    } else if (c == '{' && paren == 0 && bracket == 0) {
                        int close = SourceScanner.findMatching(body, i, '{', '}');
                        if (close < 0) {
                            members.add(body.substring(start));
                            return members;
                        }
                        members.add(body.substring(start, close + 1));
                        i = close;
                        start = close + 1;
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
                    if (c == '"' && i + 2 < body.length()
                            && body.charAt(i + 1) == '"'
                            && body.charAt(i + 2) == '"') {
                        state = SourceScanner.State.CODE;
                        i += 2;
                    }
                }
            }
        }

        if (start < body.length()) {
            members.add(body.substring(start));
        }
        return members;
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

    private static String generateRecord(String access, String name, String typeParameters, List<Field> fields) {
        StringBuilder out = new StringBuilder();
        String typeArguments = toTypeArgumentList(typeParameters);
        out.append(access)
                .append("record ")
                .append(name)
                .append(typeParameters)
                .append('(')
                .append(recordComponents(fields))
                .append(") {\n");
        out.append(generateCopyMethods(name, typeArguments, fields));
        out.append("}\n");
        return out.toString();
    }

    private static String generateClass(String access, String name, String typeParameters, Body body) {
        StringBuilder out = new StringBuilder();
        String typeArguments = toTypeArgumentList(typeParameters);
        out.append(access)
                .append("final class ")
                .append(name)
                .append(typeParameters)
                .append(" {\n");

        for (Field field : body.fields()) {
            out.append("    private final ")
                    .append(field.type())
                    .append(' ')
                    .append(field.name())
                    .append(";\n");
        }
        out.append('\n');

        out.append("    public ")
                .append(name)
                .append('(')
                .append(recordComponents(body.fields()))
                .append(") {\n");
        for (Field field : body.fields()) {
            out.append("        this.")
                    .append(field.name())
                    .append(" = ")
                    .append(field.name())
                    .append(";\n");
        }
        out.append("    }\n\n");

        for (Field field : body.fields()) {
            out.append("    public ")
                    .append(field.type())
                    .append(' ')
                    .append(field.name())
                    .append("() {\n")
                    .append("        return ")
                    .append(field.name())
                    .append(";\n")
                    .append("    }\n\n");
        }

        out.append(generateCopyMethods(name, typeArguments, body.fields()));
        out.append(generateEquals(name, body.fields()));
        out.append(generateHashCode(body.fields()));
        out.append(generateToString(name, body.fields()));

        for (String member : body.members()) {
            out.append(indentMember(member)).append('\n');
        }

        out.append("}\n");
        return out.toString();
    }

    private static String generateCopyMethods(String name, String typeArguments, List<Field> fields) {
        String type = name + typeArguments;
        String constructor = name + (typeArguments.isBlank() ? "" : "<>");
        StringBuilder out = new StringBuilder();
        out.append("    public ")
                .append(type)
                .append(" copy(")
                .append(recordComponents(fields))
                .append(") {\n")
                .append("        return new ")
                .append(constructor)
                .append('(')
                .append(fieldNames(fields))
                .append(");\n")
                .append("    }\n\n");

        for (Field field : fields) {
            out.append("    public ")
                    .append(type)
                    .append(" with")
                    .append(capitalize(field.name()))
                    .append('(')
                    .append(field.type())
                    .append(' ')
                    .append(field.name())
                    .append(") {\n")
                    .append("        return copy(")
                    .append(copyArguments(fields, field))
                    .append(");\n")
                    .append("    }\n\n");
        }
        return out.toString();
    }

    private static String copyArguments(List<Field> fields, Field replacement) {
        List<String> arguments = new ArrayList<>();
        for (Field field : fields) {
            if (field.name().equals(replacement.name())) {
                arguments.add(field.name());
            } else {
                arguments.add("this." + field.name());
            }
        }
        return String.join(", ", arguments);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String generateEquals(String name, List<Field> fields) {
        return """
                    @Override
                    public boolean equals(Object other) {
                        if (this == other) {
                            return true;
                        }
                        if (!(other instanceof %s that)) {
                            return false;
                        }
                        return %s;
                    }

                """.formatted(name, equalsExpression(fields));
    }

    private static String equalsExpression(List<Field> fields) {
        if (fields.isEmpty()) {
            return "true";
        }
        List<String> comparisons = new ArrayList<>();
        for (Field field : fields) {
            comparisons.add("java.util.Objects.equals(" + field.name() + ", that." + field.name() + ")");
        }
        return String.join("\n                && ", comparisons);
    }

    private static String generateHashCode(List<Field> fields) {
        return """
                    @Override
                    public int hashCode() {
                        return java.util.Objects.hash(%s);
                    }

                """.formatted(fieldNames(fields));
    }

    private static String generateToString(String name, List<Field> fields) {
        String fieldsString;
        if (fields.isEmpty()) {
            fieldsString = "\"" + name + "[]\"";
        } else {
            String first = fields.get(0).name();
            fieldsString = "\"" + name + "[" + first + "=\" + " + first;
            for (int i = 1; i < fields.size(); i++) {
                Field field = fields.get(i);
                fieldsString += " + \", " + field.name() + "=\" + " + field.name();
            }
            fieldsString += " + \"]\"";
        }
        return """
                    @Override
                    public String toString() {
                        return %s;
                    }

                """.formatted(fieldsString);
    }

    private static String fieldNames(List<Field> fields) {
        List<String> names = new ArrayList<>();
        for (Field field : fields) {
            names.add(field.name());
        }
        return String.join(", ", names);
    }

    private static String indentMember(String member) {
        String stripped = member.strip();
        String[] lines = stripped.split("\\R", -1);
        int trim = memberBodyIndent(lines);
        StringBuilder out = new StringBuilder(stripped.length() + lines.length * 4);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (!lines[i].isBlank()) {
                String line = i == 0 ? lines[i] : trimIndent(lines[i], trim);
                out.append("    ").append(line);
            }
        }
        return out.toString();
    }

    private static int memberBodyIndent(String[] lines) {
        int indent = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            indent = Math.min(indent, leadingWhitespace(lines[i]));
        }
        return indent == Integer.MAX_VALUE ? 0 : indent;
    }

    private static int leadingWhitespace(String line) {
        int count = 0;
        while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }

    private static String trimIndent(String line, int trim) {
        int end = Math.min(trim, line.length());
        int index = 0;
        while (index < end && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return line.substring(index);
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

    private static String toTypeArgumentList(String typeParameters) {
        if (typeParameters.isBlank()) {
            return "";
        }
        String inner = typeParameters.substring(1, typeParameters.length() - 1);
        List<String> names = new ArrayList<>();
        for (String part : SourceScanner.splitTopLevel(inner, ',')) {
            String trimmed = part.strip();
            int bound = trimmed.indexOf(" extends ");
            if (bound >= 0) {
                trimmed = trimmed.substring(0, bound).strip();
            }
            names.add(trimmed);
        }
        return "<" + String.join(", ", names) + ">";
    }

    private record Field(String type, String name) {
    }

    private record Body(List<Field> fields, List<String> members) {
    }
}
