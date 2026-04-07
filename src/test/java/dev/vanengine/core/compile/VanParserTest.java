package dev.vanengine.core.compile;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VanParserTest {

    // ─── parseBlocks tests ──────────────────────────────────────────

    @Nested
    class ParseBlocks {

        @Test
        void basic() {
            String source = """
                    <script setup lang="ts">
                    import Hello from './hello.van'
                    </script>

                    <template>
                      <div>Hello {{ name }}</div>
                    </template>

                    <style scoped>
                    .hello { color: red; }
                    </style>
                    """;
            VanParser.VanBlock blocks = VanParser.parseBlocks(source);
            assertNotNull(blocks.template());
            assertTrue(blocks.template().contains("Hello {{ name }}"));
            assertNotNull(blocks.scriptSetup());
            assertTrue(blocks.scriptSetup().contains("import Hello"));
            assertNotNull(blocks.style());
            assertTrue(blocks.style().contains("color: red"));
            assertNull(blocks.scriptServer());
        }

        @Test
        void withJavaScript() {
            String source = """
                    <template>
                      <div></div>
                    </template>

                    <script setup lang="ts">
                    // ts code
                    </script>

                    <script lang="java">
                    // java code
                    </script>
                    """;
            VanParser.VanBlock blocks = VanParser.parseBlocks(source);
            assertNotNull(blocks.template());
            assertNotNull(blocks.scriptSetup());
            assertNotNull(blocks.scriptServer());
            assertTrue(blocks.scriptServer().contains("java code"));
        }

        @Test
        void empty() {
            VanParser.VanBlock blocks = VanParser.parseBlocks("");
            assertNull(blocks.template());
            assertNull(blocks.scriptSetup());
            assertNull(blocks.scriptServer());
            assertNull(blocks.style());
        }

        @Test
        void nestedTemplateSlots() {
            String source = """
                    <template>
                      <default-layout>
                        <template #title>{{ title }}</template>
                        <h1>Welcome</h1>
                      </default-layout>
                    </template>

                    <script setup lang="ts">
                    import DefaultLayout from '../layouts/default.van'
                    </script>
                    """;
            VanParser.VanBlock blocks = VanParser.parseBlocks(source);
            String template = blocks.template();
            assertNotNull(template);
            assertTrue(template.contains("<default-layout>"), "Should contain opening tag");
            assertTrue(template.contains("</default-layout>"), "Should contain closing tag");
            assertTrue(template.contains("<template #title>"), "Should contain slot template");
            assertTrue(template.contains("<h1>Welcome</h1>"), "Should contain h1");
        }

        @Test
        void includesProps() {
            String source = """
                    <script setup lang="ts">
                    defineProps({ title: String, count: Number })
                    </script>

                    <template>
                      <h1>{{ title }}</h1>
                    </template>
                    """;
            VanParser.VanBlock blocks = VanParser.parseBlocks(source);
            assertEquals(2, blocks.props().size());
            assertEquals("title", blocks.props().get(0).name());
            assertEquals("count", blocks.props().get(1).name());
        }
    }

    // ─── parseImports tests ─────────────────────────────────────────

    @Nested
    class ParseImports {

        @Test
        void basic() {
            String script = """
                    import DefaultLayout from '../layouts/default.van'
                    import Hello from '../components/hello.van'

                    defineProps({
                      title: String
                    })
                    """;
            List<VanParser.VanImport> imports = VanParser.parseImports(script);
            assertEquals(2, imports.size());
            assertEquals("DefaultLayout", imports.get(0).name());
            assertEquals("default-layout", imports.get(0).tagName());
            assertEquals("../layouts/default.van", imports.get(0).path());
            assertEquals("Hello", imports.get(1).name());
            assertEquals("hello", imports.get(1).tagName());
            assertEquals("../components/hello.van", imports.get(1).path());
        }

        @Test
        void doubleQuotes() {
            String script = """
                    import Foo from "../components/foo.van"
                    """;
            List<VanParser.VanImport> imports = VanParser.parseImports(script);
            assertEquals(1, imports.size());
            assertEquals("Foo", imports.get(0).name());
            assertEquals("../components/foo.van", imports.get(0).path());
        }

        @Test
        void noVanFiles() {
            String script = "import { ref } from 'vue'";
            List<VanParser.VanImport> imports = VanParser.parseImports(script);
            assertTrue(imports.isEmpty());
        }

        @Test
        void scopedPackage() {
            String script = """
                    import VanButton from '@van-ui/button/button.van'
                    import DefaultLayout from '../layouts/default.van'
                    """;
            List<VanParser.VanImport> imports = VanParser.parseImports(script);
            assertEquals(2, imports.size());
            assertEquals("VanButton", imports.get(0).name());
            assertEquals("van-button", imports.get(0).tagName());
            assertEquals("@van-ui/button/button.van", imports.get(0).path());
            assertEquals("DefaultLayout", imports.get(1).name());
            assertEquals("../layouts/default.van", imports.get(1).path());
        }
    }

    // ─── parseScriptImports tests ───────────────────────────────────

    @Nested
    class ParseScriptImports {

        @Test
        void ts() {
            String script = """
                    import { formatDate } from '../utils/format.ts'
                    import DefaultLayout from '../layouts/default.van'
                    const count = ref(0)
                    """;
            List<VanParser.ScriptImport> imports = VanParser.parseScriptImports(script);
            assertEquals(1, imports.size());
            assertEquals("../utils/format.ts", imports.get(0).path());
            assertFalse(imports.get(0).isTypeOnly());
            assertTrue(imports.get(0).raw().contains("formatDate"));
        }

        @Test
        void typeOnly() {
            String script = """
                    import type { User } from '../types/models.ts'
                    import { formatDate } from '../utils/format.ts'
                    """;
            List<VanParser.ScriptImport> imports = VanParser.parseScriptImports(script);
            assertEquals(2, imports.size());
            assertTrue(imports.get(0).isTypeOnly());
            assertEquals("../types/models.ts", imports.get(0).path());
            assertFalse(imports.get(1).isTypeOnly());
            assertEquals("../utils/format.ts", imports.get(1).path());
        }

        @Test
        void js() {
            String script = "import foo from '../utils/helper.js'";
            List<VanParser.ScriptImport> imports = VanParser.parseScriptImports(script);
            assertEquals(1, imports.size());
            assertEquals("../utils/helper.js", imports.get(0).path());
            assertFalse(imports.get(0).isTypeOnly());
        }

        @Test
        void ignoresVan() {
            String script = """
                    import Hello from './hello.van'
                    import Foo from '../foo.van'
                    """;
            List<VanParser.ScriptImport> imports = VanParser.parseScriptImports(script);
            assertTrue(imports.isEmpty());
        }

        @Test
        void ignoresBare() {
            String script = "import { ref } from 'vue'";
            List<VanParser.ScriptImport> imports = VanParser.parseScriptImports(script);
            assertTrue(imports.isEmpty());
        }

        @Test
        void mixedType() {
            // `import { type User, formatDate } from ...` is NOT type-only
            String script = "import { type User, formatDate } from '../utils.ts'";
            List<VanParser.ScriptImport> imports = VanParser.parseScriptImports(script);
            assertEquals(1, imports.size());
            assertFalse(imports.get(0).isTypeOnly());
        }

        @Test
        void scopedPackage() {
            String script = """
                    import { formatDate } from '@van-ui/utils/format.ts'
                    import { helper } from '../utils/helper.ts'
                    """;
            List<VanParser.ScriptImport> imports = VanParser.parseScriptImports(script);
            assertEquals(2, imports.size());
            assertEquals("@van-ui/utils/format.ts", imports.get(0).path());
            assertEquals("../utils/helper.ts", imports.get(1).path());
        }

        @Test
        void tsxJsx() {
            String script = """
                    import { render } from '../lib/render.tsx'
                    import { helper } from '../lib/helper.jsx'
                    """;
            List<VanParser.ScriptImport> imports = VanParser.parseScriptImports(script);
            assertEquals(2, imports.size());
            assertEquals("../lib/render.tsx", imports.get(0).path());
            assertEquals("../lib/helper.jsx", imports.get(1).path());
        }
    }

    // ─── pascalToKebab tests ────────────────────────────────────────

    @Test
    void pascalToKebab() {
        assertEquals("default-layout", VanParser.pascalToKebab("DefaultLayout"));
        assertEquals("hello", VanParser.pascalToKebab("Hello"));
        assertEquals("my-component", VanParser.pascalToKebab("MyComponent"));
        assertEquals("a", VanParser.pascalToKebab("A"));
    }

    // ─── Scoped style tests ─────────────────────────────────────────

    @Nested
    class ScopedStyle {

        @Test
        void scopedDetection() {
            String scopedSource = """
                    <template><div>Hi</div></template>
                    <style scoped>
                    .card { color: red; }
                    </style>
                    """;
            VanParser.VanBlock blocks = VanParser.parseBlocks(scopedSource);
            assertTrue(blocks.styleScoped());
            assertTrue(blocks.style().contains("color: red"));

            String unscopedSource = """
                    <template><div>Hi</div></template>
                    <style>
                    .card { color: blue; }
                    </style>
                    """;
            VanParser.VanBlock blocks2 = VanParser.parseBlocks(unscopedSource);
            assertFalse(blocks2.styleScoped());
            assertTrue(blocks2.style().contains("color: blue"));
        }

        @Test
        void scopedWithLang() {
            String source = """
                    <template><div>Hi</div></template>
                    <style scoped lang="css">
                    h1 { font-size: 2rem; }
                    </style>
                    """;
            VanParser.VanBlock blocks = VanParser.parseBlocks(source);
            assertTrue(blocks.styleScoped());
        }

        @Test
        void scopeIdDeterministic() {
            String id1 = VanParser.scopeId(".card { color: red; }");
            String id2 = VanParser.scopeId(".card { color: red; }");
            assertEquals(id1, id2);
            assertEquals(8, id1.length());
            // Different content → different ID
            String id3 = VanParser.scopeId("h1 { color: blue; }");
            assertNotEquals(id1, id3);
        }

        @Test
        void addScopeClassAllElements() {
            String html = "<div class=\"card\"><h1>Title</h1><p>Text</p></div>";
            String result = VanParser.addScopeClass(html, "a1b2c3d4");
            assertEquals(
                    "<div class=\"card a1b2c3d4\"><h1 class=\"a1b2c3d4\">Title</h1><p class=\"a1b2c3d4\">Text</p></div>",
                    result
            );
        }

        @Test
        void addScopeClassNoClass() {
            String html = "<div><h1>Title</h1></div>";
            String result = VanParser.addScopeClass(html, "a1b2c3d4");
            assertEquals("<div class=\"a1b2c3d4\"><h1 class=\"a1b2c3d4\">Title</h1></div>", result);
        }

        @Test
        void addScopeClassSelfClosing() {
            String html = "<div><img src=\"x.png\" /><br /></div>";
            String result = VanParser.addScopeClass(html, "a1b2c3d4");
            assertEquals(
                    "<div class=\"a1b2c3d4\"><img src=\"x.png\" class=\"a1b2c3d4\" /><br class=\"a1b2c3d4\" /></div>",
                    result
            );
        }

        @Test
        void addScopeClassSkipsComments() {
            String html = "<!-- comment --><div>Hi</div>";
            String result = VanParser.addScopeClass(html, "a1b2c3d4");
            assertEquals("<!-- comment --><div class=\"a1b2c3d4\">Hi</div>", result);
        }

        @Test
        void addScopeClassSkipsSlot() {
            String html = "<div><slot /><slot name=\"x\">fallback</slot></div>";
            String result = VanParser.addScopeClass(html, "a1b2c3d4");
            assertEquals("<div class=\"a1b2c3d4\"><slot /><slot name=\"x\">fallback</slot></div>", result);
        }

        @Test
        void addScopeClassSkipsStructural() {
            String html = "<html><head><meta charset=\"UTF-8\" /></head><body><nav class=\"x\">Hi</nav></body></html>";
            String result = VanParser.addScopeClass(html, "a1b2c3d4");
            assertEquals(
                    "<html><head><meta charset=\"UTF-8\" /></head><body><nav class=\"x a1b2c3d4\">Hi</nav></body></html>",
                    result
            );
        }
    }

    // ─── scopeCss tests ─────────────────────────────────────────────

    @Nested
    class ScopeCss {

        @Test
        void singleSelector() {
            String css = ".card { border: 1px solid; }";
            String result = VanParser.scopeCss(css, "a1b2c3d4");
            assertEquals(".card.a1b2c3d4 { border: 1px solid; }", result);
        }

        @Test
        void multipleRules() {
            String css = ".card { border: 1px solid; }\nh1 { color: navy; }";
            String result = VanParser.scopeCss(css, "a1b2c3d4");
            assertTrue(result.contains(".card.a1b2c3d4 { border: 1px solid; }"));
            assertTrue(result.contains("h1.a1b2c3d4 { color: navy; }"));
        }

        @Test
        void commaSelectors() {
            String css = ".card, .box { border: 1px solid; }";
            String result = VanParser.scopeCss(css, "a1b2c3d4");
            assertEquals(".card.a1b2c3d4, .box.a1b2c3d4 { border: 1px solid; }", result);
        }

        @Test
        void descendantSelector() {
            String css = ".card h1 { color: navy; }";
            String result = VanParser.scopeCss(css, "a1b2c3d4");
            assertEquals(".card h1.a1b2c3d4 { color: navy; }", result);
        }

        @Test
        void pseudoClass() {
            String css = ".demo-list a:hover { text-decoration: underline; }";
            String result = VanParser.scopeCss(css, "a1b2c3d4");
            assertEquals(".demo-list a.a1b2c3d4:hover { text-decoration: underline; }", result);
        }

        @Test
        void pseudoElement() {
            String css = ".item::before { content: '-'; }";
            String result = VanParser.scopeCss(css, "a1b2c3d4");
            assertEquals(".item.a1b2c3d4::before { content: '-'; }", result);
        }

        @Test
        void noPseudo() {
            String css = "h1 { font-size: 2rem; }";
            String result = VanParser.scopeCss(css, "a1b2c3d4");
            assertEquals("h1.a1b2c3d4 { font-size: 2rem; }", result);
        }
    }

    // ─── defineProps tests ──────────────────────────────────────────

    @Nested
    class DefineProps {

        @Test
        void simple() {
            String script = "defineProps({ title: String, count: Number })";
            List<VanParser.PropDef> props = VanParser.parseDefineProps(script);
            assertEquals(2, props.size());
            assertEquals("title", props.get(0).name());
            assertEquals("String", props.get(0).propType());
            assertFalse(props.get(0).required());
            assertEquals("count", props.get(1).name());
            assertEquals("Number", props.get(1).propType());
            assertFalse(props.get(1).required());
        }

        @Test
        void withRequired() {
            String script = "defineProps({ user: { type: Object, required: true } })";
            List<VanParser.PropDef> props = VanParser.parseDefineProps(script);
            assertEquals(1, props.size());
            assertEquals("user", props.get(0).name());
            assertEquals("Object", props.get(0).propType());
            assertTrue(props.get(0).required());
        }

        @Test
        void mixed() {
            String script = """
                    defineProps({
                      title: String,
                      user: { type: Object, required: true },
                      count: Number
                    })""";
            List<VanParser.PropDef> props = VanParser.parseDefineProps(script);
            assertEquals(3, props.size());
            assertEquals("title", props.get(0).name());
            assertEquals("String", props.get(0).propType());
            assertFalse(props.get(0).required());
            assertEquals("user", props.get(1).name());
            assertEquals("Object", props.get(1).propType());
            assertTrue(props.get(1).required());
            assertEquals("count", props.get(2).name());
            assertEquals("Number", props.get(2).propType());
            assertFalse(props.get(2).required());
        }

        @Test
        void missing() {
            String script = "const count = ref(0)";
            List<VanParser.PropDef> props = VanParser.parseDefineProps(script);
            assertTrue(props.isEmpty());
        }

        @Test
        void empty() {
            String script = "defineProps({})";
            List<VanParser.PropDef> props = VanParser.parseDefineProps(script);
            assertTrue(props.isEmpty());
        }
    }
}
