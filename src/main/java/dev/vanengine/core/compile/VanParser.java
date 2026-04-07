package dev.vanengine.core.compile;

import dev.vanengine.core.support.VanUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for {@code .van} files (Vue SFC syntax).
 * Extracts template, script, style blocks and parses imports, props, and scoped styles.
 */
public final class VanParser {

    private VanParser() {}

    // ─── Data records ──────────────────────────────────────────────

    /**
     * Extracted blocks from a {@code .van} file.
     */
    public record VanBlock(
            String template,
            String scriptSetup,
            String scriptServer,
            String style,
            boolean styleScoped,
            List<PropDef> props
    ) {
        public VanBlock {
            props = props != null ? List.copyOf(props) : List.of();
        }
    }

    /**
     * A {@code .van} component import from {@code <script setup>}.
     *
     * @param name    the imported identifier, e.g. {@code DefaultLayout}
     * @param tagName the kebab-case tag name, e.g. {@code default-layout}
     * @param path    the import path, e.g. {@code ../layouts/default.van}
     */
    public record VanImport(String name, String tagName, String path) {
    }

    /**
     * A non-component import from {@code <script setup>} (.ts/.js/.tsx/.jsx files).
     *
     * @param raw         the full import statement as-is
     * @param isTypeOnly  whether this is a type-only import ({@code import type { ... }})
     * @param path        the module path, e.g. {@code ../utils/format.ts}
     */
    public record ScriptImport(String raw, boolean isTypeOnly, String path) {
    }

    /**
     * A single prop declaration from {@code defineProps({ ... })}.
     *
     * @param name     the prop name
     * @param propType the declared type ("String", "Number", "Boolean", "Array", "Object"), or null
     * @param required whether the prop is required
     */
    public record PropDef(String name, String propType, boolean required) {
    }

    // ─── Import parsing ─────────────────────────────────────────────

    private static final Pattern VAN_IMPORT_RE =
            Pattern.compile("import\\s+(\\w+)\\s+from\\s+['\"]([^'\"]+\\.van)['\"]");

    private static final Pattern SCRIPT_IMPORT_RE =
            Pattern.compile("(?m)^[ \\t]*(import\\s+(?:type\\s+)?.*?\\s+from\\s+['\"]([^'\"]+\\.(?:ts|js|tsx|jsx))['\"].*)");

    private static final Pattern TYPE_ONLY_RE =
            Pattern.compile("^import\\s+type\\s");

    /**
     * Parse {@code import X from './path.van'} statements from a script setup block.
     */
    public static List<VanImport> parseImports(String scriptSetup) {
        List<VanImport> result = new ArrayList<>();
        Matcher m = VAN_IMPORT_RE.matcher(scriptSetup);
        while (m.find()) {
            String name = m.group(1);
            String tagName = pascalToKebab(name);
            String path = m.group(2);
            result.add(new VanImport(name, tagName, path));
        }
        return result;
    }

    /**
     * Parse non-.van imports from a script setup block.
     * Returns imports from .ts, .js, .tsx, .jsx files.
     */
    public static List<ScriptImport> parseScriptImports(String scriptSetup) {
        List<ScriptImport> result = new ArrayList<>();
        Matcher m = SCRIPT_IMPORT_RE.matcher(scriptSetup);
        while (m.find()) {
            String raw = m.group(1).trim();
            String path = m.group(2);
            boolean isTypeOnly = TYPE_ONLY_RE.matcher(raw).find();
            result.add(new ScriptImport(raw, isTypeOnly, path));
        }
        return result;
    }

    // ─── PascalCase to kebab-case ───────────────────────────────────

