package dev.vanengine.core.support;

import java.util.*;

/**
 * Mutable HTML AST for the Van template pipeline.
 * Parse once → operate on tree → serialize once.
 */
public final class VanAst {

    private VanAst() {}

    // ── Node types ──

    public sealed interface Node permits Node.Element, Node.Text, Node.Comment {

        final class Element implements Node {
            private String tag;
            private final List<Attr> attrs;
            private final List<Node> children;
            private boolean selfClosing;
            private int sourceStart = -1;
            private boolean dynamic; // has directives or {{ }} — needs copy on evaluate

            public Element(String tag, List<Attr> attrs, List<Node> children, boolean selfClosing) {
                this.tag = tag;
                this.attrs = new ArrayList<>(attrs);
                this.children = new ArrayList<>(children);
                this.selfClosing = selfClosing;
            }

            public String tag() { return tag; }
            public List<Attr> attrs() { return attrs; }
            public List<Node> children() { return children; }
            public boolean selfClosing() { return selfClosing; }
            public int sourceStart() { return sourceStart; }
            void setSourceStart(int pos) { this.sourceStart = pos; }
            boolean isDynamic() { return dynamic; }

            // ── Attribute operations ──

            public String getAttr(String name) {
                for (Attr a : attrs) if (a.name.equals(name)) return a.value;
                return null;
            }

            public boolean hasAttr(String name) {
                for (Attr a : attrs) if (a.name.equals(name)) return true;
                return false;
            }

            public void setAttr(String name, String value) {
                for (int i = 0; i < attrs.size(); i++) {
                    if (attrs.get(i).name.equals(name)) {
                        attrs.set(i, new Attr(name, value));
                        return;
                    }
                }
                attrs.add(new Attr(name, value));
            }

            public void removeAttr(String name) {
                attrs.removeIf(a -> a.name.equals(name));
            }

            /** Deep copy of this element and all descendants. */
            public Element copy() {
                List<Attr> newAttrs = new ArrayList<>(attrs.size());
                for (Attr a : attrs) newAttrs.add(new Attr(a.name, a.value));
                List<Node> newChildren = new ArrayList<>(children.size());
                for (Node child : children) newChildren.add(copyNode(child));
                Element e = new Element(tag, newAttrs, newChildren, selfClosing);
                e.sourceStart = this.sourceStart;
                return e;
            }
        }

        final class Text implements Node {
            private String content;
            public Text(String content) { this.content = content; }
            public String content() { return content; }
            public void setContent(String content) { this.content = content; }
        }

        final class Comment implements Node {
            private final String content;
            public Comment(String content) { this.content = content; }
            public String content() { return content; }
        }
    }

    public record Attr(String name, String value) {}

    /** Mark dynamic subtrees — nodes with directives, {{ }}, or :attr. Static subtrees can be shared. */
    public static void markDynamic(List<Node> nodes) {
        for (Node node : nodes) {
            if (node instanceof Node.Element e) {
                boolean hasDynamic = false;
                for (Attr a : e.attrs) {
                    String n = a.name();
                    if (n.startsWith(":") || n.startsWith("@") || n.startsWith("v-") ||
                            (a.value() != null && a.value().contains("{{"))) {
                        hasDynamic = true; break;
                    }
                }
                markDynamic(e.children);
                // Also dynamic if any child is dynamic
                if (!hasDynamic) {
                    for (Node child : e.children) {
                        if ((child instanceof Node.Element ce && ce.dynamic) ||
                                (child instanceof Node.Text t && t.content().contains("{{"))) {
                            hasDynamic = true; break;
                        }
                    }
                }
                e.dynamic = hasDynamic;
            }
        }
    }

