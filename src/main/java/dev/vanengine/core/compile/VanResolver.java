package dev.vanengine.core.compile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vanengine.core.ResolvedComponent;
import dev.vanengine.core.ResolvedModule;
import dev.vanengine.core.VanTemplateException;
import dev.vanengine.core.support.VanUtil;

import dev.vanengine.core.support.VanAst;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code .van} component imports, slots, scoped styles, and interpolation.
 */
public final class VanResolver {

    private VanResolver() {}

    private static final int MAX_DEPTH = 10;
    private static final ObjectMapper MAPPER = VanUtil.MAPPER;

    // ─── Immutable resolve context ─────────────────────────────────

    /**
     * Holds the six parameters that are passed unchanged through every
     * recursive call: files, blockCache, compile, reactiveNames, debug,
     * fileOrigins.
     */
    private record ResolveContext(Map<String, String> files, Map<String, VanParser.VanBlock> blockCache,
                                   boolean compile, List<String> reactiveNames,
                                   boolean debug, Map<String, String> fileOrigins) {}

    // ─── Public API ─────────────────────────────────────────────────

    /**
     * Resolve a .van entry file with all component imports from an in-memory file map.
     */
    public static ResolvedComponent resolveWithFiles(
            String entryPath, Map<String, String> files, JsonNode data) {
        return resolveWithFilesInner(entryPath, files, data, false, Map.of());
    }

    /**
     * Like resolveWithFiles, but with debug HTML comments.
     */
    public static ResolvedComponent resolveWithFilesDebug(
            String entryPath, Map<String, String> files, JsonNode data,
            Map<String, String> fileOrigins) {
        return resolveWithFilesInner(entryPath, files, data, true, fileOrigins);
    }

    static ResolvedComponent resolveSingle(String source, JsonNode data) {
        VanParser.VanBlock blocks = VanParser.parseBlocks(source);
        String template = blocks.template() != null
                ? blocks.template() : "<p>No template block found.</p>";

        List<String> styles = new ArrayList<>();
        if (blocks.style() != null) {
            if (blocks.styleScoped()) {
                String id = VanParser.scopeId(blocks.style());
                template = VanParser.addScopeClass(template, id);
                styles.add(VanParser.scopeCss(blocks.style(), id));
            } else {
                styles.add(blocks.style());
            }
        }

        List<String> reactiveNames = blocks.scriptSetup() != null
                ? VanSignalGen.collectReactiveNamesList(VanSignalGen.analyzeScript(blocks.scriptSetup())) : List.of();

        String html = !reactiveNames.isEmpty()
                ? interpolateSkipReactive(template, data, reactiveNames)
                : interpolate(template, data);

        return new ResolvedComponent(html, styles, blocks.scriptSetup(), List.of());
    }

    // ─── Internal resolve ───────────────────────────────────────────

    private static ResolvedComponent resolveWithFilesInner(
            String entryPath, Map<String, String> files, JsonNode data,
            boolean debug, Map<String, String> fileOrigins) {
        String source = files.get(entryPath);
        if (source == null) {
            throw new VanTemplateException("Entry file not found", entryPath);
        }

        boolean compile = isEmptyObject(data);

        Map<String, VanParser.VanBlock> blockCache = new HashMap<>();
        List<String> reactiveNames = new ArrayList<>();
        for (var entry : files.entrySet()) {
            if (entry.getKey().endsWith(".van")) {
                VanParser.VanBlock blk = VanParser.parseBlocks(entry.getValue());
                blockCache.put(entry.getKey(), blk);
                if (blk.scriptSetup() != null) {
                    reactiveNames.addAll(VanSignalGen.collectReactiveNamesList(VanSignalGen.analyzeScript(blk.scriptSetup())));
                }
            }
        }

        // ── Compile-time validation ──
        validateImports(blockCache, files);

        ResolveContext ctx = new ResolveContext(files, blockCache, compile,
                reactiveNames, debug, fileOrigins);

        return resolveRecursive(source, data, entryPath, ctx, 0);
    }

