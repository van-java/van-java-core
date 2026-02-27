package dev.vanengine.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages a long-lived van-compiler-wasi daemon subprocess and provides
 * .van file compilation with mtime-based caching.
 *
 * <p>Lifecycle: call {@link #init()} to start the daemon,
 * {@link #close()} to stop it.
 */
public class VanCompiler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VanCompiler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private Process process;
    private BufferedWriter processStdin;
    private BufferedReader processStdout;

    public void init() throws Exception {
        Path binary = new NativeBinaryResolver().resolve();
        ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--daemon");
        pb.redirectErrorStream(false);
        process = pb.start();
        processStdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        processStdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        log.info("van-compiler daemon started (pid {})", process.pid());
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            log.info("Stopping van-compiler daemon (pid {})", process.pid());
            try {
                processStdin.close();
            } catch (IOException ignored) {
            }
            process.destroy();
        }
    }

    /**
     * Compile a .van file, returning cached results when the file hasn't changed.
     *
     * @param vanFile  path to the .van entry file
     * @param basePath base directory for resolving relative imports
     * @return compiled HTML string
     */
    public CompiledResult compile(Path vanFile, Path basePath) throws IOException {
        String key = vanFile.toAbsolutePath().toString();
        long mtime = Files.getLastModifiedTime(vanFile).toMillis();

        CacheEntry entry = cache.get(key);
        if (entry != null && entry.mtime == mtime) {
            return entry.result;
        }

        // Read the entry file and resolve imports
        Map<String, String> files = readVanFiles(vanFile, basePath);
        String entryPath = basePath.relativize(vanFile.toAbsolutePath()).toString()
                .replace(File.separatorChar, '/');

        CompiledResult result = doCompile(entryPath, files);
        cache.put(key, new CacheEntry(result, mtime));
        return result;
    }

    /**
     * Compile with an explicit files map (for classpath resources).
     */
    public CompiledResult compile(String entryPath, Map<String, String> files) throws IOException {
        return doCompile(entryPath, files);
    }

    private synchronized CompiledResult doCompile(String entryPath, Map<String, String> files) throws IOException {
        ensureProcessAlive();

        Map<String, Object> request = new HashMap<>();
        request.put("entry_path", entryPath);
        request.put("files", files);
        request.put("mock_data_json", "{}");

        String jsonLine = objectMapper.writeValueAsString(request);
        processStdin.write(jsonLine);
        processStdin.newLine();
        processStdin.flush();

        String responseLine = processStdout.readLine();
        if (responseLine == null) {
            throw new IOException("van-compiler daemon terminated unexpectedly");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(responseLine, Map.class);
        boolean ok = Boolean.TRUE.equals(response.get("ok"));
        if (!ok) {
            String error = (String) response.get("error");
            throw new IOException("van-compiler error: " + error);
        }

        String html = (String) response.get("html");
        @SuppressWarnings("unchecked")
        Map<String, String> assets = (Map<String, String>) response.get("assets");
        return new CompiledResult(html, assets);
    }

    private void ensureProcessAlive() throws IOException {
        if (process == null || !process.isAlive()) {
            log.warn("van-compiler daemon is not running, restarting...");
            try {
                init();
            } catch (Exception e) {
                throw new IOException("Failed to restart van-compiler daemon", e);
            }
        }
    }

    private Map<String, String> readVanFiles(Path entryFile, Path basePath) throws IOException {
        Map<String, String> files = new HashMap<>();
        collectVanFiles(entryFile, basePath, files);
        return files;
    }

    private void collectVanFiles(Path file, Path basePath, Map<String, String> files) throws IOException {
        String relativePath = basePath.relativize(file.toAbsolutePath()).toString()
                .replace(File.separatorChar, '/');
        if (files.containsKey(relativePath)) return;

        String content = Files.readString(file, StandardCharsets.UTF_8);
        files.put(relativePath, content);

        // Parse import paths from the content and recursively collect
        for (String importPath : parseImportPaths(content)) {
            Path importFile = file.getParent().resolve(importPath).normalize();
            if (Files.isRegularFile(importFile)) {
                collectVanFiles(importFile, basePath, files);
            }
        }
    }

    /**
     * Extract component import paths from .van file content.
     * Matches: import XxxYyy from './path.van' or "../path.van"
     */
    public static java.util.List<String> parseImportPaths(String content) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        // Match <script setup> block content
        int scriptStart = content.indexOf("<script setup>");
        if (scriptStart == -1) return paths;
        int scriptEnd = content.indexOf("</script>", scriptStart);
        if (scriptEnd == -1) return paths;
        String script = content.substring(scriptStart + "<script setup>".length(), scriptEnd);

        // Match import statements with .van paths
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "import\\s+\\w+\\s+from\\s+['\"]([^'\"]+\\.van)['\"]");
        java.util.regex.Matcher matcher = pattern.matcher(script);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    record CacheEntry(CompiledResult result, long mtime) {
    }

    public record CompiledResult(String html, Map<String, String> assets) {
    }
}
