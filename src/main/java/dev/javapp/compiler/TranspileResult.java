package dev.javapp.compiler;

import java.nio.file.Path;
import java.util.List;

public record TranspileResult(Path source, Path output, List<Diagnostic> diagnostics) {
}

