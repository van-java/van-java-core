package dev.vanengine.core.compile;

import dev.vanengine.core.compile.VanSignalGen.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScriptSetupParser — especially edge cases that regex-based parsing cannot handle.
 */
class ScriptSetupParserTest {

    // ─── Nested parentheses ─────────────────────────────────────────

    @Nested
    class NestedParentheses {

        @Test
        void refWithNestedFunctionCall() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("const count = ref(someFunc(a, b))");
            assertEquals(1, a.signals().size());
            assertEquals("count", a.signals().get(0).name());
            assertEquals("someFunc(a, b)", a.signals().get(0).initialValue());
        }

        @Test
        void refWithNestedTernary() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("const x = ref(cond ? fn(a) : fn(b))");
            assertEquals(1, a.signals().size());
            assertEquals("cond ? fn(a) : fn(b)", a.signals().get(0).initialValue());
        }

        @Test
        void computedWithComplexExpression() {
            ScriptAnalysis a = VanSignalGen.analyzeScript(
                    "const total = computed(() => items.reduce((sum, item) => sum + item.price, 0))");
            assertEquals(1, a.computeds().size());
            assertEquals("total", a.computeds().get(0).name());
            assertTrue(a.computeds().get(0).body().contains("reduce"));
        }
    }

    // ─── Strings with special chars ─────────────────────────────────

    @Nested
    class StringLiterals {

        @Test
        void refWithStringContainingParens() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("const msg = ref('hello (world)')");
            assertEquals(1, a.signals().size());
            assertEquals("'hello (world)'", a.signals().get(0).initialValue());
        }

        @Test
        void refWithStringContainingBraces() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("const tpl = ref('value: {x}')");
            assertEquals(1, a.signals().size());
            assertEquals("'value: {x}'", a.signals().get(0).initialValue());
        }

        @Test
        void refWithDoubleQuotedString() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("const name = ref(\"hello, world\")");
            assertEquals(1, a.signals().size());
            assertEquals("\"hello, world\"", a.signals().get(0).initialValue());
        }

        @Test
        void functionBodyWithStringContainingBraces() {
            ScriptAnalysis a = VanSignalGen.analyzeScript(
                    "function greet() { return 'Hello {name}' + count.value }");
            assertEquals(1, a.functions().size());
            assertEquals("greet", a.functions().get(0).name());
            assertTrue(a.functions().get(0).body().contains("Hello {name}"));
        }
    }

    // ─── Template literals ──────────────────────────────────────────

    @Nested
    class TemplateLiterals {

        @Test
        void refWithTemplateLiteral() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("const msg = ref(`hello ${name}`)");
            assertEquals(1, a.signals().size());
            assertTrue(a.signals().get(0).initialValue().contains("hello"));
        }

        @Test
        void functionWithTemplateLiteral() {
            ScriptAnalysis a = VanSignalGen.analyzeScript(
                    "function format() { return `Count: ${count.value}` }");
            assertEquals(1, a.functions().size());
            assertTrue(a.functions().get(0).body().contains("Count:"));
        }
    }

    // ─── Complex arrow functions ────────────────────────────────────

    @Nested
    class ArrowFunctions {

        @Test
        void arrowWithDefaultParam() {
            ScriptAnalysis a = VanSignalGen.analyzeScript(
                    "const count = ref(0)\nconst add = (n = 1) => { count.value += n }");
            assertEquals(1, a.functions().size());
            assertEquals("add", a.functions().get(0).name());
            assertEquals("n = 1", a.functions().get(0).params());
        }

        @Test
        void arrowWithDestructuredParam() {
            ScriptAnalysis a = VanSignalGen.analyzeScript(
                    "const count = ref(0)\nconst update = ({ value }) => { count.value = value }");
            assertEquals(1, a.functions().size());
            assertEquals("update", a.functions().get(0).name());
            assertTrue(a.functions().get(0).params().contains("value"));
        }

        @Test
        void arrowConciseWithMultipleOps() {
            ScriptAnalysis a = VanSignalGen.analyzeScript(
                    "const count = ref(0)\nconst increment = () => count.value++");
            assertEquals(1, a.functions().size());
            assertEquals("count.value++", a.functions().get(0).body());
        }
    }

    // ─── Comments ───────────────────────────────────────────────────

    @Nested
    class Comments {

        @Test
        void lineCommentIgnored() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("""
                    // this is a comment
                    const count = ref(0)
                    // another comment
                    function increment() { count.value++ }
                    """);
            assertEquals(1, a.signals().size());
            assertEquals(1, a.functions().size());
        }

        @Test
        void blockCommentIgnored() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("""
                    /* block comment */
                    const count = ref(0)
                    /* multi
                       line */
                    function increment() { count.value++ }
                    """);
            assertEquals(1, a.signals().size());
            assertEquals(1, a.functions().size());
        }

        @Test
        void commentInsideStringNotTreatedAsComment() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("const url = ref('http://example.com')");
            assertEquals(1, a.signals().size());
            assertTrue(a.signals().get(0).initialValue().contains("http://"));
        }
    }

    // ─── Multiple declarations ──────────────────────────────────────

    @Nested
    class MultipleDeclarations {

        @Test
        void fullScriptSetup() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("""
                    import Layout from './layout.van'
                    import { formatDate } from './utils.ts'
                    import type { User } from './types'

                    defineProps({ title: String })
                    const emit = defineEmits(['update', 'delete'])

                    const count = ref(0)
                    const name = ref('hello')
                    const doubled = computed(() => count * 2)
                    const state = reactive({ theme: 'dark' })

                    function increment() { count.value++ }
                    const decrement = () => count.value--
                    const reset = function() { count.value = 0 }

                    watch(count, (newVal, oldVal) => { console.log(newVal) })
                    watchEffect(() => { document.title = count.value })
                    onMounted(() => { console.log('mounted') })
                    onUnmounted(() => { cleanup() })
                    """);

            assertEquals(2, a.signals().size());
            assertEquals("count", a.signals().get(0).name());
            assertEquals("name", a.signals().get(1).name());
            assertEquals(1, a.computeds().size());
            assertEquals(1, a.reactives().size());
            assertEquals(3, a.functions().size()); // increment, decrement, reset
            assertEquals(1, a.watches().size());
            assertEquals(1, a.watchEffects().size());
            assertEquals(2, a.lifecycles().size());
            assertTrue(a.hasEmit());
        }
    }

    // ─── Watch with complex callbacks ───────────────────────────────

    @Nested
    class WatchCallbacks {

        @Test
        void watchWithBlockBody() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("""
                    const count = ref(0)
                    watch(count, (newVal, oldVal) => {
                      if (newVal > 10) {
                        console.log('big')
                      }
                    })
                    """);
            assertEquals(1, a.watches().size());
            assertEquals("count", a.watches().get(0).source());
            assertTrue(a.watches().get(0).body().contains("console.log"));
        }

        @Test
        void watchEffectWithNestedBlocks() {
            ScriptAnalysis a = VanSignalGen.analyzeScript("""
                    const count = ref(0)
                    watchEffect(() => {
                      if (count.value > 0) {
                        document.title = 'Count: ' + count.value
                      } else {
                        document.title = 'Zero'
                      }
                    })
                    """);
            assertEquals(1, a.watchEffects().size());
            assertTrue(a.watchEffects().get(0).body().contains("document.title"));
        }
    }

    // ─── Reactive object with complex initial value ─────────────────

    @Nested
    class ReactiveObjects {

        @Test
        void reactiveWithNestedObject() {
            ScriptAnalysis a = VanSignalGen.analyzeScript(
                    "const state = reactive({ user: { name: 'Alice', age: 30 }, items: [1, 2] })");
            assertEquals(1, a.reactives().size());
            assertEquals("state", a.reactives().get(0).name());
            assertTrue(a.reactives().get(0).initialValue().contains("Alice"));
            assertTrue(a.reactives().get(0).initialValue().contains("items"));
        }
    }
}
