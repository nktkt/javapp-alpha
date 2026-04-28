package dev.javapp.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class JppFormatter {
    public List<FormatResult> formatTree(Path root, boolean checkOnly) throws IOException {
        List<FormatResult> results = new ArrayList<>();
        if (!Files.exists(root)) {
            return results;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> sources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jpp"))
                    .sorted()
                    .toList();

            for (Path source : sources) {
                String original = Files.readString(source, StandardCharsets.UTF_8);
                String formatted = format(original);
                boolean changed = !original.equals(formatted);
                if (changed && !checkOnly) {
                    Files.writeString(source, formatted, StandardCharsets.UTF_8);
                }
                results.add(new FormatResult(source, changed));
            }
        }
        return results;
    }

    public String format(String source) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length() + 1);
        int blankRun = 0;

        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) {
                break;
            }

            String trimmedRight = trimRight(lines[i]);
            if (trimmedRight.isBlank()) {
                blankRun++;
                if (blankRun > 2) {
                    continue;
                }
                out.append('\n');
                continue;
            }

            blankRun = 0;
            out.append(trimmedRight).append('\n');
        }

        if (out.isEmpty() && !source.isEmpty()) {
            out.append('\n');
        }
        return out.toString();
    }

    private static String trimRight(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    public record FormatResult(Path file, boolean changed) {
    }
}
