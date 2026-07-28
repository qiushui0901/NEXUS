package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Repository and Git scanner supporting every enabled language adapter. */
@Component
@Primary
public class MultiLanguageCodeScanner implements CodeScanner {
    private static final Logger log = LoggerFactory.getLogger(MultiLanguageCodeScanner.class);
    private final CodeLanguageRegistry registry;

    public MultiLanguageCodeScanner(CodeLanguageRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ScanResult scan(RagProperties.Code config) throws IOException {
        Path root = Path.of(config.repositoryPath()).toAbsolutePath().normalize();
        String commit = git(root, List.of("rev-parse", "HEAD"), false);
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> supports(path.toString()))
                    .filter(path -> include(root, path, config))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path).toString())))
                    .toList();
        }
        Aggregate aggregate = new Aggregate(registry.capabilityDiagnostics());
        for (Path file : files) {
            String relative = normalize(root.relativize(file).toString());
            parse(config, commit, relative, Files.readString(file, StandardCharsets.UTF_8), aggregate);
        }
        return aggregate.result(config.projectId(), commit.isBlank() ? "unknown" : commit, files.size());
    }

    @Override
    public ScanResult scanFiles(RagProperties.Code config, String commitSha, List<String> paths) throws IOException {
        Path root = Path.of(config.repositoryPath()).toAbsolutePath().normalize();
        Aggregate aggregate = new Aggregate(registry.capabilityDiagnostics());
        int scanned = 0;
        for (String path : paths) {
            String relative = normalize(path);
            if (!supports(relative)) continue;
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root) || !include(root, resolved, config)) continue;
            String text = git(root, List.of("show", commitSha + ":" + relative), true);
            if (text == null) continue;
            scanned++;
            parse(config, commitSha, relative, text, aggregate);
        }
        return aggregate.result(config.projectId(), commitSha, scanned);
    }

    @Override
    public boolean supports(String path) {
        return registry.supports(path);
    }

    private void parse(RagProperties.Code config, String commit, String path, String text, Aggregate aggregate) {
        if (text == null || text.indexOf('\0') >= 0
                || text.getBytes(StandardCharsets.UTF_8).length > config.resolvedMaxFileBytes()) {
            aggregate.diagnostics.add(new CodeScanDiagnostic(CodeLanguage.fromPath(path).id(), path,
                    "FILE_SKIPPED", "Binary or oversized source file"));
            return;
        }
        try {
            ParsedCodeFile parsed = registry.adapter(path).orElseThrow().parse(
                    config.projectId(), commit, path, text);
            aggregate.chunks.addAll(parsed.chunks());
            aggregate.symbols.addAll(parsed.symbols());
            aggregate.calls.addAll(parsed.calls());
            aggregate.diagnostics.addAll(parsed.diagnostics());
        }
        catch (RuntimeException | LinkageError exception) {
            log.warn("Code parsing failed for {}: {}", path, exception.getMessage(), exception);
            aggregate.diagnostics.add(new CodeScanDiagnostic(CodeLanguage.fromPath(path).id(), path,
                    "PARSE_FAILED", exception.getClass().getSimpleName()));
        }
    }

    private String git(Path root, List<String> arguments, boolean missingAllowed) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(false).start();
        byte[] output = process.getInputStream().readAllBytes();
        byte[] error = process.getErrorStream().readAllBytes();
        try {
            int exit = process.waitFor();
            if (exit == 0) return new String(output, StandardCharsets.UTF_8).stripTrailing();
            if (missingAllowed) return null;
            log.warn("Git command failed in repository {} with exit {}: {}", root.getFileName(), exit,
                    new String(error, StandardCharsets.UTF_8).strip());
            return "";
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", exception);
        }
    }

    private boolean include(Path root, Path file, RagProperties.Code config) {
        String relative = "/" + normalize(root.relativize(file).toString());
        if (config.excludes().stream().anyMatch(relative::contains)) return false;
        return config.includes().isEmpty() || config.includes().stream().anyMatch(relative::contains);
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static final class Aggregate {
        private final List<CodeChunk> chunks = new ArrayList<>();
        private final List<CodeSymbol> symbols = new ArrayList<>();
        private final List<CodeCall> calls = new ArrayList<>();
        private final List<CodeScanDiagnostic> diagnostics = new ArrayList<>();

        private Aggregate(List<CodeScanDiagnostic> capabilities) {
            diagnostics.addAll(capabilities);
        }

        private ScanResult result(String project, String commit, int files) {
            return new ScanResult(project, commit, files, chunks, symbols, calls, diagnostics);
        }
    }
}
