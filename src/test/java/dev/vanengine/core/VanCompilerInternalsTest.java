package dev.vanengine.core;

import dev.vanengine.core.compile.VanParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.vanengine.core.TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

class VanCompilerInternalsTest {

    // ─── fillData tests ──────────────

    @Nested
    class FillData {

        @Test
        void stripsEvents() {
            // fillData operates on compile output where @click is already stripped.
            // Test through full compile path instead.
            ResolvedComponent resolved = new ResolvedComponent(
                    "<button @click=\"increment\">+1</button>", List.of(), null, List.of());
            String html = VanCompiler.renderToString(resolved, json("{}"), "Van");
            assertFalse(html.contains("@click"));
            assertTrue(html.contains("+1"));
        }

        @Test
        void vShowFalsy() {
            String html = "<p v-show=\"visible\">Hello</p>";
            String result = VanCompiler.fillData(html, json("{\"visible\": false}"));
            assertFalse(result.contains("v-show"));
            assertTrue(result.contains("style=\"display:none\""));
        }

        @Test
        void vShowTruthy() {
            String html = "<p v-show=\"visible\">Hello</p>";
            String result = VanCompiler.fillData(html, json("{\"visible\": true}"));
            assertFalse(result.contains("v-show"));
            assertEquals("<p>Hello</p>", result);
        }

        @Test
        void stripsTransitionTags() {
            // fillData doesn't strip Transition (that's compile-time cleanup),
            // but it handles remaining v-show
            String html = "<div><p v-show=\"open\">Hi</p></div>";
            String result = VanCompiler.fillData(html, json("{\"open\": false}"));
            assertTrue(result.contains("display:none"));
            assertTrue(result.contains("<p"));
        }

        @Test
        void stripsVElse() {
            String html = "<div v-if=\"a\">A</div><div v-else>B</div>";
            String result = VanCompiler.fillData(html, json("{\"a\": true}"));
            assertFalse(result.contains("v-if"));
            assertFalse(result.contains("v-else"));
        }

        @Test
        void stripsClassStyleKey() {
            String html = "<div :class=\"{active: x}\" :style=\"{color: y}\" :key=\"id\">text</div>";
            String result = VanCompiler.fillData(html, json("{}"));
            assertFalse(result.contains(":class"));
            assertFalse(result.contains(":style"));
            assertFalse(result.contains(":key"));
        }

        @Test
        void interpolatesRemaining() {
            String html = "<h1>{{ title }}</h1>";
            String result = VanCompiler.fillData(html, json("{\"title\": \"Hello\"}"));
            assertEquals("<h1>Hello</h1>", result);
        }
    }

    // ─── Compile mode tests ─────────────────────────────────────────

    @Nested
    class Compile {

        @Test
        void renderToStringBasic() {
            ResolvedComponent resolved = new ResolvedComponent(
                    "<h1>Hello</h1>",
                    List.of("h1 { color: red; }"),
                    null, List.of());
            String html = VanCompiler.renderToString(resolved, json("{}"), "Van");
            assertTrue(html.contains("<h1>Hello</h1>"));
            assertTrue(html.contains("h1 { color: red; }"));
        }

        @Test
        void compilePreservesDirectives() {
            ResolvedComponent resolved = new ResolvedComponent(
                    "<ul><li v-for=\"item in items\">{{ item }}</li></ul>",
                    List.of(), null, List.of());
            String html = VanCompiler.buildPage(resolved, "Van");
            assertTrue(html.contains("v-for=\"item in items\""), "v-for preserved");
            // {{ item }} preserved (may have varying whitespace)
            assertTrue(html.contains("{{ item }}") || html.contains("{{item}}"), "{{ }} preserved");
        }

        @Test
        void compileStripsEvents() {
            ResolvedComponent resolved = new ResolvedComponent(
                    "<button @click=\"handler\">text</button>",
                    List.of(), null, List.of());
            String html = VanCompiler.buildPage(resolved, "Van");
            assertFalse(html.contains("@click"));
        }

