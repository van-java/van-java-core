package dev.vanengine.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VanTemplateTest {

    private VanEngine engine() {
        return VanEngine.builder().build();
    }

    @Test
    void evaluateInterpolation() {
        VanTemplate tmpl = engine().getLiteralTemplate("<template><p>{{ name }}</p></template>");
        assertEquals(true, tmpl.evaluate(Map.of("name", "Alice")).contains("Alice"));
    }

    @Test
    void evaluateVFor() {
        VanTemplate tmpl = engine().getLiteralTemplate(
                "<template><li v-for=\"x in items\">{{ x }}</li></template>");
        String html = tmpl.evaluate(Map.of("items", List.of("a", "b")));
        assertTrue(html.contains("a"));
        assertTrue(html.contains("b"));
    }

    @Test
    void evaluateVIf() {
        VanTemplate tmpl = engine().getLiteralTemplate(
                "<template><p v-if=\"show\">yes</p></template>");
        assertTrue(tmpl.evaluate(Map.of("show", true)).contains("yes"));
        assertFalse(tmpl.evaluate(Map.of("show", false)).contains("yes"));
    }

    @Test
    void evaluateWithI18n() {
        VanEngine eng = engine();
        eng.addI18nMessages("en", Map.of("hello", "Hello!"));
        VanTemplate tmpl = eng.getLiteralTemplate("<template><p>{{ $t('hello') }}</p></template>");
        String html = tmpl.evaluate(Map.of(), "en");
        assertTrue(html.contains("Hello!"));
    }

    @Test
    void templateIsReusable() {
        VanTemplate tmpl = engine().getLiteralTemplate("<template><span>{{ x }}</span></template>");
        assertTrue(tmpl.evaluate(Map.of("x", "1")).contains("1"));
        assertTrue(tmpl.evaluate(Map.of("x", "2")).contains("2"));
    }

    @Test
    void getHtml() {
        VanTemplate tmpl = engine().getLiteralTemplate("<template><p>{{ msg }}</p></template>");
        assertNotNull(tmpl.getHtml());
        // Compiled HTML wraps in shell; the {{ msg }} placeholder is preserved for evaluation
        assertTrue(tmpl.getHtml().contains("<p>"));
    }

    @Test
    void classBinding() {
        VanTemplate tmpl = engine().getLiteralTemplate(
                "<template><div :class=\"{ active: isActive }\">x</div></template>");
        assertTrue(tmpl.evaluate(Map.of("isActive", true)).contains("active"));
        assertFalse(tmpl.evaluate(Map.of("isActive", false)).contains("active"));
    }

    @Test
    void styleBinding() {
        VanTemplate tmpl = engine().getLiteralTemplate(
                "<template><div :style=\"{ color: c }\">x</div></template>");
        assertTrue(tmpl.evaluate(Map.of("c", "red")).contains("color: red"));
    }
}
