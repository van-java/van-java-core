package dev.vanengine.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for the Van template engine.
 */
public final class VanUtil {

    private VanUtil() {}

    /** Shared ObjectMapper instance — thread-safe, reuse everywhere. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── HTML ───────────────────────────────────────────────────────

    public static String escapeHtml(String text) {
        // Fast path: no escaping needed (common case for most values)
        boolean needsEscape = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '&' || ch == '<' || ch == '>' || ch == '"' || ch == '\'') {
                needsEscape = true;
                break;
            }
        }
        if (!needsEscape) return text;

        var sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    public static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    public static final Pattern MUSTACHE = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*\\}\\}");
    public static final Pattern TRIPLE_MUSTACHE = Pattern.compile("\\{\\{\\{\\s*([^}]+?)\\s*\\}\\}\\}");

    // ─── Text ───────────────────────────────────────────────────────

    public static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public static boolean containsWholeWord(String text, String word) {
        int idx = 0;
        while ((idx = text.indexOf(word, idx)) >= 0) {
            boolean beforeOk = idx == 0 || !isWordChar(text.charAt(idx - 1));
            boolean afterOk = idx + word.length() == text.length()
                    || !isWordChar(text.charAt(idx + word.length()));
            if (beforeOk && afterOk) return true;
            idx += word.length();
        }
        return false;
    }

    public static boolean containsAnyWholeWord(String expr, Collection<String> names) {
        for (String name : names) {
            if (containsWholeWord(expr, name)) return true;
        }
        return false;
    }

    @FunctionalInterface
    public interface MatchReplacer {
        String replace(Matcher m);
    }

    public static String replaceAll(Pattern pattern, String input, MatchReplacer replacer) {
        Matcher m = pattern.matcher(input);
        var sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.replace(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static List<String> splitRespectingNesting(String s) {
        var result = new ArrayList<String>();
        int depth = 0, start = 0;
        char inQuote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inQuote != 0) {
                if (ch == inQuote) inQuote = 0;
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                inQuote = ch;
            } else if (ch == '{' || ch == '[' || ch == '(') {
                depth++;
            } else if (ch == '}' || ch == ']' || ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                result.add(s.substring(start, i));
                start = i + 1;
            }
        }
        String tail = s.substring(start);
        if (!tail.isBlank()) result.add(tail);
        return result;
    }

    // ─── Map ────────────────────────────────────────────────────────

    // ─── Shared constants ────────────────────────────────────────

    public static final Set<String> SKIP_SCOPE_TAGS = Set.of(
            "slot", "template", "html", "head", "body", "meta", "link",
            "title", "script", "style", "base", "noscript");

    // ─── String helpers ────────────────────────────────────────

    /** Strip surrounding single or double quotes. Returns input unchanged if not quoted. */
    public static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0), last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    // ─── v-for expression parsing ─────────────────────────────────

    /**
     * Parsed v-for expression. Either a single itemVar or destructured field names.
     */
    public record VForExpr(String itemVar, List<String> destructKeys, String indexVar, String arrayExpr) {
        public boolean isDestructured() { return destructKeys != null; }
    }

    public static VForExpr parseVForExpr(String expr) {
        String[] parts = expr.split(" in ", 2);
        if (parts.length != 2) return new VForExpr(expr, null, null, "");
        String lhs = parts[0].trim();
        String arrayExpr = parts[1].trim();

        String indexVar = null;
        if (lhs.startsWith("(") && lhs.endsWith(")")) {
            String inner = lhs.substring(1, lhs.length() - 1);
            int braceDepth = 0, splitPos = -1;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                else if (c == ',' && braceDepth == 0) { splitPos = i; break; }
            }
            if (splitPos >= 0) {
                indexVar = inner.substring(splitPos + 1).trim();
                lhs = inner.substring(0, splitPos).trim();
            } else {
                lhs = inner.trim();
            }
        }

        if (lhs.startsWith("{") && lhs.endsWith("}")) {
            String inner = lhs.substring(1, lhs.length() - 1).trim();
            List<String> keys = new ArrayList<>();
            for (String key : inner.split(",")) {
                String k = key.trim();
                if (!k.isEmpty()) keys.add(k);
            }
            return new VForExpr(null, keys, indexVar, arrayExpr);
        }

        return new VForExpr(lhs, null, indexVar, arrayExpr);
    }

    public static Object resolveNestedKey(Map<?, ?> map, String key) {
        Object current = map;
        for (String part : key.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
