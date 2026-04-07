package dev.vanengine.core.compile;

import dev.vanengine.core.support.VanAst;
import dev.vanengine.core.support.VanAst.Attr;
import dev.vanengine.core.support.VanUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signal-based JavaScript code generator for {@code <script setup>} reactive code.
 * Compiles reactive declarations into direct DOM manipulation JS (~4KB runtime).
 *
 */
public final class VanSignalGen {

    private VanSignalGen() {}

    private static final String NAMESPACE_PLACEHOLDER = "__VAN_NS__";

    // ─── Data types ─────────────────────────────────────────────────

    public record SignalDecl(String name, String initialValue) {}
    public record ComputedDecl(String name, String body) {}
    public record FunctionDecl(String name, String params, String body) {}
    public record WatchDecl(String source, String params, String body) {}
    public record WatchEffectDecl(String body) {}
    public record ReactiveDecl(String name, String initialValue) {}
    public record LifecycleDecl(String hook, String body) {}

    public record ScriptAnalysis(
            List<SignalDecl> signals, List<ComputedDecl> computeds,
            List<FunctionDecl> functions, List<WatchDecl> watches,
            List<WatchEffectDecl> watchEffects, List<ReactiveDecl> reactives,
            List<LifecycleDecl> lifecycles, boolean hasEmit) {}

    /** Event modifiers parsed from @click.prevent.stop → modifiers=["prevent","stop"] */
    public record EventBinding(List<Integer> path, String event, String handler, List<String> modifiers) {}
    public record TextBinding(List<Integer> path, String template) {}
    public record ShowBinding(List<Integer> path, String expr, String transition) {}
    public record HtmlDirectiveBinding(List<Integer> path, String expr) {}
    public record TextDirectiveBinding(List<Integer> path, String expr) {}
    public record ClassBinding(List<Integer> path, String expr) {}
    public record StyleBinding(List<Integer> path, String expr) {}
    /** v-model with modifiers: v-model.lazy.number → modifiers=["lazy","number"] */
    public record ModelBinding(List<Integer> path, String signalName, List<String> modifiers) {}
    public record DynamicAttrBinding(List<Integer> path, String attrExpr, String valueExpr) {}
    public record DynamicEventBinding(List<Integer> path, String eventExpr, String handler) {}
    public record TeleportBinding(List<Integer> path, String target) {}

    public record TemplateBindings(
            List<EventBinding> events, List<TextBinding> texts,
            List<ShowBinding> shows, List<HtmlDirectiveBinding> htmls,
            List<TextDirectiveBinding> textDirectives,
            List<ClassBinding> classes, List<StyleBinding> styles,
            List<ModelBinding> models,
            List<DynamicAttrBinding> dynamicAttrs, List<DynamicEventBinding> dynamicEvents,
            List<TeleportBinding> teleports) {
        void forEachPath(Consumer<List<Integer>> action) {
            events.forEach(b -> action.accept(b.path));
            texts.forEach(b -> action.accept(b.path));
            shows.forEach(b -> action.accept(b.path));
            htmls.forEach(b -> action.accept(b.path));
            textDirectives.forEach(b -> action.accept(b.path));
            classes.forEach(b -> action.accept(b.path));
            styles.forEach(b -> action.accept(b.path));
            models.forEach(b -> action.accept(b.path));
            dynamicAttrs.forEach(b -> action.accept(b.path));
            dynamicEvents.forEach(b -> action.accept(b.path));
            teleports.forEach(b -> action.accept(b.path));
        }
    }

    // ─── Runtime JS ─────────────────────────────────────────────────

    private static final String RUNTIME_JS;
    static {
        try (InputStream is = VanSignalGen.class.getResourceAsStream("/van-runtime.js")) {
            RUNTIME_JS = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new RuntimeException("Failed to load van-runtime.js", e);
        }
    }

    public static String runtimeJs() { return RUNTIME_JS; }

    public static String runtimeJs(String globalName) {
        return RUNTIME_JS.replace(NAMESPACE_PLACEHOLDER, globalName);
    }

    // ─── Stage A: Script Analysis ──────────────────────────────────

    public static ScriptAnalysis analyzeScript(String script) {
        return new ScriptSetupParser(script).parse();
    }