    private static ResolvedComponent resolveRecursive(
            String source, JsonNode data, String currentPath,
            ResolveContext ctx, int depth) {
        if (depth > MAX_DEPTH) {
            throw new VanTemplateException(
                    "Component nesting exceeded maximum depth of " + MAX_DEPTH, currentPath);
        }

        VanParser.VanBlock blocks = ctx.blockCache().getOrDefault(currentPath, VanParser.parseBlocks(source));
        String templateStr = blocks.template() != null
                ? blocks.template() : "<p>No template block found.</p>";

        List<String> styles = new ArrayList<>();

        // ── Parse to AST once ──
        List<VanAst.Node> nodes = VanAst.parse(templateStr);

        // Scoped styles on AST
        if (blocks.style() != null) {
            if (blocks.styleScoped()) {
                String id = VanParser.scopeId(blocks.style());
                addScopeClassAst(nodes, id);
                styles.add(VanParser.scopeCss(blocks.style(), id));
            } else {
                styles.add(blocks.style());
            }
        }

        List<VanParser.VanImport> imports = blocks.scriptSetup() != null
                ? VanParser.parseImports(blocks.scriptSetup()) : List.of();
        Map<String, VanParser.VanImport> importMap = new LinkedHashMap<>();
        for (VanParser.VanImport imp : imports) {
            importMap.put(imp.tagName(), imp);
        }

        // v-for on AST (render mode)
        if (!ctx.compile()) {
            expandVForNodes(nodes, data);
        }

        // ClientOnly on AST (compile mode)
        if (ctx.compile()) {
            replaceClientOnlyNodes(nodes);
        }

        // Component resolution on AST
        AstResolveResult tagResult = resolveComponentTagsAst(
                nodes, data, importMap, currentPath, ctx, depth, templateStr);
        styles.addAll(tagResult.styles);

        // Interpolate on AST text nodes
        interpolateAstFull(nodes, data, ctx.reactiveNames());

        // ── Serialize once ──
        String html = VanAst.toHtml(nodes);

        // Merge script_setup
        String scriptSetup = blocks.scriptSetup();
        if (!tagResult.childScripts.isEmpty()) {
            String merged = String.join("\n", tagResult.childScripts);
            scriptSetup = scriptSetup != null ? scriptSetup + "\n" + merged : merged;
        }

        // Resolve module imports
        List<ResolvedModule> moduleImports = new ArrayList<>();
        if (blocks.scriptSetup() != null) {
            for (VanParser.ScriptImport si : VanParser.parseScriptImports(blocks.scriptSetup())) {
                if (si.isTypeOnly()) continue;
                String rk = resolveVirtualPath(currentPath, si.path());
                String content = ctx.files().get(rk);
                if (content != null) {
                    moduleImports.add(new ResolvedModule(rk, content, false));
                }
            }
        }
        moduleImports.addAll(tagResult.childModuleImports);

        return new ResolvedComponent(html, styles, scriptSetup, moduleImports);
    }

    // ─── Component tag resolution (AST-based) ──────────────────────

    /** Result for the string-based entry point (resolveSlotComponents). */
    private record ComponentTagResult(String template, List<String> styles,
                                       List<String> childScripts,
                                       List<ResolvedModule> childModuleImports) {}

    /** Result for the AST-based entry point (resolveRecursive). */
    private record AstResolveResult(List<String> styles, List<String> childScripts,
                                     List<ResolvedModule> childModuleImports) {}

    private record ComponentMatch(VanAst.Node.Element element, List<VanAst.Node> parentChildren,
                                   int index, String tagName) {}

    /** String entry point — used by resolveSlotComponents. */
    private static ComponentTagResult resolveComponentTags(
            String template, JsonNode data, Map<String, VanParser.VanImport> importMap,
            String currentPath, ResolveContext ctx, int depth) {
        List<VanAst.Node> nodes = VanAst.parse(template);
        AstResolveResult r = resolveComponentTagsAst(nodes, data, importMap, currentPath, ctx, depth, template);
        return new ComponentTagResult(VanAst.toHtml(nodes), r.styles, r.childScripts, r.childModuleImports);
    }

