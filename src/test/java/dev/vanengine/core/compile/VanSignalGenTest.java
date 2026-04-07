package dev.vanengine.core.compile;

import dev.vanengine.core.compile.VanSignalGen.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VanSignalGenTest {

    // ─── Script Analysis ────────────────────────────────────────────

    @Nested
    class AnalyzeScript {

        @Test
        void ref() {
            String script = """
                    const count = ref(0)
                    const name = ref('hello')
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(2, a.signals().size());
            assertEquals("count", a.signals().get(0).name());
            assertEquals("0", a.signals().get(0).initialValue());
            assertEquals("name", a.signals().get(1).name());
            assertEquals("'hello'", a.signals().get(1).initialValue());
        }

        @Test
        void computed() {
            String script = "const doubled = computed(() => count * 2)";
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.computeds().size());
            assertEquals("doubled", a.computeds().get(0).name());
            assertEquals("count * 2", a.computeds().get(0).body());
        }

        @Test
        void functions() {
            String script = "function increment() { count.value++ }";
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.functions().size());
            assertEquals("increment", a.functions().get(0).name());
            assertEquals("count.value++", a.functions().get(0).body());
        }

        @Test
        void full() {
            String script = """
                    import DefaultLayout from '../layouts/default.van'
                    import Hello from '../components/hello.van'

                    defineProps({ title: String })

                    const count = ref(0)
                    function increment() { count.value++ }
                    function decrement() { count.value-- }
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.signals().size());
            assertEquals("count", a.signals().get(0).name());
            assertEquals(2, a.functions().size());
            assertEquals("increment", a.functions().get(0).name());
            assertEquals("decrement", a.functions().get(1).name());
        }

        @Test
        void arrowFunctionExpression() {
            String script = """
                    const count = ref(0)
                    const increment = () => count.value++
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.signals().size());
            assertEquals(1, a.functions().size());
            assertEquals("increment", a.functions().get(0).name());
            assertEquals("count.value++", a.functions().get(0).body());
        }

        @Test
        void arrowFunctionBlock() {
            String script = """
                    const count = ref(0)
                    const add = (n) => { count.value += n }
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.functions().size());
            assertEquals("add", a.functions().get(0).name());
            assertEquals("n", a.functions().get(0).params());
            assertTrue(a.functions().get(0).body().contains("count.value += n"));
        }

        @Test
        void functionExpression() {
            String script = """
                    const count = ref(0)
                    const reset = function() { count.value = 0 }
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.functions().size());
            assertEquals("reset", a.functions().get(0).name());
        }

        @Test
        void refComplexInitialValues() {
            String script = """
                    const items = ref([1, 2, 3])
                    const config = ref({ theme: 'dark' })
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(2, a.signals().size());
            assertEquals("items", a.signals().get(0).name());
            assertEquals("[1, 2, 3]", a.signals().get(0).initialValue());
            assertEquals("config", a.signals().get(1).name());
            assertTrue(a.signals().get(1).initialValue().contains("theme"));
        }
    }

    // ─── Template Walking ───────────────────────────────────────────

    @Nested
    class WalkTemplate {

        @Test
        void events() {
            String html = "<div><button @click=\"increment\">+1</button></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"count"});
            assertEquals(1, b.events().size());
            assertEquals("click", b.events().get(0).event());
            assertEquals("increment", b.events().get(0).handler());
            assertEquals(List.of(0, 0), b.events().get(0).path());
        }

        @Test
        void textBinding() {
            String html = "<div><p>Count: {{ count }}</p></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"count"});
            assertEquals(1, b.texts().size());
            assertEquals("Count: {{ count }}", b.texts().get(0).template());
            assertEquals(List.of(0, 0), b.texts().get(0).path());
        }

        @Test
        void show() {
            String html = "<div><p v-show=\"visible\">Hello</p></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"visible"});
            assertEquals(1, b.shows().size());
            assertEquals("visible", b.shows().get(0).expr());
            assertEquals(List.of(0, 0), b.shows().get(0).path());
            assertNull(b.shows().get(0).transition());
        }

        @Test
        void transitionSkipsPath() {
            String html = "<div><p>Before</p><Transition name=\"slide\"><div v-show=\"open\">Drawer</div></Transition><p>After</p></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"open"});
            assertEquals(1, b.shows().size());
            assertEquals(List.of(0, 1), b.shows().get(0).path());
            assertEquals("open", b.shows().get(0).expr());
            assertEquals("slide", b.shows().get(0).transition());
        }

        @Test
        void transitionNoName() {
            String html = "<div><Transition><p v-show=\"visible\">Hi</p></Transition></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"visible"});
            assertEquals(1, b.shows().size());
            assertEquals(List.of(0, 0), b.shows().get(0).path());
            assertEquals("v", b.shows().get(0).transition());
        }

        @Test
        void body() {
            String html = "<html><head><title>Test</title></head><body><nav>nav</nav><main><p>Count: {{ count }}</p><button @click=\"inc\">+</button></main></body></html>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"count"});
            assertEquals(1, b.texts().size());
            assertEquals(List.of(1, 0), b.texts().get(0).path());
            assertEquals(1, b.events().size());
            assertEquals(List.of(1, 1), b.events().get(0).path());
        }

        @Test
        void noReactiveText() {
            String html = "<div><p>Hello {{ name }}</p></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"count"});
            assertEquals(0, b.texts().size());
        }
    }

    // ─── Expression Transform ───────────────────────────────────────

    @Nested
    class TransformExpr {

        @Test
        void basic() {
            String[] names = {"count"};
            assertEquals("count.value", VanSignalGen.transformExpr("count", names));
            assertEquals("count.value++", VanSignalGen.transformExpr("count.value++", names));
            assertEquals("'Count: ' + count.value", VanSignalGen.transformExpr("'Count: ' + count", names));
        }

        @Test
        void templateToJsExpr() {
            String[] names = {"count"};
            assertEquals("'Count: ' + count.value", VanSignalGen.templateToJsExpr("Count: {{ count }}", names));
        }

        @Test
        void templateToJsExprOnlyReactive() {
            String[] names = {"count"};
            assertEquals("count.value", VanSignalGen.templateToJsExpr("{{ count }}", names));
        }
    }

    // ─── :class/:style parsing ──────────────────────────────────────

    @Nested
    class ClassStyleParsing {

        @Test
        void classExprObject() {
            var items = VanSignalGen.parseClassExpr("{ active: isActive }");
            assertEquals(1, items.size());
            assertInstanceOf(ClassItem.Toggle.class, items.get(0));
            assertEquals("active", ((ClassItem.Toggle) items.get(0)).className());
        }

        @Test
        void classExprArray() {
            var items = VanSignalGen.parseClassExpr("[{ active: isActive, highlight: isHighlighted }, 'static-cls']");
            assertEquals(3, items.size());
            assertInstanceOf(ClassItem.Toggle.class, items.get(0));
            assertInstanceOf(ClassItem.Toggle.class, items.get(1));
            assertInstanceOf(ClassItem.Static.class, items.get(2));
            assertEquals("static-cls", ((ClassItem.Static) items.get(2)).className());
        }

        @Test
        void styleExprObject() {
            var pairs = VanSignalGen.parseStyleExpr("{ color: textColor }");
            assertEquals(1, pairs.size());
            assertEquals("color", pairs.get(0)[0]);
            assertEquals("textColor", pairs.get(0)[1]);
        }

        @Test
        void styleExprArray() {
            var pairs = VanSignalGen.parseStyleExpr("[{ color: c }, { fontSize: s }]");
            assertEquals(2, pairs.size());
            assertEquals("color", pairs.get(0)[0]);
            assertEquals("fontSize", pairs.get(1)[0]);
        }
    }

    // ─── Signal Generation ──────────────────────────────────────────

    @Nested
    class GenerateSignals {

        @Test
        void positional() {
            String script = """
                    const count = ref(0)
                    function increment() { count.value++ }
                    function decrement() { count.value-- }
                    """;
            String html = "<body><nav>nav</nav><main><h1>Title</h1><div class=\"counter\"><p>Count: {{ count }}</p><button @click=\"increment\">+1</button><button @click=\"decrement\">-1</button></div></main></body>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");

            assertNotNull(js);
            assertFalse(js.contains("querySelectorAll"));
            assertTrue(js.contains("document.body"));
            assertTrue(js.contains(".children["));
            assertTrue(js.contains("addEventListener('click'"));
            assertTrue(js.contains("V.effect("));
            assertTrue(js.contains("textContent"));
            assertTrue(js.contains("count.value"));
        }

        @Test
        void noneForStatic() {
            String script = "defineProps({ title: String })";
            String html = "<div><h1>Hello</h1></div>";
            assertNull(VanSignalGen.generateSignals(script, html, List.of(), "Van"));
        }

        @Test
        void customGlobalName() {
            String script = """
                    const count = ref(0)
                    function increment() { count.value++ }
                    """;
            String html = "<div><p>{{ count }}</p><button @click=\"increment\">+</button></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "MyApp");
            assertNotNull(js);
            assertTrue(js.contains("var V = MyApp;"));
            assertFalse(js.contains("var V = Van;"));
        }

        @Test
        void withTransition() {
            String script = """
                    const open = ref(false)
                    function toggle() { open.value = !open.value }
                    """;
            String html = "<div><button @click=\"toggle\">Toggle</button><Transition name=\"fade\"><div v-show=\"open\">Content</div></Transition></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("V.transition("));
            assertTrue(js.contains("'fade'"));
            assertFalse(js.contains("style.display"));
        }

        @Test
        void classBinding() {
            String script = "const isActive = ref(true)";
            String html = "<div :class=\"[{ active: isActive }, 'base']\"><p>Hello</p></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("classList.toggle('active'"));
            assertTrue(js.contains("classList.add('base')"));
        }

        @Test
        void styleBinding() {
            String script = """
                    const textColor = ref('red')
                    const size = ref('16px')
                    """;
            String html = "<div :style=\"[{ color: textColor }, { fontSize: size }]\">Hello</div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("style.color"));
            assertTrue(js.contains("style.fontSize"));
            assertTrue(js.contains("textColor.value"));
            assertTrue(js.contains("size.value"));
        }

        @Test
        void withModuleCode() {
            String script = """
                    import { formatDate } from '../utils/format.ts'
                    const count = ref(0)
                    function increment() { count.value++ }
                    """;
            String html = "<body><div><p>Count: {{ count }}</p><button @click=\"increment\">+1</button></div></body>";
            List<String> modules = List.of("function formatDate(d) { return d.toISOString(); }\nreturn { formatDate: formatDate };");
            String js = VanSignalGen.generateSignals(script, html, modules, "Van");
            assertNotNull(js);
            assertTrue(js.contains("var __mod_0 = (function()"));
            assertTrue(js.contains("V.signal(0)"));
        }

        @Test
        void arrowClickHandler() {
            String script = """
                    const count = ref(0)
                    const increment = () => count.value++
                    """;
            String html = "<body><p>{{ count }}</p><button @click=\"increment\">+1</button></body>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("function increment("));
            assertTrue(js.contains("addEventListener('click', increment)"));
            assertFalse(js.contains("function() { increment }"));
        }
    }

    // ─── Runtime JS ─────────────────────────────────────────────────

    @Nested
    class RuntimeJs {

        @Test
        void included() {
            String js = VanSignalGen.runtimeJs();
            assertTrue(js.contains("__VAN_NS__"));
            assertTrue(js.contains("signal"));
            assertTrue(js.contains("effect"));
            assertTrue(js.contains("computed"));
        }

        @Test
        void defaultName() {
            String js = VanSignalGen.runtimeJs("Van");
            assertTrue(js.contains("window.Van"));
            assertFalse(js.contains("__VAN_NS__"));
        }

        @Test
        void customName() {
            String js = VanSignalGen.runtimeJs("MyApp");
            assertTrue(js.contains("window.MyApp"));
            assertFalse(js.contains("window.Van"));
        }

        @Test
        void hasTransition() {
            String js = VanSignalGen.runtimeJs();
            assertTrue(js.contains("transition"));
            assertTrue(js.contains("__van_t"));
            assertTrue(js.contains("enter-from"));
            assertTrue(js.contains("leave-to"));
        }
    }
}
