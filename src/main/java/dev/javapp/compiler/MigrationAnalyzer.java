package dev.javapp.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MigrationAnalyzer {
    public List<Diagnostic> analyze(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of(new Diagnostic(Diagnostic.Severity.WARN, root, 0, "JPP_MIGRATE_000", "path does not exist"));
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNumber = i + 1;

                if (line.contains("return null")) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.WARN,
                            file,
                            lineNumber,
                            "JPP_MIGRATE_001",
                            "possible null return; consider T? or Result/Option"
                    ));
                }
                if (line.contains("CompletableFuture")) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.INFO,
                            file,
                            lineNumber,
                            "JPP_MIGRATE_002",
                            "CompletableFuture chain candidate for structured async"
                    ));
                }
                if (line.contains("ExecutorService") || line.contains("new Thread(")) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.INFO,
                            file,
                            lineNumber,
                            "JPP_MIGRATE_003",
                            "manual thread/executor management candidate for scoped concurrency"
                    ));
                }
                if (line.contains("finally") || line.contains(".close()")) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.INFO,
                            file,
                            lineNumber,
                            "JPP_MIGRATE_004",
                            "resource cleanup candidate for use/defer"
                    ));
                }
                if (line.contains("Optional<")) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.INFO,
                            file,
                            lineNumber,
                            "JPP_MIGRATE_005",
                            "Optional usage can map to Java++ Option at API boundaries"
                    ));
                }
            }
        }

        return List.copyOf(diagnostics);
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
