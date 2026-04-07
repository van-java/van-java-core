package dev.vanengine.core;

import dev.vanengine.core.compile.VanSignalGen;
import dev.vanengine.core.compile.VanSignalGen.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Vue 3 client-side features: event modifiers, v-model modifiers,
 * watchEffect, lifecycle hooks, defineEmits, reactive, dynamic arguments,
 * defineModel, TransitionGroup, Teleport.
 */
class ClientFeaturesTest {

    // ─── Event modifiers ────────────────────────────────────────────

    @Nested
    class EventModifiers {

        @Test
        void parsesModifiers() {
            String html = "<div><button @click.prevent.stop=\"handler\">Go</button></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"count"});
            assertEquals(1, b.events().size());
            assertEquals("click", b.events().get(0).event());
            assertEquals("handler", b.events().get(0).handler());
            assertEquals(List.of("prevent", "stop"), b.events().get(0).modifiers());
        }

        @Test
        void generatesPreventStop() {
            String script = "const count = ref(0)\nfunction handler() { count.value++ }";
            String html = "<div><button @click.prevent.stop=\"handler\">Go</button></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("e.preventDefault()"), "Should have preventDefault");
            assertTrue(js.contains("e.stopPropagation()"), "Should have stopPropagation");
        }

        @Test
        void generatesOnceOption() {
            String script = "const count = ref(0)\nfunction handler() { count.value++ }";
            String html = "<div><button @click.once=\"handler\">Go</button></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("once: true"), "Should have once option");
        }

        @Test
        void generatesCaptureOption() {
            String script = "const count = ref(0)\nfunction handler() { count.value++ }";
            String html = "<div><div @click.capture=\"handler\"><span>inner</span></div></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("capture: true"), "Should have capture option");
        }

        @Test
        void generatesSelfGuard() {
            String script = "const count = ref(0)\nfunction handler() { count.value++ }";
            String html = "<div><div @click.self=\"handler\">text</div></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("e.target !== e.currentTarget"), "Should have self guard");
        }

        @Test
        void noModifiers() {
            String html = "<div><button @click=\"handler\">Go</button></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"count"});
            assertTrue(b.events().get(0).modifiers().isEmpty(), "No modifiers");
        }
    }

    // ─── v-model modifiers ──────────────────────────────────────────

    @Nested
    class VModelModifiers {

        @Test
        void parsesModifiers() {
            String html = "<div><input v-model.lazy.trim=\"name\" /></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"name"});
            assertEquals(1, b.models().size());
            assertEquals("name", b.models().get(0).signalName());
            assertEquals(List.of("lazy", "trim"), b.models().get(0).modifiers());
        }

        @Test
        void lazyUsesChangeEvent() {
            String script = "const name = ref('')";
            String html = "<div><input v-model.lazy=\"name\" /></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("'change'"), "lazy → change event");
            assertFalse(js.contains("'input'"), "lazy → no input event");
        }

        @Test
        void numberConverts() {
            String script = "const age = ref(0)";
            String html = "<div><input v-model.number=\"age\" /></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("Number(_v)"), "number modifier converts");
        }

        @Test
        void trimStrips() {
            String script = "const name = ref('')";
            String html = "<div><input v-model.trim=\"name\" /></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains(".trim()"), "trim modifier strips whitespace");
        }
    }

    // ─── watchEffect ────────────────────────────────────────────────

    @Nested
    class WatchEffect {

        @Test
        void parsesWatchEffect() {
            String script = """
                    const count = ref(0)
                    watchEffect(() => { console.log(count.value) })
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.watchEffects().size());
            assertTrue(a.watchEffects().get(0).body().contains("console.log"));
        }

        @Test
        void generatesWatchEffect() {
            String script = "const count = ref(0)\nwatchEffect(() => { document.title = count.value })";
            String html = "<div><p>{{ count }}</p></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("V.watchEffect("), "Should emit V.watchEffect");
        }
    }

    // ─── Lifecycle hooks ────────────────────────────────────────────

    @Nested
    class LifecycleHooks {

        @Test
        void parsesOnMounted() {
            String script = """
                    const count = ref(0)
                    onMounted(() => { console.log('mounted') })
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.lifecycles().size());
            assertEquals("mounted", a.lifecycles().get(0).hook());
            assertTrue(a.lifecycles().get(0).body().contains("console.log"));
        }

