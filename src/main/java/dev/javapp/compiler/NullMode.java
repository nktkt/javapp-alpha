package dev.javapp.compiler;

public enum NullMode {
    OFF,
    WARN,
    STRICT;

    public static NullMode parse(String value) {
        return switch (value.toLowerCase()) {
            case "off" -> OFF;
            case "warn" -> WARN;
            case "strict" -> STRICT;
            default -> throw new IllegalArgumentException("unknown null mode: " + value);
        };
    }
}

