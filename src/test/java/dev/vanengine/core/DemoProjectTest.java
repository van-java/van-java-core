package dev.vanengine.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end demo project test.
 * Reads real .van files from src/test/resources/demo/ and compiles them.
 */
class DemoProjectTest {

    private static Map<String, String> files;
    private static String dataJson;
    private static final VanCompiler compiler = new VanCompiler();

    @BeforeAll
    static void loadDemoProject() throws IOException {
        files = new HashMap<>();
        files.put("pages/index.van", loadResource("demo/pages/index.van"));
        files.put("layouts/default.van", loadResource("demo/layouts/default.van"));
        files.put("components/card.van", loadResource("demo/components/card.van"));
        files.put("components/counter.van", loadResource("demo/components/counter.van"));
        files.put("components/user-list.van", loadResource("demo/components/user-list.van"));
        files.put("components/toggle-panel.van", loadResource("demo/components/toggle-panel.van"));
        dataJson = loadResource("demo/data/index.json");
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream is = DemoProjectTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ─── Debug: slot content with nested components ───────────────────

    @Test
    void debugSlotNestedComponent() {
        var f = new HashMap<String, String>();
        f.put("card.van", """
                <template><div class="card"><h2>{{ heading }}</h2><div class="body"><slot /></div></div></template>
                <script setup>
                defineProps({ heading: String })
                </script>""");
        f.put("counter.van", """
                <template><p>Count: {{ count }}</p></template>
                <script setup>
                const count = ref(0)
                </script>""");
        f.put("index.van", """
                <template>
                <my-card :heading="title">
                <my-counter />
                <p>Hello</p>
                </my-card>
                </template>
                <script setup>
                import MyCard from './card.van'
                import MyCounter from './counter.van'
                </script>""");

        String html = compiler.compile("index.van", f).html();
        assertTrue(html.contains("Count:"), "Counter should be resolved inside card slot. HTML: " +
                html.substring(html.indexOf("<div class=\"card"), Math.min(html.indexOf("</div></div>") + 12, html.length())));
    }

    // ─── Compile mode ───────────────────────────────────────────────

    @Test
    void compileProducesValidHtml() throws IOException {
        VanCompiler.CompiledResult result = compiler.compile("pages/index.van", files);
        String html = result.html();

        assertNotNull(html);
        assertFalse(html.isEmpty());

        // Layout <html> tag propagated (no double-wrap)
        assertTrue(html.contains("<html lang=\"en\">"), "Layout html tag");
        assertTrue(html.contains("</html>"), "Closing html tag");

        java.nio.file.Files.writeString(java.nio.file.Path.of("build/demo-compile.html"), html);
        // Also generate render output
        String rendered = compiler.renderToString("pages/index.van", files, dataJson);
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/demo-render.html"), rendered);
    }

    @Test
    void compilePreservesModelDirectives() {
        String html = compiler.compile("pages/index.van", files).html();

        // v-for preserved for Java SSR (inside user-list component)
        assertTrue(html.contains("v-for="), "v-for preserved");
        // v-if preserved
        assertTrue(html.contains("v-if="), "v-if preserved");
        // {{ model }} expressions preserved
        assertTrue(html.contains("{{"), "{{ }} expressions preserved");
    }

    @Test
    void compileStripsClientDirectives() {
        String html = compiler.compile("pages/index.van", files).html();

        // @click stripped (handled by signal JS)
        assertFalse(html.contains("@click="), "@click stripped");
    }

    @Test
    void compileIncludesAllScopedStyles() {
        String html = compiler.compile("pages/index.van", files).html();

        // Layout scoped styles
        assertTrue(html.contains("background:") || html.contains("background: "), "Layout navbar style");
        assertTrue(html.contains("text-align:") || html.contains("text-align: "), "Layout footer style");
        // Card scoped styles
        assertTrue(html.contains("border-radius:") || html.contains("border-radius: "), "Card style");
        // Counter scoped styles
        assertTrue(html.contains("cursor:") || html.contains("cursor: "), "Counter button style");
        // User list scoped styles
        assertTrue(html.contains("list-style:") || html.contains("list-style: "), "User list style");
    }

    @Test
    void compileIncludesSignalRuntime() {
        String html = compiler.compile("pages/index.van", files).html();

        // Counter has ref() → signal JS should be generated
        assertTrue(html.contains("signal"), "Signal runtime present");
        assertTrue(html.contains("effect"), "Effect in runtime");
        assertTrue(html.contains("computed"), "Computed in runtime");
    }

    @Test
    void compileResolvesComponentHierarchy() {
        String html = compiler.compile("pages/index.van", files).html();

        // Layout structure
        assertTrue(html.contains("<nav"), "Layout nav element");
        assertTrue(html.contains("<main"), "Layout main element");
        assertTrue(html.contains("<footer"), "Layout footer element");

        // Named slot content (#nav)
        assertTrue(html.contains("Home"), "Nav slot: Home link");
        assertTrue(html.contains("About"), "Nav slot: About link");

        // Card component resolved
        assertTrue(html.contains("card"), "Card class");

        // Counter component resolved
        assertTrue(html.contains("counter"), "Counter class");
    }

    // ─── Render mode ────────────────────────────────────────────────

    @Test
    void renderInterpolatesAllData() throws IOException {
        String html = compiler.renderToString("pages/index.van", files, dataJson);

        // Page title
        assertTrue(html.contains("Van Demo Dashboard"), "Page title interpolated");
        assertTrue(html.contains("<title>Van Demo Dashboard</title>"), "Layout title tag");

        // Description
        assertTrue(html.contains("demonstration of the Van template engine"), "Description");

        // Card headings
        assertTrue(html.contains("Interactive Counter"), "Counter card heading");
        assertTrue(html.contains("Statistics"), "Stats card heading");

        java.nio.file.Files.writeString(java.nio.file.Path.of("build/demo-render.html"), html);
    }

    @Test
    void renderExpandsVFor() {
        String html = compiler.renderToString("pages/index.van", files, dataJson);

        assertFalse(html.contains("v-for="), "v-for expanded");
        assertTrue(html.contains("Alice Chen"), "User: Alice");
        assertTrue(html.contains("Bob Smith"), "User: Bob");
        assertTrue(html.contains("Charlie Wang"), "User: Charlie");
        assertTrue(html.contains("Diana Lee"), "User: Diana");
        assertTrue(html.contains("Lead Developer"), "Role");
    }

    @Test
    void renderEvaluatesVIf() {
        String html = compiler.renderToString("pages/index.van", files, dataJson);

        assertFalse(html.contains("v-if="), "v-if evaluated");
        assertFalse(html.contains("v-else"), "v-else evaluated");
    }

    @Test
    void renderInterpolatesNestedData() {
        String html = compiler.renderToString("pages/index.van", files, dataJson);

        // stats.total and stats.active should be resolved
        assertTrue(html.contains("4"), "Total users: 4");
        assertTrue(html.contains("3"), "Active users: 3");
    }

    @Test
    void renderProducesCompleteHtml() {
        String html = compiler.renderToString("pages/index.van", files, dataJson);

        assertTrue(html.contains("<html"), "Has html tag");
        assertTrue(html.contains("</body>"), "Has closing body");
        assertTrue(html.contains("</html>"), "Has closing html");
        assertTrue(html.contains("<style>"), "Has style tags");

        // Signal-managed {{ }} (like {{ doubled }}) may remain — they are
        // updated by client-side JS. Only model {{ }} should be fully resolved.
        // Verify key data fields are interpolated (not raw {{ }}).
        assertTrue(html.contains("Van Demo Dashboard"), "pageTitle resolved");
        assertFalse(html.contains("{{pageTitle}}"), "pageTitle not raw");
    }
}
