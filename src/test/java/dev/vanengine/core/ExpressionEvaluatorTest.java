package dev.vanengine.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

    private ExpressionEvaluator eval;

    @BeforeEach
    void setUp() {
        eval = new ExpressionEvaluator();
    }

    // ── Property access ──

    @Nested
    class PropertyAccess {

        @Test
        void simpleKey() {
            var scope = Map.<String, Object>of("name", "Alice");
            assertEquals("Alice", eval.evaluate("name", scope));
        }

        @Test
        void nestedMapPath() {
            var scope = Map.<String, Object>of("user", Map.of("profile", Map.of("name", "Bob")));
            assertEquals("Bob", eval.evaluate("user.profile.name", scope));
        }

        @Test
        void missingPath() {
            var scope = Map.<String, Object>of("user", Map.of("name", "Alice"));
            assertNull(eval.evaluate("user.age", scope));
            assertNull(eval.evaluate("missing", scope));
        }

        @Test
        void collectionLength() {
            var scope = Map.<String, Object>of("items", List.of("a", "b", "c"));
            assertEquals(3, eval.evaluate("items.length", scope));
        }

        @Test
        void collectionIsEmpty() {
            assertEquals(true, eval.evaluate("items.isEmpty", Map.<String, Object>of("items", List.of())));
            assertEquals(false, eval.evaluate("items.isEmpty", Map.<String, Object>of("items", List.of("x"))));
        }

        @Test
        void nestedCollectionLength() {
            var scope = Map.<String, Object>of("ctx", Map.of("packages", List.of("a", "b")));
            assertEquals(2, eval.evaluate("ctx.packages.length", scope));
        }

        @Test
        void recordAccess() {
            record User(String name, int age) {}
            var scope = Map.<String, Object>of("user", new User("Alice", 30));
            assertEquals("Alice", eval.evaluate("user.name", scope));
            assertEquals(30, eval.evaluate("user.age", scope));
        }

        @Test
        void recordWithList() {
            record Ctx(List<Map<String, Object>> packages) {}
            var ctx = new Ctx(List.of(Map.of("name", "foo"), Map.of("name", "bar")));
            var scope = Map.<String, Object>of("ctx", ctx);
            assertEquals(2, eval.evaluate("ctx.packages.length", scope));
        }
    }

    // ── Comparison operators ──

    @Nested
    class Comparison {

        @Test
        void equals() {
            var scope = Map.<String, Object>of("status", "MAVEN", "count", 0);
            assertEquals(true, eval.evaluate("status === 'MAVEN'", scope));
            assertEquals(false, eval.evaluate("status === 'NPM'", scope));
            assertEquals(true, eval.evaluate("count == 0", scope));
        }

        @Test
        void notEquals() {
            var scope = Map.<String, Object>of("status", "MAVEN");
            assertEquals(true, eval.evaluate("status !== 'NPM'", scope));
            assertEquals(false, eval.evaluate("status !== 'MAVEN'", scope));
        }

        @Test
        void greaterLess() {
            var scope = Map.<String, Object>of("role", 4);
            assertEquals(true, eval.evaluate("role >= 3", scope));
            assertEquals(true, eval.evaluate("role > 3", scope));
            assertEquals(false, eval.evaluate("role < 3", scope));
            assertEquals(false, eval.evaluate("role <= 3", scope));
        }

        @Test
        void collectionLengthComparison() {
            var scope = Map.<String, Object>of("packages", List.of("a", "b"));
            assertEquals(true, eval.evaluate("packages.length > 0", scope));
            assertEquals(false, eval.evaluate("packages.length == 0", scope));

            var empty = Map.<String, Object>of("packages", List.of());
            assertEquals(true, eval.evaluate("packages.length == 0", empty));
            assertEquals(false, eval.evaluate("packages.length > 0", empty));
        }
    }

    // ── Logical operators ──

    @Nested
    class Logical {

        @Test
        void and() {
            var scope = Map.<String, Object>of("a", true, "b", true, "c", false);
            assertEquals(true, eval.evaluate("a && b", scope));
            // a && c: a is truthy, return c's value (false)
            assertEquals(false, eval.evaluate("a && c", scope));
        }

        @Test
        void or() {
            var scope = Map.<String, Object>of("a", false, "b", true);
            assertEquals(true, eval.evaluate("a || b", scope));
            assertEquals(false, eval.evaluate("a || a", scope));
        }

        @Test
        void not() {
            var scope = Map.<String, Object>of("visible", true, "empty", false);
            assertEquals(false, eval.evaluate("!visible", scope));
            assertEquals(true, eval.evaluate("!empty", scope));
        }

        @Test
        void combined() {
            var scope = Map.<String, Object>of("packages", List.of("a"), "format", "MAVEN");
            assertEquals(true, eval.evaluate("packages.length > 0 && format === 'MAVEN'", scope));
            assertEquals(false, eval.evaluate("packages.length > 0 && format === 'NPM'", scope));
        }
    }

    // ── Ternary ──

    @Nested
    class Ternary {

        @Test
        void basic() {
            var scope = Map.<String, Object>of("active", true);
            assertEquals("Enabled", eval.evaluate("active ? 'Enabled' : 'Disabled'", scope));
        }

        @Test
        void withExpression() {
            var scope = Map.<String, Object>of("role", 4);
            assertEquals("Admin", eval.evaluate("role >= 4 ? 'Admin' : 'User'", scope));
            var scope2 = Map.<String, Object>of("role", 2);
            assertEquals("User", eval.evaluate("role >= 4 ? 'Admin' : 'User'", scope2));
        }

        @Test
        void classBinding() {
            var scope = Map.<String, Object>of("isActive", true);
            assertEquals("bg-gray-900 text-white",
                    eval.evaluate("isActive ? 'bg-gray-900 text-white' : 'bg-gray-100'", scope));
        }
    }

    // ── Arithmetic ──

    @Nested
    class Arithmetic {

        @Test
        void basic() {
            var scope = Map.<String, Object>of("a", 10, "b", 3);
            assertEquals(13, eval.evaluate("a + b", scope));
            assertEquals(7, eval.evaluate("a - b", scope));
            assertEquals(30, eval.evaluate("a * b", scope));
            assertEquals(1, eval.evaluate("a % b", scope));
        }

        @Test
        void pageCalculation() {
            var scope = Map.<String, Object>of("currentPage", 2);
            assertEquals(3, eval.evaluate("currentPage + 1", scope));
        }

        @Test
        void stringConcatenation() {
            var scope = Map.<String, Object>of("id", 5);
            assertEquals("/ui/registries/5", eval.evaluate("'/ui/registries/' + id", scope));
        }

        @Test
        void stringConcatWithPath() {
            var scope = Map.<String, Object>of("r", Map.of("id", 42));
            assertEquals("/ui/registries/42", eval.evaluate("'/ui/registries/' + r.id", scope));
        }
    }

    // ── Parentheses ──

    @Nested
    class Parentheses {

        @Test
        void grouping() {
            var scope = Map.<String, Object>of("isAdmin", false, "isOwner", true, "format", "MAVEN");
            assertEquals(true, eval.evaluate("(isAdmin || isOwner) && format === 'MAVEN'", scope));
            assertEquals(false, eval.evaluate("isAdmin && (isOwner || format === 'NPM')", scope));
        }

        @Test
        void arithmeticGrouping() {
            var scope = Map.<String, Object>of("a", 2, "b", 3, "c", 4);
            assertEquals(14, eval.evaluate("a * (b + c)", scope));   // 2*(3+4)=14
            assertEquals(18, eval.evaluate("(a + b) * c - a", scope)); // (2+3)*4-2=18
        }
    }

    // ── Literals ──

    @Nested
    class Literals {

        @Test
        void booleans() {
            assertEquals(true, eval.evaluate("true", Map.of()));
            assertEquals(false, eval.evaluate("false", Map.of()));
        }

        @Test
        void nullLiteral() {
            assertNull(eval.evaluate("null", Map.of()));
        }

        @Test
        void numbers() {
            assertEquals(42, eval.evaluate("42", Map.of()));
            assertEquals(0, eval.evaluate("0", Map.of()));
        }

        @Test
        void strings() {
            assertEquals("hello", eval.evaluate("'hello'", Map.of()));
            assertEquals("world", eval.evaluate("\"world\"", Map.of()));
        }
    }

    // ── Truthiness ──

    @Nested
    class Truthiness {

        @Test
        void falsy() {
            assertFalse(ExpressionEvaluator.isTruthy(null));
            assertFalse(ExpressionEvaluator.isTruthy(false));
            assertFalse(ExpressionEvaluator.isTruthy(0));
            assertFalse(ExpressionEvaluator.isTruthy(""));
            assertFalse(ExpressionEvaluator.isTruthy(List.of()));
        }

        @Test
        void truthy() {
            assertTrue(ExpressionEvaluator.isTruthy(true));
            assertTrue(ExpressionEvaluator.isTruthy(1));
            assertTrue(ExpressionEvaluator.isTruthy("hello"));
            assertTrue(ExpressionEvaluator.isTruthy(List.of("a")));
            assertTrue(ExpressionEvaluator.isTruthy(Map.of("k", "v")));
        }
    }

    // ── Operator precedence ──

    @Nested
    class Precedence {

        @Test
        void arithmeticBeforeComparison() {
            // 2 + 3 > 4 → (2+3) > 4 → 5 > 4 → true
            assertEquals(true, eval.evaluate("2 + 3 > 4", Map.of()));
        }

        @Test
        void comparisonBeforeLogical() {
            // true && 3 > 2 → true && (3>2) → true && true → true
            assertEquals(true, eval.evaluate("true && 3 > 2", Map.of()));
        }

        @Test
        void andBeforeOr() {
            // false && true || true → (false && true) || true → false || true → true
            var result = eval.evaluate("false && true || true", Map.of());
            assertTrue(ExpressionEvaluator.isTruthy(result));
        }

        @Test
        void multiplicationBeforeAddition() {
            // 2 + 3 * 4 → 2 + 12 → 14
            assertEquals(14, eval.evaluate("2 + 3 * 4", Map.of()));
        }
    }

    // ── Real-world OmniRepo expressions ──

    @Nested
    class RealWorld {

        @Test
        void packageListCondition() {
            var scope = Map.<String, Object>of("ctx", Map.of("packages", List.of(
                    Map.of("name", "foo", "format", "MAVEN"),
                    Map.of("name", "bar", "format", "NPM")
            )));
            assertEquals(true, eval.evaluate("ctx.packages.length > 0", scope));
        }

        @Test
        void emptyPackageList() {
            var scope = Map.<String, Object>of("ctx", Map.of("packages", List.of()));
            assertEquals(true, eval.evaluate("ctx.packages.length == 0", scope));
        }

        @Test
        void roleCheck() {
            var scope = Map.<String, Object>of("currentUser", Map.of("role", 4));
            assertEquals(true, eval.evaluate("currentUser.role >= 3", scope));
        }

        @Test
        void formatBadgeClass() {
            var scope = Map.<String, Object>of("pkg", Map.of("format", "MAVEN"));
            assertEquals(true, eval.evaluate("pkg.format === 'MAVEN'", scope));
            assertEquals(false, eval.evaluate("pkg.format === 'NPM'", scope));
        }

        @Test
        void dynamicHref() {
            var scope = Map.<String, Object>of("r", Map.of("id", 5));
            assertEquals("/ui/registries/5", eval.evaluate("'/ui/registries/' + r.id", scope));
        }

        @Test
        void selectedOption() {
            var scope = Map.<String, Object>of("ctx", Map.of("format", "MAVEN"));
            assertEquals(true, eval.evaluate("ctx.format === 'MAVEN'", scope));
        }

        @Test
        void combinedCondition() {
            var scope = Map.<String, Object>of(
                    "packages", List.of(Map.of("name", "foo")),
                    "format", "MAVEN"
            );
            assertEquals(true, eval.evaluate("packages.length > 0 && format === 'MAVEN'", scope));
        }

        @Test
        void paginationCheck() {
            var scope = Map.<String, Object>of("ctx", Map.of("totalPages", 5, "currentPage", 2));
            assertEquals(true, eval.evaluate("ctx.totalPages > 1", scope));
            assertEquals(3, eval.evaluate("ctx.currentPage + 1", scope));
        }
    }

    // ── Error tolerance ──

    @Nested
    class ErrorTolerance {

        @Test
        void nullExpression() {
            assertNull(eval.evaluate(null, Map.of()));
            assertNull(eval.evaluate("", Map.of()));
            assertNull(eval.evaluate("   ", Map.of()));
        }

        @Test
        void undefinedVariable() {
            assertNull(eval.evaluate("missing", Map.of()));
            assertFalse(eval.isTruthy("missing", Map.of()));
        }

        @Test
        void undefinedInComparison() {
            // missing === 'MAVEN' → null === 'MAVEN' → false
            assertEquals(false, eval.evaluate("missing === 'MAVEN'", Map.of()));
        }
    }
}