        @Test
        void parsesOnUnmounted() {
            String script = """
                    const count = ref(0)
                    onUnmounted(() => { cleanup() })
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.lifecycles().size());
            assertEquals("unmounted", a.lifecycles().get(0).hook());
        }

        @Test
        void generatesLifecycleCallbacks() {
            String script = "const count = ref(0)\nonMounted(() => { init() })\nonUnmounted(() => { cleanup() })";
            String html = "<div><p>{{ count }}</p></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("V.onMounted("), "Should emit onMounted");
            assertTrue(js.contains("V.onUnmounted("), "Should emit onUnmounted");
        }
    }

    // ─── defineEmits + emit() ───────────────────────────────────────

    @Nested
    class DefineEmits {

        @Test
        void detectsDefineEmits() {
            String script = """
                    const emit = defineEmits(['update', 'delete'])
                    const count = ref(0)
                    """;
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertTrue(a.hasEmit());
        }

        @Test
        void generatesEmitFunction() {
            String script = "const emit = defineEmits(['update'])\nconst count = ref(0)";
            String html = "<div><p>{{ count }}</p></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("function emit("), "Should generate emit function");
            assertTrue(js.contains("V.emit("), "Should use V.emit");
        }
    }

    // ─── reactive() ─────────────────────────────────────────────────

    @Nested
    class Reactive {

        @Test
        void parsesReactive() {
            String script = "const state = reactive({ count: 0, name: 'hello' })";
            ScriptAnalysis a = VanSignalGen.analyzeScript(script);
            assertEquals(1, a.reactives().size());
            assertEquals("state", a.reactives().get(0).name());
            assertTrue(a.reactives().get(0).initialValue().contains("count"));
        }

        @Test
        void generatesReactive() {
            String script = "const state = reactive({ count: 0 })";
            String html = "<div><p>{{ state.count }}</p></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("V.reactive("), "Should emit V.reactive");
        }
    }

    // ─── Dynamic arguments ──────────────────────────────────────────

    @Nested
    class DynamicArguments {

        @Test
        void parsesDynamicAttr() {
            String html = "<div><a :[attrName]=\"value\">link</a></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"attrName", "value"});
            assertEquals(1, b.dynamicAttrs().size());
            assertEquals("attrName", b.dynamicAttrs().get(0).attrExpr());
            assertEquals("value", b.dynamicAttrs().get(0).valueExpr());
        }

        @Test
        void parsesDynamicEvent() {
            String html = "<div><button @[eventName]=\"handler\">Go</button></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"eventName"});
            assertEquals(1, b.dynamicEvents().size());
            assertEquals("eventName", b.dynamicEvents().get(0).eventExpr());
            assertEquals("handler", b.dynamicEvents().get(0).handler());
        }

        @Test
        void generatesSetAttribute() {
            String script = "const attrName = ref('href')\nconst value = ref('https://example.com')";
            String html = "<div><a :[attrName]=\"value\">link</a></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("setAttribute("), "Should use setAttribute for dynamic attr");
        }
    }

    // ─── Teleport ───────────────────────────────────────────────────

    @Nested
    class TeleportTest {

        @Test
        void parsesTeleport() {
            String html = "<div><Teleport to=\"#modal-container\"><p>Modal</p></Teleport></div>";
            TemplateBindings b = VanSignalGen.walkTemplate(html, new String[]{"count"});
            assertEquals(1, b.teleports().size());
            assertEquals("#modal-container", b.teleports().get(0).target());
        }

        @Test
        void generatesTeleport() {
            String script = "const count = ref(0)";
            String html = "<div><p>{{ count }}</p><Teleport to=\"#modal\"><p>content</p></Teleport></div>";
            String js = VanSignalGen.generateSignals(script, html, List.of(), "Van");
            assertNotNull(js);
            assertTrue(js.contains("V.teleport("), "Should emit V.teleport");
            assertTrue(js.contains("#modal"), "Should have target selector");
        }
    }

    // ─── Runtime JS ─────────────────────────────────────────────────

    @Nested
    class RuntimeFeatures {

        @Test
        void runtimeHasWatchEffect() {
            assertTrue(VanSignalGen.runtimeJs().contains("watchEffect"));
        }

        @Test
        void runtimeHasReactive() {
            assertTrue(VanSignalGen.runtimeJs().contains("reactive"));
            assertTrue(VanSignalGen.runtimeJs().contains("Proxy"));
        }

        @Test
        void runtimeHasLifecycleHooks() {
            assertTrue(VanSignalGen.runtimeJs().contains("onMounted"));
            assertTrue(VanSignalGen.runtimeJs().contains("onUnmounted"));
        }

        @Test
        void runtimeHasEmit() {
            assertTrue(VanSignalGen.runtimeJs().contains("CustomEvent"));
        }

        @Test
        void runtimeHasTeleport() {
            assertTrue(VanSignalGen.runtimeJs().contains("teleport"));
            assertTrue(VanSignalGen.runtimeJs().contains("querySelector"));
        }

        @Test
        void runtimeHasTransitionGroup() {
            assertTrue(VanSignalGen.runtimeJs().contains("transitionGroup"));
        }
    }

    // ─── End-to-end: compile with new features ──────────────────────

    @Nested
    class EndToEnd {

        @Test
        void fullFeatureComponent() {
            Map<String, String> files = Map.of("main.van", """
                    <script setup>
                    const emit = defineEmits(['save'])
                    const count = ref(0)
                    const state = reactive({ name: 'World' })
                    const doubled = computed(() => count * 2)
                    function increment() { count.value++ }
                    watchEffect(() => { document.title = 'Count: ' + count.value })
                    onMounted(() => { console.log('mounted') })
                    onUnmounted(() => { console.log('unmounted') })
                    </script>
                    <template>
                      <div>
                        <p>{{ count }}</p>
                        <button @click.prevent="increment">+1</button>
                        <input v-model.lazy.trim="state.name" />
                      </div>
                    </template>
                    """);

            VanCompiler compiler = new VanCompiler();
            String html = compiler.compile("main.van", files).html();

            assertTrue(html.contains("V.signal(0)"), "ref generated");
            assertTrue(html.contains("V.reactive("), "reactive generated");
            assertTrue(html.contains("V.computed("), "computed generated");
            assertTrue(html.contains("V.watchEffect("), "watchEffect generated");
            assertTrue(html.contains("V.onMounted("), "onMounted generated");
            assertTrue(html.contains("V.onUnmounted("), "onUnmounted generated");
            assertTrue(html.contains("function emit("), "emit generated");
            assertTrue(html.contains("preventDefault"), "event modifier");
            assertTrue(html.contains("'change'"), "v-model.lazy → change");
            assertTrue(html.contains(".trim()"), "v-model.trim");
        }
    }
}
