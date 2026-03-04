package dev.vanengine.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class VanEngine {

    private static final Logger log = LoggerFactory.getLogger(VanEngine.class);

    private final VanCompiler compiler;
    private final List<I18nFileParser> fileParsers = new CopyOnWriteArrayList<>();
    private Path basePath;
    private String defaultLocale = "en";
    private MissingKeyStrategy missingKeyStrategy = MissingKeyStrategy.RETURN_KEY;

    // locale (lower-cased) → raw messages from single file
    private final Map<String, Map<String, Object>> i18nMessages = new ConcurrentHashMap<>();
    // locale (lower-cased) → merged messages (with fallback chain applied)
    private final Map<String, Map<String, Object>> mergedCache = new ConcurrentHashMap<>();

    public VanEngine(VanCompiler compiler) {
        this.compiler = compiler;
        this.fileParsers.add(new JsonI18nFileParser());
    }

    public void setBasePath(Path basePath) {
        this.basePath = basePath;
    }

    public void setDefaultLocale(String defaultLocale) {
        this.defaultLocale = defaultLocale;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public void setMissingKeyStrategy(MissingKeyStrategy missingKeyStrategy) {
        this.missingKeyStrategy = missingKeyStrategy;
    }

    public MissingKeyStrategy getMissingKeyStrategy() {
        return missingKeyStrategy;
    }

    public void setGlobalName(String globalName) {
        this.compiler.setGlobalName(globalName);
    }

    /**
     * Register translation messages for a locale.
     */
    public void addI18nMessages(String locale, Map<String, Object> messages) {
        i18nMessages.put(locale.toLowerCase(), messages);
        mergedCache.clear();
    }

    /**
     * Register an additional i18n file parser (e.g. YAML).
     */
    public void addI18nFileParser(I18nFileParser parser) {
        fileParsers.add(parser);
    }

    /**
     * Return all file extensions currently supported by registered parsers.
     */
    public Set<String> supportedI18nExtensions() {
        Set<String> extensions = new LinkedHashSet<>();
        for (I18nFileParser parser : fileParsers) {
            extensions.addAll(parser.supportedExtensions());
        }
        return extensions;
    }

    /**
     * Load i18n messages from a stream. Used by starter for classpath loading.
     */
    public void loadI18nFromStream(String locale, String extension, InputStream inputStream) throws IOException {
        I18nFileParser parser = findParser(extension);
        if (parser == null) {
            log.warn("No i18n parser registered for extension '{}'", extension);
            return;
        }
        Map<String, Object> messages = parser.parse(inputStream);
        String normalizedLocale = locale.toLowerCase();
        if (i18nMessages.containsKey(normalizedLocale)) {
            log.warn("Locale '{}' already loaded, overwriting with .{} file", normalizedLocale, extension);
        }
        i18nMessages.put(normalizedLocale, messages);
        mergedCache.clear();
        log.debug("Loaded i18n messages for locale '{}' from .{} stream", normalizedLocale, extension);
    }

    private I18nFileParser findParser(String extension) {
        String ext = extension.toLowerCase();
        for (I18nFileParser parser : fileParsers) {
            if (parser.supportedExtensions().contains(ext)) {
                return parser;
            }
        }
        return null;
    }

    /**
     * Get translation messages for the given locale with key-level merge fallback.
     * E.g. for zh-CN: deep-merge en + zh + zh-cn (highest priority last).
     */
    public Map<String, Object> getI18nMessages(String locale) {
        if (locale == null) {
            return i18nMessages.getOrDefault(defaultLocale.toLowerCase(), Map.of());
        }
        String normalized = locale.toLowerCase();
        return mergedCache.computeIfAbsent(normalized, this::buildMergedMessages);
    }

    private Map<String, Object> buildMergedMessages(String normalized) {
        List<String> chain = buildFallbackChain(normalized);
        Map<String, Object> result = new HashMap<>();
        // Iterate from lowest priority to highest, so higher priority overwrites
        for (int i = chain.size() - 1; i >= 0; i--) {
            Map<String, Object> msgs = i18nMessages.get(chain.get(i));
            if (msgs != null) {
                deepMerge(result, msgs);
            }
        }
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(result);
    }

    /**
     * Build fallback chain: [zh-cn, zh, en] (highest priority first).
     */
    private List<String> buildFallbackChain(String normalized) {
        List<String> chain = new ArrayList<>();
        String current = normalized;
        while (!current.isEmpty()) {
            chain.add(current);
            int lastDash = current.lastIndexOf('-');
            current = (lastDash > 0) ? current.substring(0, lastDash) : "";
        }
        String defKey = defaultLocale.toLowerCase();
        if (!chain.contains(defKey)) {
            chain.add(defKey);
        }
        return chain;
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            String key = entry.getKey();
            Object overlayValue = entry.getValue();
            Object baseValue = base.get(key);
            if (baseValue instanceof Map<?, ?> baseMap && overlayValue instanceof Map<?, ?> overlayMap) {
                Map<String, Object> merged = new HashMap<>((Map<String, Object>) baseMap);
                deepMerge(merged, (Map<String, Object>) overlayMap);
                base.put(key, merged);
            } else {
                base.put(key, overlayValue);
            }
        }
    }

    /**
     * Whether any i18n messages have been loaded.
     */
    public boolean hasI18nMessages() {
        return !i18nMessages.isEmpty();
    }

    public Path getBasePath() {
        return basePath;
    }

    /**
     * Scan an i18n directory for translation files and load them.
     * Supports all extensions registered via {@link #addI18nFileParser(I18nFileParser)}.
     */
    public void loadI18nFiles(Path i18nDir) {
        if (i18nDir == null || !Files.isDirectory(i18nDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(i18nDir)) {
            for (Path file : stream) {
                if (Files.isDirectory(file)) continue;
                String fileName = file.getFileName().toString();
                int dotIndex = fileName.lastIndexOf('.');
                if (dotIndex <= 0) continue;
                String extension = fileName.substring(dotIndex + 1);
                I18nFileParser parser = findParser(extension);
                if (parser == null) continue;
                String locale = fileName.substring(0, dotIndex);
                String normalizedLocale = locale.toLowerCase();
                if (i18nMessages.containsKey(normalizedLocale)) {
                    log.warn("Locale '{}' has multiple i18n files, overwriting with {}", normalizedLocale, fileName);
                }
                try (InputStream is = Files.newInputStream(file)) {
                    Map<String, Object> messages = parser.parse(is);
                    i18nMessages.put(normalizedLocale, messages);
                    log.debug("Loaded i18n messages for locale '{}' from {}", locale, file);
                } catch (IOException e) {
                    log.warn("Failed to load i18n file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan i18n directory {}: {}", i18nDir, e.getMessage());
        }
    }

    /**
     * Clear and reload all i18n files from the basePath/i18n directory.
     */
    public void reloadI18nFiles() {
        if (basePath == null) return;
        Path i18nDir = basePath.resolve("i18n");
        i18nMessages.clear();
        mergedCache.clear();
        loadI18nFiles(i18nDir);
        log.info("Reloaded i18n files from {}", i18nDir);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Path basePath;
        private String defaultLocale = "en";
        private MissingKeyStrategy missingKeyStrategy = MissingKeyStrategy.RETURN_KEY;
        private String globalName;

        private Builder() {}

        public Builder basePath(Path basePath) {
            this.basePath = basePath;
            return this;
        }

        public Builder defaultLocale(String defaultLocale) {
            this.defaultLocale = defaultLocale;
            return this;
        }

        public Builder missingKeyStrategy(MissingKeyStrategy missingKeyStrategy) {
            this.missingKeyStrategy = missingKeyStrategy;
            return this;
        }

        public Builder globalName(String globalName) {
            this.globalName = globalName;
            return this;
        }

        public VanEngine build() {
            try {
                VanCompiler compiler = new VanCompiler();
                compiler.init();
                if (globalName != null) {
                    compiler.setGlobalName(globalName);
                }
                VanEngine engine = new VanEngine(compiler);
                engine.setDefaultLocale(defaultLocale);
                engine.setMissingKeyStrategy(missingKeyStrategy);
                if (basePath != null) {
                    engine.setBasePath(basePath);
                    engine.loadI18nFiles(basePath.resolve("i18n"));
                }
                return engine;
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize VanCompiler", e);
            }
        }
    }

    /**
     * Compile a .van file from the filesystem and return a reusable template.
     */
    public VanTemplate getTemplate(String templatePath) throws IOException {
        Path vanFile = basePath.resolve(templatePath);
        String html = compiler.compile(vanFile, basePath).html();
        return new VanTemplate(html, this);
    }

    /**
     * Compile from an explicit files map (for classpath resources) and return a reusable template.
     */
    public VanTemplate getTemplate(String entryPath, Map<String, String> files) throws IOException {
        String html = compiler.compile(entryPath, files).html();
        return new VanTemplate(html, this);
    }

    /**
     * Compile an inline template string and return a reusable template.
     */
    public VanTemplate getLiteralTemplate(String templateContent) throws IOException {
        String html = compiler.compile("literal.van", Map.of("literal.van", templateContent)).html();
        return new VanTemplate(html, this);
    }

    // ── i18n programmatic API ──

    /**
     * Get a translated message by dot-separated key, with {placeholder} replacement.
     * Returns the formatted message, or the key itself if not found.
     */
    public String getMessage(String key, String locale, Map<String, ?> params) {
        Map<String, Object> messages = getI18nMessages(locale);
        Object value = resolveNestedKey(messages, key);
        if (value == null) {
            return handleMissingKey(key, locale);
        }
        return MessageFormatter.format(value.toString(), params);
    }

    private String handleMissingKey(String key, String locale) {
        return switch (missingKeyStrategy) {
            case RETURN_KEY -> key;
            case WARN_AND_RETURN_KEY -> {
                log.warn("Missing i18n key '{}' for locale '{}'", key, locale);
                yield key;
            }
            case THROW_EXCEPTION -> throw new MissingTranslationException(key, locale);
            case RETURN_PLACEHOLDER -> "[missing: " + key + "]";
        };
    }

    /**
     * Get a translated message by dot-separated key (no parameters).
     */
    public String getMessage(String key, String locale) {
        return getMessage(key, locale, null);
    }

    @SuppressWarnings("unchecked")
    private Object resolveNestedKey(Map<String, Object> messages, String key) {
        String[] parts = key.split("\\.");
        Object current = messages;
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    // ── i18n diagnostics ──

    /**
     * Find keys present in defaultLocale but missing in other locales.
     * Returns a map of locale → set of missing dot-separated keys.
     */
    public Map<String, Set<String>> findMissingKeys() {
        String defKey = defaultLocale.toLowerCase();
        Map<String, Object> defaultMessages = i18nMessages.get(defKey);
        if (defaultMessages == null) return Map.of();

        Set<String> referenceKeys = new LinkedHashSet<>();
        flattenKeys(defaultMessages, "", referenceKeys);

        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : i18nMessages.entrySet()) {
            if (entry.getKey().equals(defKey)) continue;
            Set<String> localeKeys = new LinkedHashSet<>();
            flattenKeys(entry.getValue(), "", localeKeys);

            Set<String> missing = new LinkedHashSet<>(referenceKeys);
            missing.removeAll(localeKeys);
            if (!missing.isEmpty()) {
                result.put(entry.getKey(), missing);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void flattenKeys(Map<String, Object> map, String prefix, Set<String> keys) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String fullKey = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flattenKeys((Map<String, Object>) nested, fullKey, keys);
            } else {
                keys.add(fullKey);
            }
        }
    }

    // ── convenience methods (対標 TwigRenderEngine) ──

    /**
     * Compile a .van file and evaluate with model data in one step.
     */
    public String compile(String templatePath, Map<String, ?> model) throws IOException {
        return getTemplate(templatePath).evaluate(model);
    }

    /**
     * Compile an inline template and evaluate with model data in one step.
     */
    public String compileLiteral(String templateContent, Map<String, ?> model) throws IOException {
        return getLiteralTemplate(templateContent).evaluate(model);
    }
}
