package dev.vanengine.core.compile;

import dev.vanengine.core.TestUtil;
import dev.vanengine.core.ResolvedComponent;
import dev.vanengine.core.VanTemplateException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.vanengine.core.TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

class VanResolverTest {

    // ─── Reactive names ─────────────────────────────────────────────

    @Test
    void extractReactiveNames() {
        String script = """
                const count = ref(0)
                const doubled = computed(() => count * 2)
                """;
        List<String> names = VanSignalGen.collectReactiveNamesList(VanSignalGen.analyzeScript(script));
        assertEquals(List.of("count", "doubled"), names);
    }

    // ─── Single-file resolve ────────────────────────────────────────

    @Nested
    class ResolveSingle {

        @Test
        void basic() {
            String source = """
                    <template>
                      <h1>{{ title }}</h1>
                    </template>
                    """;
            ResolvedComponent r = VanResolver.resolveSingle(source, json("{\"title\":\"Hello\"}"));
            assertTrue(r.html().contains("<h1>Hello</h1>"));
            assertTrue(r.styles().isEmpty());
            assertNull(r.scriptSetup());
        }

        @Test
        void withStyle() {
            String source = """
                    <template>
                      <h1>Hello</h1>
                    </template>

                    <style scoped>
                    h1 { color: red; }
                    </style>
                    """;
            ResolvedComponent r = VanResolver.resolveSingle(source, emptyObj());
            assertEquals(1, r.styles().size());
            assertTrue(r.styles().get(0).contains("color: red"));
        }

        @Test
        void reactivePreserved() {
            String source = """
                    <template>
                      <p>Count: {{ count }}</p>
                    </template>

                    <script setup>
                    const count = ref(0)
                    </script>
                    """;
            ResolvedComponent r = VanResolver.resolveSingle(source, emptyObj());
            assertTrue(r.html().contains("{{ count }}"));
            assertNotNull(r.scriptSetup());
        }

        @Test
        void unscopedStyleUnchanged() {
            String source = """
                    <template>
                      <div class="app"><p>Hello</p></div>
                    </template>

                    <style>
                    .app { margin: 0; }
                    </style>
                    """;
            ResolvedComponent r = VanResolver.resolveSingle(source, emptyObj());
            assertEquals(1, countOccurrences(r.html(), "class="), "Only the original class attr");
            assertTrue(r.html().contains("class=\"app\""), "Original class preserved");
            assertEquals(".app { margin: 0; }", r.styles().get(0));
        }
    }

    // ─── Virtual path ───────────────────────────────────────────────

    @Nested
    class VirtualPath {

        @Test
        void sameDir() {
            assertEquals("hello.van", VanResolver.resolveVirtualPath("index.van", "./hello.van"));
        }

        @Test
        void parentDir() {
            assertEquals("components/hello.van",
                    VanResolver.resolveVirtualPath("pages/index.van", "../components/hello.van"));
        }

        @Test
        void subDir() {
            assertEquals("pages/sub.van",
                    VanResolver.resolveVirtualPath("pages/index.van", "./sub.van"));
        }

        @Test
        void normalizeVirtualPath() {
            assertEquals("hello.van", VanResolver.normalizeVirtualPath("./hello.van"));
            assertEquals("components/hello.van",
                    VanResolver.normalizeVirtualPath("pages/../components/hello.van"));
            assertEquals("a/b/c", VanResolver.normalizeVirtualPath("a/b/./c"));
        }

        @Test
        void scopedPackage() {
            assertEquals("@van-ui/button/button.van",
                    VanResolver.resolveVirtualPath("pages/index.van", "@van-ui/button/button.van"));
            assertEquals("@van-ui/utils/format.ts",
                    VanResolver.resolveVirtualPath("index.van", "@van-ui/utils/format.ts"));
        }
    }

    // ─── Multi-file resolve ─────────────────────────────────────────

    @Nested
    class ResolveWithFiles {

        @Test
        void basicImport() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <hello :name="title" />
                    </template>
                    <script setup>
                    import Hello from './hello.van'
                    </script>
                    """);
            files.put("hello.van", """
                    <template>
                      <h1>Hello, {{ name }}!</h1>
                    </template>
                    """);

            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files,
                    json("{\"title\":\"World\"}"));
            assertTrue(r.html().contains("<h1>Hello, World!</h1>"));
        }

        @Test
        void missingComponent() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <hello />
                    </template>
                    <script setup>
                    import Hello from './hello.van'
                    </script>
                    """);

