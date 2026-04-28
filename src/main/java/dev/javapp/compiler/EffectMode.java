package dev.javapp.compiler;

public enum EffectMode {
    OFF,
    WARN,
    STRICT;

    public static EffectMode parse(String value) {
        return switch (value.toLowerCase()) {
            case "off" -> OFF;
            case "warn" -> WARN;
            case "strict" -> STRICT;
            default -> throw new IllegalArgumentException("unknown effects mode: " + value);
        };
    }
}

