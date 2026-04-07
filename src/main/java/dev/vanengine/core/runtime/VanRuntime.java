package dev.vanengine.core.runtime;

import dev.vanengine.core.support.VanAst;
import dev.vanengine.core.support.VanAst.Attr;
import dev.vanengine.core.support.VanAst.Node;
import dev.vanengine.core.support.VanUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side rendering processor for Van templates.
 * AST-based single-pass: parse → walk tree (v-for/v-if/v-show/:class/{{ }}) → serialize.
 */
public class VanRuntime {

    private static final Logger log = LoggerFactory.getLogger(VanRuntime.class);

    private static final Set<String> SELF_CLOSING = VanUtil.VOID_ELEMENTS;

    private static final Set<String> BOOLEAN_ATTRS = Set.of(
            "selected", "disabled", "checked", "readonly", "required",
            "multiple", "autofocus", "autoplay", "controls", "loop",
            "muted", "open", "hidden", "novalidate");

    private VanRuntime() {}

    /** Default render timeout: 5 seconds. */
    private static final long DEFAULT_TIMEOUT_MS = 5_000;
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process all SSR directives with default timeout (5s).
     */
    public static String processAll(String html, Map<String, Object> scope) {
        return processAll(html, scope, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Process all SSR directives with custom timeout.
     * @param timeoutMs maximum processing time in milliseconds (0 = no limit)
     */
    public static String processAll(String html, Map<String, Object> scope, long timeoutMs) {
        List<Node> nodes = VanAst.parse(html);
        runWithTimeout(timeoutMs, () -> processNodes(nodes, scope));
        return VanAst.toHtml(nodes);
    }

    /**
     * Process on pre-parsed AST nodes (avoids re-parsing for cached templates).
     */
    public static String renderAst(List<Node> nodes, Map<String, Object> scope) {
        runWithTimeout(DEFAULT_TIMEOUT_MS, () -> processNodes(nodes, scope));
        return VanAst.toHtml(nodes);
    }

    private static void runWithTimeout(long timeoutMs, Runnable task) {
        Long prevDeadline = DEADLINE.get();
        try {
            DEADLINE.set(timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0L);
            task.run();
        } finally {
            if (prevDeadline == null) DEADLINE.remove();
            else DEADLINE.set(prevDeadline);
        }
    }

    private static final int TIMEOUT_CHECK_INTERVAL = 64;
    private static final ThreadLocal<int[]> NODE_COUNTER = ThreadLocal.withInitial(() -> new int[]{0});

    private static void checkTimeout() {
        int[] counter = NODE_COUNTER.get();
        if (++counter[0] < TIMEOUT_CHECK_INTERVAL) return;
        counter[0] = 0;
        Long deadline = DEADLINE.get();
        if (deadline != null && deadline > 0 && System.currentTimeMillis() > deadline) {
            throw new IllegalStateException("Template render timeout exceeded");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AST tree walk — single pass handles all directives
    // ═══════════════════════════════════════════════════════════════

    private static void processNodes(List<Node> nodes, Map<String, Object> scope) {
        int i = 0;
        while (i < nodes.size()) {
            checkTimeout();
            Node node = nodes.get(i);

            // Client-only region: skip to closing comment
            if (node instanceof Node.Comment c && "client-only".equals(c.content())) {
                i = skipClientOnlyRegion(nodes, i);
                continue;
            }

            if (node instanceof Node.Element elem) {
                i = processElement(nodes, i, elem, scope);
            } else if (node instanceof Node.Text text) {
                processTextNode(text, scope);
                i++;
            } else {
                i++;
            }
        }
    }

    /**
     * Process a single element. Returns the next index to process.
     */
    private static int processElement(List<Node> siblings, int index,
                                       Node.Element elem, Map<String, Object> scope) {
        // Skip raw text elements — their content is not template syntax
        String tagLower = elem.tag().toLowerCase();
        if ("script".equals(tagLower) || "style".equals(tagLower)) {
            return index + 1;
        }

        // 1. v-for — expand in place
        if (elem.hasAttr("v-for")) {
            return expandVFor(siblings, index, elem, scope);
        }

        // 2. v-if — conditional chain
        if (elem.hasAttr("v-if")) {
            return processConditional(siblings, index, scope);
        }

        // 3. Process remaining directives + recurse
        processElementBody(elem, scope);
        return index + 1;
    }

    /**
     * Process all non-structural directives on an element, then recurse into children.
     */
    private static void processElementBody(Node.Element elem, Map<String, Object> scope) {
        // v-show
        String vshow = elem.getAttr("v-show");
        if (vshow != null) {
            boolean visible = VanExpressions.isTruthy(vshow, scope);
            elem.removeAttr("v-show");
            if (!visible) {
                mergeStyle(elem, "display:none");
            }
        }

        boolean childrenReplaced = false;

        // v-html
        String vhtml = elem.getAttr("v-html");
        if (vhtml != null) {
            Object value = VanExpressions.evaluate(vhtml, scope);
            elem.removeAttr("v-html");
            elem.children().clear();
            elem.children().add(new Node.Text(value != null ? value.toString() : ""));
            childrenReplaced = true;
        }

        // v-text
        if (!childrenReplaced) {
            String vtext = elem.getAttr("v-text");
            if (vtext != null) {
                Object value = VanExpressions.evaluate(vtext, scope);
                elem.removeAttr("v-text");
                elem.children().clear();
                elem.children().add(new Node.Text(
                        value != null ? VanUtil.escapeHtml(value.toString()) : ""));
                childrenReplaced = true;
            }
        }

        // :attr bindings
        processBindings(elem, scope);

        // Strip client directives (@click, v-model, etc.)
        stripClientDirectives(elem);

        // Interpolate {{ }} in attribute values
        interpolateAttrs(elem, scope);

        // Recurse into children (unless replaced by v-html/v-text)
        if (!childrenReplaced) {
            processNodes(elem.children(), scope);
        }
    }

    // ── v-for ──

    private static int expandVFor(List<Node> siblings, int index,
                                   Node.Element elem, Map<String, Object> scope) {
        String vforExpr = elem.getAttr("v-for");
        VanUtil.VForExpr parsed = VanUtil.parseVForExpr(vforExpr);
        if (parsed.arrayExpr().isEmpty()) {
            log.warn("Invalid v-for expression: '{}' on <{}>", vforExpr, elem.tag());
            elem.removeAttr("v-for");
            processElementBody(elem, scope);
            processNodes(elem.children(), scope);
            return index + 1;
        }

        Object collection = VanExpressions.evaluate(parsed.arrayExpr(), scope);
        List<IterItem> iterItems = normalizeCollection(collection, parsed.arrayExpr());

        boolean isTemplate = "template".equalsIgnoreCase(elem.tag());
        elem.removeAttr("v-for");
        elem.removeAttr(":key");

        siblings.remove(index);

        List<Node> expanded = new ArrayList<>();
        for (int idx = 0; idx < iterItems.size(); idx++) {
            checkTimeout();
            IterItem iter = iterItems.get(idx);
            var iterScope = new HashMap<>(scope);

            if (parsed.isDestructured()) {
                // { name, id } in items → put each field into scope
                if (iter.value instanceof Map<?,?> map) {
                    for (String key : parsed.destructKeys()) {
                        iterScope.put(key, map.get(key));
                    }
                }
            } else {
                iterScope.put(parsed.itemVar(), iter.value);
            }
            if (parsed.indexVar() != null) iterScope.put(parsed.indexVar(), iter.key);

            if (isTemplate) {
                List<Node> copies = new ArrayList<>();
                for (Node child : elem.children()) copies.add(VanAst.copyNode(child));
                processNodes(copies, iterScope);
                expanded.addAll(copies);
            } else {
                Node.Element copy = elem.copy();
                List<Node> singleList = new ArrayList<>();
                singleList.add(copy);
                processNodes(singleList, iterScope);
                expanded.addAll(singleList);
            }
        }

        siblings.addAll(index, expanded);
        return index + expanded.size();
    }

    private record IterItem(Object value, Object key) {}

    @SuppressWarnings("unchecked")
    private static List<IterItem> normalizeCollection(Object collection, String expr) {
        if (collection instanceof List<?> list) {
            List<IterItem> items = new ArrayList<>(list.size());
            for (int j = 0; j < list.size(); j++) items.add(new IterItem(list.get(j), j));
            return items;
        }
        if (collection instanceof Map<?,?> map) {
            List<IterItem> items = new ArrayList<>(map.size());
            for (var entry : map.entrySet()) items.add(new IterItem(entry.getValue(), entry.getKey()));
            return items;
        }
        if (collection instanceof Number n) {
            int count = n.intValue();
            List<IterItem> items = new ArrayList<>(Math.max(count, 0));
            for (int j = 1; j <= count; j++) items.add(new IterItem(j, j - 1));
            return items;
        }
        if (collection instanceof Collection<?> c) {
            List<IterItem> items = new ArrayList<>(c.size());
            int j = 0;
            for (Object item : c) items.add(new IterItem(item, j++));
            return items;
        }
        if (collection != null) {
            log.warn("v-for collection is not iterable: '{}' (resolved to {})", expr,
                    collection.getClass().getSimpleName());
        }
        return List.of();
    }

    // ── v-if / v-else-if / v-else ──

    private static int processConditional(List<Node> siblings, int index,
                                           Map<String, Object> scope) {
        Node.Element elem = (Node.Element) siblings.get(index);
        String expr = elem.getAttr("v-if");
        boolean condition = VanExpressions.isTruthy(expr, scope);

        if (condition) {
            elem.removeAttr("v-if");
            removeElseBranches(siblings, index + 1);
            processElementBody(elem, scope);
            return index + 1;
        } else {
            siblings.remove(index);
            return handleElseChain(siblings, index, scope);
        }
    }

    private static int handleElseChain(List<Node> siblings, int fromIndex,
                                        Map<String, Object> scope) {
        // Find next element sibling (skip whitespace text and comments)
        int nextIdx = fromIndex;
        while (nextIdx < siblings.size()) {
            Node n = siblings.get(nextIdx);
            if (n instanceof Node.Text t && t.content().isBlank()) {
                nextIdx++;
                continue;
            }
            if (n instanceof Node.Comment) {
                nextIdx++;
                continue;
            }
            if (n instanceof Node.Element nextElem) {
                String elseIfExpr = nextElem.getAttr("v-else-if");
                if (elseIfExpr != null) {
                    boolean condition = VanExpressions.isTruthy(elseIfExpr, scope);
                    if (condition) {
                        nextElem.removeAttr("v-else-if");
                        removeElseBranches(siblings, nextIdx + 1);
                        processElementBody(nextElem, scope);
                        return nextIdx + 1;
                    } else {
                        siblings.remove(nextIdx);
                        return handleElseChain(siblings, fromIndex, scope);
                    }
                }
                if (nextElem.hasAttr("v-else")) {
                    nextElem.removeAttr("v-else");
                    processElementBody(nextElem, scope);
                    return nextIdx + 1;
                }
            }
            break;
        }
        return fromIndex;
    }

    private static void removeElseBranches(List<Node> siblings, int fromIndex) {
        int i = fromIndex;
        while (i < siblings.size()) {
            Node node = siblings.get(i);
            if (node instanceof Node.Text t && t.content().isBlank()) {
                i++;
                continue;
            }
            if (node instanceof Node.Comment) {
                i++;
                continue;
            }
            if (node instanceof Node.Element elem) {
                if (elem.hasAttr("v-else-if") || elem.hasAttr("v-else")) {
                    siblings.remove(i);
                    continue;
                }
            }
            break;
        }
    }

    // ── :attr bindings ──

    private static void processBindings(Node.Element elem, Map<String, Object> scope) {
        // Collect binding attrs first (avoid ConcurrentModification)
        List<Attr> bindings = new ArrayList<>();
        for (Attr attr : elem.attrs()) {
            if (attr.name().startsWith(":") && !attr.name().startsWith(":[")) {
                bindings.add(attr);
            }
        }

        for (Attr attr : bindings) {
            String attrName = attr.name().substring(1);
            String expr = attr.value();
            elem.removeAttr(attr.name());

            if ("class".equals(attrName)) {
                processClassBinding(elem, expr, scope);
            } else if ("style".equals(attrName)) {
                processStyleBinding(elem, expr, scope);
            } else if ("key".equals(attrName)) {
                // :key is for v-for — strip silently
            } else if (BOOLEAN_ATTRS.contains(attrName)) {
                if (VanExpressions.isTruthy(expr, scope)) {
                    elem.setAttr(attrName, null);
                }
            } else {
                Object value = VanExpressions.evaluate(expr, scope);
                String strValue = value != null ? VanUtil.escapeHtml(value.toString()) : "";
                elem.setAttr(attrName, strValue);
            }
        }
    }

    private static void processClassBinding(Node.Element elem, String expr,
                                              Map<String, Object> scope) {
        String dynamicClasses;
        String trimmed = expr.trim();
        if (trimmed.startsWith("{")) {
            dynamicClasses = evaluateClassObject(expr, scope);
        } else if (trimmed.startsWith("[")) {
            dynamicClasses = evaluateClassArray(expr, scope);
        } else {
            Object result = VanExpressions.evaluate(expr, scope);
            dynamicClasses = classValueToString(result, scope);
        }

        if (!dynamicClasses.isEmpty()) {
            String existing = elem.getAttr("class");
            if (existing != null && !existing.isEmpty()) {
                elem.setAttr("class", existing + " " + dynamicClasses);
            } else {
                elem.setAttr("class", dynamicClasses);
            }
        }
    }

    /** Vue :class array syntax: [expr, 'static', { active: cond }] */
    private static String evaluateClassArray(String expr, Map<String, Object> scope) {
        Object result = VanExpressions.evaluate(expr, scope);
        return classValueToString(result, scope);
    }

    /** Convert a :class value (String, List, Map) to a space-separated class string. */
    @SuppressWarnings("unchecked")
    private static String classValueToString(Object value, Map<String, Object> scope) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof List<?> list) {
            var sb = new StringBuilder();
            for (Object item : list) {
                String part = classValueToString(item, scope);
                if (!part.isEmpty()) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(part);
                }
            }
            return sb.toString();
        }
        if (value instanceof Map<?,?> map) {
            var sb = new StringBuilder();
            for (var entry : map.entrySet()) {
                if (VanExpressions.isTruthy(entry.getValue())) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(entry.getKey());
                }
            }
            return sb.toString();
        }
        return value.toString();
    }

