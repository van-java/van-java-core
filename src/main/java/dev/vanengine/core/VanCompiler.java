package dev.vanengine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vanengine.core.compile.VanParser;
import dev.vanengine.core.compile.VanResolver;
import dev.vanengine.core.compile.VanSignalGen;
import dev.vanengine.core.runtime.VanRuntime;
import dev.vanengine.core.support.VanAst;
import dev.vanengine.core.support.VanUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * <p>Pipeline: VanParser → VanResolver → render (with VanSignalGen for client JS).
 *
 * <p>Lifecycle: no {@code init()} or {@code close()} needed — pure Java, no subprocess.</p>
 */
public class VanCompiler {

    // ────────────────────────────────────────────────────────────────

    private static final Logger log = LoggerFactory.getLogger(VanCompiler.class);
    private static final ObjectMapper MAPPER = VanUtil.MAPPER;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private String globalName = "Van";

    public void setGlobalName(String globalName) {
        this.globalName = globalName;
    }

    public String getGlobalName() {
        return globalName;
    }


    /**
     * Compile a .van file, returning cached results when no file in the dependency tree has changed.
     *
     * @param vanFile  path to the .van entry file
     * @param basePath base directory for resolving relative imports
     * @return compiled HTML string
     */
    public CompiledResult compile(Path vanFile, Path basePath) throws IOException {
        String key = vanFile.toAbsolutePath().toString();

        // Fast path: check cached file set mtimes (stat only, no reads)
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            Map<String, Long> current = checkMtimes(basePath, entry.fileMtimes);
            if (current != null && current.equals(entry.fileMtimes)) {
                return entry.result;
            }
        }

        // Cache miss — read all files and compile
        Map<String, Long> mtimes = new LinkedHashMap<>();
        Map<String, String> files = readVanFiles(vanFile, basePath, mtimes);
        String entryPath = basePath.relativize(vanFile.toAbsolutePath()).toString()
                .replace(File.separatorChar, '/');

