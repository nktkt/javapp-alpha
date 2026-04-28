package dev.javapp.compiler;

import java.nio.file.Path;
import java.util.List;

public record BuildResult(
        boolean success,
        List<TranspileResult> transpiled,
        List<Path> javaSources,
        String compilerOutput
) {
}