    /** AST entry point — operates on nodes in-place, no serialize/re-parse. */
    private static AstResolveResult resolveComponentTagsAst(
            List<VanAst.Node> nodes, JsonNode data, Map<String, VanParser.VanImport> importMap,
            String currentPath, ResolveContext ctx, int depth, String templateSource) {

        List<String> styles = new ArrayList<>();
        List<String> childScripts = new ArrayList<>();
        List<ResolvedModule> childModuleImports = new ArrayList<>();

        while (true) {
            ComponentMatch match = findComponentElement(nodes, importMap);
            if (match == null) break;

            VanAst.Node.Element elem = match.element;
            VanParser.VanImport imp = importMap.get(match.tagName);
            String resolvedKey = resolveVirtualPath(currentPath, imp.path());
            String componentSource = ctx.files().get(resolvedKey);
            if (componentSource == null) {
                int[] lc = VanTemplateException.offsetToLineCol(templateSource, elem.sourceStart());
                throw new VanTemplateException(
                        "Component not found: " + resolvedKey + " (imported as '" + imp.path() + "')",
                        currentPath, null, lc[0], lc[1]);
            }

            JsonNode childData = parsePropsFromAttrs(elem.attrs(), data);
            String childrenStr = VanAst.toHtml(elem.children());

            List<VanParser.VanImport> importList = new ArrayList<>(importMap.values());
            SlotResult slotResult = parseSlotContent(childrenStr, data,
                    importList, currentPath, ctx, depth);

            ResolvedComponent childResolved = resolveRecursive(componentSource, childData,
                    resolvedKey, ctx, depth + 1);

            String fileTheme = ctx.fileOrigins().getOrDefault(currentPath, "");
            Map<String, String> slotThemes = new HashMap<>();
            for (String slotName : slotResult.slots.keySet()) {
                String slotKey = currentPath + "#" + slotName;
                slotThemes.put(slotName, ctx.fileOrigins().getOrDefault(slotKey, fileTheme));
            }

            List<VanAst.Node> replacementNodes = VanAst.parse(childResolved.html());
            distributeSlotsAst(replacementNodes, slotResult.slots, slotResult.slotParams,
                    childData, ctx.debug(), slotThemes);

            if (ctx.debug()) {
                String themePrefix = ctx.fileOrigins().containsKey(resolvedKey)
                        ? "[" + ctx.fileOrigins().get(resolvedKey) + "] " : "";
                replacementNodes.add(0, new VanAst.Node.Comment(" START: " + themePrefix + resolvedKey + " "));
                replacementNodes.add(new VanAst.Node.Comment(" END: " + themePrefix + resolvedKey + " "));
            }

            match.parentChildren.remove(match.index);
            match.parentChildren.addAll(match.index, replacementNodes);

            if (childResolved.scriptSetup() != null) childScripts.add(childResolved.scriptSetup());
            childModuleImports.addAll(childResolved.moduleImports());
            if (slotResult.scriptSetup != null) childScripts.add(slotResult.scriptSetup);
            childModuleImports.addAll(slotResult.moduleImports);
            styles.addAll(childResolved.styles());
            styles.addAll(slotResult.styles);
        }

        return new AstResolveResult(styles, childScripts, childModuleImports);
    }