        @Test
        void compileAssetsProducesExternalFiles() {
            // HTML must contain reactive bindings for signal JS to be generated
            ResolvedComponent resolved = new ResolvedComponent(
                    "<div><p>{{ count }}</p><button @click=\"inc\">+</button></div>",
                    List.of("h1 { color: red; }"),
                    "const count = ref(0)\nfunction inc() { count.value++ }",
                    List.of());
            VanCompiler.CompiledResult assets = VanCompiler.compileAssets(resolved, "index", "/assets", "Van");
            assertNotNull(assets.html());
            assertFalse(assets.assets().isEmpty());
            assertTrue(assets.assets().keySet().stream().anyMatch(k -> k.endsWith(".css")));
            long jsCount = assets.assets().keySet().stream().filter(k -> k.endsWith(".js")).count();
            assertEquals(2, jsCount);
        }

        @Test
        void compileWrapsInHtmlShell() {
            ResolvedComponent resolved = new ResolvedComponent(
                    "<h1>Hello</h1>", List.of(), null, List.of());
            String html = VanCompiler.buildPage(resolved, "Van");
            assertTrue(html.contains("<!DOCTYPE html>"));
            assertTrue(html.contains("<html"));
            assertTrue(html.contains("</html>"));
        }

        @Test
        void compilePreservesExistingHtmlTag() {
            ResolvedComponent resolved = new ResolvedComponent(
                    "<html lang=\"en\"><head><title>Test</title></head><body><h1>Hi</h1></body></html>",
                    List.of("h1 { color: red; }"), null, List.of());
            String html = VanCompiler.buildPage(resolved, "Van");
            // Should NOT double-wrap with another <html>
            assertEquals(1, countOccurrences(html, "<html"));
            assertTrue(html.contains("color: red"));
        }
    }

    // ─── End-to-end: full .van project ──────────────────────────────

    @Nested
    class EndToEnd {

        @Test
        void layoutWithNamedSlotAndScopedStyle() {
            var files = new java.util.HashMap<String, String>();
            files.put("layout.van", """
                    <template>
                      <html lang="en">
                      <head><title>{{ title }}</title></head>
                      <body>
                        <header><slot name="header">Default Header</slot></header>
                        <main><slot /></main>
                      </body>
                      </html>
                    </template>
                    <script setup>
                    defineProps({ title: String })
                    </script>
                    <style scoped>
                    header { color: gray; }
                    </style>
                    """);
            files.put("index.van", """
                    <template>
                      <my-layout :title="pageTitle">
                        <template #header><h1>{{ pageTitle }}</h1></template>
                        <p>{{ description }}</p>
                      </my-layout>
                    </template>
                    <script setup>
                    import MyLayout from './layout.van'
                    </script>
                    """);

            VanCompiler compiler = new VanCompiler();

            // Compile mode
            String compiled = compiler.compile("index.van", files).html();
            assertTrue(compiled.contains("<html"), "Layout <html> propagated");
            assertTrue(compiled.contains("color: gray"), "Scoped style present");

            // Render mode
            String rendered = compiler.renderToString("index.van", files,
                    "{\"pageTitle\":\"My App\",\"description\":\"Hello World\"}");
            assertTrue(rendered.contains("<h1>My App</h1>"), "Named slot content");
            assertTrue(rendered.contains("<p>Hello World</p>"), "Default slot content");
            assertTrue(rendered.contains("<title>My App</title>"), "Layout title");
        }

        @Test
        void componentWithSignals() {
            var files = new java.util.HashMap<String, String>();
            files.put("index.van", """
                    <template>
                      <div>
                        <p>Count: {{ count }}</p>
                        <button @click="increment">+1</button>
                      </div>
                    </template>
                    <script setup>
                    const count = ref(0)
                    function increment() { count.value++ }
                    </script>
                    """);

            VanCompiler compiler = new VanCompiler();
            String compiled = compiler.compile("index.van", files).html();

            assertTrue(compiled.contains("Count: 0"), "Signal initial value");
            assertFalse(compiled.contains("@click"), "@click stripped");
            assertTrue(compiled.contains("V.signal(0)"), "Signal JS");
            assertTrue(compiled.contains("addEventListener"), "Event binding JS");
        }

