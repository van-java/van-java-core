package dev.vanengine.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiled, immutable template holding HTML with {{ expr }} placeholders.
 * Thread-safe and reusable — separate from the expensive WASM compilation step.
 */
public class VanTemplate {

    private static final Pattern TRIPLE_MUSTACHE = Pattern.compile("\\{\\{\\{\\s*(.+?)\\s*}}}");
    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");
    private static final Pattern T_CALL = Pattern.compile(
            "\\$t\\(\\s*['\"](.+?)['\"]\\s*(?:,\\s*\\{(.+?)})?\\s*\\)");

    private final String compiledHtml;
    private final VanEngine engine; // nullable — for locale-aware evaluate

    VanTemplate(String compiledHtml) {
        this.compiledHtml = compiledHtml;
        this.engine = null;
    }

    VanTemplate(String compiledHtml, VanEngine engine) {
        this.compiledHtml = compiledHtml;
        this.engine = engine;
    }

    /**
     * Interpolate placeholders with model data and locale-specific i18n messages.
     */
    public String evaluate(Map<String, ?> model, String locale) {
        if (engine == null || !engine.hasI18nMessages()) {
            return evaluate(model);
        }
        Map<String, Object> merged = new HashMap<>(model);
        merged.put("$i18n", engine.getI18nMessages(locale));
        return evaluate(merged);
    }

    /**
     * Interpolate {{{ expr }}} (raw) and {{ expr }} (escaped) placeholders with model data.
     * Supports $t() expressions for i18n translation when $i18n is present in the model.
     */
    public String evaluate(Map<String, ?> model) {
        // First pass: resolve {{{ expr }}} → raw (no escaping)
        Matcher rawMatcher = TRIPLE_MUSTACHE.matcher(compiledHtml);
        StringBuilder raw = new StringBuilder();
        while (rawMatcher.find()) {
            String expr = rawMatcher.group(1);
            String tResult = tryResolveT(expr, model);
            if (tResult != null) {
                rawMatcher.appendReplacement(raw, Matcher.quoteReplacement(tResult));
            } else {
                Object value = resolve(expr, model);
                if (value != null) {
                    rawMatcher.appendReplacement(raw, Matcher.quoteReplacement(value.toString()));
                } else {
                    rawMatcher.appendReplacement(raw, Matcher.quoteReplacement(rawMatcher.group(0)));
                }
            }
        }
        rawMatcher.appendTail(raw);

        // Second pass: resolve {{ expr }} → HTML-escaped
        Matcher matcher = MUSTACHE.matcher(raw.toString());
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String expr = matcher.group(1);
            String tResult = tryResolveT(expr, model);
            if (tResult != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(escapeHtml(tResult)));
            } else {
                Object value = resolve(expr, model);
                if (value != null) {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(escapeHtml(value.toString())));
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                }
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Return the raw compiled HTML (with unresolved {{ expr }} placeholders).
     */
    public String getHtml() {
        return compiledHtml;
    }

    /**
     * Try to resolve a $t(...) expression against the model's $i18n messages.
     * Returns the translated string, or null if not a $t() expression or no $i18n data.
     */
    private String tryResolveT(String expr, Map<String, ?> model) {
        Matcher m = T_CALL.matcher(expr.trim());
        if (!m.matches()) return null;

        String key = m.group(1);
        String paramsStr = m.group(2); // may be null

        // Get $i18n messages from model
        Object i18nObj = model.get("$i18n");
        if (!(i18nObj instanceof Map<?, ?> i18nMap)) return null;

        // Look up key (supports dot-separated keys)
        Object messageObj = resolveNestedKey(i18nMap, key);
        if (messageObj == null) return key; // fallback to key name

        String message = messageObj.toString();

        // Parse params
        Map<String, String> params = new HashMap<>();
        if (paramsStr != null) {
            parseTParams(paramsStr, model, params);
        }

        // Handle plural
        if (params.containsKey("count") && message.contains("|")) {
            try {
                int count = Integer.parseInt(params.get("count"));
                message = MessageFormatter.resolvePlural(message, count);
            } catch (NumberFormatException ignored) {
                message = MessageFormatter.resolvePlural(message, -1);
            }
        } else if (message.contains("|")) {
            message = MessageFormatter.resolvePlural(message, -1);
        }

        // Replace {placeholder} with resolved params
        for (Map.Entry<String, String> entry : params.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        return message;
    }

    private Object resolveNestedKey(Map<?, ?> map, String key) {
        String[] parts = key.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private void parseTParams(String paramsStr, Map<String, ?> model, Map<String, String> params) {
        for (String part : splitParams(paramsStr)) {
            String trimmed = part.trim();
            int colonPos = trimmed.indexOf(':');
            if (colonPos < 0) continue;
            String paramKey = trimmed.substring(0, colonPos).trim();
            String paramVal = trimmed.substring(colonPos + 1).trim();

            String resolved;
            if ((paramVal.startsWith("'") && paramVal.endsWith("'"))
                    || (paramVal.startsWith("\"") && paramVal.endsWith("\""))) {
                // Literal string
                resolved = paramVal.substring(1, paramVal.length() - 1);
            } else if (paramVal.matches("-?\\d+(\\.\\d+)?")) {
                // Numeric literal
                resolved = paramVal;
            } else {
                // Data path — resolve from model
                Object value = resolve(paramVal, model);
                resolved = value != null ? value.toString() : paramVal;
            }
            params.put(paramKey, resolved);
        }
    }

    private List<String> splitParams(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Character inQuote = null;
        for (char ch : s.toCharArray()) {
            if (inQuote != null) {
                current.append(ch);
                if (ch == inQuote) inQuote = null;
            } else if (ch == '\'' || ch == '"') {
                current.append(ch);
                inQuote = ch;
            } else if (ch == ',') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    private Object resolve(String expr, Map<String, ?> model) {
        String[] parts = expr.split("\\.");
        Object current = model.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
