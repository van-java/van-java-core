package dev.vanengine.core.runtime;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.vanengine.core.TestUtil.countOccurrences;

import static org.junit.jupiter.api.Assertions.*;

class VanRuntimeTest {


    private Map<String, Object> scope(Object... kvs) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < kvs.length; i += 2) {
            map.put((String) kvs[i], kvs[i + 1]);
        }
        return map;
    }

    // ── v-for ──

    @Nested
    class VFor {

        @Test
        void basicList() {
            String html = "<ul><li v-for=\"item in items\">{{ item.name }}</li></ul>";
            var scope = scope("items", List.of(
                    Map.of("name", "foo"),
                    Map.of("name", "bar")
            ));
            // processAll handles v-for + interpolation in single pass
            String result = VanRuntime.processAll(html, scope);
            assertTrue(result.contains("foo") && result.contains("bar"));
        }

        @Test
        void fullPipeline() {
            String html = "<ul><li v-for=\"item in items\">{{ item.name }}</li></ul>";
            var scope = scope("items", List.of(
                    Map.of("name", "foo"),
                    Map.of("name", "bar")
            ));
            String result = VanRuntime.processAll(html, scope);
            assertFalse(result.contains("v-for"));
            // processAll now includes {{ }} interpolation within v-for scope
            assertTrue(result.contains("foo"), "{{ item.name }} should resolve to 'foo'");
            assertTrue(result.contains("bar"), "{{ item.name }} should resolve to 'bar'");
            assertFalse(result.contains("{{"), "No unresolved {{ }} should remain");
        }

        @Test
        void withIndex() {
            String html = "<li v-for=\"(item, i) in items\">{{ i }}</li>";
            var scope = scope("items", List.of(Map.of("n", "a"), Map.of("n", "b")));
            String result = VanRuntime.processAll(html, scope);
            assertFalse(result.contains("v-for"));
        }

        @Test
        void emptyList() {
            String html = "<li v-for=\"item in items\">{{ item.name }}</li>";
            var scope = scope("items", List.of());
            String result = VanRuntime.processAll(html, scope);
            assertEquals("", result.trim());
        }

        @Test
        void templateVirtualElement() {
            String html = "<template v-for=\"item in items\"><span>{{ item.name }}</span></template>";
            var scope = scope("items", List.of(Map.of("name", "a"), Map.of("name", "b")));
            String result = VanRuntime.processAll(html, scope);
            assertFalse(result.contains("<template"));
            assertFalse(result.contains("</template>"));
            // Should have two <span> elements without template wrapper
            assertEquals(2, countOccurrences(result, "<span>"));
        }

        @Test
        void stripsKey() {
            String html = "<li v-for=\"item in items\" :key=\"item.id\">text</li>";
            var scope = scope("items", List.of(Map.of("id", 1), Map.of("id", 2)));
            String result = VanRuntime.processAll(html, scope);
            assertFalse(result.contains(":key"));
            assertEquals(2, countOccurrences(result, "<li>"));
        }

        @Test
        void destructuring() {
            String html = "<ul><li v-for=\"{ name, role } in users\">{{ name }}-{{ role }}</li></ul>";
            var scope = scope("users", List.of(
                    Map.of("name", "Alice", "role", "dev"),
                    Map.of("name", "Bob", "role", "pm")));
            String result = VanRuntime.processAll(html, scope);
            assertTrue(result.contains("Alice-dev"));
            assertTrue(result.contains("Bob-pm"));
        }

        @Test
        void destructuringWithIndex() {
            String html = "<li v-for=\"({ id, name }, i) in items\">{{ i }}: {{ name }}</li>";
            var scope = scope("items", List.of(
                    Map.of("id", 1, "name", "A"),
                    Map.of("id", 2, "name", "B")));
            String result = VanRuntime.processAll(html, scope);
            assertTrue(result.contains("0: A"));
            assertTrue(result.contains("1: B"));
        }
    }

    // ── v-if / v-else-if / v-else ──

    @Nested
    class VIf {

        @Test
        void trueCondition() {
            String html = "<div v-if=\"visible\">content</div>";
            var scope = scope("visible", true);
            String result = VanRuntime.processAll(html, scope);
            assertTrue(result.contains("content"));
            assertFalse(result.contains("v-if"));
        }

        @Test
        void falseCondition() {
            String html = "<div v-if=\"visible\">content</div>";
            var scope = scope("visible", false);
            String result = VanRuntime.processAll(html, scope);
            assertFalse(result.contains("content"));
            assertFalse(result.contains("<div"));
        }

        @Test
        void ifElse() {
            String html = "<div v-if=\"show\">yes</div><div v-else>no</div>";
            assertEquals("<div>yes</div>",
                    VanRuntime.processAll(html, scope("show", true)).trim());
            assertEquals("<div>no</div>",
                    VanRuntime.processAll(html, scope("show", false)).trim());
        }

        @Test
        void ifElseIfElse() {
            String html = """
                    <div v-if="status === 'a'">A</div>\
                    <div v-else-if="status === 'b'">B</div>\
                    <div v-else>C</div>""";
            assertTrue(VanRuntime.processAll(html, scope("status", "a")).contains("A"));
            assertTrue(VanRuntime.processAll(html, scope("status", "b")).contains("B"));
            assertTrue(VanRuntime.processAll(html, scope("status", "c")).contains("C"));
        }

        @Test
        void expressionWithLength() {
            String html = "<table v-if=\"items.length > 0\"><tr></tr></table><div v-else>empty</div>";
            var withItems = scope("items", List.of("a"));
            assertTrue(VanRuntime.processAll(html, withItems).contains("<table>"));

            var empty = scope("items", List.of());
            String result = VanRuntime.processAll(html, empty);
            assertFalse(result.contains("<table"));
            assertTrue(result.contains("empty"));
        }

        @Test
        void trueRemoval() {
            // v-if=false should truly remove, not hide
            String html = "<div v-if=\"false\">gone</div><p>stays</p>";
            String result = VanRuntime.processAll(html, scope());
            assertFalse(result.contains("gone"));
            assertTrue(result.contains("stays"));
        }
    }

    // ── v-show ──

    @Nested
    class VShow {

        @Test
        void visible() {
            String html = "<div v-show=\"active\">content</div>";
            String result = VanRuntime.processAll(html, scope("active", true));
            assertTrue(result.contains("content"));
            assertFalse(result.contains("display:none"));
            assertFalse(result.contains("v-show"));
        }

        @Test
        void hidden() {
            String html = "<div v-show=\"active\">content</div>";
            String result = VanRuntime.processAll(html, scope("active", false));
            assertTrue(result.contains("content")); // element stays
            assertTrue(result.contains("display:none"));
        }

        @Test
        void mergeExistingStyle() {
            String html = "<div style=\"color: red\" v-show=\"active\">content</div>";
            String result = VanRuntime.processAll(html, scope("active", false));
            assertTrue(result.contains("display:none"));
            assertTrue(result.contains("color: red"));
        }
    }

    // ── :class binding ──

    @Nested
    class ClassBinding {

        @Test
        void objectSyntax() {
            String html = "<span :class=\"{ 'bg-pink': isMaven, 'bg-red': isNpm }\">text</span>";
            String result = VanRuntime.processAll(html, scope("isMaven", true, "isNpm", false));
            assertTrue(result.contains("class=\"bg-pink\""));
            assertFalse(result.contains("bg-red"));
            assertFalse(result.contains(":class"));
        }

        @Test
        void ternarySyntax() {
            String html = "<button :class=\"active ? 'btn-primary' : 'btn-secondary'\">text</button>";
            String result = VanRuntime.processAll(html, scope("active", true));
            assertTrue(result.contains("class=\"btn-primary\""));
        }

        @Test
        void mergeWithStaticClass() {
            String html = "<span class=\"px-2 text-xs\" :class=\"{ 'bg-pink': isMaven }\">text</span>";
            String result = VanRuntime.processAll(html, scope("isMaven", true));
            assertTrue(result.contains("px-2 text-xs bg-pink"));
            assertFalse(result.contains(":class"));
        }

        @Test
        void multipleConditionsTrue() {
            String html = "<span :class=\"{ 'a': x, 'b': y, 'c': z }\">text</span>";
            String result = VanRuntime.processAll(html, scope("x", true, "y", true, "z", false));
            assertTrue(result.contains("class=\"a b\"") || result.contains("class=\"b a\""));
            assertFalse(result.matches(".*\\bclass=\"[^\"]*\\bc\\b[^\"]*\".*"));
        }

        @Test
        void expressionCondition() {
            String html = "<span :class=\"{ 'bg-pink': pkg.format === 'MAVEN' }\">text</span>";
            String result = VanRuntime.processAll(html, scope("pkg", Map.of("format", "MAVEN")));
            assertTrue(result.contains("bg-pink"));
        }

        @Test
        void arraySyntax() {
            String html = "<div :class=\"[active ? 'yes' : 'no', 'base']\">text</div>";
            String result = VanRuntime.processAll(html, scope("active", true));
            assertTrue(result.contains("yes"), "ternary resolved");
            assertTrue(result.contains("base"), "static class");
            assertFalse(result.contains("["), "no brackets");
        }

        @Test
        void arraySyntaxFalse() {
            String html = "<div :class=\"[active ? 'yes' : 'no', 'base']\">text</div>";
            String result = VanRuntime.processAll(html, scope("active", false));
            assertTrue(result.contains("no base") || result.contains("class=\"no base\""));
        }

        @Test
        void arrayWithObjectMixed() {
            String html = "<div :class=\"['static', { bold: isBold }]\">text</div>";
            String result = VanRuntime.processAll(html, scope("isBold", true));
            assertTrue(result.contains("static"));
            assertTrue(result.contains("bold"));
        }
    }

    // ── :href / :value / :selected ──

    @Nested
    class AttrBindings {

        @Test
        void dynamicHref() {
            String html = "<a :href=\"'/ui/registries/' + r.id\">link</a>";
            String result = VanRuntime.processAll(html, scope("r", Map.of("id", 5)));
            assertTrue(result.contains("href=\"/ui/registries/5\""));
            assertFalse(result.contains(":href"));
        }

        @Test
        void dynamicValue() {
            String html = "<input :value=\"ctx.search\">";
            String result = VanRuntime.processAll(html, scope("ctx", Map.of("search", "test")));
            assertTrue(result.contains("value=\"test\""));
        }

        @Test
        void booleanSelected() {
            String html = "<option :selected=\"fmt === 'MAVEN'\">Maven</option>";
            String result = VanRuntime.processAll(html, scope("fmt", "MAVEN"));
            assertTrue(result.contains("selected"));

            String result2 = VanRuntime.processAll(html, scope("fmt", "NPM"));
            assertFalse(result2.contains("selected"));
        }

        @Test
        void booleanDisabled() {
            String html = "<button :disabled=\"!canSubmit\">Go</button>";
            String result = VanRuntime.processAll(html, scope("canSubmit", false));
            assertTrue(result.contains("disabled"));
        }
    }

    // ── v-html / v-text ──

    @Nested
    class VHtmlVText {

        @Test
        void vHtml() {
            String html = "<div v-html=\"content\"></div>";
            String result = VanRuntime.processAll(html, scope("content", "<b>bold</b>"));
            assertTrue(result.contains("<b>bold</b>"));
            assertFalse(result.contains("v-html"));
        }

        @Test
        void vText() {
            String html = "<span v-text=\"msg\"></span>";
            String result = VanRuntime.processAll(html, scope("msg", "Hello & World"));
            assertTrue(result.contains("Hello &amp; World"));
        }
    }

    // ── Client directive stripping ──

    @Nested
    class StripDirectives {

        @Test
        void stripClick() {
            String html = "<button @click=\"doSomething\">text</button>";
            String result = VanRuntime.processAll(html, scope());
            assertFalse(result.contains("@click"));
            assertTrue(result.contains("<button>"));
        }

        @Test
        void stripVModel() {
            String html = "<input v-model=\"name\">";
            String result = VanRuntime.processAll(html, scope());
            assertFalse(result.contains("v-model"));
        }
    }

    // ── ClientOnly boundary ──

    @Nested
    class ClientOnly {

        @Test
        void skipsClientOnlyRegion() {
            String html = """
                    <div v-if="show">SSR</div>\
                    <!--client-only-->\
                    <div v-if="signal">client</div>\
                    <!--/client-only-->\
                    <p>after</p>""";
            // v-if="show" should be processed, v-if="signal" should be skipped
            String result = VanRuntime.processAll(html, scope("show", true));
            assertTrue(result.contains("<div>SSR</div>"));
            assertTrue(result.contains("v-if=\"signal\"")); // preserved
            assertTrue(result.contains("after"));
        }

        @Test
        void preservesClientOnlyComments() {
            String html = "<!--client-only--><div>client</div><!--/client-only-->";
            String result = VanRuntime.processAll(html, scope());
            assertTrue(result.contains("<!--client-only-->"));
            assertTrue(result.contains("<!--/client-only-->"));
        }
    }

    // ── v-for + v-if combined (recursive pipeline) ──

    @Nested
    class Combined {

        @Test
        void vForWithInnerVIf() {
            String html = "<template v-for=\"pkg in packages\"><tr v-if=\"pkg.visible\"><td>{{ pkg.name }}</td></tr></template>";
            var scope = scope("packages", List.of(
                    Map.of("name", "foo", "visible", true),
                    Map.of("name", "bar", "visible", false),
                    Map.of("name", "baz", "visible", true)
            ));
            String result = VanRuntime.processAll(html, scope);
            assertEquals(2, countOccurrences(result, "<tr>"));
            assertFalse(result.contains("bar")); // filtered out by v-if
        }

        @Test
        void vForWithClassBinding() {
            String html = "<tr v-for=\"pkg in packages\"><td :class=\"{ 'pink': pkg.format === 'MAVEN' }\">text</td></tr>";
            var scope = scope("packages", List.of(
                    Map.of("format", "MAVEN"),
                    Map.of("format", "NPM")
            ));
            String result = VanRuntime.processAll(html, scope);
            assertEquals(2, countOccurrences(result, "<tr>"));
            assertTrue(result.contains("class=\"pink\"")); // first item
        }
    }

    // ── Timeout ──

    @Nested
    class Timeout {
        @Test
        void normalRenderWithinTimeout() {
            String html = "<ul><li v-for=\"i in items\">{{ i }}</li></ul>";
            var scope = scope("items", List.of("a", "b", "c"));
            // Should complete well within 5s default
            String result = VanRuntime.processAll(html, scope);
            assertTrue(result.contains("a"));
        }

        @Test
        void customTimeoutParam() {
            String html = "<p>{{ msg }}</p>";
            // 10 second timeout — trivial template
            String result = VanRuntime.processAll(html, scope("msg", "ok"), 10_000);
            assertTrue(result.contains("ok"));
        }
    }

    // ── Real-world OmniRepo pattern ──

    @Nested
    class RealWorld {

        @Test
        void packagesPage() {
            String html = """
                    <table v-if="ctx.packages.length > 0">\
                    <tr v-for="pkg in ctx.packages">\
                    <td>{{ pkg.name }}</td>\
                    <td><span class="badge" :class="{ 'bg-pink': pkg.format === 'MAVEN', 'bg-red': pkg.format === 'NPM' }">{{ pkg.format }}</span></td>\
                    </tr>\
                    </table>\
                    <div v-else>No packages yet.</div>""";

            // With packages
            var withPkgs = scope("ctx", Map.of("packages", List.of(
                    Map.of("name", "foo", "format", "MAVEN"),
                    Map.of("name", "bar", "format", "NPM")
            )));
            String result = VanRuntime.processAll(html, withPkgs);
            assertTrue(result.contains("<table>"));
            assertEquals(2, countOccurrences(result, "<tr>"));
            assertTrue(result.contains("badge bg-pink"));
            assertTrue(result.contains("badge bg-red"));
            assertFalse(result.contains("No packages"));

            // Empty packages
            var empty = scope("ctx", Map.of("packages", List.of()));
            String resultEmpty = VanRuntime.processAll(html, empty);
            assertFalse(resultEmpty.contains("<table"));
            assertTrue(resultEmpty.contains("No packages yet."));
        }
    }

}