        @Test
        void nestedComponentWithScopedStyle() {
            var files = new java.util.HashMap<String, String>();
            files.put("card.van", """
                    <template>
                      <div class="card"><h2>{{ heading }}</h2><slot /></div>
                    </template>
                    <script setup>
                    defineProps({ heading: String })
                    </script>
                    <style scoped>
                    .card { border: 1px solid #ccc; }
                    </style>
                    """);
            files.put("index.van", """
                    <template>
                      <my-card :heading="title"><p>Body</p></my-card>
                    </template>
                    <script setup>
                    import MyCard from './card.van'
                    </script>
                    """);

            VanCompiler compiler = new VanCompiler();
            // Compile mode: heading becomes {{ title }}
            String compiled = compiler.compile("index.van", files).html();
            assertTrue(compiled.contains("border: 1px solid"), "Scoped CSS present");
            assertTrue(compiled.contains("class=\"card"), "Card class present");

            // Render mode: heading resolved to actual value
            String rendered = compiler.renderToString("index.van", files, "{\"title\":\"Demo\"}");
            assertTrue(rendered.contains("Demo"), "Prop value appears in output");
            assertTrue(rendered.contains("Body"), "Slot content rendered");
        }

        @Test
        void vForWithNestedComponents() {
            // v-for expansion happens in VanResolver before component resolution.
            // After expansion, badge tags in each iteration are resolved.
            // Test through compile mode (v-for preserved for Java SSR).
            var files = new java.util.HashMap<String, String>();
            files.put("badge.van", """
                    <template><span class="badge">{{ label }}</span></template>
                    <script setup>
                    defineProps({ label: String })
                    </script>
                    """);
            files.put("index.van", """
                    <template>
                      <ul>
                        <li v-for="item in items">
                          <badge :label="item.name" />
                        </li>
                      </ul>
                    </template>
                    <script setup>
                    import Badge from './badge.van'
                    </script>
                    """);

            VanCompiler compiler = new VanCompiler();

            // Compile mode: v-for preserved, badge resolved with {{ }} prop
            String compiled = compiler.compile("index.van", files).html();
            assertTrue(compiled.contains("v-for"), "v-for preserved in compile mode");
            assertTrue(compiled.contains("class=\"badge\""), "Badge component resolved");
        }

        @Test
        void scopedStyleIsolation() {
            var files = new java.util.HashMap<String, String>();
            files.put("a.van", """
                    <template><div class="box">A</div></template>
                    <style scoped>.box { color: red; }</style>
                    """);
            files.put("b.van", """
                    <template><div class="box">B</div></template>
                    <style scoped>.box { color: blue; }</style>
                    """);
            files.put("index.van", """
                    <template><div><comp-a /><comp-b /></div></template>
                    <script setup>
                    import CompA from './a.van'
                    import CompB from './b.van'
                    </script>
                    """);

            VanCompiler compiler = new VanCompiler();
            String html = compiler.renderToString("index.van", files, "{}");

            // Both components should have their scoped styles
            assertTrue(html.contains("color: red"), "Component A style");
            assertTrue(html.contains("color: blue"), "Component B style");
            // Scoped class IDs should be different
            String idA = VanParser.scopeId(".box { color: red; }");
            String idB = VanParser.scopeId(".box { color: blue; }");
            assertNotEquals(idA, idB, "Scope IDs should differ");
            assertTrue(html.contains(idA), "Component A scope class present");
            assertTrue(html.contains(idB), "Component B scope class present");
        }

        @Test
        void slotFallbackContent() {
            var files = new java.util.HashMap<String, String>();
            files.put("wrapper.van", """
                    <template>
                      <div>
                        <slot name="title">Default Title</slot>
                        <slot>Default Body</slot>
                      </div>
                    </template>
                    """);
            files.put("index.van", """
                    <template>
                      <wrapper><p>Custom Body</p></wrapper>
                    </template>
                    <script setup>
                    import Wrapper from './wrapper.van'
                    </script>
                    """);

            VanCompiler compiler = new VanCompiler();
            String html = compiler.renderToString("index.van", files, "{}");

            // Named slot not provided → fallback
            assertTrue(html.contains("Default Title"), "Named slot fallback");
            // Default slot provided → custom content
            assertTrue(html.contains("Custom Body"), "Default slot custom");
            assertFalse(html.contains("Default Body"), "Default fallback replaced");
        }

