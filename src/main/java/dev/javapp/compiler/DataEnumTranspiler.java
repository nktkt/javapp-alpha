package dev.javapp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DataEnumTranspiler {
    private static final Pattern HEADER = Pattern.compile(
            "(?:(public)\\s+)?data\\s+enum\\s+([A-Za-z_$][A-Za-z0-9_$]*)(\\s*<([^>{}]*)>)?\\s*\\{");

    private DataEnumTranspiler() {
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
            String typeParameterNames = toTypeArgumentList(typeParameters);
            String body = source.substring(openBrace + 1, closeBrace);
            out.append(generate(access, name, typeParameters, typeParameterNames, parseVariants(body)));
            cursor = closeBrace + 1;
        }

        out.append(source, cursor, source.length());
        return out.toString();
    }

    private static String generate(
            String access,
            String name,
            String typeParameters,
            String typeParameterNames,
            List<Variant> variants
    ) {
        String enumType = name + typeParameterNames;
        StringBuilder out = new StringBuilder();

        out.append(access)
                .append("sealed interface ")
                .append(name)
                .append(typeParameters)
                .append(" permits ");

        for (int i = 0; i < variants.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(variants.get(i).name());
        }
        out.append(" {\n");

        for (Variant variant : variants) {
            out.append("    static ");
            if (!typeParameters.isBlank()) {
                out.append(typeParameters).append(' ');
            }
            out.append(enumType)
                    .append(' ')
                    .append(variant.name())
                    .append('(')
                    .append(variant.parameters())
                    .append(") {\n")
                    .append("        return ");
            if (variant.noArgs()) {
                out.append(variant.name()).append(".instance()");
            } else {
                out.append("new ")
                        .append(variant.name());
                if (!typeParameters.isBlank()) {
                    out.append("<>");
                }
                out.append('(').append(variant.argumentNames()).append(')');
            }
            out.append(";\n")
                    .append("    }\n\n");
        }

        out.append("}\n\n");

        for (Variant variant : variants) {
            if (variant.noArgs()) {
                out.append(generateSingletonVariant(variant.name(), typeParameters, enumType));
            } else {
                out.append("record ")
                        .append(variant.name())
                        .append(typeParameters)
                        .append('(')
                        .append(variant.parameters())
                        .append(") implements ")
                        .append(enumType)
                        .append(" {}\n");
            }
        }

        return out.toString();
    }

    private static String generateSingletonVariant(String name, String typeParameters, String enumType) {
        boolean generic = !typeParameters.isBlank();
        StringBuilder out = new StringBuilder();
        out.append("final class ")
                .append(name)
                .append(typeParameters)
                .append(" implements ")
                .append(enumType)
                .append(" {\n");
        if (generic) {
            out.append("    private static final ")
                    .append(name)
                    .append("<?> INSTANCE = new ")
                    .append(name)
                    .append("<>();\n\n");
        } else {
            out.append("    private static final ")
                    .append(name)
                    .append(" INSTANCE = new ")
                    .append(name)
                    .append("();\n\n");
        }
        out.append("    private ")
                .append(name)
                .append("() {\n")
                .append("    }\n\n");
        if (generic) {
            out.append("    @SuppressWarnings(\"unchecked\")\n")
                    .append("    static ")
                    .append(typeParameters)
                    .append(' ')
                    .append(name)
                    .append(toTypeArgumentList(typeParameters))
                    .append(" instance() {\n")
                    .append("        return (")
                    .append(name)
                    .append(toTypeArgumentList(typeParameters))
                    .append(") INSTANCE;\n")
                    .append("    }\n\n");
        } else {
            out.append("    static ")
                    .append(name)
                    .append(" instance() {\n")
                    .append("        return INSTANCE;\n")
                    .append("    }\n\n");
        }
        out.append("    @Override\n")
                .append("    public boolean equals(Object other) {\n")
                .append("        return other instanceof ")
                .append(name);
        if (generic) {
            out.append("<?>");
        }
        out.append(";\n")
                .append("    }\n\n")
                .append("    @Override\n")
                .append("    public int hashCode() {\n")
                .append("        return ")
                .append(name)
                .append(".class.hashCode();\n")
                .append("    }\n\n")
                .append("    @Override\n")
                .append("    public String toString() {\n")
                .append("        return \"")
                .append(name)
                .append("\";\n")
                .append("    }\n")
                .append("}\n");
        return out.toString();
    }

    private static List<Variant> parseVariants(String body) {
        List<Variant> variants = new ArrayList<>();
        for (String rawPart : SourceScanner.splitTopLevel(body, ',')) {
            String part = rawPart.strip();
            if (part.isEmpty()) {
                continue;
            }
            int paren = part.indexOf('(');
            if (paren < 0) {
                variants.add(new Variant(part, "", "", true));
                continue;
            }

            int close = SourceScanner.findMatching(part, paren, '(', ')');
            if (close < 0) {
                variants.add(new Variant(part, "", "", true));
                continue;
            }

            String name = part.substring(0, paren).strip();
            String parameters = part.substring(paren + 1, close).strip();
            variants.add(new Variant(name, parameters, argumentNames(parameters), parameters.isBlank()));
        }
        return variants;
    }

    private static String argumentNames(String parameters) {
        if (parameters.isBlank()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String component : SourceScanner.splitTopLevel(parameters, ',')) {
            String trimmed = component.strip();
            int split = lastWhitespace(trimmed);
            if (split < 0 || split + 1 >= trimmed.length()) {
                names.add(trimmed);
            } else {
                names.add(trimmed.substring(split + 1).strip());
            }
        }
        return String.join(", ", names);
    }

    private static int lastWhitespace(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
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

    private record Variant(String name, String parameters, String argumentNames, boolean noArgs) {
    }
}