    /** Walk AST to find the first element whose tag matches an import. */
    private static ComponentMatch findComponentElement(List<VanAst.Node> nodes,
                                                        Map<String, VanParser.VanImport> importMap) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) instanceof VanAst.Node.Element elem) {
                String tag = elem.tag();
                for (var entry : importMap.entrySet()) {
                    String kebab = entry.getKey();
                    VanParser.VanImport imp = entry.getValue();
                    if (!HTML_ELEMENTS.contains(kebab) && kebab.equals(tag)) {
                        return new ComponentMatch(elem, nodes, i, kebab);
                    }
                    if (!imp.name().equals(kebab) && imp.name().equals(tag)) {
                        return new ComponentMatch(elem, nodes, i, kebab);
                    }
                }
                ComponentMatch child = findComponentElement(elem.children(), importMap);
                if (child != null) return child;
            }
        }
        return null;
    }

    /** Extract :prop bindings from AST attribute list. */
    private static JsonNode parsePropsFromAttrs(List<VanAst.Attr> attrs, JsonNode parentData) {
        boolean compile = isEmptyObject(parentData);
        ObjectNode map = MAPPER.createObjectNode();
        for (VanAst.Attr attr : attrs) {
            if (attr.name().startsWith(":") && attr.value() != null) {
                String key = attr.name().substring(1);
                String expr = attr.value();
                if (compile) {
                    map.put(key, "{{ " + expr + " }}");
                } else {
                    JsonNode resolved = resolvePathValue(parentData, expr);
                    if (resolved != null && !resolved.isMissingNode()) {
                        map.set(key, resolved);
                    } else {
                        map.put(key, resolvePath(parentData, expr));
                    }
                }
            }
        }
        if (parentData.has("$i18n")) map.set("$i18n", parentData.get("$i18n"));
        return map;
    }

    private static ResolvedComponent resolveSlotComponents(
            String content, JsonNode data, Map<String, VanParser.VanImport> importMap,
            String currentPath, ResolveContext ctx, int depth) {

        ComponentTagResult tagResult = resolveComponentTags(
                content, data, importMap, currentPath, ctx, depth);

        String html = !ctx.reactiveNames().isEmpty()
                ? interpolateSkipReactive(tagResult.template(), data, ctx.reactiveNames())
                : interpolate(tagResult.template(), data);

        String scriptSetup = !tagResult.childScripts().isEmpty()
                ? String.join("\n", tagResult.childScripts()) : null;
        return new ResolvedComponent(html, tagResult.styles(), scriptSetup,
                tagResult.childModuleImports());
    }

    // ─── Virtual path resolution ────────────────────────────────────

    static String resolveVirtualPath(String currentFile, String importPath) {
        if (importPath.startsWith("@")) return importPath;

        int slashPos = currentFile.lastIndexOf('/');
        String dir = slashPos >= 0 ? currentFile.substring(0, slashPos) : "";

        String combined = dir.isEmpty() ? importPath : dir + "/" + importPath;
        return normalizeVirtualPath(combined);
    }

    static String normalizeVirtualPath(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            switch (part) {
                case ".", "" -> {}
                case ".." -> { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
                default -> parts.add(part);
            }
        }
        return String.join("/", parts);
    }



    // ─── Interpolation ──────────────────────────────────────────────

    static String interpolate(String template, JsonNode data) {
        return interpolateImpl(template, data, null);
    }

    static String interpolateSkipReactive(String template, JsonNode data,
                                                  List<String> reactiveNames) {
        return interpolateImpl(template, data, reactiveNames);
    }

    private static String interpolateImpl(String template, JsonNode data,
                                           List<String> reactiveNames) {
        StringBuilder result = new StringBuilder(template.length());
        String rest = template;

        while (true) {
            int start = rest.indexOf("{{");
            if (start < 0) break;
            result.append(rest, 0, start);

            if (rest.startsWith("{{{", start)) {
                String afterOpen = rest.substring(start + 3);
                int end = afterOpen.indexOf("}}}");
                if (end >= 0) {
                    String expr = afterOpen.substring(0, end).trim();
                    if (expr.startsWith("$t(")) {
                        result.append("{{{").append(expr).append("}}}");
                    } else if (reactiveNames != null && isReactiveExpr(expr, reactiveNames)) {
                        result.append("{{ ").append(expr).append(" }}");
                    } else {
                        result.append(resolvePath(data, expr));
                    }
                    rest = afterOpen.substring(end + 3);
                } else {
                    result.append("{{{");
                    rest = rest.substring(start + 3);
                }
            } else {
                String afterOpen = rest.substring(start + 2);
                int end = afterOpen.indexOf("}}");
                if (end >= 0) {
                    String expr = afterOpen.substring(0, end).trim();
                    if (expr.startsWith("$t(")) {
                        result.append("{{").append(expr).append("}}");
                    } else if (reactiveNames != null && isReactiveExpr(expr, reactiveNames)) {
                        result.append("{{ ").append(expr).append(" }}");
                    } else {
                        String value = resolvePath(data, expr);
                        if (value.contains("{{")) {
                            result.append(value);
                        } else {
                            result.append(VanUtil.escapeHtml(value));
                        }
                    }
                    rest = afterOpen.substring(end + 2);
                } else {
                    result.append("{{");
                    rest = afterOpen;
                }
            }
        }
        result.append(rest);
        return result.toString();
    }

    private static boolean isReactiveExpr(String expr, List<String> reactiveNames) {
        return VanUtil.containsAnyWholeWord(expr, reactiveNames);
    }

    static String resolvePath(JsonNode data, String path) {
        JsonNode current = data;
        String[] keys = path.split("\\.");
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i].trim();
            JsonNode child = current.get(key);
            if (child == null) {
                return "{{" + path + "}}";
            }
            // Compile-mode expression forwarding
            if (child.isTextual() && i + 1 < keys.length) {
                String s = child.asText();
                if (s.startsWith("{{") && s.endsWith("}}")) {
                    String inner = s.substring(2, s.length() - 2).trim();
                    String remaining = String.join(".", Arrays.copyOfRange(keys, i + 1, keys.length));
                    return "{{ " + inner + "." + remaining + " }}";
                }
            }
            current = child;
        }
        if (current.isTextual()) return current.asText();
        if (current.isNull()) return "";
        return current.toString();
    }


    // ─── Compile-time validation ──────────────────────────────────

    private static void validateImports(Map<String, VanParser.VanBlock> blockCache,
                                         Map<String, String> files) {
        for (var entry : blockCache.entrySet()) {
            String filePath = entry.getKey();
            VanParser.VanBlock block = entry.getValue();
            if (block.scriptSetup() == null) continue;

            for (VanParser.VanImport imp : VanParser.parseImports(block.scriptSetup())) {
                if (imp.path().startsWith("@")) continue; // scoped package — not file
                String resolvedKey = resolveVirtualPath(filePath, imp.path());
                if (!files.containsKey(resolvedKey)) {
                    // Find line number of the import in the original source
                    String source = files.get(filePath);
                    int importIdx = source != null ? source.indexOf(imp.path()) : -1;
                    int[] lc = importIdx >= 0
                            ? VanTemplateException.offsetToLineCol(source, importIdx)
                            : new int[]{-1, -1};
                    throw new VanTemplateException(
                            "Imported component not found: " + resolvedKey
                                    + " (import '" + imp.name() + "' from '" + imp.path() + "')",
                            filePath, null, lc[0], lc[1]);
                }
            }
        }
    }

    // ─── HTML element set (for component vs HTML tag distinction) ──

    private static final Set<String> HTML_ELEMENTS = Set.of(
            "a", "abbr", "address", "area", "article", "aside", "audio",
            "b", "base", "bdi", "bdo", "blockquote", "body", "br", "button",
            "canvas", "caption", "cite", "code", "col", "colgroup",
            "data", "datalist", "dd", "del", "details", "dfn", "dialog", "div", "dl", "dt",
            "em", "embed",
            "fieldset", "figcaption", "figure", "footer", "form",
            "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html",
            "i", "iframe", "img", "input", "ins",
            "kbd", "label", "legend", "li", "link",
            "main", "map", "mark", "menu", "meta", "meter",
            "nav", "noscript",
            "object", "ol", "optgroup", "option", "output",
            "p", "picture", "pre", "progress",
            "q", "rp", "rt", "ruby",
            "s", "samp", "script", "search", "section", "select", "small", "source",
            "span", "strong", "style", "sub", "summary", "sup",
            "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead",
            "time", "title", "tr", "track",
            "u", "ul", "var", "video", "wbr",
            // SVG
            "svg", "path", "circle", "rect", "line", "polyline", "polygon",
            "ellipse", "text", "tspan", "g", "defs", "use", "symbol",
            "clippath", "mask", "pattern", "image", "foreignobject",
            "animate", "animatetransform", "set"
    );

    // ─── Props ──────────────────────────────────────────────────────

    private static final Pattern PROP_RE = Pattern.compile(":(\\w+)=\"([^\"]*)\"");

    static JsonNode parseProps(String attrs, JsonNode parentData) {
        boolean compile = isEmptyObject(parentData);
        ObjectNode map = MAPPER.createObjectNode();
        Matcher m = PROP_RE.matcher(attrs);
        while (m.find()) {
            String key = m.group(1);
            String expr = m.group(2);
            if (compile) {
                map.put(key, "{{ " + expr + " }}");
            } else {
                // Preserve original JsonNode type (array, object) for v-for etc.
                JsonNode resolved = resolvePathValue(parentData, expr);
                if (resolved != null && !resolved.isMissingNode()) {
                    map.set(key, resolved);
                } else {
                    map.put(key, resolvePath(parentData, expr));
                }
            }
        }
        if (parentData.has("$i18n")) {
            map.set("$i18n", parentData.get("$i18n"));
        }
        return map;
    }

    // ─── Slots ──────────────────────────────────────────────────────

    /**
     * @param slots       slot name → HTML content (already interpolated for non-scoped slots)
     * @param slotParams  slot name → parameter destructure keys (for scoped slots, null if not scoped)
     */
    private record SlotResult(Map<String, String> slots, Map<String, List<String>> slotParams,
                              List<String> styles,
                              String scriptSetup, List<ResolvedModule> moduleImports) {}

    /**
     * Extract named and default slot content from component children (AST-based).
     */
    private static SlotResult parseSlotContent(
            String children, JsonNode parentData, List<VanParser.VanImport> parentImports,
            String currentPath, ResolveContext ctx, int depth) {
        Map<String, String> slots = new HashMap<>();
        Map<String, List<String>> slotParams = new HashMap<>();
        List<String> styles = new ArrayList<>();
        List<VanAst.Node> defaultNodes = new ArrayList<>();

        List<VanAst.Node> childNodes = VanAst.parse(children);
        for (VanAst.Node node : childNodes) {
            if (node instanceof VanAst.Node.Element elem && "template".equals(elem.tag())) {
                String slotName = null;
                String scopeExpr = null;
                for (VanAst.Attr attr : elem.attrs()) {
                    if (attr.name().startsWith("#")) {
                        slotName = attr.name().substring(1);
                        scopeExpr = attr.value(); // e.g. "{ user, index }" or null
                        break;
                    }
                }
                if (slotName != null) {
                    String slotContent = VanAst.toHtml(elem.children()).trim();

                    if (scopeExpr != null && scopeExpr.contains("{")) {
                        // Scoped slot: parse destructured params, DON'T interpolate yet
                        List<String> params = parseDestructureKeys(scopeExpr);
                        slotParams.put(slotName, params);
                        // Only interpolate parent data that's NOT a slot param
                        slots.put(slotName, slotContent);
                    } else {
                        // Non-scoped slot: interpolate with parent data immediately
                        String interpolated = !ctx.reactiveNames().isEmpty()
                                ? interpolateSkipReactive(slotContent, parentData, ctx.reactiveNames())
                                : interpolate(slotContent, parentData);
                        slots.put(slotName, interpolated);
                    }
                    continue;
                }
            }
            defaultNodes.add(node);
        }

        // Process default slot content
        String scriptSetup = null;
        List<ResolvedModule> moduleImports = new ArrayList<>();
        boolean hasContent = defaultNodes.stream()
                .anyMatch(n -> !(n instanceof VanAst.Node.Text t && t.content().isBlank()));
        if (hasContent) {
            String defaultContent = VanAst.toHtml(defaultNodes).trim();
            Map<String, VanParser.VanImport> parentImportMap = new LinkedHashMap<>();
            for (VanParser.VanImport imp : parentImports) {
                parentImportMap.put(imp.tagName(), imp);
            }
            ResolvedComponent resolved = resolveSlotComponents(
                    defaultContent, parentData, parentImportMap, currentPath, ctx, depth);
            slots.put("default", resolved.html());
            styles.addAll(resolved.styles());
            scriptSetup = resolved.scriptSetup();
            moduleImports = new ArrayList<>(resolved.moduleImports());
        }

        return new SlotResult(slots, slotParams, styles, scriptSetup, moduleImports);
    }

    /** Parse "{ user, index }" into ["user", "index"]. */
    private static List<String> parseDestructureKeys(String expr) {
        String inner = expr.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
        List<String> keys = new ArrayList<>();
        for (String key : inner.split(",")) {
            String k = key.trim();
            if (!k.isEmpty()) keys.add(k);
        }
        return keys;
    }

    // ─── Slot distribution (AST-based) ───────────────────────────────

    /**
     * Public API — parses HTML, distributes slots on AST, serializes back.
     */
    static String distributeSlots(String html, Map<String, String> slots,
                                          boolean debug, Map<String, String> slotThemes) {
        List<VanAst.Node> nodes = VanAst.parse(html);
        distributeSlotsAst(nodes, slots, debug, slotThemes);
        return VanAst.toHtml(nodes);
    }

    /**
     * In-place slot distribution on AST nodes. Finds &lt;slot&gt; elements and replaces
     * them with provided content or fallback.
     */
    static void distributeSlotsAst(List<VanAst.Node> nodes, Map<String, String> slots,
                                    boolean debug, Map<String, String> slotThemes) {
        distributeSlotsAst(nodes, slots, Map.of(), null, debug, slotThemes);
    }

    static void distributeSlotsAst(List<VanAst.Node> nodes, Map<String, String> slots,
                                    Map<String, List<String>> slotParams, JsonNode childData,
                                    boolean debug, Map<String, String> slotThemes) {
        int i = 0;
        while (i < nodes.size()) {
            VanAst.Node node = nodes.get(i);
            if (node instanceof VanAst.Node.Element elem && "slot".equals(elem.tag())) {
                String name = elem.getAttr("name");
                if (name == null) name = "default";

                String provided = slots.get(name);
                List<VanAst.Node> content;
                if (provided != null) {
                    List<String> params = slotParams.get(name);
                    if (params != null && !params.isEmpty()) {
                        // Scoped slot: build scope from <slot :prop="expr"> attrs
                        ObjectNode scopeData = MAPPER.createObjectNode();
                        for (VanAst.Attr attr : elem.attrs()) {
                            if (attr.name().startsWith(":") && attr.value() != null) {
                                String propName = attr.name().substring(1);
                                if ("name".equals(propName)) continue;
                                if (childData != null) {
                                    JsonNode resolved = resolvePathValue(childData, attr.value());
                                    if (resolved != null) scopeData.set(propName, resolved);
                                    else scopeData.put(propName, attr.value());
                                } else {
                                    scopeData.put(propName, "{{ " + attr.value() + " }}");
                                }
                            }
                        }
                        String interpolated = interpolate(provided, scopeData);
                        content = VanAst.parse(interpolated);
                    } else {
                        content = VanAst.parse(provided);
                    }
                } else if (!elem.children().isEmpty()) {
                    content = new ArrayList<>(elem.children());
                } else {
                    content = List.of();
                }

                nodes.remove(i);
                if (debug) {
                    String p = provided != null ? themePrefix(slotThemes, name) : "";
                    nodes.add(i, new VanAst.Node.Comment(" START: " + p + "#" + name + " "));
                    nodes.addAll(i + 1, content);
                    nodes.add(i + 1 + content.size(), new VanAst.Node.Comment(" END: " + p + "#" + name + " "));
                    i += content.size() + 2;
                } else {
                    nodes.addAll(i, content);
                    i += content.size();
                }
            } else if (node instanceof VanAst.Node.Element elem) {
                distributeSlotsAst(elem.children(), slots, slotParams, childData, debug, slotThemes);
                i++;
            } else {
                i++;
            }
        }
    }

    private static String themePrefix(Map<String, String> slotThemes, String name) {
        String theme = slotThemes.get(name);
        return theme != null && !theme.isEmpty() ? "[" + theme + "] " : "";
    }


    // ─── v-for expansion (AST-based, O(N)) ──────────────────────────

    static String expandVFor(String template, JsonNode data) {
        List<VanAst.Node> nodes = VanAst.parse(template);
        expandVForNodes(nodes, data);
        return VanAst.toHtml(nodes);
    }

    private static void expandVForNodes(List<VanAst.Node> nodes, JsonNode data) {
        int i = 0;
        while (i < nodes.size()) {
            VanAst.Node node = nodes.get(i);
            if (node instanceof VanAst.Node.Element elem) {
                String vfor = elem.getAttr("v-for");
                if (vfor != null) {
                    i = expandVForElement(nodes, i, elem, vfor, data);
                } else {
                    expandVForNodes(elem.children(), data);
                    i++;
                }
            } else {
                i++;
            }
        }
    }

    private static int expandVForElement(List<VanAst.Node> siblings, int index,
                                          VanAst.Node.Element elem, String vforExpr, JsonNode data) {
        VanUtil.VForExpr parsed = VanUtil.parseVForExpr(vforExpr);
        JsonNode array = resolvePathValue(data, parsed.arrayExpr());
        List<JsonNode> items = arrayToList(array);

        elem.removeAttr("v-for");
        elem.removeAttr(":key");
        siblings.remove(index);

        int inserted = 0;
        for (int idx = 0; idx < items.size(); idx++) {
            ObjectNode itemData = MAPPER.createObjectNode();
            itemData.setAll((ObjectNode) data);
            JsonNode item = items.get(idx);
            if (parsed.isDestructured()) {
                // Destructure: { name, id } → put each field into scope
                for (String key : parsed.destructKeys()) {
                    if (item.has(key)) itemData.set(key, item.get(key));
                }
            } else {
                itemData.set(parsed.itemVar(), item);
            }
            if (parsed.indexVar() != null) {
                itemData.put(parsed.indexVar(), idx);
            }

            VanAst.Node.Element copy = elem.copy();
            // Interpolate attrs + children and expand nested v-for
            interpolateAstFull(List.of(copy), itemData, List.of());
            resolveBindingAttrs(copy, itemData);
            expandVForNodes(copy.children(), itemData);

            siblings.add(index + inserted, copy);
            inserted++;
        }
        return index + inserted;
    }

    /** Interpolate {{ }} in all text nodes and attribute values of an AST subtree. */
    private static void interpolateAstNodes(List<VanAst.Node> nodes, JsonNode data) {
        interpolateAstFull(nodes, data, List.of());
    }


    private static JsonNode resolvePathValue(JsonNode data, String path) {
        JsonNode current = data;
        for (String key : path.split("\\.")) {
            key = key.trim();
            if (current == null) return null;
            current = current.get(key);
        }
        return current;
    }

    private static List<JsonNode> arrayToList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<JsonNode> list = new ArrayList<>();
        node.forEach(list::add);
        return list;
    }

    // ─── AST operations for resolveRecursive ────────────────────────

    private static final Set<String> SKIP_SCOPE_TAGS = VanUtil.SKIP_SCOPE_TAGS;

    /** Add scoped style class to all elements in AST (replaces VanParser.addScopeClass). */
    private static void addScopeClassAst(List<VanAst.Node> nodes, String id) {
        for (VanAst.Node node : nodes) {
            if (node instanceof VanAst.Node.Element elem) {
                if (!SKIP_SCOPE_TAGS.contains(elem.tag().toLowerCase())) {
                    String existing = elem.getAttr("class");
                    if (existing != null) {
                        elem.setAttr("class", existing + " " + id);
                    } else {
                        elem.setAttr("class", id);
                    }
                }
                addScopeClassAst(elem.children(), id);
            }
        }
    }

    /** Replace <ClientOnly> elements with <!--client-only-->...<!--/client-only--> comment pairs. */
    private static void replaceClientOnlyNodes(List<VanAst.Node> nodes) {
        int i = 0;
        while (i < nodes.size()) {
            VanAst.Node node = nodes.get(i);
            if (node instanceof VanAst.Node.Element elem
                    && "clientonly".equalsIgnoreCase(elem.tag())) {
                nodes.remove(i);
                nodes.add(i, new VanAst.Node.Comment("client-only"));
                int j = i + 1;
                for (VanAst.Node child : elem.children()) {
                    nodes.add(j++, child);
                }
                nodes.add(j, new VanAst.Node.Comment("/client-only"));
                i = j + 1;
            } else if (node instanceof VanAst.Node.Element elem) {
                replaceClientOnlyNodes(elem.children());
                i++;
            } else {
                i++;
            }
        }
    }

    /** Resolve :prop="expr" binding attributes in the subtree using the given data. */
    private static void resolveBindingAttrs(VanAst.Node.Element elem, JsonNode data) {
        for (int j = 0; j < elem.attrs().size(); j++) {
            VanAst.Attr attr = elem.attrs().get(j);
            if (attr.name().startsWith(":") && attr.value() != null && !attr.value().contains("{{")) {
                String propName = attr.name().substring(1);
                if ("key".equals(propName)) continue;
                String resolved = resolvePath(data, attr.value());
                // Only replace if actually resolved (not sentinel {{...}})
                if (!resolved.startsWith("{{")) {
                    elem.attrs().set(j, new VanAst.Attr(attr.name(), resolved));
                }
            }
        }
        for (VanAst.Node child : elem.children()) {
            if (child instanceof VanAst.Node.Element childElem) {
                resolveBindingAttrs(childElem, data);
            }
        }
    }

    /** Interpolate {{ }} on all AST text nodes, with reactive-name awareness. */
    private static void interpolateAstFull(List<VanAst.Node> nodes, JsonNode data,
                                            List<String> reactiveNames) {
        for (VanAst.Node node : nodes) {
            if (node instanceof VanAst.Node.Text text) {
                String content = text.content();
                if (content.contains("{{")) {
                    text.setContent(!reactiveNames.isEmpty()
                            ? interpolateSkipReactive(content, data, reactiveNames)
                            : interpolate(content, data));
                }
            } else if (node instanceof VanAst.Node.Element elem) {
                // Interpolate attribute values
                for (int j = 0; j < elem.attrs().size(); j++) {
                    VanAst.Attr attr = elem.attrs().get(j);
                    if (attr.value() != null && attr.value().contains("{{")) {
                        String val = !reactiveNames.isEmpty()
                                ? interpolateSkipReactive(attr.value(), data, reactiveNames)
                                : interpolate(attr.value(), data);
                        elem.attrs().set(j, new VanAst.Attr(attr.name(), val));
                    }
                }
                interpolateAstFull(elem.children(), data, reactiveNames);
            }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static boolean isEmptyObject(JsonNode data) {
        return data != null && data.isObject() && data.isEmpty();
    }
}
