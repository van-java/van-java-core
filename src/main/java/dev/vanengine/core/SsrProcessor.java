package dev.vanengine.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side rendering processor for Van templates.
 * Handles v-for, v-if/v-else-if/v-else, v-show, :class/:style/:attr bindings,
 * and strips client-only directives (@click, v-model).
 *
 * <p>Processing is recursive: v-for expansion calls the full pipeline per iteration,
 * so loop variables are in scope for inner v-if, :class, {{ }} etc.
 *
 * <p>Regions between {@code <!--client-only-->} and {@code <!--/client-only-->}
 * are skipped (already handled by Rust compiler).
 */
public class SsrProcessor {

    private static final Logger log = LoggerFactory.getLogger(SsrProcessor.class);

    private static final Set<String> SELF_CLOSING = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    private static final Set<String> BOOLEAN_ATTRS = Set.of(
            "selected", "disabled", "checked", "readonly", "required",
            "multiple", "autofocus", "autoplay", "controls", "loop",
            "muted", "open", "hidden", "novalidate");

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    /**
     * Process all SSR directives in the HTML with the given scope.
     * This is the recursive entry point — v-for calls this per iteration.
     */
    public String processAll(String html, Map<String, Object> scope) {
        html = processVFor(html, scope);
        html = processConditionals(html, scope);
        html = processVShow(html, scope);
        html = processVHtmlVText(html, scope);
        html = processBindings(html, scope);
        html = processInterpolation(html, scope);
        html = stripClientDirectives(html);
        return html;
    }

    // ── v-for ──

    private static final Pattern V_FOR_ATTR = Pattern.compile(
            "\\s+v-for=\"([^\"]+)\"");
    private static final Pattern V_FOR_EXPR = Pattern.compile(
            "\\(?\\s*(\\w+)\\s*(?:,\\s*(\\w+))?\\s*\\)?\\s+in\\s+(.+)");