    /**
     * Smart copy: only deep-copy dynamic subtrees. Static subtrees are shared by reference.
     */
    public static List<Node> smartCopy(List<Node> nodes) {
        List<Node> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) result.add(smartCopyNode(node));
        return result;
    }

    private static Node smartCopyNode(Node node) {
        if (node instanceof Node.Element e) {
            if (!e.dynamic) return e; // static — share reference
            return e.copy(); // dynamic — deep copy
        }
        if (node instanceof Node.Text t) {
            return t.content().contains("{{") ? new Node.Text(t.content()) : t;
        }
        return node; // Comment — immutable
    }

    public static Node copyNode(Node node) {
        if (node instanceof Node.Element e) return e.copy();
        if (node instanceof Node.Text t) return new Node.Text(t.content);
        if (node instanceof Node.Comment c) return new Node.Comment(c.content);
        throw new IllegalStateException("Unknown node type");
    }

    // ── Parser ──

    private static final Set<String> VOID_ELEMENTS = VanUtil.VOID_ELEMENTS;
    private static final Set<String> RAW_TEXT_ELEMENTS = Set.of("script", "style");

    public static List<Node> parse(String html) {
        List<Node> nodes = new ArrayList<>();
        int pos = 0;
        while (pos < html.length()) {
            if (html.charAt(pos) == '<') {
                // HTML comment
                if (html.startsWith("<!--", pos)) {
                    int end = html.indexOf("-->", pos + 4);
                    if (end >= 0) {
                        nodes.add(new Node.Comment(html.substring(pos + 4, end)));
                        pos = end + 3;
                    } else {
                        nodes.add(new Node.Text(html.substring(pos)));
                        pos = html.length();
                    }
                    continue;
                }
                // DOCTYPE or other declaration
                if (html.startsWith("<!", pos)) {
                    int end = html.indexOf('>', pos);
                    if (end >= 0) {
                        nodes.add(new Node.Text(html.substring(pos, end + 1)));
                        pos = end + 1;
                    } else {
                        nodes.add(new Node.Text(html.substring(pos)));
                        pos = html.length();
                    }
                    continue;
                }
                // Closing tag (stray — skip)
                if (html.startsWith("</", pos)) {
                    int end = html.indexOf('>', pos);
                    pos = end >= 0 ? end + 1 : html.length();
                    continue;
                }
                // Opening tag
                int[] endPos = new int[1];
                Node.Element elem = parseElement(html, pos, endPos);
                if (elem != null) {
                    nodes.add(elem);
                    pos = endPos[0];
                } else {
                    // Malformed — treat as text until next '<'
                    int textEnd = html.indexOf('<', pos + 1);
                    if (textEnd < 0) textEnd = html.length();
                    nodes.add(new Node.Text(html.substring(pos, textEnd)));
                    pos = textEnd;
                }
            } else {
                int textEnd = html.indexOf('<', pos);
                if (textEnd < 0) textEnd = html.length();
                String text = html.substring(pos, textEnd);
                if (!text.isEmpty()) nodes.add(new Node.Text(text));
                pos = textEnd;
            }
        }
        return nodes;
    }

    private static Node.Element parseElement(String html, int pos, int[] endPos) {
        if (html.charAt(pos) != '<') return null;

        // Find end of opening tag (respecting quotes in attributes)
        int gtPos = findTagEnd(html, pos);
        if (gtPos < 0) return null;

        String tagContent = html.substring(pos + 1, gtPos);
        boolean selfClosing = tagContent.endsWith("/");
        if (selfClosing) tagContent = tagContent.substring(0, tagContent.length() - 1).stripTrailing();

        // Extract tag name
        int tagEnd = 0;
        while (tagEnd < tagContent.length() && !Character.isWhitespace(tagContent.charAt(tagEnd))) tagEnd++;
        String tagName = tagContent.substring(0, tagEnd);
        if (tagName.isEmpty()) return null;

        List<Attr> attrs = parseAttrs(tagContent.substring(tagEnd));
        int afterOpen = gtPos + 1;

        boolean isVoid = VOID_ELEMENTS.contains(tagName.toLowerCase());
        if (selfClosing || isVoid) {
            endPos[0] = afterOpen;
            Node.Element e = new Node.Element(tagName, attrs, new ArrayList<>(), selfClosing || isVoid);
            e.setSourceStart(pos);
            return e;
        }

        // Raw text elements — don't parse children
        if (RAW_TEXT_ELEMENTS.contains(tagName.toLowerCase())) {
            String closeTag = "</" + tagName;
            int closeIdx = indexOfIgnoreCase(html, closeTag, afterOpen);
            if (closeIdx >= 0) {
                int closeGt = html.indexOf('>', closeIdx);
                if (closeGt < 0) closeGt = html.length() - 1;
                String rawContent = html.substring(afterOpen, closeIdx);
                List<Node> children = new ArrayList<>();
                if (!rawContent.isEmpty()) children.add(new Node.Text(rawContent));
                endPos[0] = closeGt + 1;
                Node.Element e = new Node.Element(tagName, attrs, children, false);
                e.setSourceStart(pos);
                return e;
            }
        }

        // Parse children until closing tag
        ParseResult result = parseChildrenUntil(html, afterOpen, tagName);
        endPos[0] = result.endPos;
        Node.Element e = new Node.Element(tagName, attrs, result.nodes, false);
        e.setSourceStart(pos);
        return e;
    }

    private record ParseResult(List<Node> nodes, int endPos) {}

    private static ParseResult parseChildrenUntil(String html, int start, String parentTag) {
        List<Node> nodes = new ArrayList<>();
        int pos = start;
        String closeTag = "</" + parentTag + ">";

        while (pos < html.length()) {
            // Check for closing tag (case-insensitive)
            if (html.charAt(pos) == '<' && html.startsWith("/", pos + 1)) {
                if (regionMatchesIgnoreCase(html, pos, closeTag)) {
                    return new ParseResult(nodes, pos + closeTag.length());
                }
                // Stray closing tag — skip
                int end = html.indexOf('>', pos);
                pos = end >= 0 ? end + 1 : html.length();
                continue;
            }

            if (html.charAt(pos) == '<') {
                // Comment
                if (html.startsWith("<!--", pos)) {
                    int end = html.indexOf("-->", pos + 4);
                    if (end >= 0) {
                        nodes.add(new Node.Comment(html.substring(pos + 4, end)));
                        pos = end + 3;
                    } else {
                        pos = html.length();
                    }
                    continue;
                }
                // DOCTYPE/declaration
                if (html.startsWith("<!", pos)) {
                    int end = html.indexOf('>', pos);
                    if (end >= 0) {
                        nodes.add(new Node.Text(html.substring(pos, end + 1)));
                        pos = end + 1;
                    } else {
                        pos = html.length();
                    }
                    continue;
                }
                // Child element
                int[] childEnd = new int[1];
                Node.Element child = parseElement(html, pos, childEnd);
                if (child != null) {
                    nodes.add(child);
                    pos = childEnd[0];
                } else {
                    int textEnd = html.indexOf('<', pos + 1);
                    if (textEnd < 0) textEnd = html.length();
                    nodes.add(new Node.Text(html.substring(pos, textEnd)));
                    pos = textEnd;
                }
            } else {
                int textEnd = html.indexOf('<', pos);
                if (textEnd < 0) textEnd = html.length();
                String text = html.substring(pos, textEnd);
                if (!text.isEmpty()) nodes.add(new Node.Text(text));
                pos = textEnd;
            }
        }
        // No closing tag found
        return new ParseResult(nodes, pos);
    }

    static List<Attr> parseAttrs(String attrStr) {
        List<Attr> attrs = new ArrayList<>();
        String s = attrStr.trim();
        int pos = 0;
        while (pos < s.length()) {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos >= s.length()) break;

            int nameStart = pos;
            while (pos < s.length() && s.charAt(pos) != '=' && !Character.isWhitespace(s.charAt(pos))
                    && s.charAt(pos) != '>') pos++;
            String name = s.substring(nameStart, pos);
            if (name.isEmpty()) { pos++; continue; }

            // Skip whitespace between name and =
            int savedPos = pos;
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;

            if (pos < s.length() && s.charAt(pos) == '=') {
                pos++;
                // Skip whitespace after =
                while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
                if (pos < s.length() && (s.charAt(pos) == '"' || s.charAt(pos) == '\'')) {
                    char quote = s.charAt(pos); pos++;
                    int valStart = pos;
                    while (pos < s.length() && s.charAt(pos) != quote) pos++;
                    attrs.add(new Attr(name, s.substring(valStart, pos)));
                    if (pos < s.length()) pos++;
                } else {
                    int valStart = pos;
                    while (pos < s.length() && !Character.isWhitespace(s.charAt(pos))) pos++;
                    attrs.add(new Attr(name, s.substring(valStart, pos)));
                }
            } else {
                // Standalone attribute (no =)
                pos = savedPos; // restore position after name
                attrs.add(new Attr(name, null));
            }
        }
        return attrs;
    }

    /** Find the '>' that closes an opening tag, respecting quoted attribute values. */
    private static int findTagEnd(String html, int from) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = from + 1; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (inQuote) {
                if (ch == quoteChar) inQuote = false;
            } else if (ch == '"' || ch == '\'') {
                inQuote = true;
                quoteChar = ch;
            } else if (ch == '>') {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String source, String target, int from) {
        int limit = source.length() - target.length();
        for (int i = from; i <= limit; i++) {
            if (source.regionMatches(true, i, target, 0, target.length())) return i;
        }
        return -1;
    }

    private static boolean regionMatchesIgnoreCase(String source, int pos, String target) {
        return pos + target.length() <= source.length()
                && source.regionMatches(true, pos, target, 0, target.length());
    }

    // ── Serializer ──

    public static String toHtml(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node node : nodes) serialize(node, sb);
        return sb.toString();
    }

    private static void serialize(Node node, StringBuilder sb) {
        if (node instanceof Node.Element elem) {
            serializeElement(elem, sb);
        } else if (node instanceof Node.Text text) {
            sb.append(text.content);
        } else if (node instanceof Node.Comment comment) {
            sb.append("<!--").append(comment.content).append("-->");
        }
    }

    private static void serializeElement(Node.Element elem, StringBuilder sb) {
        sb.append('<').append(elem.tag);
        for (Attr attr : elem.attrs) {
            sb.append(' ').append(attr.name);
            if (attr.value != null) {
                sb.append("=\"").append(attr.value).append('"');
            }
        }

        if (elem.selfClosing && elem.children.isEmpty()) {
            sb.append(" />");
            return;
        }

        if (VOID_ELEMENTS.contains(elem.tag.toLowerCase()) && elem.children.isEmpty()) {
            sb.append(">");
            return;
        }

        sb.append('>');
        for (Node child : elem.children) serialize(child, sb);
        sb.append("</").append(elem.tag).append('>');
    }
}
