package dev.javapp.compiler;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JavappCompiler {
    private final NullSafetyAnalyzer nullSafetyAnalyzer = new NullSafetyAnalyzer();

    public List<TranspileResult> transpile(Path sourceRoot, Path generatedRoot, NullMode nullMode) throws IOException {
        return transpile(sourceRoot, generatedRoot, nullMode, EffectMode.WARN);
    }

    public List<TranspileResult> transpile(
            Path sourceRoot,
            Path generatedRoot,
            NullMode nullMode,
            EffectMode effectMode
    ) throws IOException {
        List<Path> sources = listFiles(sourceRoot, ".jpp");
        List<TranspileResult> results = new ArrayList<>();
        Map<Path, String> inputs = readInputs(sources);
        Map<String, Set<String>> dataEnums = MatchExhaustivenessAnalyzer.collectDataEnums(inputs.values());
        Map<String, Set<String>> effectfulMethods = EffectAnalyzer.collectEffectfulMethods(inputs.values());
        ExtensionRegistry extensions = ExtensionMethodTranspiler.collectExtensions(inputs.values());

        for (Path source : sources) {
            Path relative = sourceRoot.relativize(source);
            String javaFileName = replaceExtension(relative.toString(), ".java");
            Path output = generatedRoot.resolve(javaFileName);

            String input = inputs.get(source);
            List<Diagnostic> diagnostics = analyzeSource(source, input, dataEnums, effectfulMethods, nullMode, effectMode);

            String java = transpileSource(input, extensions);
            Files.createDirectories(output.getParent());
            Files.writeString(output, java, StandardCharsets.UTF_8);

            results.add(new TranspileResult(source, output, List.copyOf(diagnostics)));
        }

        return List.copyOf(results);
    }

    public List<Diagnostic> check(Path sourceRoot, NullMode nullMode, EffectMode effectMode) throws IOException {
        List<Path> sources = listFiles(sourceRoot, ".jpp");
        Map<Path, String> inputs = readInputs(sources);
        Map<String, Set<String>> dataEnums = MatchExhaustivenessAnalyzer.collectDataEnums(inputs.values());
        Map<String, Set<String>> effectfulMethods = EffectAnalyzer.collectEffectfulMethods(inputs.values());

        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Path source : sources) {
            diagnostics.addAll(analyzeSource(
                    source,
                    inputs.get(source),
                    dataEnums,
                    effectfulMethods,
                    nullMode,
                    effectMode
            ));
        }
        return List.copyOf(diagnostics);
    }

    public BuildResult build(
            Path sourceRoot,
            Path javaSourceRoot,
            Path generatedRoot,
            Path classesRoot,
            NullMode nullMode
    ) throws IOException {
        return build(sourceRoot, javaSourceRoot, generatedRoot, classesRoot, nullMode, EffectMode.WARN);
    }

    public BuildResult build(
            Path sourceRoot,
            Path javaSourceRoot,
            Path generatedRoot,
            Path classesRoot,
            NullMode nullMode,
            EffectMode effectMode
    ) throws IOException {
        List<TranspileResult> transpiled = transpile(sourceRoot, generatedRoot, nullMode, effectMode);
        List<Path> javaSources = new ArrayList<>();
        for (TranspileResult result : transpiled) {
            javaSources.add(result.output());
        }
        javaSources.addAll(listFiles(javaSourceRoot, ".java"));

        if (javaSources.isEmpty()) {
            return new BuildResult(true, transpiled, List.of(), "");
        }

        Files.createDirectories(classesRoot);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new BuildResult(false, transpiled, List.copyOf(javaSources), "No system Java compiler is available.\n");
        }

        StringWriter output = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(javaSources);
            List<String> options = List.of("--release", "25", "-d", classesRoot.toString());
            Boolean success = compiler.getTask(output, fileManager, null, options, null, units).call();
            return new BuildResult(Boolean.TRUE.equals(success), transpiled, List.copyOf(javaSources), output.toString());
        }
    }

    public String transpileSource(String input) {
        return transpileSource(input, ExtensionMethodTranspiler.collectExtensions(List.of(input)));
    }

    private String transpileSource(String input, ExtensionRegistry extensionRegistry) {
        String dataClasses = DataClassTranspiler.transpile(input);
        String extensions = ExtensionMethodTranspiler.transpile(dataClasses);
        String normalized = JavaSourceNormalizer.normalize(extensions);
        String extensionCalls = ExtensionCallRewriter.rewrite(normalized, extensionRegistry);
        String dataEnums = DataEnumTranspiler.transpile(extensionCalls);
        String matches = MatchLowerer.lower(dataEnums);
        String async = StructuredAsyncLowerer.lower(matches);
        String resources = ResourceLowerer.lower(async);
        String nullCoalescing = NullCoalescingLowerer.lower(resources);
        return StringInterpolationLowerer.lower(nullCoalescing);
    }

    private Map<Path, String> readInputs(List<Path> sources) throws IOException {
        Map<Path, String> inputs = new LinkedHashMap<>();
        for (Path source : sources) {
            inputs.put(source, Files.readString(source, StandardCharsets.UTF_8));
        }
        return inputs;
    }

    private List<Diagnostic> analyzeSource(
            Path source,
            String input,
            Map<String, Set<String>> dataEnums,
            Map<String, Set<String>> effectfulMethods,
            NullMode nullMode,
            EffectMode effectMode
    ) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(nullSafetyAnalyzer.analyze(source, input, nullMode));
        diagnostics.addAll(MatchExhaustivenessAnalyzer.analyze(source, input, dataEnums));
        diagnostics.addAll(EffectAnalyzer.analyze(source, input, effectfulMethods, effectMode));
        return List.copyOf(diagnostics);
    }

    private static List<Path> listFiles(Path root, String extension) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String replaceExtension(String path, String extension) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return path + extension;
        }
        return path.substring(0, dot) + extension;
    }
}