        @Test
        void componentNameCollidesWithHtmlElement() {
            var files = new java.util.HashMap<String, String>();
            files.put("index.van", """
                    <template><Nav :items="menuItems" /></template>
                    <script setup>
                    import Nav from './Nav.van'
                    </script>
                    """);
            files.put("Nav.van", """
                    <template><nav><slot /></nav></template>
                    """);

            VanCompiler compiler = new VanCompiler();
            String html = compiler.renderToString("index.van", files, "{\"menuItems\":[]}");

            assertTrue(html.contains("<nav>"), "HTML <nav> element preserved");
            assertFalse(html.contains("<Nav"), "PascalCase tag resolved");
        }
    }

    // ─── Cross-validation fixtures ──────────────────────────────────

    @Nested
    class CrossValidation {

        @Test
        void simpleTemplateMatchesExpected() {
            // Reference output: what a correctly working compiler should produce
            var files = Map.of("main.van", """
                    <template><h1>{{ title }}</h1></template>
                    """);
            VanCompiler compiler = new VanCompiler();
            String html = compiler.renderToString("main.van", files, "{\"title\":\"Hello\"}");

            assertTrue(html.contains("<h1>Hello</h1>"));
            assertTrue(html.contains("<!DOCTYPE html>"));
            assertTrue(html.contains("<html"));
        }

        @Test
        void signalCounterMatchesExpected() {
            var files = Map.of("main.van", """
                    <template>
                      <div>
                        <p>Count: {{ count }}</p>
                        <button @click="increment">+1</button>
                      </div>
                    </template>
                    <script setup>
                    const count = ref(0)
                    function increment() { count.value++ }
                    </script>
                    """);

            VanCompiler compiler = new VanCompiler();

            // Compile mode
            String compiled = compiler.compile("main.van", files).html();
            assertTrue(compiled.contains("Count: 0"), "Signal initial value interpolated");
            assertFalse(compiled.contains("@click"), "Events stripped");
            assertTrue(compiled.contains("V.signal(0)"), "Signal JS present");
            assertTrue(compiled.contains("count.value++"), "Function body in JS");
            assertTrue(compiled.contains("addEventListener('click'"), "Event binding in JS");

            // Render mode
            String rendered = compiler.renderToString("main.van", files, "{}");
            assertTrue(rendered.contains("Count: 0"), "Signal initial value in render mode");
            assertTrue(rendered.contains("V.signal(0)"), "Signal JS in render mode");
        }

        @Test
        void multiComponentPropPassingMatchesExpected() {
            var files = new java.util.HashMap<String, String>();
            files.put("index.van", """
                    <template><greeting :name="userName" /></template>
                    <script setup>
                    import Greeting from './greeting.van'
                    </script>
                    """);
            files.put("greeting.van", """
                    <template><p>Hello, {{ name }}!</p></template>
                    """);

            VanCompiler compiler = new VanCompiler();
            String html = compiler.renderToString("index.van", files, "{\"userName\":\"Alice\"}");
            assertTrue(html.contains("<p>Hello, Alice!</p>"));
        }

        @Test
        void styleCollectionOrderMatchesExpected() {
            var files = new java.util.HashMap<String, String>();
            files.put("index.van", """
                    <template><child /></template>
                    <script setup>
                    import Child from './child.van'
                    </script>
                    <style>.parent { margin: 0; }</style>
                    """);
            files.put("child.van", """
                    <template><div>child</div></template>
                    <style>.child { padding: 0; }</style>
                    """);

            VanCompiler compiler = new VanCompiler();
            String html = compiler.renderToString("index.van", files, "{}");
            // Parent style should come before child style in output
            int parentIdx = html.indexOf("margin: 0");
            int childIdx = html.indexOf("padding: 0");
            assertTrue(parentIdx >= 0 && childIdx >= 0, "Both styles present");
            assertTrue(parentIdx < childIdx, "Parent style before child style");
        }
    }

}