    String processVFor(String html, Map<String, Object> scope) {
        while (true) {
            // Find next v-for outside client-only regions
            int searchFrom = 0;
            TagMatch tag = null;
            while (searchFrom < html.length()) {
                tag = findTagWithAttribute(html, "v-for", searchFrom);
                if (tag == null) break;
                if (isInsideClientOnly(html, tag.start)) {
                    searchFrom = tag.start + 1;
                    tag = null;
                    continue;
                }
                break;
            }
            if (tag == null) break;

            String vForExpr = tag.getAttributeValue("v-for");
            Matcher m = V_FOR_EXPR.matcher(vForExpr);
            if (!m.matches()) {
                log.warn("Invalid v-for expression: {}", vForExpr);
                html = removeAttribute(html, tag, "v-for");
                continue;
            }

            String itemVar = m.group(1);
            String indexVar = m.group(2); // nullable
            String collectionExpr = m.group(3).trim();

            Object collection = evaluator.evaluate(collectionExpr, scope);
            List<?> items;
            if (collection instanceof List<?> list) {
                items = list;
            } else if (collection instanceof Collection<?> c) {
                items = new ArrayList<>(c);
            } else {
                log.warn("v-for collection is not iterable: {}", collectionExpr);
                items = List.of();
            }

            // Get inner content
            String innerHtml;
            int elementEnd;
            boolean isTemplate = "template".equalsIgnoreCase(tag.tagName);

            // Remove v-for and :key from the tag
            String cleanTag = tag.fullTag;
            cleanTag = removeAttributeFromTag(cleanTag, "v-for");
            cleanTag = removeAttributeFromTag(cleanTag, ":key");

            if (tag.selfClosing || SELF_CLOSING.contains(tag.tagName.toLowerCase())) {
                innerHtml = null;
                elementEnd = tag.end;
            } else {
                int closePos = findMatchingCloseTag(html, tag.tagName, tag.end);
                if (closePos < 0) {
                    log.warn("No matching close tag for v-for element: <{}>", tag.tagName);
                    break;
                }
                innerHtml = html.substring(tag.end, closePos);
                elementEnd = closePos + ("</" + tag.tagName + ">").length();
            }

            // Expand loop
            var expanded = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                var iterScope = new HashMap<>(scope);
                iterScope.put(itemVar, item);
                if (indexVar != null) {
                    iterScope.put(indexVar, i);
                }

                if (innerHtml == null) {
                    // Self-closing: just repeat the tag
                    String processedTag = processAll(cleanTag, iterScope);
                    expanded.append(processedTag);
                } else if (isTemplate) {
                    // <template> is virtual — render children only
                    String processedInner = processAll(innerHtml, iterScope);
                    expanded.append(processedInner);
                } else {
                    String processedTag = processBindingsInTag(cleanTag, iterScope);
                    String processedInner = processAll(innerHtml, iterScope);
                    expanded.append(processedTag).append(processedInner)
                            .append("</").append(tag.tagName).append(">");
                }
            }

            html = html.substring(0, tag.start) + expanded + html.substring(elementEnd);
        }
        return html;
    }

    // ── v-if / v-else-if / v-else ──

    String processConditionals(String html, Map<String, Object> scope) {
        int searchFrom = 0;
        while (true) {
            TagMatch tag = findTagWithAttribute(html, "v-if", searchFrom);
            if (tag == null) break;
            if (isInsideClientOnly(html, tag.start)) {
                searchFrom = tag.end;
                continue;
            }

            String expr = tag.getAttributeValue("v-if");
            boolean condition = evaluator.isTruthy(expr, scope);

            int elementEnd = findElementEnd(html, tag);
            if (elementEnd < 0) break;

            if (condition) {
                // Keep this element, remove v-if attribute
                String oldTag = tag.fullTag;
                html = removeAttribute(html, tag, "v-if");
                // Adjust elementEnd for the attribute removal
                int adjustment = oldTag.length() - removeAttributeFromTag(oldTag, "v-if").length();
                html = removeElseBranches(html, elementEnd - adjustment);
            } else {
                // Remove this element
                html = html.substring(0, tag.start) + html.substring(elementEnd);
                // Check for v-else-if or v-else at the same position
                html = processElseChain(html, tag.start, scope);
            }
        }
        return html;
    }

    private String processElseChain(String html, int pos, Map<String, Object> scope) {
        // Skip whitespace
        int i = pos;
        while (i < html.length() && Character.isWhitespace(html.charAt(i))) i++;

        // Check for v-else-if
        TagMatch nextTag = findTagAt(html, i);
        if (nextTag == null) return html;

        String elseIfExpr = nextTag.getAttributeValue("v-else-if");
        if (elseIfExpr != null) {
            boolean condition = evaluator.isTruthy(elseIfExpr, scope);
            int elementEnd = findElementEnd(html, nextTag);
            if (elementEnd < 0) return html;

            if (condition) {
                html = removeAttribute(html, nextTag, "v-else-if");
                html = removeElseBranches(html, elementEnd);
            } else {
                html = html.substring(0, nextTag.start) + html.substring(elementEnd);
                html = processElseChain(html, nextTag.start, scope);
            }
            return html;
        }

        // Check for v-else
        if (nextTag.hasAttribute("v-else")) {
            html = removeAttribute(html, nextTag, "v-else");
            return html;
        }

        return html;
    }

    private String removeElseBranches(String html, int afterPos) {
        while (true) {
            int i = afterPos;
            while (i < html.length() && Character.isWhitespace(html.charAt(i))) i++;

            TagMatch nextTag = findTagAt(html, i);
            if (nextTag == null) break;

            if (nextTag.hasAttribute("v-else-if") || nextTag.hasAttribute("v-else")) {
                int end = findElementEnd(html, nextTag);
                if (end < 0) break;
                html = html.substring(0, nextTag.start) + html.substring(end);
                afterPos = nextTag.start;
            } else {
                break;
            }
        }
        return html;
    }

    // ── v-show ──

    private static final Pattern V_SHOW_ATTR = Pattern.compile(
            "\\s+v-show=\"([^\"]+)\"");

    String processVShow(String html, Map<String, Object> scope) {
        int searchFrom = 0;
        while (true) {
            TagMatch tag = findTagWithAttribute(html, "v-show", searchFrom);
            if (tag == null) break;
            if (isInsideClientOnly(html, tag.start)) {
                searchFrom = tag.end;
                continue;
            }

            String expr = tag.getAttributeValue("v-show");
            boolean visible = evaluator.isTruthy(expr, scope);

            String newTag = removeAttributeFromTag(tag.fullTag, "v-show");

            if (!visible) {
                // Add display:none
                if (newTag.contains("style=\"")) {
                    newTag = newTag.replaceFirst("style=\"", "style=\"display:none; ");
                } else {
                    newTag = newTag.replaceFirst(">$", " style=\"display:none\">");
                    newTag = newTag.replaceFirst("/>$", " style=\"display:none\" />");
                }
            }

            html = html.substring(0, tag.start) + newTag + html.substring(tag.end);
        }
        return html;
    }

    // ── v-html / v-text ──

    String processVHtmlVText(String html, Map<String, Object> scope) {
        // v-html
        int searchFrom = 0;
        while (true) {
            TagMatch tag = findTagWithAttribute(html, "v-html", searchFrom);
            if (tag == null) break;
            if (isInsideClientOnly(html, tag.start)) {
                searchFrom = tag.end;
                continue;
            }

            String expr = tag.getAttributeValue("v-html");
            Object value = evaluator.evaluate(expr, scope);
            String content = value != null ? value.toString() : "";

            String newTag = removeAttributeFromTag(tag.fullTag, "v-html");
            int elementEnd = findElementEnd(html, tag);
            if (elementEnd < 0) break;

            String closeTag = "</" + tag.tagName + ">";
            html = html.substring(0, tag.start) + newTag + content + closeTag + html.substring(elementEnd);
        }

        // v-text
        searchFrom = 0;
        while (true) {
            TagMatch tag = findTagWithAttribute(html, "v-text", searchFrom);
            if (tag == null) break;
            if (isInsideClientOnly(html, tag.start)) {
                searchFrom = tag.end;
                continue;
            }

            String expr = tag.getAttributeValue("v-text");
            Object value = evaluator.evaluate(expr, scope);
            String content = value != null ? escapeHtml(value.toString()) : "";

            String newTag = removeAttributeFromTag(tag.fullTag, "v-text");
            int elementEnd = findElementEnd(html, tag);
            if (elementEnd < 0) break;

            String closeTag = "</" + tag.tagName + ">";
            html = html.substring(0, tag.start) + newTag + content + closeTag + html.substring(elementEnd);
        }

        return html;
    }

    // ── :attr bindings ──

    private static final Pattern BIND_ATTR = Pattern.compile(
            "\\s+:([a-zA-Z][a-zA-Z0-9-]*)=\"([^\"]+)\"");

    String processBindings(String html, Map<String, Object> scope) {
        // Process all tags that have :attr bindings
        var result = new StringBuilder();
        int i = 0;
        while (i < html.length()) {
            if (html.charAt(i) == '<' && i + 1 < html.length() && html.charAt(i + 1) != '/'
                    && html.charAt(i + 1) != '!') {
                // Check if inside client-only
                if (isInsideClientOnly(html, i)) {
                    // Skip to end of client-only
                    int coEnd = html.indexOf("<!--/client-only-->", i);
                    if (coEnd < 0) {
                        result.append(html.substring(i));
                        break;
                    }
                    result.append(html, i, coEnd + "<!--/client-only-->".length());
                    i = coEnd + "<!--/client-only-->".length();
                    continue;
                }

                int tagEnd = findTagEnd(html, i);
                if (tagEnd < 0) {
                    result.append(html.charAt(i));
                    i++;
                    continue;
                }

                String tag = html.substring(i, tagEnd);
                if (BIND_ATTR.matcher(tag).find()) {
                    tag = processBindingsInTag(tag, scope);
                }
                result.append(tag);
                i = tagEnd;
            } else {
                result.append(html.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    String processBindingsInTag(String tag, Map<String, Object> scope) {
        Matcher m = BIND_ATTR.matcher(tag);
        while (m.find()) {
            String attrName = m.group(1);
            String expr = m.group(2);

            if ("class".equals(attrName)) {
                tag = processClassBinding(tag, expr, scope);
            } else if ("style".equals(attrName)) {
                tag = processStyleBinding(tag, expr, scope);
            } else if (BOOLEAN_ATTRS.contains(attrName)) {
                tag = processBooleanBinding(tag, attrName, expr, scope);
            } else {
                // Regular :attr binding
                Object value = evaluator.evaluate(expr, scope);
                String strValue = value != null ? escapeHtml(value.toString()) : "";
                tag = tag.replace(":" + attrName + "=\"" + expr + "\"",
                        attrName + "=\"" + strValue + "\"");
            }
            m = BIND_ATTR.matcher(tag); // re-match after modification
        }
        return tag;
    }

    private String processClassBinding(String tag, String expr, Map<String, Object> scope) {
        String dynamicClasses;

        if (expr.trim().startsWith("{")) {
            // Object syntax: { 'class-name': condition, ... }
            dynamicClasses = evaluateClassObject(expr, scope);
        } else {
            // Expression (ternary, etc.)
            Object result = evaluator.evaluate(expr, scope);
            dynamicClasses = result != null ? result.toString() : "";
        }

        // Remove :class attribute
        tag = tag.replaceFirst("\\s*:class=\"[^\"]*\"", "");

        // Merge with static class
        if (tag.contains("class=\"")) {
            if (!dynamicClasses.isEmpty()) {
                tag = tag.replaceFirst("class=\"([^\"]*?)\"", "class=\"$1 " + dynamicClasses + "\"");
            }
        } else if (!dynamicClasses.isEmpty()) {
            tag = tag.replaceFirst(">$", " class=\"" + dynamicClasses + "\">");
            tag = tag.replaceFirst("\\s*/>$", " class=\"" + dynamicClasses + "\" />");
        }

        return tag;
    }

    private String evaluateClassObject(String expr, Map<String, Object> scope) {
        // Parse { 'class1 class2': condition1, 'class3': condition2 }
        String inner = expr.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);

        var classes = new StringBuilder();
        for (String entry : splitClassEntries(inner)) {
            entry = entry.trim();
            int colonPos = findClassEntryColon(entry);
            if (colonPos < 0) continue;

            String classNames = entry.substring(0, colonPos).trim();
            String condition = entry.substring(colonPos + 1).trim();

            // Remove quotes from class names
            if ((classNames.startsWith("'") && classNames.endsWith("'"))
                    || (classNames.startsWith("\"") && classNames.endsWith("\""))) {
                classNames = classNames.substring(1, classNames.length() - 1);
            }

            if (evaluator.isTruthy(condition, scope)) {
                if (!classes.isEmpty()) classes.append(" ");
                classes.append(classNames);
            }
        }
        return classes.toString();
    }

    private List<String> splitClassEntries(String s) {
        List<String> entries = new ArrayList<>();
        int depth = 0;
        char inQuote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inQuote != 0) {
                if (ch == inQuote) inQuote = 0;
            } else if (ch == '\'' || ch == '"') {
                inQuote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                entries.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) entries.add(s.substring(start));
        return entries;
    }

    private int findClassEntryColon(String entry) {
        // Find the colon that separates class name from condition
        // Must handle 'class:name': condition (colon inside quotes)
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

    private String processStyleBinding(String tag, String expr, Map<String, Object> scope) {
        // Parse { color: ctx.textColor, 'font-size': ctx.fontSize + 'px' }
        String inner = expr.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);

        var styles = new StringBuilder();
        for (String entry : splitClassEntries(inner)) {
            entry = entry.trim();
            int colonPos = findClassEntryColon(entry);
            if (colonPos < 0) continue;

            String prop = entry.substring(0, colonPos).trim();
            String valueExpr = entry.substring(colonPos + 1).trim();

            if ((prop.startsWith("'") && prop.endsWith("'"))
                    || (prop.startsWith("\"") && prop.endsWith("\""))) {
                prop = prop.substring(1, prop.length() - 1);
            }

            Object value = evaluator.evaluate(valueExpr, scope);
            if (value != null) {
                if (!styles.isEmpty()) styles.append("; ");
                styles.append(prop).append(": ").append(value);
            }
        }

        tag = tag.replaceFirst("\\s*:style=\"[^\"]*\"", "");

        String styleStr = styles.toString();
        if (tag.contains("style=\"")) {
            if (!styleStr.isEmpty()) {
                tag = tag.replaceFirst("style=\"([^\"]*?)\"", "style=\"$1; " + styleStr + "\"");
            }
        } else if (!styleStr.isEmpty()) {
            tag = tag.replaceFirst(">$", " style=\"" + styleStr + "\">");
            tag = tag.replaceFirst("\\s*/>$", " style=\"" + styleStr + "\" />");
        }

        return tag;
    }

    private String processBooleanBinding(String tag, String attrName, String expr,
                                         Map<String, Object> scope) {
        boolean value = evaluator.isTruthy(expr, scope);
        tag = tag.replaceFirst("\\s*:" + attrName + "=\"[^\"]*\"", "");
        if (value) {
            tag = tag.replaceFirst(">$", " " + attrName + ">");
            tag = tag.replaceFirst("\\s*/>$", " " + attrName + " />");
        }
        return tag;
    }

    // ── {{ }} interpolation ──

    private static final Pattern TRIPLE_MUSTACHE = Pattern.compile("\\{\\{\\{\\s*(.+?)\\s*}}}");
    private static final Pattern DOUBLE_MUSTACHE = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");

    /**
     * Resolve {{ expr }} and {{{ expr }}} against the current scope.
     * Expressions that resolve to a value are replaced; unresolvable ones
     * (including $t() calls) are left for VanTemplate.evaluate() to handle.
     */
    String processInterpolation(String html, Map<String, Object> scope) {
        // Pass 1: {{{ expr }}} → raw (no escaping)
        html = interpolatePass(html, TRIPLE_MUSTACHE, scope, false);
        // Pass 2: {{ expr }} → HTML-escaped
        html = interpolatePass(html, DOUBLE_MUSTACHE, scope, true);
        return html;
    }

    private String interpolatePass(String html, Pattern pattern, Map<String, Object> scope,
                                   boolean escape) {
        Matcher m = pattern.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1);
            // Skip $t() calls — leave for VanTemplate i18n handling
            if (expr.trim().startsWith("$t(")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            Object value = evaluator.evaluate(expr, scope);
            if (value != null) {
                String str = value.toString();
                m.appendReplacement(sb, Matcher.quoteReplacement(escape ? escapeHtml(str) : str));
            } else {
                // Unresolvable — leave as-is
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── Strip client directives ──

    private static final Pattern CLIENT_DIRECTIVE = Pattern.compile(
            "\\s+(?:@[a-zA-Z][a-zA-Z0-9.-]*|v-model)=\"[^\"]*\"");

    String stripClientDirectives(String html) {
        var result = new StringBuilder();
        int i = 0;
        while (i < html.length()) {
            if (html.charAt(i) == '<' && i + 1 < html.length()
                    && html.charAt(i + 1) != '/' && html.charAt(i + 1) != '!') {
                if (isInsideClientOnly(html, i)) {
                    result.append(html.charAt(i));
                    i++;
                    continue;
                }
                int tagEnd = findTagEnd(html, i);
                if (tagEnd < 0) {
                    result.append(html.charAt(i));
                    i++;
                    continue;
                }
                String tag = html.substring(i, tagEnd);
                tag = CLIENT_DIRECTIVE.matcher(tag).replaceAll("");
                result.append(tag);
                i = tagEnd;
            } else {
                result.append(html.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    // ── HTML parsing utilities ──

    record TagMatch(int start, int end, String fullTag, String tagName, boolean selfClosing) {
        String getAttributeValue(String attrName) {
            Pattern p = Pattern.compile("\\s+" + Pattern.quote(attrName) + "=\"([^\"]+)\"");
            Matcher m = p.matcher(fullTag);
            return m.find() ? m.group(1) : null;
        }

        boolean hasAttribute(String attrName) {
            // Match both attr="value" and standalone attr (v-else)
            return fullTag.matches("(?s).*\\s+" + Pattern.quote(attrName) + "(?:\\s|=|>|/).*");
        }
    }

    TagMatch findTagWithAttribute(String html, String attrName, int from) {
        Pattern p = Pattern.compile("<(\\w[\\w-]*)\\s[^>]*?" + Pattern.quote(attrName) + "(?:=|\\s|>|/)");
        Matcher m = p.matcher(html);
        if (!m.find(from)) return null;

        int tagStart = m.start();
        int tagEnd = findTagEnd(html, tagStart);
        if (tagEnd < 0) return null;

        String fullTag = html.substring(tagStart, tagEnd);
        String tagName = m.group(1);
        boolean selfClosing = fullTag.endsWith("/>");
        return new TagMatch(tagStart, tagEnd, fullTag, tagName, selfClosing);
    }

    private TagMatch findTagAt(String html, int pos) {
        if (pos >= html.length() || html.charAt(pos) != '<') return null;
        Matcher m = Pattern.compile("<(\\w[\\w-]*)").matcher(html);
        if (!m.find(pos) || m.start() != pos) return null;

        int tagEnd = findTagEnd(html, pos);
        if (tagEnd < 0) return null;

        String fullTag = html.substring(pos, tagEnd);
        String tagName = m.group(1);
        boolean selfClosing = fullTag.endsWith("/>");
        return new TagMatch(pos, tagEnd, fullTag, tagName, selfClosing);
    }

    int findTagEnd(String html, int from) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = from; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (inQuote) {
                if (ch == quoteChar) inQuote = false;
            } else if (ch == '"' || ch == '\'') {
                inQuote = true;
                quoteChar = ch;
            } else if (ch == '>') {
                return i + 1;
            }
        }
        return -1;
    }

    int findMatchingCloseTag(String html, String tagName, int from) {
        String openPattern = "<" + tagName;
        String closePattern = "</" + tagName + ">";
        int depth = 1;
        int i = from;
        while (i < html.length() && depth > 0) {
            int nextOpen = indexOfIgnoreCase(html, openPattern, i);
            int nextClose = indexOfIgnoreCase(html, closePattern, i);

            if (nextClose < 0) return -1; // No matching close tag

            if (nextOpen >= 0 && nextOpen < nextClose) {
                // Check if it's a real open tag (not just text containing the pattern)
                int afterOpen = findTagEnd(html, nextOpen);
                if (afterOpen > 0) {
                    String tag = html.substring(nextOpen, afterOpen);
                    if (!tag.endsWith("/>")) {
                        depth++;
                    }
                }
                i = nextOpen + openPattern.length();
            } else {
                depth--;
                if (depth == 0) return nextClose;
                i = nextClose + closePattern.length();
            }
        }
        return -1;
    }

    private int findElementEnd(String html, TagMatch tag) {
        if (tag.selfClosing || SELF_CLOSING.contains(tag.tagName.toLowerCase())) {
            return tag.end;
        }
        int closePos = findMatchingCloseTag(html, tag.tagName, tag.end);
        if (closePos < 0) return -1;
        return closePos + ("</" + tag.tagName + ">").length();
    }

    private static int indexOfIgnoreCase(String html, String pattern, int from) {
        String lower = html.toLowerCase();
        return lower.indexOf(pattern.toLowerCase(), from);
    }

    // ── Client-only region detection ──

    boolean isInsideClientOnly(String html, int pos) {
        int lastOpen = html.lastIndexOf("<!--client-only-->", pos);
        if (lastOpen < 0) return false;
        int lastClose = html.lastIndexOf("<!--/client-only-->", pos);
        return lastClose < lastOpen; // inside if last open is after last close
    }

    // ── Attribute manipulation ──

    private String removeAttribute(String html, TagMatch tag, String attrName) {
        String newTag = removeAttributeFromTag(tag.fullTag, attrName);
        return html.substring(0, tag.start) + newTag + html.substring(tag.end);
    }

    static String removeAttributeFromTag(String tag, String attrName) {
        // Remove attr="value"
        String result = tag.replaceFirst("\\s+" + Pattern.quote(attrName) + "=\"[^\"]*\"", "");
        // Remove standalone attr (like v-else)
        if (result.equals(tag)) {
            result = tag.replaceFirst("\\s+" + Pattern.quote(attrName) + "(?=\\s|>|/>)", "");
        }
        return result;
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
