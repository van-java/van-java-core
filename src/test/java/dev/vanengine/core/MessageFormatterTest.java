package dev.vanengine.core;

import dev.vanengine.core.i18n.MessageFormatter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageFormatterTest {

    @Test
    void simplePlaceholder() {
        assertEquals("Hello Alice", MessageFormatter.format("Hello {name}", Map.of("name", "Alice")));
    }

    @Test
    void multiplePlaceholders() {
        assertEquals("A is B", MessageFormatter.format("{a} is {b}", Map.of("a", "A", "b", "B")));
    }

    @Test
    void unmatchedPlaceholder() {
        assertEquals("Hello {unknown}", MessageFormatter.format("Hello {unknown}", Map.of()));
    }

    @Test
    void nullMessage() {
        assertNull(MessageFormatter.format(null, Map.of("a", "b")));
    }

    @Test
    void nullParams() {
        assertEquals("Hello", MessageFormatter.format("Hello", null));
    }

    @Test
    void pluralTwoForms() {
        assertEquals("item", MessageFormatter.resolvePlural("item | items", 1));
        assertEquals("items", MessageFormatter.resolvePlural("item | items", 2));
        assertEquals("item", MessageFormatter.resolvePlural("item | items", 0));
    }

    @Test
    void pluralThreeForms() {
        assertEquals("no items", MessageFormatter.resolvePlural("no items | one item | many items", 0));
        assertEquals("one item", MessageFormatter.resolvePlural("no items | one item | many items", 1));
        assertEquals("many items", MessageFormatter.resolvePlural("no items | one item | many items", 5));
    }

    @Test
    void pluralNegativeCount() {
        assertEquals("item", MessageFormatter.resolvePlural("item | items", -1));
    }

    @Test
    void formatWithCount() {
        assertEquals("2 items", MessageFormatter.format("{count} item | {count} items", Map.of("count", 2)));
    }

    @Test
    void formatSafe() {
        assertEquals("Hello &lt;b&gt;", MessageFormatter.formatSafe("Hello {x}", Map.of("x", "<b>")));
    }
}