            var ex = assertThrows(VanTemplateException.class, () ->
                    VanResolver.resolveWithFiles("index.van", files, emptyObj()));
            assertTrue(ex.getMessage().contains("index.van"), "has file path");
            assertTrue(ex.getLine() > 0, "has line number");
        }

        @Test
        void slots() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <wrapper>
                        <p>Default slot content</p>
                      </wrapper>
                    </template>
                    <script setup>
                    import Wrapper from './wrapper.van'
                    </script>
                    """);
            files.put("wrapper.van", """
                    <template>
                      <div class="wrapper"><slot /></div>
                    </template>
                    """);

            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files, emptyObj());
            assertTrue(r.html().contains("<div class=\"wrapper\">"));
            assertTrue(r.html().contains("<p>Default slot content</p>"));
        }

        @Test
        void scopedSlot() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <my-list :items="items">
                        <template #default="{ item, index }">
                          <span>{{ index }}: {{ item }}</span>
                        </template>
                      </my-list>
                    </template>
                    <script setup>
                    import MyList from './my-list.van'
                    </script>
                    """);
            files.put("my-list.van", """
                    <template>
                      <ul>
                        <li v-for="(item, i) in items">
                          <slot :item="item" :index="i">{{ item }}</slot>
                        </li>
                      </ul>
                    </template>
                    <script setup>
                    defineProps({ items: Array })
                    </script>
                    """);

            var data = json("{\"items\": [\"Alice\", \"Bob\"]}");
            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files, data);
            assertTrue(r.html().contains("0: Alice"), "scoped slot with index: " + r.html());
            assertTrue(r.html().contains("1: Bob"), "scoped slot: " + r.html());
        }

        @Test
        void stylesCollected() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <hello />
                    </template>
                    <script setup>
                    import Hello from './hello.van'
                    </script>
                    <style>
                    .app { color: blue; }
                    </style>
                    """);
            files.put("hello.van", """
                    <template>
                      <h1>Hello</h1>
                    </template>
                    <style>
                    h1 { color: red; }
                    </style>
                    """);

            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files, emptyObj());
            assertEquals(2, r.styles().size());
            assertTrue(r.styles().get(0).contains("color: blue"));
            assertTrue(r.styles().get(1).contains("color: red"));
        }

        @Test
        void reactivePreserved() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <div>
                        <p>Count: {{ count }}</p>
                        <hello :name="title" />
                      </div>
                    </template>
                    <script setup>
                    import Hello from './hello.van'
                    const count = ref(0)
                    </script>
                    """);
            files.put("hello.van", """
                    <template>
                      <h1>Hello, {{ name }}!</h1>
                    </template>
                    """);

            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files,
                    json("{\"title\":\"World\"}"));
            assertTrue(r.html().contains("{{ count }}"), "Reactive preserved");
            assertTrue(r.html().contains("<h1>Hello, World!</h1>"), "Non-reactive interpolated");
            assertNotNull(r.scriptSetup());
        }

        @Test
        void scopedImport() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <van-button :label="title" />
                    </template>
                    <script setup>
                    import VanButton from '@van-ui/button/button.van'
                    </script>
                    """);
            files.put("@van-ui/button/button.van", """
                    <template>
                      <button>{{ label }}</button>
                    </template>
                    """);

            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files,
                    json("{\"title\":\"Click me\"}"));
            assertTrue(r.html().contains("<button>Click me</button>"));
        }

        @Test
        void componentNameSameAsHtmlElement() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <Header :title="title" />
                    </template>
                    <script setup>
                    import Header from './Header.van'
                    </script>
                    """);
            files.put("Header.van", """
                    <template>
                      <header><h1>{{ title }}</h1></header>
                    </template>
                    """);

            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files,
                    json("{\"title\":\"My Site\"}"));
            assertTrue(r.html().contains("<header>"), "Should contain <header> HTML element");
            assertTrue(r.html().contains("<h1>My Site</h1>"), "Should interpolate title prop");
        }
    }

    // ─── Component tag extraction (verified via multi-file resolve) ──

    @Nested
    class ComponentTag {

        @Test
        void selfClosingResolved() {
            // Verified through basicImport test - self-closing <hello /> is resolved
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template><hello :name="title" /></template>
                    <script setup>
                    import Hello from './hello.van'
                    </script>
                    """);
            files.put("hello.van", """
                    <template><span>{{ name }}</span></template>
                    """);
            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files, json("{\"title\":\"OK\"}"));
            assertTrue(r.html().contains("<span>OK</span>"));
        }

        @Test
        void pairedResolved() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template><wrapper><h1>Content</h1></wrapper></template>
                    <script setup>
                    import Wrapper from './wrapper.van'
                    </script>
                    """);
            files.put("wrapper.van", """
                    <template><div><slot /></div></template>
                    """);
            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files, emptyObj());
            assertTrue(r.html().contains("<h1>Content</h1>"));
        }

        @Test
        void noMatch() {
            // No import → no component matching
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template><div>no components</div></template>
                    """);
            ResolvedComponent r = VanResolver.resolveWithFiles("index.van", files, emptyObj());
            assertTrue(r.html().contains("no components"));
        }
    }

    // ─── Slot distribution ──────────────────────────────────────────

    @Nested
    class DistributeSlots {

        @Test
        void defaultSlot() {
            String html = "<div><slot /></div>";
            Map<String, String> slots = Map.of("default", "Hello World");
            String result = VanResolver.distributeSlots(html, slots, false, Map.of());
            assertEquals("<div>Hello World</div>", result);
        }

        @Test
        void namedSlot() {
            String html = "<title><slot name=\"title\">Fallback</slot></title><div><slot /></div>";
            Map<String, String> slots = Map.of("title", "My Title", "default", "Body");
            String result = VanResolver.distributeSlots(html, slots, false, Map.of());
            assertEquals("<title>My Title</title><div>Body</div>", result);
        }

        @Test
        void fallback() {
            String html = "<title><slot name=\"title\">Fallback Title</slot></title>";
            String result = VanResolver.distributeSlots(html, Map.of(), false, Map.of());
            assertEquals("<title>Fallback Title</title>", result);
        }
    }

    // ─── v-for expansion ────────────────────────────────────────────

    @Nested
    class ExpandVFor {

        @Test
        void basic() {
            var data = json("{\"items\": [\"Alice\", \"Bob\", \"Charlie\"]}");
            String template = "<ul><li v-for=\"item in items\">{{ item }}</li></ul>";
            String result = VanResolver.expandVFor(template, data);
            assertTrue(result.contains("<li>Alice</li>"));
            assertTrue(result.contains("<li>Bob</li>"));
            assertTrue(result.contains("<li>Charlie</li>"));
            assertFalse(result.contains("v-for"));
        }

        @Test
        void withIndex() {
            var data = json("{\"items\": [\"A\", \"B\"]}");
            String template = "<ul><li v-for=\"(item, index) in items\">{{ index }}: {{ item }}</li></ul>";
            String result = VanResolver.expandVFor(template, data);
            assertTrue(result.contains("0: A"));
            assertTrue(result.contains("1: B"));
        }

        @Test
        void nestedPath() {
            var data = json("{\"user\": {\"hobbies\": [\"coding\", \"reading\"]}}");
            String template = "<span v-for=\"h in user.hobbies\">{{ h }}</span>";
            String result = VanResolver.expandVFor(template, data);
            assertTrue(result.contains("<span>coding</span>"));
            assertTrue(result.contains("<span>reading</span>"));
        }
    }

    // ─── Interpolation ──────────────────────────────────────────────

    @Nested
    class Interpolation {

        @Test
        void simple() {
            assertEquals("Hello World!",
                    VanResolver.interpolate("Hello {{ name }}!", json("{\"name\":\"World\"}")));
        }

        @Test
        void dotPath() {
            assertEquals("Alice",
                    VanResolver.interpolate("{{ user.name }}", json("{\"user\":{\"name\":\"Alice\"}}")));
        }

        @Test
        void missingKey() {
            assertEquals("{{missing}}",
                    VanResolver.interpolate("{{ missing }}", emptyObj()));
        }

        @Test
        void escapesHtml() {
            assertEquals("&lt;b&gt;bold&lt;/b&gt;",
                    VanResolver.interpolate("{{ html }}", json("{\"html\":\"<b>bold</b>\"}")));
        }

        @Test
        void tripleMustacheRaw() {
            assertEquals("<b>bold</b>",
                    VanResolver.interpolate("{{{ html }}}", json("{\"html\":\"<b>bold</b>\"}")));
        }

        @Test
        void mixedEscapedAndRaw() {
            String data = "{\"safe\":\"<b>bold</b>\", \"text\":\"<em>hi</em>\"}";
            assertEquals("<b>bold</b> and &lt;em&gt;hi&lt;/em&gt;",
                    VanResolver.interpolate("{{{ safe }}} and {{ text }}", json(data)));
        }
    }

    // ─── Props ──────────────────────────────────────────────────────

    @Test
    void parseProps() {
        var data = json("{\"title\": \"World\", \"count\": 42}");
        String attrs = ":name=\"title\" :num=\"count\"";
        var result = VanResolver.parseProps(attrs, data);
        assertEquals("World", result.get("name").asText());
        assertEquals("42", result.get("num").asText());
    }

    // ─── Helpers ────────────────────────────────────────────────────

}
