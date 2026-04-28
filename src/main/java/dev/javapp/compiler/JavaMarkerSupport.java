package dev.javapp.compiler;

final class JavaMarkerSupport {
    private static final java.util.Set<String> PRIMITIVE_TYPES = java.util.Set.of(
            "byte", "short", "int", "long", "float", "double", "boolean", "char"
    );

    private JavaMarkerSupport() {
    }

    static boolean isTypeMarker(String source, int index) {
        int before = index - 1;
        int after = index + 1;
        while (after < source.length() && Character.isWhitespace(source.charAt(after))) {
            after++;
        }

        if (before < 0 || after >= source.length()) {
            return false;
        }

        char previous = source.charAt(before);
        char next = source.charAt(after);
        boolean previousLooksLikeType = previous == '>' || previous == ']' || previousIdentifierLooksLikeType(source, before);
        boolean nextLooksLikeDeclaration = Character.isJavaIdentifierStart(next)
                || next == ','
                || next == ')'
                || next == ';'
                || next == '='
                || next == '>'
                || next == ']';
        return previousLooksLikeType && nextLooksLikeDeclaration;
    }

    private static boolean previousIdentifierLooksLikeType(String source, int end) {
        if (!Character.isJavaIdentifierPart(source.charAt(end))) {
            return false;
        }

        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
            start--;
        }

        String token = source.substring(start, end + 1);
        return !token.isEmpty() && (Character.isUpperCase(token.charAt(0)) || PRIMITIVE_TYPES.contains(token));
    }
}