    public static String jsLiteralToDisplay(String literal) {
        String s = literal.trim();
        return switch (s) {
            case "true" -> "true";
            case "false" -> "false";
            case "null", "undefined", "" -> "";
            default -> {
                if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
                    yield s.substring(1, s.length() - 1);
                }
                yield s;
            }
        };
    }

    // ─── Stage B: HTML Tree Walker ──────────────────────────────────

    /** Mutable accumulator for tree walking. */
    private static class WalkContext {
        final String[] reactiveNames;
        final List<EventBinding> events = new ArrayList<>();
        final List<TextBinding> texts = new ArrayList<>();
        final List<ShowBinding> shows = new ArrayList<>();
        final List<HtmlDirectiveBinding> htmls = new ArrayList<>();
        final List<TextDirectiveBinding> textDirectives = new ArrayList<>();
        final List<ClassBinding> classes = new ArrayList<>();
        final List<StyleBinding> styles = new ArrayList<>();
        final List<ModelBinding> models = new ArrayList<>();
        final List<DynamicAttrBinding> dynamicAttrs = new ArrayList<>();
        final List<DynamicEventBinding> dynamicEvents = new ArrayList<>();
        final List<TeleportBinding> teleports = new ArrayList<>();

        WalkContext(String[] reactiveNames) { this.reactiveNames = reactiveNames; }

        TemplateBindings toBindings() {
            return new TemplateBindings(events, texts, shows, htmls, textDirectives,
                    classes, styles, models, dynamicAttrs, dynamicEvents, teleports);
        }
    }

    public static TemplateBindings walkTemplate(String html, String[] reactiveNames) {
        List<VanAst.Node> nodes = VanAst.parse(html);
        WalkContext ctx = new WalkContext(reactiveNames);

        VanAst.Node.Element body = findBody(nodes);
        List<VanAst.Node> walkNodes = body != null ? body.children() : nodes;
        walkChildren(walkNodes, List.of(), ctx, null);

        return ctx.toBindings();
    }

    private static VanAst.Node.Element findBody(List<VanAst.Node> nodes) {
        for (VanAst.Node node : nodes) {
            if (node instanceof VanAst.Node.Element e) {
                if ("body".equals(e.tag())) return e;
                if ("html".equals(e.tag())) {
                    VanAst.Node.Element b = findBody(e.children());
                    if (b != null) return b;
                }
            }
        }
        return null;
    }

    private static void walkChildren(List<VanAst.Node> children, List<Integer> path,
                                     WalkContext ctx, String transition) {
        int[] elementIndex = {0};
        walkNodes(children, path, ctx, elementIndex, transition);
    }

    private static void walkNodes(List<VanAst.Node> children, List<Integer> path,
                                  WalkContext ctx, int[] elementIndex, String transition) {
        for (VanAst.Node node : children) {
            if (node instanceof VanAst.Node.Element elem) {
                String tagLower = elem.tag().toLowerCase();
                if ("transition".equals(tagLower)) {
                    String name = elem.getAttr("name");
                    if (name == null) name = "v";
                    walkNodes(elem.children(), path, ctx, elementIndex, name);
                    continue;
                }
                if ("transition-group".equals(tagLower) || "transitiongroup".equals(tagLower)) {
                    String name = elem.getAttr("name");
                    if (name == null) name = "v";
                    walkNodes(elem.children(), path, ctx, elementIndex, name);
                    continue;
                }
                if ("teleport".equals(tagLower)) {
                    String target = elem.getAttr("to");
                    if (target == null) target = "";
                    List<Integer> tp = new ArrayList<>(path);
                    tp.add(elementIndex[0]);
                    ctx.teleports.add(new TeleportBinding(List.copyOf(tp), target));
                    walkChildren(elem.children(), tp, ctx, null);
                    elementIndex[0]++;
                    continue;
                }

                List<Integer> currentPath = new ArrayList<>(path);
                currentPath.add(elementIndex[0]);

                for (Attr attr : elem.attrs()) {
                    String attrName = attr.name();
                    String attrValue = attr.value() != null ? attr.value() : "";
                    List<Integer> cp = List.copyOf(currentPath);

                    if (attrName.startsWith("@")) {
                        String raw = attrName.substring(1);
                        if (raw.startsWith("[") && raw.contains("]")) {
                            String expr = raw.substring(1, raw.indexOf(']'));
                            ctx.dynamicEvents.add(new DynamicEventBinding(cp, expr, attrValue));
                        } else {
                            String[] parts = raw.split("\\.");
                            String event = parts[0];
                            List<String> modifiers = parts.length > 1
                                    ? List.of(Arrays.copyOfRange(parts, 1, parts.length)) : List.of();
                            ctx.events.add(new EventBinding(cp, event, attrValue, modifiers));
                        }
                    } else if (attrName.startsWith("v-model")) {
                        String[] parts = attrName.split("\\.");
                        List<String> modifiers = parts.length > 1
                                ? List.of(Arrays.copyOfRange(parts, 1, parts.length)) : List.of();
                        ctx.models.add(new ModelBinding(cp, attrValue, modifiers));
                    } else if (attrName.startsWith(":[")) {
                        String expr = attrName.substring(2, attrName.indexOf(']'));
                        ctx.dynamicAttrs.add(new DynamicAttrBinding(cp, expr, attrValue));
                    } else if ("v-show".equals(attrName) || "v-if".equals(attrName) || "v-else-if".equals(attrName)) {
                        ctx.shows.add(new ShowBinding(cp, attrValue, transition));
                    } else if ("v-else".equals(attrName)) {
                        ctx.shows.add(new ShowBinding(cp, "true", transition));
                    } else if ("v-html".equals(attrName)) {
                        ctx.htmls.add(new HtmlDirectiveBinding(cp, attrValue));
                    } else if ("v-text".equals(attrName)) {
                        ctx.textDirectives.add(new TextDirectiveBinding(cp, attrValue));
                    } else if (":class".equals(attrName)) {
                        ctx.classes.add(new ClassBinding(cp, attrValue));
                    } else if (":style".equals(attrName)) {
                        ctx.styles.add(new StyleBinding(cp, attrValue));
                    }
                }

                checkTextBindings(elem, currentPath, ctx);
                walkChildren(elem.children(), currentPath, ctx, null);
                elementIndex[0]++;
            }
        }
    }


    private static void checkTextBindings(VanAst.Node.Element elem, List<Integer> path, WalkContext ctx) {
        StringBuilder fullText = new StringBuilder();
        boolean hasOnlyText = true;
        for (VanAst.Node child : elem.children()) {
            if (child instanceof VanAst.Node.Text t) fullText.append(t.content());
            else hasOnlyText = false;
        }
        if (!hasOnlyText || !fullText.toString().contains("{{")) return;

        Matcher m = VanUtil.MUSTACHE.matcher(fullText);
        while (m.find()) {
            if (VanUtil.containsAnyWholeWord(m.group(1).trim(), Arrays.asList(ctx.reactiveNames))) {
                ctx.texts.add(new TextBinding(List.copyOf(path), fullText.toString().trim()));
                return;
            }
        }
    }

    // ─── Stage C: JS Code Generation ────────────────────────────────

    public static String transformExpr(String expr, String[] reactiveNames) {
        String result = expr;
        for (String name : reactiveNames) {
            String dotValue = name + ".value";
            String placeholder = "__PH_" + name + "__";
            result = result.replace(dotValue, placeholder);
            // Word-boundary replacement without Pattern.compile in loop
            result = replaceWholeWord(result, name, dotValue);
            result = result.replace(name + ".value.value", dotValue);
            result = result.replace(placeholder, dotValue);
        }
        return result;
    }

    private static String replaceWholeWord(String text, String word, String replacement) {
        int idx = 0;
        StringBuilder sb = null;
        while (idx < text.length()) {
            int found = text.indexOf(word, idx);
            if (found < 0) break;
            boolean beforeOk = found == 0 || !VanUtil.isWordChar(text.charAt(found - 1));
            boolean afterOk = found + word.length() == text.length()
                    || !VanUtil.isWordChar(text.charAt(found + word.length()));
            if (beforeOk && afterOk) {
                if (sb == null) sb = new StringBuilder(text.length() + 16);
                sb.append(text, idx, found).append(replacement);
                idx = found + word.length();
            } else {
                if (sb == null) sb = new StringBuilder(text.length() + 16);
                sb.append(text, idx, found + word.length());
                idx = found + word.length();
            }
        }
        if (sb == null) return text;
        sb.append(text, idx, text.length());
        return sb.toString();
    }

    public static String templateToJsExpr(String template, String[] reactiveNames) {
        List<String> parts = new ArrayList<>();
        String rest = template;
        while (true) {
            int start = rest.indexOf("{{");
            if (start < 0) break;
            String before = rest.substring(0, start);
            if (!before.isEmpty()) {
                parts.add("'" + before.replace("\\", "\\\\").replace("'", "\\'") + "'");
            }
            String afterOpen = rest.substring(start + 2);
            int end = afterOpen.indexOf("}}");
            if (end >= 0) {
                String expr = afterOpen.substring(0, end).trim();
                parts.add(transformExpr(expr, reactiveNames));
                rest = afterOpen.substring(end + 2);
            } else {
                parts.add("'" + rest.replace("\\", "\\\\").replace("'", "\\'") + "'");
                rest = "";
                break;
            }
        }
        if (!rest.isEmpty()) {
            parts.add("'" + rest.replace("\\", "\\\\").replace("'", "\\'") + "'");
        }
        return parts.isEmpty() ? "''" : String.join(" + ", parts);
    }

    // ─── :class / :style parsing ────────────────────────────────────

    public sealed interface ClassItem permits ClassItem.Toggle, ClassItem.Static {
        record Toggle(String className, String condition) implements ClassItem {}
        record Static(String className) implements ClassItem {}
    }

    public static List<ClassItem> parseClassExpr(String expr) {
        String t = expr.trim();
        if (t.startsWith("[")) return parseClassArray(t);
        if (t.startsWith("{")) {
            List<ClassItem> items = new ArrayList<>();
            for (String[] p : parseObjectPairs(t)) items.add(new ClassItem.Toggle(p[0], p[1]));
            return items;
        }
        return List.of();
    }

    private static List<ClassItem> parseClassArray(String expr) {
        String inner = expr.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        List<ClassItem> items = new ArrayList<>();
        for (String part : VanUtil.splitRespectingNesting(inner)) {
            part = part.trim();
            if (part.startsWith("{")) {
                for (String[] p : parseObjectPairs(part)) items.add(new ClassItem.Toggle(p[0], p[1]));
            } else {
                String name = part.replace("'", "").replace("\"", "");
                if (!name.isEmpty()) items.add(new ClassItem.Static(name));
            }
        }
        return items;
    }

    public static List<String[]> parseStyleExpr(String expr) {
        String t = expr.trim();
        if (t.startsWith("[")) return parseStyleArray(t);
        if (t.startsWith("{")) return parseObjectPairs(t);
        return List.of();
    }

    private static List<String[]> parseStyleArray(String expr) {
        String inner = expr.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        List<String[]> pairs = new ArrayList<>();
        for (String part : VanUtil.splitRespectingNesting(inner)) {
            if (part.trim().startsWith("{")) pairs.addAll(parseObjectPairs(part.trim()));
        }
        return pairs;
    }

    private static List<String[]> parseObjectPairs(String expr) {
        String inner = expr.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
        List<String[]> pairs = new ArrayList<>();
        for (String part : VanUtil.splitRespectingNesting(inner)) {
            part = part.trim();
            int colon = part.indexOf(':');
            if (colon >= 0) {
                String key = part.substring(0, colon).trim().replace("'", "").replace("\"", "");
                String val = part.substring(colon + 1).trim();
                pairs.add(new String[]{key, val});
            }
        }
        return pairs;
    }


    // ─── Main generation: positional mode ───────────────────────────

    public static String generateSignals(String scriptSetup, String templateHtml, List<String> moduleCodes, String globalName) {
        ScriptAnalysis analysis = analyzeScript(scriptSetup);
        if (analysis.signals.isEmpty() && analysis.computeds.isEmpty() && analysis.reactives.isEmpty()
                && analysis.watchEffects.isEmpty() && analysis.lifecycles.isEmpty() && !analysis.hasEmit) return null;

        String[] reactiveNames = collectReactiveNamesList(analysis).toArray(String[]::new);
        TemplateBindings bindings = walkTemplate(templateHtml, reactiveNames);
        List<List<Integer>> requiredPaths = collectRequiredPaths(bindings);

        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");
        js.append("  var V = ").append(globalName).append(";\n");

        // Modules
        for (int i = 0; i < moduleCodes.size(); i++) {
            js.append("  var __mod_").append(i).append(" = (function() { ").append(moduleCodes.get(i).trim()).append(" })();\n");
        }

        emitDeclarations(js, analysis, reactiveNames);

        if (!requiredPaths.isEmpty()) {
            js.append("\n");
            Map<List<Integer>, String> pathVars = new LinkedHashMap<>();
            int varCounter = 0;
            js.append("  var _r = document.body;\n");

            for (List<Integer> path : requiredPaths) {
                String varName = "_e" + varCounter++;
                String parentVar = path.size() == 1 ? "_r"
                        : pathVars.getOrDefault(path.subList(0, path.size() - 1), "_r");
                js.append("  var ").append(varName).append(" = ").append(parentVar)
                        .append(".children[").append(path.get(path.size() - 1)).append("];\n");
                pathVars.put(path, varName);
            }

            emitAllBindings(js, bindings, pathVars::get, analysis, reactiveNames);
        }

        js.append("})();\n");
        return js.toString();
    }

    // ─── Comment-anchor mode ────────────────────────────────────────

    /**
     * Generate comment-anchored signal JS from pre-computed analysis and bindings.
     * Avoids redundant analyzeScript/walkTemplate when caller already has results.
     */
    public static String generateSignalsCommentFrom(ScriptAnalysis analysis, TemplateBindings bindings,
                                              List<String> moduleCodes, String globalName) {
        if (analysis.signals.isEmpty() && analysis.computeds.isEmpty() && analysis.reactives.isEmpty()
                && analysis.watchEffects.isEmpty() && analysis.lifecycles.isEmpty() && !analysis.hasEmit) return null;
        String[] reactiveNames = collectReactiveNamesList(analysis).toArray(String[]::new);

        List<List<Integer>> bindingPaths = collectBindingPaths(bindings);
        if (bindingPaths.isEmpty()) return null;

        Map<List<Integer>, Integer> pathToIdx = new LinkedHashMap<>();
        for (int i = 0; i < bindingPaths.size(); i++) pathToIdx.put(bindingPaths.get(i), i);

        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");
        js.append("  var V = ").append(globalName).append(";\n");

        for (int i = 0; i < moduleCodes.size(); i++) {
            js.append("  var __mod_").append(i).append(" = (function() { ").append(moduleCodes.get(i).trim()).append(" })();\n");
        }

        emitDeclarations(js, analysis, reactiveNames);

        js.append("\n");
        js.append("  var _ve = new Array(").append(bindingPaths.size()).append(");\n");
        js.append("  var _tw = document.createTreeWalker(document.body, NodeFilter.SHOW_COMMENT);\n");
        js.append("  var _tn;\n");
        js.append("  while (_tn = _tw.nextNode()) {\n");
        js.append("    var _td = _tn.data;\n");
        js.append("    if (_td.length > 2 && _td.charCodeAt(0) === 118 && _td.charCodeAt(1) === 58) {\n");
        js.append("      _ve[parseInt(_td.substring(2))] = _tn.nextElementSibling;\n");
        js.append("    }\n");
        js.append("  }\n");

        emitAllBindings(js, bindings, path -> {
            Integer idx = pathToIdx.get(path);
            return idx != null ? "_ve[" + idx + "]" : null;
        }, analysis, reactiveNames);

        js.append("})();\n");
        return js.toString();
    }

    // ─── Comment injection ──────────────────────────────────────────

    public static Map.Entry<String, Map<List<Integer>, Integer>> injectSignalComments(String html, List<List<Integer>> bindingPaths) {
        Map<List<Integer>, Integer> offsets = findElementOffsets(html, bindingPaths);

        List<int[]> insertions = new ArrayList<>();
        Map<List<Integer>, Integer> pathToIdx = new LinkedHashMap<>();
        for (int idx = 0; idx < bindingPaths.size(); idx++) {
            Integer offset = offsets.get(bindingPaths.get(idx));
            if (offset != null) {
                insertions.add(new int[]{offset, idx});
                pathToIdx.put(bindingPaths.get(idx), idx);
            }
        }
        insertions.sort((a, b) -> b[0] - a[0]);

        StringBuilder result = new StringBuilder(html);
        for (int[] ins : insertions) {
            result.insert(ins[0], "<!--v:" + ins[1] + "-->");
        }
        return Map.entry(result.toString(), pathToIdx);
    }

    private static Map<List<Integer>, Integer> findElementOffsets(String html, List<List<Integer>> targetPaths) {
        Set<List<Integer>> targets = new HashSet<>(targetPaths);
        List<VanAst.Node> nodes = VanAst.parse(html);
        Map<List<Integer>, Integer> offsets = new HashMap<>();
        VanAst.Node.Element body = findBody(nodes);
        if (body != null) {
            collectOffsets(body.children(), List.of(), targets, offsets);
        } else {
            collectOffsets(nodes, List.of(), targets, offsets);
        }
        return offsets;
    }

    private static void collectOffsets(List<VanAst.Node> children, List<Integer> parentPath,
                                       Set<List<Integer>> targets, Map<List<Integer>, Integer> offsets) {
        int elementIndex = 0;
        for (VanAst.Node node : children) {
            if (node instanceof VanAst.Node.Element el) {
                String tag = el.tag().toLowerCase();
                if ("transition".equals(tag) || "transition-group".equals(tag) || "transitiongroup".equals(tag)) {
                    collectOffsets(el.children(), parentPath, targets, offsets);
                    continue;
                }
                List<Integer> currentPath = new ArrayList<>(parentPath);
                currentPath.add(elementIndex);
                if (targets.contains(currentPath)) offsets.put(currentPath, el.sourceStart());
                collectOffsets(el.children(), currentPath, targets, offsets);
                elementIndex++;
            }
        }
    }

    // ─── Shared helpers ─────────────────────────────────────────────

    public static List<String> collectReactiveNamesList(ScriptAnalysis analysis) {
        List<String> names = new ArrayList<>();
        analysis.signals.forEach(s -> names.add(s.name));
        analysis.computeds.forEach(c -> names.add(c.name));
        analysis.reactives.forEach(r -> names.add(r.name));
        return names;
    }


    private static void emitDeclarations(StringBuilder js, ScriptAnalysis analysis, String[] reactiveNames) {
        for (SignalDecl s : analysis.signals) {
            js.append("  var ").append(s.name).append(" = V.signal(").append(s.initialValue).append(");\n");
        }
        for (ComputedDecl c : analysis.computeds) {
            js.append("  var ").append(c.name).append(" = V.computed(function() { return ")
                    .append(transformExpr(c.body, reactiveNames)).append("; });\n");
        }
        for (ReactiveDecl r : analysis.reactives) {
            js.append("  var ").append(r.name).append(" = V.reactive(").append(r.initialValue).append(");\n");
        }
        for (FunctionDecl f : analysis.functions) {
            js.append("  function ").append(f.name).append("(").append(f.params).append(") { ")
                    .append(transformExpr(f.body, reactiveNames)).append(" }\n");
        }
        for (WatchDecl w : analysis.watches) {
            js.append("  V.watch(").append(w.source).append(", function(").append(w.params).append(") { ")
                    .append(transformExpr(w.body, reactiveNames)).append(" });\n");
        }
        for (WatchEffectDecl we : analysis.watchEffects) {
            js.append("  V.watchEffect(function() { ").append(transformExpr(we.body, reactiveNames)).append(" });\n");
        }
        for (LifecycleDecl lc : analysis.lifecycles) {
            if ("mounted".equals(lc.hook)) {
                js.append("  V.onMounted(function() { ").append(transformExpr(lc.body, reactiveNames)).append(" });\n");
            } else if ("unmounted".equals(lc.hook)) {
                js.append("  V.onUnmounted(function() { ").append(transformExpr(lc.body, reactiveNames)).append(" });\n");
            }
        }
        if (analysis.hasEmit) {
            js.append("  var _root = document.body.children[0];\n");
            js.append("  function emit(name, detail) { V.emit(_root, name, detail); }\n");
        }
    }

    @FunctionalInterface
    private interface ElementAccessor {
        /** Resolve a binding path to a JS expression for the element, or null to skip. */
        String resolve(List<Integer> path);
    }

    private static void emitAllBindings(StringBuilder js, TemplateBindings bindings,
                                         ElementAccessor accessor, ScriptAnalysis analysis, String[] reactiveNames) {
        for (EventBinding b : bindings.events) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            boolean isNamedFn = analysis.functions.stream().anyMatch(f -> f.name.equals(b.handler));
            // Build handler with modifiers
            String handlerBody;
            if (isNamedFn && b.modifiers.isEmpty()) {
                handlerBody = b.handler;
            } else {
                StringBuilder hb = new StringBuilder("function(e) { ");
                for (String mod : b.modifiers) {
                    switch (mod) {
                        case "prevent" -> hb.append("e.preventDefault(); ");
                        case "stop" -> hb.append("e.stopPropagation(); ");
                        case "self" -> hb.append("if (e.target !== e.currentTarget) return; ");
                    }
                }
                if (isNamedFn) hb.append(b.handler).append("(e); ");
                else hb.append(transformExpr(b.handler, reactiveNames)).append("; ");
                hb.append("}");
                handlerBody = hb.toString();
            }
            // addEventListener options
            List<String> opts = new ArrayList<>();
            if (b.modifiers.contains("once")) opts.add("once: true");
            if (b.modifiers.contains("capture")) opts.add("capture: true");
            if (b.modifiers.contains("passive")) opts.add("passive: true");
            js.append("  ").append(v).append(".addEventListener('").append(b.event).append("', ").append(handlerBody);
            if (!opts.isEmpty()) js.append(", { ").append(String.join(", ", opts)).append(" }");
            js.append(");\n");
        }
        for (TextBinding b : bindings.texts) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            js.append("  V.effect(function() { ").append(v).append(".textContent = ")
                    .append(templateToJsExpr(b.template, reactiveNames)).append("; });\n");
        }
        for (ShowBinding b : bindings.shows) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            String transformed = transformExpr(b.expr, reactiveNames);
            if (b.transition != null) {
                js.append("  V.effect(function() { V.transition(").append(v).append(", ")
                        .append(transformed).append(", '").append(b.transition).append("'); });\n");
            } else {
                js.append("  V.effect(function() { ").append(v).append(".style.display = ")
                        .append(transformed).append(" ? '' : 'none'; });\n");
            }
        }
        for (HtmlDirectiveBinding b : bindings.htmls) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            js.append("  V.effect(function() { ").append(v).append(".innerHTML = ")
                    .append(transformExpr(b.expr, reactiveNames)).append("; });\n");
        }
        for (TextDirectiveBinding b : bindings.textDirectives) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            js.append("  V.effect(function() { ").append(v).append(".textContent = ")
                    .append(transformExpr(b.expr, reactiveNames)).append("; });\n");
        }
        for (ClassBinding b : bindings.classes) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            for (ClassItem item : parseClassExpr(b.expr)) {
                if (item instanceof ClassItem.Toggle t) {
                    js.append("  V.effect(function() { ").append(v).append(".classList.toggle('")
                            .append(t.className).append("', !!").append(transformExpr(t.condition, reactiveNames)).append("); });\n");
                } else if (item instanceof ClassItem.Static s) {
                    js.append("  ").append(v).append(".classList.add('").append(s.className).append("');\n");
                }
            }
        }
        for (StyleBinding b : bindings.styles) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            for (String[] pair : parseStyleExpr(b.expr)) {
                js.append("  V.effect(function() { ").append(v).append(".style.").append(pair[0]).append(" = ")
                        .append(transformExpr(pair[1], reactiveNames)).append("; });\n");
            }
        }
        for (ModelBinding b : bindings.models) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            String signal = b.signalName;
            boolean hasLazy = b.modifiers.contains("lazy");
            boolean hasNumber = b.modifiers.contains("number");
            boolean hasTrim = b.modifiers.contains("trim");
            String eventName = hasLazy ? "change" : "input";
            // Sync signal → DOM
            js.append("  V.effect(function() { ").append(v).append(".value = ").append(signal).append(".value; });\n");
            // Sync DOM → signal with modifiers
            StringBuilder setter = new StringBuilder();
            setter.append("var _v = e.target.value; ");
            if (hasTrim) setter.append("_v = _v.trim(); ");
            if (hasNumber) setter.append("_v = Number(_v) || _v; ");
            setter.append(signal).append(".value = _v;");
            js.append("  ").append(v).append(".addEventListener('").append(eventName)
                    .append("', function(e) { ").append(setter).append(" });\n");
        }
        // Dynamic attribute bindings: :[attrExpr]="valueExpr"
        for (DynamicAttrBinding b : bindings.dynamicAttrs) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            String attrExpr = transformExpr(b.attrExpr, reactiveNames);
            String valExpr = transformExpr(b.valueExpr, reactiveNames);
            js.append("  V.effect(function() { ").append(v).append(".setAttribute(")
                    .append(attrExpr).append(", ").append(valExpr).append("); });\n");
        }
        // Dynamic event bindings: @[eventExpr]="handler"
        for (DynamicEventBinding b : bindings.dynamicEvents) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            String eventExpr = transformExpr(b.eventExpr, reactiveNames);
            String handler = analysis.functions.stream().anyMatch(f -> f.name.equals(b.handler))
                    ? b.handler : "function() { " + transformExpr(b.handler, reactiveNames) + " }";
            js.append("  ").append(v).append(".addEventListener(").append(eventExpr).append(", ").append(handler).append(");\n");
        }
        // Teleport bindings
        for (TeleportBinding b : bindings.teleports) {
            String v = accessor.resolve(b.path); if (v == null) continue;
            js.append("  V.teleport(").append(v).append(", '").append(b.target).append("');\n");
        }
    }

    private static List<List<Integer>> collectRequiredPaths(TemplateBindings bindings) {
        TreeSet<List<Integer>> paths = new TreeSet<>(VanSignalGen::comparePaths);
        bindings.forEachPath(p -> addWithAncestors(paths, p));
        return new ArrayList<>(paths);
    }

    public static List<List<Integer>> collectBindingPaths(TemplateBindings bindings) {
        TreeSet<List<Integer>> paths = new TreeSet<>(VanSignalGen::comparePaths);
        bindings.forEachPath(paths::add);
        return new ArrayList<>(paths);
    }

    private static void addWithAncestors(TreeSet<List<Integer>> paths, List<Integer> path) {
        for (int i = 1; i <= path.size(); i++) paths.add(List.copyOf(path.subList(0, i)));
    }

    private static int comparePaths(List<Integer> a, List<Integer> b) {
        int len = Math.min(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            int cmp = Integer.compare(a.get(i), b.get(i));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.size(), b.size());
    }

    // ─── Script Setup Parser (inner class) ─────────────────────────

    /**
     * Lightweight recursive-descent parser for {@code <script setup>} blocks.
     * Replaces regex-based analysis with proper token-level parsing that handles
     * nested parentheses, braces, brackets, string literals, and template strings.
     *
     * <p>Parses only the subset of JS/TS relevant to Vue 3 script setup:
     * variable declarations, function declarations, arrow functions,
     * and top-level call expressions (watch, watchEffect, onMounted, etc.).
     */
    private static final class ScriptSetupParser {

        private final String source;
        private int pos;

        ScriptSetupParser(String source) {
            this.source = source;
            this.pos = 0;
        }

        /**
         * Parse the full script setup and return a ScriptAnalysis.
         */
        ScriptAnalysis parse() {
            List<SignalDecl> signals = new ArrayList<>();
            List<ComputedDecl> computeds = new ArrayList<>();
            List<FunctionDecl> functions = new ArrayList<>();
            List<WatchDecl> watches = new ArrayList<>();
            List<WatchEffectDecl> watchEffects = new ArrayList<>();
            List<ReactiveDecl> reactives = new ArrayList<>();
            List<LifecycleDecl> lifecycles = new ArrayList<>();
            boolean hasEmit = false;

            while (pos < source.length()) {
                skipWhitespaceAndComments();
                if (pos >= source.length()) break;

                // Import statement — skip entirely
                if (lookingAt("import ") || lookingAt("import{")) {
                    skipUntilAfter(';', '\n');
                    continue;
                }

                // const/let/var declaration
                if (lookingAt("const ") || lookingAt("let ") || lookingAt("var ")) {
                    int kwLen = source.charAt(pos) == 'c' ? 6 : source.charAt(pos) == 'l' ? 4 : 4;
                    pos += kwLen;
                    skipWhitespace();
                    String name = readIdentifier();
                    if (name.isEmpty()) { skipToNextStatement(); continue; }
                    skipWhitespace();
                    if (pos < source.length() && source.charAt(pos) == '=') {
                        pos++; // skip '='
                        skipWhitespace();

                        // Check for call expression: name(...) — but not "function(...)"
                        String callee = peekIdentifier();
                        if (!callee.isEmpty() && !"function".equals(callee)
                                && pos + callee.length() < source.length()
                                && source.charAt(pos + callee.length()) == '(') {
                            pos += callee.length();
                            String args = readBalancedParens();

                            switch (callee) {
                                case "ref" -> signals.add(new SignalDecl(name, args));
                                case "computed" -> computeds.add(new ComputedDecl(name, extractCallbackBody(args)));
                                case "reactive" -> reactives.add(new ReactiveDecl(name, args));
                                case "defineEmits" -> hasEmit = true;
                                case "defineModel" -> hasEmit = true; // defineModel implies emit
                                default -> {} // Other calls: ignore (e.g. defineProps)
                            }
                        }
                        // Arrow function: (...) => ... or () => ...
                        else if (source.charAt(pos) == '(') {
                            String params = readBalancedParens();
                            skipWhitespace();
                            if (lookingAt("=>")) {
                                pos += 2;
                                skipWhitespace();
                                String body;
                                if (pos < source.length() && source.charAt(pos) == '{') {
                                    body = readBalancedBraces();
                                } else {
                                    body = readExpressionUntilNewline();
                                }
                                functions.add(new FunctionDecl(name, params, body));
                            }
                        }
                        // Function expression: function(...) { ... }
                        else if (lookingAt("function")) {
                            pos += 8; // "function"
                            skipWhitespace();
                            if (pos < source.length() && source.charAt(pos) == '(') {
                                String params = readBalancedParens();
                                skipWhitespace();
                                String body = (pos < source.length() && source.charAt(pos) == '{')
                                        ? readBalancedBraces() : "";
                                functions.add(new FunctionDecl(name, params, body));
                            }
                        }
                        else {
                            skipToNextStatement();
                        }
                    } else {
                        skipToNextStatement();
                    }
                    continue;
                }

                // Function declaration: function name(...) { ... }
                if (lookingAt("function ") || lookingAt("function\t") || lookingAt("function\n")) {
                    pos += 9;
                    skipWhitespace();
                    String name = readIdentifier();
                    if (!name.isEmpty() && !isBuiltinCall(name)) {
                        skipWhitespace();
                        if (pos < source.length() && source.charAt(pos) == '(') {
                            String params = readBalancedParens();
                            skipWhitespace();
                            String body = (pos < source.length() && source.charAt(pos) == '{')
                                    ? readBalancedBraces() : "";
                            functions.add(new FunctionDecl(name, params, body));
                            continue;
                        }
                    }
                    skipToNextStatement();
                    continue;
                }

                // Top-level call expression: watch(...), watchEffect(...), onMounted(...), etc.
                String ident = peekIdentifier();
                if (!ident.isEmpty() && pos + ident.length() < source.length()
                        && source.charAt(pos + ident.length()) == '(') {
                    pos += ident.length();

                    switch (ident) {
                        case "watch" -> {
                            pos++; // skip '('
                            skipWhitespace();
                            String sourceName = readIdentifier();
                            skipWhitespace();
                            if (pos < source.length() && source.charAt(pos) == ',') {
                                pos++;
                                skipWhitespace();
                                String callbackStr = readUntilMatchingParen();
                                ParsedCallback cb = parseCallback(callbackStr);
                                if (cb != null) {
                                    watches.add(new WatchDecl(sourceName, cb.params, cb.body));
                                }
                            }
                            skipToCloseParen();
                        }
                        case "watchEffect" -> {
                            String args = readBalancedParens();
                            String body = extractCallbackBody(args);
                            watchEffects.add(new WatchEffectDecl(body));
                        }
                        case "onMounted" -> {
                            String args = readBalancedParens();
                            String body = extractCallbackBody(args);
                            lifecycles.add(new LifecycleDecl("mounted", body));
                        }
                        case "onUnmounted" -> {
                            String args = readBalancedParens();
                            String body = extractCallbackBody(args);
                            lifecycles.add(new LifecycleDecl("unmounted", body));
                        }
                        case "defineEmits" -> {
                            hasEmit = true;
                            skipBalancedParens();
                        }
                        case "defineProps", "defineModel" -> {
                            if ("defineModel".equals(ident)) hasEmit = true;
                            skipBalancedParens();
                        }
                        default -> skipBalancedParens();
                    }
                    continue;
                }

                // Anything else: skip to next statement
                skipToNextStatement();
            }

            return new ScriptAnalysis(signals, computeds, functions, watches,
                    watchEffects, reactives, lifecycles, hasEmit);
        }

        // ─── Token reading ──────────────────────────────────────────────

        private String readIdentifier() {
            int start = pos;
            while (pos < source.length() && isIdentChar(source.charAt(pos))) pos++;
            return source.substring(start, pos);
        }

        private String peekIdentifier() {
            int saved = pos;
            String id = readIdentifier();
            pos = saved;
            return id;
        }

        /**
         * Read balanced parens content: assumes pos is at '(', returns content between ( and ),
         * advances pos past the closing ')'.
         */
        private String readBalancedParens() {
            if (pos >= source.length() || source.charAt(pos) != '(') return "";
            pos++; // skip '('
            int start = pos;
            int depth = 1;
            while (pos < source.length() && depth > 0) {
                char ch = source.charAt(pos);
                if (ch == '(' || ch == '[' || ch == '{') { depth++; pos++; }
                else if (ch == ')' || ch == ']' || ch == '}') { depth--; if (depth > 0) pos++; }
                else if (ch == '\'' || ch == '"' || ch == '`') { skipString(ch); }
                else if (ch == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') { skipLineComment(); }
                else if (ch == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '*') { skipBlockComment(); }
                else { pos++; }
            }
            String content = source.substring(start, pos);
            if (pos < source.length()) pos++; // skip closing ')'
            return content.trim();
        }

        /** Skip balanced parens without capturing content. */
        private void skipBalancedParens() {
            readBalancedParens();
        }

        /** Read balanced braces content: assumes pos is at '{', returns inner content. */
        private String readBalancedBraces() {
            if (pos >= source.length() || source.charAt(pos) != '{') return "";
            pos++; // skip '{'
            int start = pos;
            int depth = 1;
            while (pos < source.length() && depth > 0) {
                char ch = source.charAt(pos);
                if (ch == '{') { depth++; pos++; }
                else if (ch == '}') { depth--; if (depth > 0) pos++; }
                else if (ch == '\'' || ch == '"' || ch == '`') { skipString(ch); }
                else if (ch == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '/') { skipLineComment(); }
                else if (ch == '/' && pos + 1 < source.length() && source.charAt(pos + 1) == '*') { skipBlockComment(); }
                else { pos++; }
            }
            String content = source.substring(start, pos);
            if (pos < source.length()) pos++; // skip closing '}'
            return content.trim();
        }

        /** Read until end of expression (newline, semicolon, or EOF). */
        private String readExpressionUntilNewline() {
            int start = pos;
            while (pos < source.length()) {
                char ch = source.charAt(pos);
                if (ch == '\n' || ch == ';') break;
                if (ch == '\'' || ch == '"' || ch == '`') { skipString(ch); continue; }
                pos++;
            }
            return source.substring(start, pos).trim();
        }

        /** Read content until the matching ')' for an already-opened '(' at depth 1. */
        private String readUntilMatchingParen() {
            int start = pos;
            int depth = 1;
            while (pos < source.length() && depth > 0) {
                char ch = source.charAt(pos);
                if (ch == '(' || ch == '[' || ch == '{') depth++;
                else if (ch == ')') { depth--; if (depth == 0) break; }
                else if (ch == ']' || ch == '}') depth--;
                else if (ch == '\'' || ch == '"' || ch == '`') { skipString(ch); continue; }
                pos++;
            }
            return source.substring(start, pos).trim();
        }

        /** Skip to the closing ')' of an already-opened watch( call. */
        private void skipToCloseParen() {
            int depth = 1;
            while (pos < source.length() && depth > 0) {
                char ch = source.charAt(pos);
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == '\'' || ch == '"' || ch == '`') skipString(ch);
                pos++;
            }
        }

        // ─── String/comment skipping ────────────────────────────────────

        private void skipString(char quote) {
            pos++; // skip opening quote
            if (quote == '`') {
                // Template literal — handle ${...} nesting
                while (pos < source.length()) {
                    char ch = source.charAt(pos);
                    if (ch == '`') { pos++; return; }
                    if (ch == '\\') { pos += 2; continue; }
                    if (ch == '$' && pos + 1 < source.length() && source.charAt(pos + 1) == '{') {
                        pos += 2;
                        int depth = 1;
                        while (pos < source.length() && depth > 0) {
                            char c = source.charAt(pos);
                            if (c == '{') depth++;
                            else if (c == '}') depth--;
                            if (depth > 0) pos++;
                        }
                        if (pos < source.length()) pos++; // skip '}'
                        continue;
                    }
                    pos++;
                }
            } else {
                while (pos < source.length()) {
                    char ch = source.charAt(pos);
                    if (ch == quote) { pos++; return; }
                    if (ch == '\\') { pos++; } // skip escaped char
                    pos++;
                }
            }
        }

        private void skipLineComment() {
            while (pos < source.length() && source.charAt(pos) != '\n') pos++;
        }

        private void skipBlockComment() {
            pos += 2; // skip /*
            while (pos + 1 < source.length()) {
                if (source.charAt(pos) == '*' && source.charAt(pos + 1) == '/') {
                    pos += 2;
                    return;
                }
                pos++;
            }
            pos = source.length();
        }

        // ─── Whitespace ─────────────────────────────────────────────────

        private void skipWhitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
        }

        private void skipWhitespaceAndComments() {
            while (pos < source.length()) {
                char ch = source.charAt(pos);
                if (Character.isWhitespace(ch)) { pos++; continue; }
                if (ch == '/' && pos + 1 < source.length()) {
                    if (source.charAt(pos + 1) == '/') { skipLineComment(); continue; }
                    if (source.charAt(pos + 1) == '*') { skipBlockComment(); continue; }
                }
                break;
            }
        }

        // ─── Navigation ─────────────────────────────────────────────────

        private void skipToNextStatement() {
            while (pos < source.length()) {
                char ch = source.charAt(pos);
                if (ch == ';' || ch == '\n') { pos++; return; }
                if (ch == '\'' || ch == '"' || ch == '`') { skipString(ch); continue; }
                if (ch == '{') { skipBalancedBlock('{', '}'); continue; }
                if (ch == '(') { skipBalancedBlock('(', ')'); continue; }
                if (ch == '[') { skipBalancedBlock('[', ']'); continue; }
                pos++;
            }
        }

        private void skipBalancedBlock(char open, char close) {
            pos++; // skip open
            int depth = 1;
            while (pos < source.length() && depth > 0) {
                char ch = source.charAt(pos);
                if (ch == open) depth++;
                else if (ch == close) depth--;
                else if (ch == '\'' || ch == '"' || ch == '`') { skipString(ch); continue; }
                pos++;
            }
        }

        private void skipUntilAfter(char... chars) {
            while (pos < source.length()) {
                char ch = source.charAt(pos);
                for (char c : chars) {
                    if (ch == c) { pos++; return; }
                }
                if (ch == '\'' || ch == '"' || ch == '`') { skipString(ch); continue; }
                pos++;
            }
        }

        private boolean lookingAt(String s) {
            return source.startsWith(s, pos);
        }

        private static boolean isIdentChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }

        private static boolean isBuiltinCall(String name) {
            return "defineProps".equals(name) || "defineEmits".equals(name)
                    || "defineModel".equals(name);
        }

        // ─── Callback extraction ────────────────────────────────────────

        /** Extract the body from a callback argument: () => expr OR () => { ... } OR function() { ... } */
        private String extractCallbackBody(String callbackStr) {
            String s = callbackStr.trim();
            int arrow = findArrowIndex(s);
            if (arrow >= 0) {
                String afterArrow = s.substring(arrow + 2).trim();
                if (afterArrow.startsWith("{")) {
                    return extractBalancedBracesStatic(afterArrow);
                }
                // Concise body: take until comma at depth 0 (may have trailing options)
                return extractUntilCommaAtDepth0(afterArrow);
            }
            if (s.startsWith("function")) {
                int braceStart = s.indexOf('{');
                if (braceStart >= 0) {
                    return extractBalancedBracesStatic(s.substring(braceStart));
                }
            }
            return s;
        }

        private record ParsedCallback(String params, String body) {}

        private ParsedCallback parseCallback(String s) {
            s = s.trim();
            int arrow = findArrowIndex(s);
            if (arrow >= 0) {
                String before = s.substring(0, arrow).trim();
                String afterArrow = s.substring(arrow + 2).trim();
                String params = before.startsWith("(") && before.endsWith(")")
                        ? before.substring(1, before.length() - 1).trim() : before;
                String body;
                if (afterArrow.startsWith("{")) {
                    body = extractBalancedBracesStatic(afterArrow);
                } else {
                    body = extractUntilCommaAtDepth0(afterArrow);
                }
                return new ParsedCallback(params, body);
            }
            if (s.startsWith("function")) {
                int openParen = s.indexOf('(');
                if (openParen >= 0) {
                    int closeParen = findMatchingClose(s, openParen, '(', ')');
                    if (closeParen > openParen) {
                        String params = s.substring(openParen + 1, closeParen).trim();
                        String rest = s.substring(closeParen + 1).trim();
                        String body = rest.startsWith("{") ? extractBalancedBracesStatic(rest) : "";
                        return new ParsedCallback(params, body);
                    }
                }
            }
            return null;
        }

        /** Find the position of the matching close delimiter, respecting nesting and strings. */
        private static int findMatchingClose(String s, int openPos, char open, char close) {
            int depth = 0;
            boolean inStr = false;
            char strQuote = 0;
            for (int i = openPos; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (inStr) {
                    if (ch == '\\') { i++; continue; }
                    if (ch == strQuote) inStr = false;
                    continue;
                }
                if (ch == '\'' || ch == '"' || ch == '`') { inStr = true; strQuote = ch; continue; }
                if (ch == open) depth++;
                else if (ch == close) { depth--; if (depth == 0) return i; }
            }
            return -1;
        }

        /** Extract content inside balanced braces from a static string (not using parser pos). */
        private static String extractBalancedBracesStatic(String s) {
            if (!s.startsWith("{")) return "";
            int depth = 0;
            boolean inStr = false;
            char strQuote = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (inStr) {
                    if (ch == '\\') { i++; continue; }
                    if (ch == strQuote) inStr = false;
                    continue;
                }
                if (ch == '\'' || ch == '"' || ch == '`') { inStr = true; strQuote = ch; continue; }
                if (ch == '{') depth++;
                else if (ch == '}') {
                    depth--;
                    if (depth == 0) return s.substring(1, i).trim();
                }
            }
            return s.substring(1).trim();
        }

        /** Extract expression until comma at depth 0 (to separate callback from trailing options). */
        private static String extractUntilCommaAtDepth0(String s) {
            int depth = 0;
            boolean inStr = false;
            char strQuote = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (inStr) {
                    if (ch == '\\') { i++; continue; }
                    if (ch == strQuote) inStr = false;
                    continue;
                }
                if (ch == '\'' || ch == '"' || ch == '`') { inStr = true; strQuote = ch; continue; }
                if (ch == '(' || ch == '[' || ch == '{') depth++;
                else if (ch == ')' || ch == ']' || ch == '}') depth--;
                else if (ch == ',' && depth == 0) return s.substring(0, i).trim();
            }
            return s.trim();
        }

        private static int findArrowIndex(String s) {
            int depth = 0;
            boolean inStr = false;
            char strQuote = 0;
            for (int i = 0; i < s.length() - 1; i++) {
                char ch = s.charAt(i);
                if (inStr) {
                    if (ch == '\\') { i++; continue; }
                    if (ch == strQuote) inStr = false;
                    continue;
                }
                if (ch == '\'' || ch == '"' || ch == '`') { inStr = true; strQuote = ch; continue; }
                if (ch == '(' || ch == '[' || ch == '{') depth++;
                else if (ch == ')' || ch == ']' || ch == '}') depth--;
                else if (depth == 0 && ch == '=' && s.charAt(i + 1) == '>') return i;
            }
            return -1;
        }
    }
}
