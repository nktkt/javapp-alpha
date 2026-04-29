package dev.javapp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VariantConstructorLowerer {
    private static final Pattern DATA_ENUM_HEADER = Pattern.compile(
            "\\bdata\\s+enum\\s+([A-Za-z_$][A-Za-z0-9_$]*)(\\s*<[^>{}]*>)?\\s*\\{");

    private VariantConstructorLowerer() {
    }

    static Registry collect(Iterable<String> sources) {
        Map<String, VariantInfo> variants = new LinkedHashMap<>();
        for (String source : sources) {
            Matcher matcher = DATA_ENUM_HEADER.matcher(source);
            int cursor = 0;
            while (matcher.find(cursor)) {
                int openBrace = matcher.end() - 1;
                int closeBrace = SourceScanner.findMatching(source, openBrace, '{', '}');
                if (closeBrace < 0) {
                    break;
                }

                boolean generic = matcher.group(2) != null && !matcher.group(2).isBlank();
                for (String variant : parseVariants(source.substring(openBrace + 1, closeBrace))) {
                    variants.merge(variant, new VariantInfo(variant, generic),
                            (left, right) -> new VariantInfo(left.name(), left.generic() || right.generic()));
                }
                cursor = closeBrace + 1;
            }
        }
        return new Registry(Map.copyOf(variants));
    }

    static String lower(String source, Registry registry) {
        if (registry.isEmpty()) {
            return source;
        }

        List<Range> dataEnumRanges = dataEnumRanges(source);
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
                        if (i + 2 < source.length()
                                && source.charAt(i + 1) == '"'
                                && source.charAt(i + 2) == '"') {
                            out.append("\"\"");
                            state = SourceScanner.State.TEXT_BLOCK;
                            i += 2;
                        } else {
                            state = SourceScanner.State.STRING;
                        }
                    } else if (c == '\'') {
                        out.append(c);
                        state = SourceScanner.State.CHAR;
                    } else if (Character.isJavaIdentifierStart(c)) {
                        int end = readIdentifierEnd(source, i);
                        String identifier = source.substring(i, end);
                        VariantInfo variant = registry.variants().get(identifier);
                        int callStart = SourceScanner.skipWhitespace(source, end);
                        if (variant != null
                                && callStart < source.length()
                                && source.charAt(callStart) == '('
                                && !insideAny(dataEnumRanges, i)
                                && isExpressionContext(source, i)
                                && !isMatchPattern(source, callStart)) {
                            int callEnd = SourceScanner.findMatching(source, callStart, '(', ')');
                            if (callEnd >= 0) {
                                out.append("new ")
                                        .append(identifier)
                                        .append(variant.generic() ? "<>" : "")
                                        .append(source, callStart, callEnd + 1);
                                i = callEnd;
                            } else {
                                out.append(identifier);
                                i = end - 1;
                            }
                        } else {
                            out.append(identifier);
                            i = end - 1;
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

    private static List<String> parseVariants(String body) {
        List<String> variants = new ArrayList<>();
        for (String rawPart : SourceScanner.splitTopLevel(body, ',')) {
            String part = rawPart.strip();
            if (part.isEmpty()) {
                continue;
            }
            int paren = part.indexOf('(');
            variants.add(paren < 0 ? part : part.substring(0, paren).strip());
        }
        return variants;
    }

    private static List<Range> dataEnumRanges(String source) {
        List<Range> ranges = new ArrayList<>();
        Matcher matcher = DATA_ENUM_HEADER.matcher(source);
        int cursor = 0;
        while (matcher.find(cursor)) {
            int openBrace = matcher.end() - 1;
            int closeBrace = SourceScanner.findMatching(source, openBrace, '{', '}');
            if (closeBrace < 0) {
                break;
            }
            ranges.add(new Range(matcher.start(), closeBrace + 1));
            cursor = closeBrace + 1;
        }
        return ranges;
    }

    private static boolean insideAny(List<Range> ranges, int index) {
        for (Range range : ranges) {
            if (index >= range.start() && index < range.end()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExpressionContext(String source, int index) {
        int before = skipWhitespaceLeft(source, index);
        if (before == 0) {
            return true;
        }
        if (before >= 2 && source.charAt(before - 2) == '-' && source.charAt(before - 1) == '>') {
            return true;
        }

        char previous = source.charAt(before - 1);
        if ("=({[,?:;".indexOf(previous) >= 0) {
            return true;
        }
        if (previous == '.') {
            return false;
        }

        String token = previousToken(source, before);
        return token.equals("return") || token.equals("yield") || token.equals("throw");
    }

    private static boolean isMatchPattern(String source, int callStart) {
        int close = SourceScanner.findMatching(source, callStart, '(', ')');
        if (close < 0) {
            return false;
        }
        int next = SourceScanner.skipWhitespace(source, close + 1);
        return next + 1 < source.length() && source.charAt(next) == '-' && source.charAt(next + 1) == '>';
    }

    private static int readIdentifierEnd(String source, int start) {
        int end = start + 1;
        while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int skipWhitespaceLeft(String source, int fromExclusive) {
        int i = fromExclusive;
        while (i > 0 && Character.isWhitespace(source.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static String previousToken(String source, int beforeExclusive) {
        int end = skipWhitespaceLeft(source, beforeExclusive);
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return "";
        }
        return source.substring(start, end);
    }

    record Registry(Map<String, VariantInfo> variants) {
        boolean isEmpty() {
            return variants.isEmpty();
        }
    }

    private record VariantInfo(String name, boolean generic) {
    }

    private record Range(int start, int end) {
    }
}