    /**
     * Convert PascalCase to kebab-case: {@code DefaultLayout} -> {@code default-layout}.
     */
    public static String pascalToKebab(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ─── Block extraction ───────────────────────────────────────────

    /**
     * Extract blocks from a {@code .van} source file.
     */
    public static VanBlock parseBlocks(String source) {
        StyleResult sr = extractStyle(source);
        String scriptSetup = extractScriptSetup(source);
        List<PropDef> props = scriptSetup != null ? parseDefineProps(scriptSetup) : List.of();
        return new VanBlock(
                extractBlock(source, "template"),
                scriptSetup,
                extractScriptServer(source),
                sr.content,
                sr.scoped,
                props
        );
    }

    private static String extractBlock(String source, String tag) {
        String open = "<" + tag;
        String close = "</" + tag + ">";

        int startIdx = source.indexOf(open);
        if (startIdx < 0) return null;

        int tagEnd = source.indexOf('>', startIdx);
        if (tagEnd < 0) return null;
        int contentStart = tagEnd + 1;

        // Use lastIndexOf for closing tag to handle nested <template #slot> blocks
        int endIdx = source.lastIndexOf(close);
        if (endIdx < 0 || endIdx <= contentStart) return null;

        return source.substring(contentStart, endIdx).trim();
    }

    private static String extractScriptSetup(String source) {
        String marker = "<script setup";
        String close = "</script>";

        int startIdx = source.indexOf(marker);
        if (startIdx < 0) return null;

        int tagEnd = source.indexOf('>', startIdx);
        if (tagEnd < 0) return null;
        int contentStart = tagEnd + 1;

        int endIdx = source.indexOf(close, contentStart);
        if (endIdx < 0) return null;

        return source.substring(contentStart, endIdx).trim();
    }

    private static String extractScriptServer(String source) {
        String marker = "<script lang=\"java\">";
        String close = "</script>";

        int startIdx = source.indexOf(marker);
        if (startIdx < 0) return null;
        int contentStart = startIdx + marker.length();

        int endIdx = source.indexOf(close, contentStart);
        if (endIdx < 0) return null;

        return source.substring(contentStart, endIdx).trim();
    }

    private record StyleResult(String content, boolean scoped) {}

    private static StyleResult extractStyle(String source) {
        String open = "<style";
        String close = "</style>";

        // Only match top-level <style> blocks (after </template>)
        int searchStart = source.lastIndexOf("</template>");
        if (searchStart >= 0) {
            searchStart += "</template>".length();
        } else {
            searchStart = 0;
        }

        int relIdx = source.indexOf(open, searchStart);
        if (relIdx < 0) return new StyleResult(null, false);

        int tagEnd = source.indexOf('>', relIdx);
        if (tagEnd < 0) return new StyleResult(null, false);

        String tagAttrs = source.substring(relIdx, tagEnd);
        boolean isScoped = tagAttrs.contains("scoped");

        int contentStart = tagEnd + 1;
        int endIdx = source.indexOf(close, contentStart);
        if (endIdx < 0) return new StyleResult(null, false);

        return new StyleResult(source.substring(contentStart, endIdx).trim(), isScoped);
    }

    // ─── defineProps parsing ────────────────────────────────────────

    /**
     * Parse {@code defineProps({ ... })} from a script setup block.
     */
    public static List<PropDef> parseDefineProps(String script) {
        int start = script.indexOf("defineProps(");
        if (start < 0) return List.of();

        int afterParen = start + "defineProps(".length();
        String rest = script.substring(afterParen);

        String inner = extractBalancedBraces(rest);
        if (inner == null || inner.trim().isEmpty()) return List.of();

        List<PropDef> props = new ArrayList<>();
        List<String> entries = VanUtil.splitRespectingNesting(inner);

        for (String entry : entries) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;

            int colonPos = entry.indexOf(':');
            if (colonPos < 0) continue;

            String name = entry.substring(0, colonPos).trim();
            // Strip quotes from name
            if ((name.startsWith("'") && name.endsWith("'")) ||
                (name.startsWith("\"") && name.endsWith("\""))) {
                name = name.substring(1, name.length() - 1);
            }
            String value = entry.substring(colonPos + 1).trim();

            if (value.startsWith("{")) {
                // Object form: { type: Type, required: true }
                String objInner = value;
                if (objInner.startsWith("{")) objInner = objInner.substring(1);
                if (objInner.endsWith("}")) objInner = objInner.substring(0, objInner.length() - 1);
                objInner = objInner.trim();

                String propType = null;
                boolean required = false;

                for (String part : objInner.split(",")) {
                    part = part.trim();
                    int cp = part.indexOf(':');
                    if (cp < 0) continue;
                    String key = part.substring(0, cp).trim();
                    String val = part.substring(cp + 1).trim();
                    if ("type".equals(key)) {
                        propType = val;
                    } else if ("required".equals(key)) {
                        required = "true".equals(val);
                    }
                }
                props.add(new PropDef(name, propType, required));
            } else {
                // Simple form: name: Type
                props.add(new PropDef(name, value, false));
            }
        }
        return props;
    }

