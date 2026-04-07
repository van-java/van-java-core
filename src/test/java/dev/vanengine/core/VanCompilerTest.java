package dev.vanengine.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the pure Java compilation pipeline.
 */
class VanCompilerTest {

    private final VanCompiler compiler = new VanCompiler();

    // ─── Render tests (with data) ───────────────────────────────────

    @Nested
    class Render {

        @Test
        void singleBasic() {
            Map<String, String> files = Map.of("main.van", """
                    <template>
                      <h1>{{ title }}</h1>
                    </template>
                    """);
            String html = compiler.renderToString("main.van", files, "{\"title\": \"Hello World\"}");
            assertTrue(html.contains("<h1>Hello World</h1>"));
            assertTrue(html.contains("<!DOCTYPE html>"));
        }

        @Test
        void singleWithSignals() {
            Map<String, String> files = Map.of("main.van", """
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
            String html = compiler.renderToString("main.van", files, "{}");
            assertTrue(html.contains("Van"));
            assertFalse(html.contains("@click"));
            assertTrue(html.contains("effect"));
        }

        @Test
        void invalidJson() {
            Map<String, String> files = Map.of("main.van", "<template><p>Hi</p></template>");
            assertThrows(IllegalArgumentException.class, () ->
                    compiler.renderToString("main.van", files, "not json"));
        }

        @Test
        void withStyle() {
            Map<String, String> files = Map.of("main.van", """
                    <template>
                      <h1>Hello</h1>
                    </template>
                    <style>
                    h1 { color: blue; }
                    </style>
                    """);
            String html = compiler.renderToString("main.van", files, "{}");
            assertTrue(html.contains("color: blue"));
        }

        @Test
        void multiFile() {
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
                    <style>
                    h1 { color: green; }
                    </style>
                    """);

            String html = compiler.renderToString("index.van", files, "{\"title\": \"Van\"}");
            assertTrue(html.contains("<h1>Hello, Van!</h1>"));
            assertTrue(html.contains("color: green"));
        }

        @Test
        void typeOnlyImportErased() {
            Map<String, String> files = new HashMap<>();
            files.put("index.van", """
                    <template>
                      <div><p>{{ count }}</p></div>
                    </template>
                    <script setup lang="ts">
                    import type { Config } from './config.ts'
                    const count = ref(0)
                    </script>
                    """);

            String html = compiler.renderToString("index.van", files, "{}");
            assertFalse(html.contains("__mod_"));
            assertTrue(html.contains("V.signal(0)"));
        }
    }

    // ─── Compile tests (no data — template for Java SSR) ────────────

    @Nested
    class Compile {

        @Test
        void preservesVFor() {
            Map<String, String> files = Map.of("main.van", """
                    <template>
                      <ul><li v-for="item in items">{{ item.name }}</li></ul>
                    </template>
                    """);
            String html = compiler.compile("main.van", files).html();
            assertTrue(html.contains("v-for=\"item in items\""), "v-for preserved");
            assertTrue(html.contains("{{item.name}}"), "{{ }} preserved");
        }

        @Test
        void preservesVIf() {
            Map<String, String> files = Map.of("main.van", """
                    <template>
                      <div v-if="visible">content</div>
                    </template>
                    """);
            String html = compiler.compile("main.van", files).html();
            assertTrue(html.contains("v-if=\"visible\""), "v-if preserved");
        }

        @Test
        void preservesClassBinding() {
            Map<String, String> files = Map.of("main.van", """
                    <template>
                      <span :class="{ 'active': isActive }">text</span>
                    </template>
                    """);
            String html = compiler.compile("main.van", files).html();
            assertTrue(html.contains(":class="), ":class preserved");
        }

        @Test
        void stripsClick() {
            Map<String, String> files = Map.of("main.van", """
                    <template>
                      <button @click="handler">text</button>
                    </template>
                    """);
            String html = compiler.compile("main.van", files).html();
            assertFalse(html.contains("@click"), "@click stripped");
        }

        @Test
        void signalVsModel() {
            Map<String, String> files = Map.of("main.van", """
                    <script setup>
                    const count = ref(0)
                    const increment = () => count.value++
                    const visible = ref(false)
                    </script>
                    <template>
                      <div>
                        <h1>{{ ctx.title }}</h1>
                        <p>Count: {{ count }}</p>
                        <button @click="increment">+1</button>
                        <div v-show="visible">Hidden</div>
                        <ul v-for="item in ctx.items"><li>{{ item }}</li></ul>
                        <div v-if="ctx.isAdmin">Admin</div>
                      </div>
                    </template>
                    """);
            String html = compiler.compile("main.van", files).html();

            // Model bindings preserved
            assertTrue(html.contains("{{ctx.title}}"), "model {{ }} preserved");
            assertTrue(html.contains("v-for="), "v-for preserved");
            assertTrue(html.contains("v-if=\"ctx.isAdmin\""), "model v-if preserved");

            // Signal bindings processed
            assertTrue(html.contains("Count: 0"), "signal {{ count }} interpolated to initial value");
            assertFalse(html.contains("@click"), "@click stripped");
            assertTrue(html.contains("display:none"), "signal v-show=false evaluated");

            // Signal JS
            assertTrue(html.contains("<!--v:"), "comment anchors injected");
            assertTrue(html.contains("V.signal(0)"), "signal JS generated");
            assertTrue(html.contains("addEventListener"), "event binding JS generated");
            assertTrue(html.contains("createTreeWalker"), "comment walker generated");
        }

        @Test
        void layoutHtmlPropagates() {
            Map<String, String> files = new HashMap<>();
            files.put("pages/index.van", """
                    <script setup>
                    import Layout from '../components/Layout.van'
                    </script>
                    <template>
                      <Layout title="Dashboard">
                        <h1>Hello</h1>
                      </Layout>
                    </template>
                    """);
            files.put("components/Layout.van", """
                    <script setup>
                    defineProps({ title: String })
                    </script>
                    <template>
                      <html lang="en">
                      <head>
                        <title>{{ title }}</title>
                        <link rel="stylesheet" href="/style.css" />
                      </head>
                      <body>
                        <slot />
                      </body>
                      </html>
                    </template>
                    """);

            String html = compiler.renderToString("pages/index.van", files, "{}");
            assertTrue(html.contains("<html"), "Contains <html from Layout");
            assertTrue(html.contains("/style.css"), "Contains CSS link from Layout");
        }

        @Test
        void renderClassAndStyleBindings() {
            Map<String, String> files = Map.of("main.van", """
                    <template>
                      <div :class="{ active: isActive }" :style="{ color: textColor }">text</div>
                    </template>
                    """);
            String html = compiler.renderToString("main.van", files, "{\"isActive\":true,\"textColor\":\"red\"}");
            assertTrue(html.contains("active"), ":class evaluated");
            assertTrue(html.contains("color: red"), ":style evaluated");
        }

        @Test
        void renderVHtmlVText() {
            Map<String, String> files = Map.of("main.van", """
                    <template>
                      <div v-html="raw">old</div>
                      <span v-text="msg">old</span>
                    </template>
                    """);
            String html = compiler.renderToString("main.van", files, "{\"raw\":\"<b>bold</b>\",\"msg\":\"hello\"}");
            assertTrue(html.contains("<b>bold</b>"), "v-html evaluated");
            assertTrue(html.contains("hello"), "v-text evaluated");
        }
    }
}
