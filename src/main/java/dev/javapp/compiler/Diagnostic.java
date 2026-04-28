package dev.javapp.compiler;

import java.nio.file.Path;

public record Diagnostic(Severity severity, Path file, int line, String code, String message) {
    public enum Severity {
        INFO,
        WARN,
        ERROR
    }

    public Diagnostic(Severity severity, Path file, int line, String message) {
        this(severity, file, line, "JPP000", message);
    }

    public String format() {
        String location = file == null ? "<unknown>" : file.toString();
        String linePart = line > 0 ? ":" + line : "";
        return location + linePart + ": " + severity.name().toLowerCase() + " " + code + ": " + message;
    }
}