        CompiledResult result = doCompile(entryPath, files);
        cache.put(key, new CacheEntry(result, mtimes));
        return result;
    }

    /**
     * Compile with an explicit files map (for classpath resources or in-memory).
     */
    public CompiledResult compile(String entryPath, Map<String, String> files) {
        return doCompile(entryPath, files);
    }

    /**
     * Render with data — compiles and binds data into final HTML.
     */
    public String renderToString(String entryPath, Map<String, String> files, String dataJson) {
        JsonNode data = parseJson(dataJson);
        ResolvedComponent resolved = VanResolver.resolveWithFiles(entryPath, files, data);
        return renderToString(resolved, data, globalName);
    }

    private CompiledResult doCompile(String entryPath, Map<String, String> files) {
        // Compile mode: empty data → preserve v-for/v-if/:class/{{ }} for Java SSR
        JsonNode emptyData = MAPPER.createObjectNode();
        ResolvedComponent resolved = VanResolver.resolveWithFiles(entryPath, files, emptyData);
        String html = buildPage(resolved, globalName);
        return new CompiledResult(html, null);
    }

    private static JsonNode parseJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new VanTemplateException("Invalid render data JSON: " + e.getMessage(), null, e);
        }
    }

    // ─── File collection ────────────────────────────────────────────

    private Map<String, String> readVanFiles(Path entryFile, Path basePath,
                                              Map<String, Long> mtimes) throws IOException {
        Map<String, String> files = new HashMap<>();
        collectVanFiles(entryFile, basePath, files, mtimes);
        return files;
    }

    private void collectVanFiles(Path file, Path basePath, Map<String, String> files,
                                  Map<String, Long> mtimes) throws IOException {
        String relativePath = basePath.relativize(file.toAbsolutePath()).toString()
                .replace(File.separatorChar, '/');
        if (files.containsKey(relativePath)) return;

        mtimes.put(relativePath, Files.getLastModifiedTime(file).toMillis());
        String content = Files.readString(file, StandardCharsets.UTF_8);
        files.put(relativePath, content);

        for (String importPath : parseImportPaths(content)) {
            Path importFile = file.getParent().resolve(importPath).normalize();
            if (Files.isRegularFile(importFile)) {
                collectVanFiles(importFile, basePath, files, mtimes);
            }
        }
    }

    /**
     * Stat-only check: return current mtimes for the cached file set,
     * or null if any file was deleted.
     */
    private static Map<String, Long> checkMtimes(Path basePath, Map<String, Long> cached) {
        Map<String, Long> current = new LinkedHashMap<>();
        for (String relPath : cached.keySet()) {
            Path file = basePath.resolve(relPath);
            if (!Files.isRegularFile(file)) return null;
            try {
                current.put(relPath, Files.getLastModifiedTime(file).toMillis());
            } catch (IOException e) {
                return null;
            }
        }
        return current;
    }

    /**
     * Extract component import paths from .van file content.
     */
    public static List<String> parseImportPaths(String content) {
        VanParser.VanBlock blocks = VanParser.parseBlocks(content);
        if (blocks.scriptSetup() == null) return List.of();
        return VanParser.parseImports(blocks.scriptSetup()).stream()
                .map(VanParser.VanImport::path)
                .filter(p -> !p.startsWith("@")) // skip scoped packages for file collection
                .toList();
    }

    record CacheEntry(CompiledResult result, Map<String, Long> fileMtimes) {
    }

    public record CompiledResult(String html, Map<String, String> assets) {
    }



    // ═══════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════


    // ─── Compile mode (no data — template for Java SSR) ─────────────

    /**
     * Build a full HTML page from a resolved component, with signal JS and comment anchors.
     * Model bindings (v-for, v-if, :class, {{ }}) are preserved for Java SSR.
     */
    static String buildPage(ResolvedComponent resolved, String globalName) {
        String styleBlock = buildStyleBlock(resolved.styles());

        List<String> moduleCodes = resolved.moduleImports().stream()
                .filter(m -> !m.isTypeOnly())
                .map(ResolvedModule::content)
                .toList();

        VanSignalGen.ScriptAnalysis analysis = resolved.scriptSetup() != null
                ? VanSignalGen.analyzeScript(resolved.scriptSetup()) : null;

        List<String> reactiveNames = analysis != null
                ? VanSignalGen.collectReactiveNamesList(analysis) : List.of();
        String[] reactiveRefs = reactiveNames.toArray(String[]::new);

        // Walk template once — reuse for signal JS and comment injection
        VanSignalGen.TemplateBindings bindings = VanSignalGen.walkTemplate(resolved.html(), reactiveRefs);

        // Compute binding paths once — reuse for signal JS and comment injection
        List<List<Integer>> bindingPaths = VanSignalGen.collectBindingPaths(bindings);

        // Generate signal JS using pre-computed analysis and bindings (no re-parsing)
        String signalScripts = "";
        if (analysis != null && !bindingPaths.isEmpty()) {
            String signalJs = VanSignalGen.generateSignalsCommentFrom(
                    analysis, bindings, moduleCodes, globalName);
            if (signalJs != null) {
                String runtime = VanSignalGen.runtimeJs(globalName);
                signalScripts = "<script>" + runtime + "</script>\n<script>" + signalJs + "</script>";
            }
        }

        // Inject comment anchors
        String htmlWithComments = resolved.html();
        if (!bindingPaths.isEmpty()) {
            htmlWithComments = VanSignalGen.injectSignalComments(resolved.html(), bindingPaths).getKey();
        }

        // Signal initial values from already-analyzed script
        Map<String, String> signalInitialValues = analysis != null
                ? extractInitialValuesFromAnalysis(analysis) : Map.of();

        // Smart cleanup
        String cleanHtml = cleanupHtmlCompileSmart(htmlWithComments, reactiveNames);
        cleanHtml = interpolateSignalsOnly(cleanHtml, signalInitialValues);

        return wrapInHtmlShell(cleanHtml, styleBlock, signalScripts);
    }

    /**
     * Compile with separated assets (CSS/JS as external files).
     */
    static CompiledResult compileAssets(ResolvedComponent resolved, String pageName,
                                        String assetPrefix, String globalName) {
        Map<String, String> assets = new LinkedHashMap<>();

        String cssRef = "";
        if (!resolved.styles().isEmpty()) {
            String cssContent = String.join("\n", resolved.styles());
            String hash = VanParser.scopeId(cssContent);
            String cssPath = assetPrefix + "/css/" + pageName + "." + hash + ".css";
            assets.put(cssPath, cssContent);
            cssRef = "<link rel=\"stylesheet\" href=\"" + cssPath + "\">";
        }

        List<String> moduleCodes = resolved.moduleImports().stream()
                .filter(m -> !m.isTypeOnly())
                .map(ResolvedModule::content)
                .toList();

        String jsRef = "";
        if (resolved.scriptSetup() != null) {
            VanSignalGen.ScriptAnalysis analysis = VanSignalGen.analyzeScript(resolved.scriptSetup());
            if (!analysis.signals().isEmpty() || !analysis.computeds().isEmpty()) {
                String[] reactiveRefs = VanSignalGen.collectReactiveNamesList(analysis).toArray(String[]::new);
                VanSignalGen.TemplateBindings bindings = VanSignalGen.walkTemplate(resolved.html(), reactiveRefs);
                String signalJs = VanSignalGen.generateSignalsCommentFrom(analysis, bindings, moduleCodes, globalName);
                if (signalJs != null) {
                    String runtime = VanSignalGen.runtimeJs(globalName);
                    String runtimeHash = VanParser.scopeId(runtime);
                    String runtimePath = assetPrefix + "/js/van-runtime." + runtimeHash + ".js";
                    String jsHash = VanParser.scopeId(signalJs);
                    String jsPath = assetPrefix + "/js/" + pageName + "." + jsHash + ".js";
                    assets.put(runtimePath, runtime);
                    assets.put(jsPath, signalJs);
                    jsRef = "<script src=\"" + runtimePath + "\"></script>\n<script src=\"" + jsPath + "\"></script>";
                }
            }
        }

        String cleanHtml = cleanupHtmlCompileSmart(resolved.html(), List.of());
        String html = wrapInHtmlShell(cleanHtml, cssRef, jsRef);
        return new CompiledResult(html, assets);
    }

    // ─── Render mode (with data — final HTML) ───────────────────────

    static String renderToString(ResolvedComponent resolved, JsonNode data, String globalName) {
        String compiled = buildPage(resolved, globalName);
        Map<String, Object> scope = jsonNodeToMap(data);
        return VanRuntime.processAll(compiled, scope);
    }

    /**
     * Process remaining SSR directives (v-show, v-if, :class, :style, v-html, v-text, {{ }})
     * against the provided data. Delegates to VanRuntime for full expression evaluation.
     */
    static String fillData(String compiledHtml, JsonNode data) {
        Map<String, Object> scope = jsonNodeToMap(data);
        return VanRuntime.processAll(compiledHtml, scope);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return new HashMap<>();
        return MAPPER.convertValue(node, Map.class);
    }

    // ─── Smart cleanup (compile mode, AST-based) ─────────────────────

    private static String cleanupHtmlCompileSmart(String html, List<String> reactiveNames) {
        List<VanAst.Node> nodes = VanAst.parse(html);
        cleanupNodesCompile(nodes, reactiveNames);
        return VanAst.toHtml(nodes);
    }

    private static void cleanupNodesCompile(List<VanAst.Node> nodes, List<String> reactiveNames) {
        int i = 0;
        while (i < nodes.size()) {
            VanAst.Node node = nodes.get(i);
            if (node instanceof VanAst.Node.Element elem) {
                String tagLower = elem.tag().toLowerCase();
                // Remove <Transition> / <transition> / </Transition> wrapper elements
                if ("transition".equals(tagLower) || "transition-group".equals(tagLower)) {
                    nodes.remove(i);
                    // Insert children in place
                    nodes.addAll(i, elem.children());
                    continue;
                }

                // Strip client-only attributes; for reactive v-show, set display:none default
                boolean addDisplayNone = false;
                elem.attrs().removeIf(a -> {
                    String name = a.name();
                    if (name.startsWith("@")) return true;
                    if (name.equals("v-model") || name.startsWith("v-model.")) return true;
                    if (a.value() != null && VanUtil.containsAnyWholeWord(a.value(), reactiveNames)) {
                        return name.equals("v-if") || name.equals(":class") || name.equals(":style");
                    }
                    return false;
                });
                // Reactive v-show: strip attr and add display:none (signal JS will toggle)
                String vshow = elem.getAttr("v-show");
                if (vshow != null && VanUtil.containsAnyWholeWord(vshow, reactiveNames)) {
                    elem.removeAttr("v-show");
                    elem.setAttr("style", "display:none");
                }

                cleanupNodesCompile(elem.children(), reactiveNames);
                i++;
            } else {
                i++;
            }
        }
    }

    private static String interpolateSignalsOnly(String html, Map<String, String> initialValues) {
        if (initialValues.isEmpty()) return html;
        return VanUtil.replaceAll(VanUtil.MUSTACHE, html, m -> {
            String val = initialValues.get(m.group(1).trim());
            return val != null ? val : m.group(0);
        });
    }

    // ─── HTML shell ─────────────────────────────────────────────────

    private static String wrapInHtmlShell(String content, String headContent, String bodyEndContent) {
        if (content.contains("<html")) {
            String html = content;
            html = injectBeforeClose(html, "</head>", headContent);
            html = injectBeforeClose(html, "</body>", bodyEndContent);
            return html;
        }
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\" />\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n"
                + "<title>Van App</title>\n" + headContent + "\n</head>\n<body>\n"
                + content + "\n" + bodyEndContent + "\n</body>\n</html>";
    }

    private static String injectBeforeClose(String html, String closeTag, String content) {
        if (content == null || content.isEmpty()) return html;
        int pos = html.indexOf(closeTag);
        if (pos < 0) return html;
        return html.substring(0, pos) + content + "\n" + html.substring(pos);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static String buildStyleBlock(List<String> styles) {
        if (styles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String css : styles) {
            sb.append("<style>").append(css).append("</style>\n");
        }
        return sb.toString().trim();
    }

    private static Map<String, String> extractInitialValuesFromAnalysis(VanSignalGen.ScriptAnalysis analysis) {
        Map<String, String> values = new LinkedHashMap<>();
        for (var s : analysis.signals()) {
            values.put(s.name(), VanSignalGen.jsLiteralToDisplay(s.initialValue()));
        }
        return values;
    }
}