    /**
     * Extract the content between balanced { and } from the start of the string.
     */
    static String extractBalancedBraces(String s) {
        s = s.trim();
        if (!s.startsWith("{")) return null;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return s.substring(1, i);
            }
        }
        return null;
    }


    // ─── Scoped styles ──────────────────────────────────────────────

    /**
     * Generate a deterministic 8-hex-char scope ID from content.
     * Uses a simple hash to ensure same content always produces same ID.
     */
    public static String scopeId(String content) {
        // FNV-1a inspired hash — better distribution than 31-multiply.
        // Mix high and low bits before truncation to use the full entropy.
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < content.length(); i++) {
            h ^= content.charAt(i);
            h *= 0x100000001b3L;
        }
        int mixed = (int)(h ^ (h >>> 32));
        return String.format("%08x", mixed);
    }

    private static final Set<String> SKIP_SCOPE_TAGS = VanUtil.SKIP_SCOPE_TAGS;

    /**
     * Add a scope class to every opening HTML tag in the fragment.
     * Skips closing tags, comments, and structural tags.
     */
    public static String addScopeClass(String html, String id) {
        StringBuilder result = new StringBuilder(html.length() + id.length() * 10);
        int pos = 0;

        while (pos < html.length()) {
            int ltPos = html.indexOf('<', pos);
            if (ltPos < 0) {
                result.append(html, pos, html.length());
                break;
            }

            result.append(html, pos, ltPos);

            // Skip closing tags and comments
            if (html.startsWith("</", ltPos) || html.startsWith("<!--", ltPos) || html.startsWith("<!", ltPos)) {
                int gt = html.indexOf('>', ltPos);
                if (gt < 0) {
                    result.append(html, ltPos, html.length());
                    break;
                }
                result.append(html, ltPos, gt + 1);
                pos = gt + 1;
                continue;
            }

            // Opening tag — find '>'
            int gt = html.indexOf('>', ltPos);
            if (gt < 0) {
                result.append(html, ltPos, html.length());
                break;
            }

            int nameStart = ltPos + 1;
            int nameEnd = nameStart;
            while (nameEnd < gt) {
                char c = html.charAt(nameEnd);
                if (!Character.isLetterOrDigit(c) && c != '-') break;
                nameEnd++;
            }
            String tagName = html.substring(nameStart, nameEnd);

            if (SKIP_SCOPE_TAGS.contains(tagName.toLowerCase())) {
                result.append(html, ltPos, gt + 1);
                pos = gt + 1;
                continue;
            }

            String tag = html.substring(ltPos, gt);
            boolean isSelfClosing = tag.stripTrailing().endsWith("/");

            int classIdx = tag.indexOf("class=\"");
            if (classIdx >= 0) {
                int afterQuote = classIdx + 7;
                int endQuote = tag.indexOf('"', afterQuote);
                if (endQuote >= 0) {
                    int absInsert = ltPos + endQuote;
                    result.append(html, ltPos, absInsert);
                    result.append(' ').append(id);
                    result.append(html, absInsert, gt + 1);
                } else {
                    result.append(html, ltPos, gt + 1);
                }
            } else if (isSelfClosing) {
                int slash = tag.lastIndexOf('/');
                result.append(html, ltPos, ltPos + slash);
                result.append("class=\"").append(id).append("\" ");
                result.append(html, ltPos + slash, gt + 1);
            } else {
                result.append(html, ltPos, gt);
                result.append(" class=\"").append(id).append("\">");
            }

            pos = gt + 1;
        }

        return result.toString();
    }

    /**
     * Scope CSS by inserting {@code .{id}} on selectors.
     * Handles nested at-rules (@media, @supports, @container, @layer) via recursive descent,
     * and passes @keyframes/@font-face through unmodified.
     */
    public static String scopeCss(String css, String id) {
        String suffix = "." + id;
        StringBuilder sb = new StringBuilder();
        int pos = 0;

        while (pos < css.length()) {
            // Skip whitespace
            if (Character.isWhitespace(css.charAt(pos))) { sb.append(css.charAt(pos++)); continue; }
            // Skip CSS comments
            if (pos + 1 < css.length() && css.charAt(pos) == '/' && css.charAt(pos + 1) == '*') {
                int end = css.indexOf("*/", pos + 2);
                end = end >= 0 ? end + 2 : css.length();
                sb.append(css, pos, end);
                pos = end;
                continue;
            }

            int braceStart = css.indexOf('{', pos);
            if (braceStart < 0) { sb.append(css, pos, css.length()); break; }

            String selector = css.substring(pos, braceStart).trim();
            int braceEnd = findMatchingBrace(css, braceStart);
            String body = css.substring(braceStart + 1, braceEnd);

            if (selector.startsWith("@media") || selector.startsWith("@supports")
                    || selector.startsWith("@container") || selector.startsWith("@layer")) {
                sb.append(selector).append(" {").append(scopeCss(body, id)).append('}');
            } else if (selector.startsWith("@")) {
                // @keyframes, @font-face, etc. — pass through unscoped
                sb.append(selector).append(" {").append(body).append('}');
            } else {
                // Regular rule: scope each selector
                String[] parts = selector.split(",");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(insertScopeSuffix(parts[i].trim(), suffix));
                }
                sb.append(" {").append(body).append('}');
            }
            pos = braceEnd + 1;
        }
        return sb.toString();
    }

    /** Find the position of the closing '}' matching the opening '{' at {@code openPos}. */
    private static int findMatchingBrace(String css, int openPos) {
        int depth = 1;
        int pos = openPos + 1;
        boolean inString = false;
        char stringChar = 0;
        while (pos < css.length() && depth > 0) {
            char ch = css.charAt(pos);
            if (inString) {
                if (ch == stringChar && (pos == 0 || css.charAt(pos - 1) != '\\')) inString = false;
            } else if (ch == '\'' || ch == '"') {
                inString = true; stringChar = ch;
            } else if (ch == '/' && pos + 1 < css.length() && css.charAt(pos + 1) == '*') {
                int end = css.indexOf("*/", pos + 2);
                pos = end >= 0 ? end + 1 : pos;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            }
            if (depth > 0) pos++;
        }
        return pos;
    }

    /**
     * Insert a scope class suffix before any pseudo-class/pseudo-element
     * at the end of a selector.
     */
    private static String insertScopeSuffix(String selector, String suffix) {
        // Find the last simple selector (after space or combinator)
        int lastStart = 0;
        for (int i = selector.length() - 1; i >= 0; i--) {
            char c = selector.charAt(i);
            if (c == ' ' || c == '>' || c == '+' || c == '~') {
                lastStart = i + 1;
                break;
            }
        }

        String lastPart = selector.substring(lastStart);

        // Find the first ':' in the last part (pseudo-class or pseudo-element)
        int colonPos = lastPart.indexOf(':');
        if (colonPos >= 0) {
            int insertAt = lastStart + colonPos;
            return selector.substring(0, insertAt) + suffix + selector.substring(insertAt);
        } else {
            return selector + suffix;
        }
    }
}