    private static String evaluateClassObject(String expr, Map<String, Object> scope) {
        String inner = expr.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);

        var classes = new StringBuilder();
        for (String entry : VanUtil.splitRespectingNesting(inner)) {
            entry = entry.trim();
            int colonPos = findClassEntryColon(entry);
            if (colonPos < 0) continue;

            String classNames = entry.substring(0, colonPos).trim();
            String condition = entry.substring(colonPos + 1).trim();

            if ((classNames.startsWith("'") && classNames.endsWith("'"))
                    || (classNames.startsWith("\"") && classNames.endsWith("\""))) {
                classNames = classNames.substring(1, classNames.length() - 1);
            }

            if (VanExpressions.isTruthy(condition, scope)) {
                if (!classes.isEmpty()) classes.append(" ");
                classes.append(classNames);
            }
        }
        return classes.toString();
    }

    private static int findClassEntryColon(String entry) {
        char inQuote = 0;
        for (int i = 0; i < entry.length(); i++) {
            char ch = entry.charAt(i);
            if (inQuote != 0) {
                if (ch == inQuote) inQuote = 0;
            } else if (ch == '\'' || ch == '"') {
                inQuote = ch;
            } else if (ch == ':') {
                return i;
            }
        }
        return -1;
    }

    private static void processStyleBinding(Node.Element elem, String expr,
                                              Map<String, Object> scope) {
        String inner = expr.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);

        var styles = new StringBuilder();
        for (String entry : VanUtil.splitRespectingNesting(inner)) {
            entry = entry.trim();
            int colonPos = findClassEntryColon(entry);
            if (colonPos < 0) continue;

            String prop = entry.substring(0, colonPos).trim();
            String valueExpr = entry.substring(colonPos + 1).trim();

            if ((prop.startsWith("'") && prop.endsWith("'"))
                    || (prop.startsWith("\"") && prop.endsWith("\""))) {
                prop = prop.substring(1, prop.length() - 1);
            }

            Object value = VanExpressions.evaluate(valueExpr, scope);
            if (value != null) {
                if (!styles.isEmpty()) styles.append("; ");
                styles.append(prop).append(": ").append(value);
            }
        }

        String styleStr = styles.toString();
        if (!styleStr.isEmpty()) {
            mergeStyle(elem, styleStr);
        }
    }

    // ── Style/class merge helpers ──

    private static void mergeStyle(Node.Element elem, String styleValue) {
        String existing = elem.getAttr("style");
        if (existing != null && !existing.isEmpty()) {
            elem.setAttr("style", styleValue + "; " + existing);
        } else {
            elem.setAttr("style", styleValue);
        }
    }

    // ── Strip client directives ──

    private static void stripClientDirectives(Node.Element elem) {
        elem.attrs().removeIf(a -> {
            String name = a.name();
            return name.startsWith("@") || name.equals("v-model") || name.startsWith("v-model.");
        });
    }

    // ── {{ }} interpolation ──

    private static void processTextNode(Node.Text text, Map<String, Object> scope) {
        String content = text.content();
        if (!content.contains("{{")) return;

        // {{{ expr }}} → raw (no escaping)
        content = interpolatePass(content, VanUtil.TRIPLE_MUSTACHE, scope, false);
        // {{ expr }} → HTML-escaped
        content = interpolatePass(content, VanUtil.MUSTACHE, scope, true);

        text.setContent(content);
    }

    private static void interpolateAttrs(Node.Element elem, Map<String, Object> scope) {
        for (int i = 0; i < elem.attrs().size(); i++) {
            Attr attr = elem.attrs().get(i);
            if (attr.value() != null && attr.value().contains("{{")) {
                String newVal = interpolatePass(attr.value(), VanUtil.MUSTACHE, scope, false);
                elem.attrs().set(i, new Attr(attr.name(), newVal));
            }
        }
    }

    private static final Pattern T_CALL = Pattern.compile(
            "\\$t\\(\\s*['\"](.+?)['\"]\\s*(?:,\\s*\\{(.+?)})?\\s*\\)");

    private static String interpolatePass(String text, Pattern pattern,
                                           Map<String, Object> scope, boolean escape) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1);
            if (expr.trim().startsWith("$t(")) {
                // Resolve i18n inline if $i18n is in scope
                String resolved = resolveT(expr.trim(), scope);
                if (resolved != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(escape ? VanUtil.escapeHtml(resolved) : resolved));
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
                continue;
            }
            Object value = VanExpressions.evaluate(expr, scope);
            if (value != null) {
                String str = value.toString();
                m.appendReplacement(sb, Matcher.quoteReplacement(escape ? VanUtil.escapeHtml(str) : str));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── i18n $t() resolution ──

    private static String resolveT(String expr, Map<String, Object> scope) {
        Matcher m = T_CALL.matcher(expr);
        if (!m.matches()) return null;

        String key = m.group(1);
        String paramsStr = m.group(2);

        Object i18nObj = scope.get("$i18n");
        if (!(i18nObj instanceof Map<?, ?> i18nMap)) return null;

        Object messageObj = VanUtil.resolveNestedKey(i18nMap, key);
        if (messageObj == null) return key;

        String message = messageObj.toString();
        Map<String, String> params = new HashMap<>();
        if (paramsStr != null) {
            for (String part : VanUtil.splitRespectingNesting(paramsStr)) {
                String trimmed = part.trim();
                int colonPos = trimmed.indexOf(':');
                if (colonPos < 0) continue;
                String paramKey = trimmed.substring(0, colonPos).trim();
                String paramVal = trimmed.substring(colonPos + 1).trim();
                if ((paramVal.startsWith("'") && paramVal.endsWith("'"))
                        || (paramVal.startsWith("\"") && paramVal.endsWith("\""))) {
                    params.put(paramKey, paramVal.substring(1, paramVal.length() - 1));
                } else {
                    Object value = VanExpressions.evaluate(paramVal, scope);
                    params.put(paramKey, value != null ? value.toString() : paramVal);
                }
            }
        }

        if (message.contains("|")) {
            int count = -1;
            if (params.containsKey("count")) {
                try { count = Integer.parseInt(params.get("count")); } catch (NumberFormatException ignored) {}
            }
            message = dev.vanengine.core.i18n.MessageFormatter.resolvePlural(message, count);
        }

        for (Map.Entry<String, String> entry : params.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    // ── Client-only region skip ──

    private static int skipClientOnlyRegion(List<Node> nodes, int commentIndex) {
        // Skip past <!--client-only-->...<!--/client-only-->
        int i = commentIndex + 1;
        while (i < nodes.size()) {
            Node n = nodes.get(i);
            if (n instanceof Node.Comment c && "/client-only".equals(c.content())) {
                return i + 1;
            }
            i++;
        }
        return i;
    }
}
