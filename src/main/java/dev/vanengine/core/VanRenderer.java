package dev.vanengine.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VanRenderer {

    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");

    public String render(String html, Map<String, ?> model) {
        Matcher matcher = MUSTACHE.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String expr = matcher.group(1);
            Object value = resolve(expr, model);
            if (value != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(escapeHtml(value.toString())));
            } else {
                // Leave unresolved expressions as-is
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
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
