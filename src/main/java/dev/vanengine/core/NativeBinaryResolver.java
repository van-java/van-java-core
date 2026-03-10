package dev.vanengine.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Properties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Resolves the van-compiler-wasi native binary for the current platform.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>{@code VAN_COMPILER_PATH} environment variable (offline / custom builds)</li>
 *   <li>{@code ~/.van/bin/van-compiler-wasi-{version}{suffix}} (local cache)</li>
 *   <li>{@code van-compiler-wasi} on {@code PATH}</li>
 *   <li>Auto-download from GitHub Release to {@code ~/.van/bin/} (Playwright mode)</li>
 * </ol>
 */
public class NativeBinaryResolver {

    private static final Logger log = LoggerFactory.getLogger(NativeBinaryResolver.class);

    private static final String VERSION = loadVersion();
    private static final String RELEASE_VERSION = VERSION.replaceFirst("-SNAPSHOT$", "");
    private static final String GITHUB_RELEASE_BASE =
            "https://github.com/vanengine/van/releases/download/v" + RELEASE_VERSION + "/";

    private static String loadVersion() {
        try (InputStream in = NativeBinaryResolver.class.getResourceAsStream("/van-core.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                return props.getProperty("van.compiler.version", "0.1.16");
            }
        } catch (IOException ignored) {
        }
        return "0.1.16";
    }

    public Path resolve() {
        // 1. Environment variable
        String envPath = System.getenv("VAN_COMPILER_PATH");
        if (envPath != null && !envPath.isBlank()) {
            Path p = Path.of(envPath);
            if (Files.isExecutable(p)) {
                log.info("Using van-compiler from VAN_COMPILER_PATH: {}", p);
                return p;
            }
            throw new IllegalStateException(
                    "VAN_COMPILER_PATH is set to " + envPath + " but file is not executable or does not exist");
        }

        // 2. Local cache
        String suffix = platformSuffix();
        Path cached = Path.of(System.getProperty("user.home"), ".van", "bin",
                "van-compiler-wasi-" + VERSION + suffix);
        if (Files.isExecutable(cached)) {
            log.debug("Using cached van-compiler: {}", cached);
            return cached;
        }

        // 3. PATH lookup
        Path onPath = findOnPath();
        if (onPath != null) {
            log.info("Using van-compiler from PATH: {}", onPath);
            return onPath;
        }

        // 4. Download from GitHub Release
        String artifactName = artifactName();
        String url = GITHUB_RELEASE_BASE + artifactName;
        log.info("Downloading van-compiler {} for {} from {} to {}", VERSION, platformDescription(), url, cached);
        download(url, cached);
        log.info("van-compiler cached at {}", cached);
        return cached;
    }

    private Path findOnPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        boolean windows = isWindows();
        String binaryName = windows ? "van-compiler-wasi.exe" : "van-compiler-wasi";
        for (String dir : pathEnv.split(System.getProperty("path.separator"))) {
            Path candidate = Path.of(dir, binaryName);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void download(String url, Path target) {
        int maxRetries = 3;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Files.createDirectories(target.getParent());
                HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL);
                ProxySelector proxySelector = detectProxy();
                if (proxySelector != null) {
                    clientBuilder.proxy(proxySelector);
                }
                HttpClient client = clientBuilder.build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 404) {
                    throw new IOException("HTTP 404 — release artifact not found. "
                            + "Check that v" + RELEASE_VERSION + " has been published at "
                            + GITHUB_RELEASE_BASE);
                }
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                try (InputStream in = response.body()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!isWindows()) {
                    target.toFile().setExecutable(true);
                }
                return;
            } catch (IOException | InterruptedException e) {
                lastException = e;
                if (attempt < maxRetries) {
                    log.warn("Download attempt {}/{} failed ({}), retrying...", attempt, maxRetries, e.getMessage());
                }
            }
        }
        String manual = "You can manually download the binary and set VAN_COMPILER_PATH:\n"
                + "  1. Download: " + url + "\n"
                + "  2. Place it at: " + target + "\n"
                + "  3. Or set environment variable: VAN_COMPILER_PATH=/path/to/van-compiler-wasi";
        throw new IllegalStateException(
                "Failed to download van-compiler after " + maxRetries + " attempts from " + url
                        + "\nCause: " + lastException.getMessage()
                        + "\n\n" + manual, lastException);
    }

    private static String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) return "linux";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("win")) return "windows";
        throw new UnsupportedOperationException("Unsupported OS: " + os);
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) return "x64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        throw new UnsupportedOperationException("Unsupported architecture: " + arch);
    }

    private static boolean isWindows() {
        return "windows".equals(detectOs());
    }

    private static String platformSuffix() {
        return isWindows() ? ".exe" : "";
    }

    private static String artifactName() {
        String os = detectOs();
        String arch = detectArch();
        String name = "van-compiler-" + os + "-" + arch;
        if (isWindows()) name += ".exe";
        return name;
    }

    private static String platformDescription() {
        return detectOs() + "-" + detectArch();
    }

    private static ProxySelector detectProxy() {
        String proxyUrl = System.getenv("https_proxy");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("HTTPS_PROXY");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("http_proxy");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("HTTP_PROXY");
        if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("all_proxy");
        if (proxyUrl == null || proxyUrl.isBlank()) return null;
        try {
            URI uri = URI.create(proxyUrl);
            log.debug("Using proxy: {}", proxyUrl);
            return ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort()));
        } catch (Exception e) {
            log.warn("Failed to parse proxy URL '{}', ignoring: {}", proxyUrl, e.getMessage());
            return null;
        }
    }
}
