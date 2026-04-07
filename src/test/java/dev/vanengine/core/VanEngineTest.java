package dev.vanengine.core;

import dev.vanengine.core.i18n.MissingKeyStrategy;
import dev.vanengine.core.i18n.MissingTranslationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VanEngineTest {

    private static VanEngine buildEngine() {
        return VanEngine.builder().defaultLocale("en").build();
    }

    @Nested
    class Builder {
        @Test
        void defaultsWork() {
            VanEngine engine = VanEngine.builder().build();
            assertEquals("en", engine.getDefaultLocale());
            assertEquals(MissingKeyStrategy.RETURN_KEY, engine.getMissingKeyStrategy());
            assertFalse(engine.hasI18nMessages());
        }

        @Test
        void customSettings() {
            VanEngine engine = VanEngine.builder()
                    .defaultLocale("zh")
                    .missingKeyStrategy(MissingKeyStrategy.RETURN_PLACEHOLDER)
                    .build();
            assertEquals("zh", engine.getDefaultLocale());
            assertEquals(MissingKeyStrategy.RETURN_PLACEHOLDER, engine.getMissingKeyStrategy());
        }
    }

    @Nested
    class TemplateLiteral {
        @Test
        void compileAndEvaluate() {
            VanEngine engine = buildEngine();
            String html = engine.compileLiteral(
                    "<template><h1>{{ title }}</h1></template>",
                    Map.of("title", "Hello"));
            assertTrue(html.contains("Hello"));
        }

        @Test
        void getLiteralTemplate() {
            VanEngine engine = buildEngine();
            VanTemplate tmpl = engine.getLiteralTemplate(
                    "<template><p>{{ msg }}</p></template>");
            String html = tmpl.evaluate(Map.of("msg", "World"));
            assertTrue(html.contains("World"));
        }

        @Test
        void templateReuse() {
            VanEngine engine = buildEngine();
            VanTemplate tmpl = engine.getLiteralTemplate(
                    "<template><span>{{ x }}</span></template>");
            assertEquals(true, tmpl.evaluate(Map.of("x", "A")).contains("A"));
            assertEquals(true, tmpl.evaluate(Map.of("x", "B")).contains("B"));
        }
    }

    @Nested
    class I18n {
        @Test
        void basicTranslation() {
            VanEngine engine = buildEngine();
            engine.addI18nMessages("en", Map.of("greeting", "Hello"));
            assertEquals("Hello", engine.getMessage("greeting", "en"));
        }

        @Test
        void nestedKeys() {
            VanEngine engine = buildEngine();
            engine.addI18nMessages("en", Map.of("nav", Map.of("home", "Home")));
            assertEquals("Home", engine.getMessage("nav.home", "en"));
        }

        @Test
        void missingKeyReturnKey() {
            VanEngine engine = buildEngine();
            engine.addI18nMessages("en", Map.of());
            assertEquals("missing.key", engine.getMessage("missing.key", "en"));
        }

        @Test
        void missingKeyThrows() {
            VanEngine engine = VanEngine.builder()
                    .missingKeyStrategy(MissingKeyStrategy.THROW_EXCEPTION).build();
            engine.addI18nMessages("en", Map.of());
            assertThrows(MissingTranslationException.class,
                    () -> engine.getMessage("missing", "en"));
        }

        @Test
        void missingKeyPlaceholder() {
            VanEngine engine = VanEngine.builder()
                    .missingKeyStrategy(MissingKeyStrategy.RETURN_PLACEHOLDER).build();
            engine.addI18nMessages("en", Map.of());
            assertEquals("[missing: no.key]", engine.getMessage("no.key", "en"));
        }

        @Test
        void localeFallback() {
            VanEngine engine = buildEngine();
            engine.addI18nMessages("en", Map.of("greeting", "Hello", "farewell", "Bye"));
            engine.addI18nMessages("zh", Map.of("greeting", "你好"));
            // zh has greeting, falls back to en for farewell
            Map<String, Object> msgs = engine.getI18nMessages("zh");
            assertEquals("你好", msgs.get("greeting"));
            assertEquals("Bye", msgs.get("farewell"));
        }

        @Test
        void templateWithI18n() {
            VanEngine engine = buildEngine();
            engine.addI18nMessages("en", Map.of("hello", "Hello World"));
            VanTemplate tmpl = engine.getLiteralTemplate(
                    "<template><p>{{ $t('hello') }}</p></template>");
            String html = tmpl.evaluate(Map.of(), "en");
            assertTrue(html.contains("Hello World"));
        }

        @Test
        void messageWithParams() {
            VanEngine engine = buildEngine();
            engine.addI18nMessages("en", Map.of("greet", "Hello {name}!"));
            assertEquals("Hello Alice!", engine.getMessage("greet", "en", Map.of("name", "Alice")));
        }

        @Test
        void findMissingKeysAcrossLocales() {
            VanEngine engine = buildEngine();
            engine.addI18nMessages("en", Map.of("a", "A", "b", "B"));
            engine.addI18nMessages("zh", Map.of("a", "甲"));
            var missing = engine.findMissingKeys();
            assertTrue(missing.containsKey("zh"));
            assertTrue(missing.get("zh").contains("b"));
        }
    }
}
