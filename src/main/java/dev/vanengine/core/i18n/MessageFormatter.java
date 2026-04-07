package dev.vanengine.core.i18n;

import dev.vanengine.core.support.VanUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple {key} placeholder replacement for i18n messages,
 * with optional plural resolution and HTML-safe formatting.
 */
public final class MessageFormatter {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private MessageFormatter() {}

    /**
     * Replace {key} placeholders in the message with values from the params map.
     * If the message contains pipe-separated plural forms and params contains "count",
     * the appropriate form is selected first.
     * Unmatched placeholders are left as-is.
     */
    public static String format(String message, Map<String, ?> params) {
        if (message == null || params == null || params.isEmpty()) return message;

        String processed = maybeResolvePlural(message, params);

        Matcher matcher = PLACEHOLDER.matcher(processed);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = params.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    value != null ? value.toString() : matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Same as {@link #format}, but HTML-escapes parameter values before substitution.
     * Use this when the output will be embedded in HTML to prevent XSS.
     */
    public static String formatSafe(String message, Map<String, ?> params) {
        if (message == null || params == null || params.isEmpty()) return message;

        Map<String, Object> escaped = new HashMap<>();
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            if (entry.getValue() != null) {
                escaped.put(entry.getKey(), VanUtil.escapeHtml(entry.getValue().toString()));
            }
        }
        return format(message, escaped);
    }

    /**
     * Resolve plural forms separated by {@code |} (pipe).
     * <p>
     * Rules (vue-i18n compatible):
     * <ul>
     *   <li>2 forms: {@code "singular | plural"} — 0,1 → first; 2+ → second</li>
     *   <li>3 forms: {@code "zero | singular | plural"} — 0 → first; 1 → second; 2+ → third</li>
     * </ul>
     * If count is negative, returns the first form.
     */
    public static String resolvePlural(String message, int count) {
        String[] forms = message.split("\\|", -1);
        for (int i = 0; i < forms.length; i++) forms[i] = forms[i].trim();

        if (forms.length == 1) return forms[0];
        if (count < 0) return forms[0];

        int idx;
        if (forms.length == 2) {
            idx = (count == 0 || count == 1) ? 0 : 1;
        } else if (forms.length == 3) {
            idx = count == 0 ? 0 : count == 1 ? 1 : 2;
        } else {
            idx = Math.min(count, forms.length - 1);
        }
        return forms[idx];
    }

    private static String maybeResolvePlural(String message, Map<String, ?> params) {
        if (!message.contains("|")) return message;

        if (params.containsKey("count")) {
            Object countObj = params.get("count");
            int count;
            if (countObj instanceof Number n) {
                count = n.intValue();
            } else {
                try {
                    count = Integer.parseInt(countObj.toString());
                } catch (NumberFormatException e) {
                    count = -1;
                }
            }
            return resolvePlural(message, count);
        }
        // No count param but has pipes — use first form
        return resolvePlural(message, -1);
    }

}
