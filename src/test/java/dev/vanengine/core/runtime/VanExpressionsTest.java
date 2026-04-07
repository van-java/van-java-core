package dev.vanengine.core.runtime;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VanExpressionsTest {

    

    // ── Property access ──

    @Nested
    class PropertyAccess {

        @Test
        void simpleKey() {
            var scope = Map.<String, Object>of("name", "Alice");
            assertEquals("Alice", VanExpressions.evaluate("name", scope));
        }

        @Test
        void nestedMapPath() {
            var scope = Map.<String, Object>of("user", Map.of("profile", Map.of("name", "Bob")));
            assertEquals("Bob", VanExpressions.evaluate("user.profile.name", scope));
        }

        @Test
        void missingPath() {
            var scope = Map.<String, Object>of("user", Map.of("name", "Alice"));
            assertNull(VanExpressions.evaluate("user.age", scope));
            assertNull(VanExpressions.evaluate("missing", scope));
        }

        @Test
        void collectionLength() {
            var scope = Map.<String, Object>of("items", List.of("a", "b", "c"));
            assertEquals(3, VanExpressions.evaluate("items.length", scope));
        }

        @Test
        void collectionIsEmpty() {
            assertEquals(true, VanExpressions.evaluate("items.isEmpty", Map.<String, Object>of("items", List.of())));
            assertEquals(false, VanExpressions.evaluate("items.isEmpty", Map.<String, Object>of("items", List.of("x"))));
        }

        @Test
        void nestedCollectionLength() {
            var scope = Map.<String, Object>of("ctx", Map.of("packages", List.of("a", "b")));
            assertEquals(2, VanExpressions.evaluate("ctx.packages.length", scope));
        }

        @Test
        void recordAccess() {
            record User(String name, int age) {}
            var scope = Map.<String, Object>of("user", new User("Alice", 30));
            assertEquals("Alice", VanExpressions.evaluate("user.name", scope));
            assertEquals(30, VanExpressions.evaluate("user.age", scope));
        }

        @Test
        void recordWithList() {
            record Ctx(List<Map<String, Object>> packages) {}
            var ctx = new Ctx(List.of(Map.of("name", "foo"), Map.of("name", "bar")));
            var scope = Map.<String, Object>of("ctx", ctx);
            assertEquals(2, VanExpressions.evaluate("ctx.packages.length", scope));
        }

        @Test
        void bracketAccessNumericIndex() {
            var scope = Map.<String, Object>of("items", List.of("a", "b", "c"));
            assertEquals("a", VanExpressions.evaluate("items[0]", scope));
            assertEquals("c", VanExpressions.evaluate("items[2]", scope));
        }

        @Test
        void bracketAccessStringKey() {
            var scope = Map.<String, Object>of("data", Map.of("key", "value"));
            assertEquals("value", VanExpressions.evaluate("data['key']", scope));
        }

        @Test
        void bracketAccessChained() {
            var scope = Map.<String, Object>of("users", List.of(
                    Map.of("name", "Alice"), Map.of("name", "Bob")));
            assertEquals("Alice", VanExpressions.evaluate("users[0].name", scope));
            assertEquals("Bob", VanExpressions.evaluate("users[1].name", scope));
        }

        @Test
        void bracketAccessWithVariable() {
            var scope = Map.<String, Object>of("items", List.of("x", "y"), "idx", 1);
            assertEquals("y", VanExpressions.evaluate("items[idx]", scope));
        }

        @Test
        void optionalChainingOnNull() {
            var scope = new java.util.HashMap<String, Object>();
            scope.put("user", null);
            assertNull(VanExpressions.evaluate("user?.name", scope));
            assertNull(VanExpressions.evaluate("user?.profile?.email", scope));
        }

        @Test
        void optionalChainingOnPresent() {
            var scope = Map.<String, Object>of("user", Map.of("name", "Alice"));
            assertEquals("Alice", VanExpressions.evaluate("user?.name", scope));
        }

        @Test
        void optionalChainingMissing() {
            var scope = Map.<String, Object>of();
            assertNull(VanExpressions.evaluate("user?.name", scope));
        }

        @Test
        void nullishCoalescing() {
            var scope = new java.util.HashMap<String, Object>();
            scope.put("name", null);
            scope.put("fallback", "default");
            assertEquals("default", VanExpressions.evaluate("name ?? 'default'", scope));
            assertEquals("default", VanExpressions.evaluate("name ?? fallback", scope));
            assertEquals("hello", VanExpressions.evaluate("'hello' ?? 'default'", scope));
        }

        @Test
        void optionalChainingWithNullishCoalescing() {
            var scope = new java.util.HashMap<String, Object>();
            scope.put("user", null);
            // null ?? 'anon' should work
            assertEquals("anon", VanExpressions.evaluate("null ?? 'anon'", scope));
            // user?.name produces null, then ?? should kick in
            assertEquals("anon", VanExpressions.evaluate("user?.name ?? 'anon'", scope));
        }
    }

    // ── Method calls ──

    @Nested
    class MethodCalls {
        @Test
        void stringMethods() {
            var scope = Map.<String, Object>of("s", "Hello World");
            assertEquals("HELLO WORLD", VanExpressions.evaluate("s.toUpperCase()", scope));
            assertEquals("hello world", VanExpressions.evaluate("s.toLowerCase()", scope));
            assertEquals(true, VanExpressions.evaluate("s.startsWith('Hello')", scope));
            assertEquals(true, VanExpressions.evaluate("s.includes('World')", scope));
            assertEquals(false, VanExpressions.evaluate("s.includes('xyz')", scope));
        }

        @Test
        void listMethods() {
            var scope = Map.<String, Object>of("items", List.of("a", "b", "c"));
            assertEquals(true, VanExpressions.evaluate("items.includes('b')", scope));
            assertEquals(false, VanExpressions.evaluate("items.includes('x')", scope));
            assertEquals("a,b,c", VanExpressions.evaluate("items.join(',')", scope));
        }

        @Test
        void mapMethods() {
            var scope = Map.<String, Object>of("obj", Map.of("x", 1));
            assertEquals(true, VanExpressions.evaluate("obj.has('x')", scope));
            assertEquals(false, VanExpressions.evaluate("obj.has('y')", scope));
        }

        @Test
        void stringSlice() {
            var scope = Map.<String, Object>of("s", "Hello");
            assertEquals("Hel", VanExpressions.evaluate("s.slice(0, 3)", scope));
            assertEquals("lo", VanExpressions.evaluate("s.slice(-2)", scope));
        }
    }

    // ── Method calls (more coverage) ──

    @Nested
    class MethodCallsExtra {
        @Test
        void stringReplace() {
            var scope = Map.<String, Object>of("s", "hello world");
            assertEquals("hello Java", VanExpressions.evaluate("s.replace('world', 'Java')", scope));
        }

        @Test
        void stringSplit() {
            var scope = Map.<String, Object>of("s", "a,b,c");
            Object result = VanExpressions.evaluate("s.split(',')", scope);
            assertEquals(List.of("a", "b", "c"), result);
        }

        @Test
        void stringSliceNegative() {
            var scope = Map.<String, Object>of("s", "Hello");
            assertEquals("lo", VanExpressions.evaluate("s.slice(-2)", scope));
        }

        @Test
        void listSlice() {
            var scope = Map.<String, Object>of("items", List.of(1, 2, 3, 4));
            assertEquals(List.of(2, 3), VanExpressions.evaluate("items.slice(1, 3)", scope));
        }

        @Test
        void mapKeys() {
            var scope = Map.<String, Object>of("obj", Map.of("a", 1, "b", 2));
            Object result = VanExpressions.evaluate("obj.keys()", scope);
            assertNotNull(result);
            assertTrue(result instanceof List);
        }

        @Test
        void nullSafe() {
            var scope = new java.util.HashMap<String, Object>();
            scope.put("x", null);
            assertNull(VanExpressions.evaluate("x.toUpperCase()", scope));
        }

        @Test
        void stringIndexOf() {
            var scope = Map.<String, Object>of("s", "hello");
            assertEquals(2, VanExpressions.evaluate("s.indexOf('l')", scope));
        }

        @Test
        void listIndexOf() {
            var scope = Map.<String, Object>of("items", List.of("a", "b", "c"));
            assertEquals(1, VanExpressions.evaluate("items.indexOf('b')", scope));
        }
    }

    // ── Literals ──

    @Nested
    class ArrayObjectLiterals {
        @Test
        void arrayLiteral() {
            var scope = Map.<String, Object>of();
            Object result = VanExpressions.evaluate("[1, 2, 3]", scope);
            assertEquals(List.of(1, 2, 3), result);
        }

        @Test
        void emptyArray() {
            assertEquals(List.of(), VanExpressions.evaluate("[]", Map.of()));
        }

        @Test
        void objectLiteral() {
            var scope = Map.<String, Object>of();
            Object result = VanExpressions.evaluate("{ 'a': 1, 'b': 2 }", scope);
            assertTrue(result instanceof Map);
            assertEquals(1, ((Map<?,?>) result).get("a"));
            assertEquals(2, ((Map<?,?>) result).get("b"));
        }

        @Test
        void arrayIncludes() {
            var scope = Map.<String, Object>of("x", 2);
            assertEquals(true, VanExpressions.evaluate("[1, 2, 3].includes(x)", scope));
            assertEquals(false, VanExpressions.evaluate("[1, 2, 3].includes(5)", scope));
        }
    }

    // ── Comparison operators ──

    @Nested
    class Comparison {

        @Test
        void equals() {
            var scope = Map.<String, Object>of("status", "MAVEN", "count", 0);
            assertEquals(true, VanExpressions.evaluate("status === 'MAVEN'", scope));
            assertEquals(false, VanExpressions.evaluate("status === 'NPM'", scope));
            assertEquals(true, VanExpressions.evaluate("count == 0", scope));
        }

        @Test
        void notEquals() {
            var scope = Map.<String, Object>of("status", "MAVEN");
            assertEquals(true, VanExpressions.evaluate("status !== 'NPM'", scope));
            assertEquals(false, VanExpressions.evaluate("status !== 'MAVEN'", scope));
        }

        @Test
        void greaterLess() {
            var scope = Map.<String, Object>of("role", 4);
            assertEquals(true, VanExpressions.evaluate("role >= 3", scope));
            assertEquals(true, VanExpressions.evaluate("role > 3", scope));
            assertEquals(false, VanExpressions.evaluate("role < 3", scope));
            assertEquals(false, VanExpressions.evaluate("role <= 3", scope));
        }

        @Test
        void collectionLengthComparison() {
            var scope = Map.<String, Object>of("packages", List.of("a", "b"));
            assertEquals(true, VanExpressions.evaluate("packages.length > 0", scope));
            assertEquals(false, VanExpressions.evaluate("packages.length == 0", scope));

            var empty = Map.<String, Object>of("packages", List.of());
            assertEquals(true, VanExpressions.evaluate("packages.length == 0", empty));
            assertEquals(false, VanExpressions.evaluate("packages.length > 0", empty));
        }
    }

    // ── Logical operators ──

    @Nested
    class Logical {

        @Test
        void and() {
            var scope = Map.<String, Object>of("a", true, "b", true, "c", false);
            assertEquals(true, VanExpressions.evaluate("a && b", scope));
            // a && c: a is truthy, return c's value (false)
            assertEquals(false, VanExpressions.evaluate("a && c", scope));
        }

        @Test
        void or() {
            var scope = Map.<String, Object>of("a", false, "b", true);
            assertEquals(true, VanExpressions.evaluate("a || b", scope));
            assertEquals(false, VanExpressions.evaluate("a || a", scope));
        }

        @Test
        void not() {
            var scope = Map.<String, Object>of("visible", true, "empty", false);
            assertEquals(false, VanExpressions.evaluate("!visible", scope));
            assertEquals(true, VanExpressions.evaluate("!empty", scope));
        }

        @Test
        void combined() {
            var scope = Map.<String, Object>of("packages", List.of("a"), "format", "MAVEN");
            assertEquals(true, VanExpressions.evaluate("packages.length > 0 && format === 'MAVEN'", scope));
            assertEquals(false, VanExpressions.evaluate("packages.length > 0 && format === 'NPM'", scope));
        }
    }

    // ── Ternary ──

    @Nested
    class Ternary {

        @Test
        void basic() {
            var scope = Map.<String, Object>of("active", true);
            assertEquals("Enabled", VanExpressions.evaluate("active ? 'Enabled' : 'Disabled'", scope));
        }

        @Test
        void withExpression() {
            var scope = Map.<String, Object>of("role", 4);
            assertEquals("Admin", VanExpressions.evaluate("role >= 4 ? 'Admin' : 'User'", scope));
            var scope2 = Map.<String, Object>of("role", 2);
            assertEquals("User", VanExpressions.evaluate("role >= 4 ? 'Admin' : 'User'", scope2));
        }

        @Test
        void classBinding() {
            var scope = Map.<String, Object>of("isActive", true);
            assertEquals("bg-gray-900 text-white",
                    VanExpressions.evaluate("isActive ? 'bg-gray-900 text-white' : 'bg-gray-100'", scope));
        }
    }

    // ── Arithmetic ──

    @Nested
    class Arithmetic {

        @Test
        void basic() {
            var scope = Map.<String, Object>of("a", 10, "b", 3);
            assertEquals(13, VanExpressions.evaluate("a + b", scope));
            assertEquals(7, VanExpressions.evaluate("a - b", scope));
            assertEquals(30, VanExpressions.evaluate("a * b", scope));
            assertEquals(1, VanExpressions.evaluate("a % b", scope));
        }

        @Test
        void pageCalculation() {
            var scope = Map.<String, Object>of("currentPage", 2);
            assertEquals(3, VanExpressions.evaluate("currentPage + 1", scope));
        }

        @Test
        void stringConcatenation() {
            var scope = Map.<String, Object>of("id", 5);
            assertEquals("/ui/registries/5", VanExpressions.evaluate("'/ui/registries/' + id", scope));
        }

        @Test
        void stringConcatWithPath() {
            var scope = Map.<String, Object>of("r", Map.of("id", 42));
            assertEquals("/ui/registries/42", VanExpressions.evaluate("'/ui/registries/' + r.id", scope));
        }
    }

    // ── Parentheses ──

    @Nested
    class Parentheses {

        @Test
        void grouping() {
            var scope = Map.<String, Object>of("isAdmin", false, "isOwner", true, "format", "MAVEN");
            assertEquals(true, VanExpressions.evaluate("(isAdmin || isOwner) && format === 'MAVEN'", scope));
            assertEquals(false, VanExpressions.evaluate("isAdmin && (isOwner || format === 'NPM')", scope));
        }

        @Test
        void arithmeticGrouping() {
            var scope = Map.<String, Object>of("a", 2, "b", 3, "c", 4);
            assertEquals(14, VanExpressions.evaluate("a * (b + c)", scope));   // 2*(3+4)=14
            assertEquals(18, VanExpressions.evaluate("(a + b) * c - a", scope)); // (2+3)*4-2=18
        }
    }

    // ── Literals ──

    @Nested
    class Literals {

        @Test
        void booleans() {
            assertEquals(true, VanExpressions.evaluate("true", Map.of()));
            assertEquals(false, VanExpressions.evaluate("false", Map.of()));
        }

        @Test
        void nullLiteral() {
            assertNull(VanExpressions.evaluate("null", Map.of()));
        }

        @Test
        void numbers() {
            assertEquals(42, VanExpressions.evaluate("42", Map.of()));
            assertEquals(0, VanExpressions.evaluate("0", Map.of()));
        }

        @Test
        void strings() {
            assertEquals("hello", VanExpressions.evaluate("'hello'", Map.of()));
            assertEquals("world", VanExpressions.evaluate("\"world\"", Map.of()));
        }
    }

    // ── Truthiness ──

    @Nested
    class Truthiness {

        @Test
        void falsy() {
            assertFalse(VanExpressions.isTruthy(null));
            assertFalse(VanExpressions.isTruthy(false));
            assertFalse(VanExpressions.isTruthy(0));
            assertFalse(VanExpressions.isTruthy(""));
            assertFalse(VanExpressions.isTruthy(List.of()));
        }

        @Test
        void truthy() {
            assertTrue(VanExpressions.isTruthy(true));
            assertTrue(VanExpressions.isTruthy(1));
            assertTrue(VanExpressions.isTruthy("hello"));
            assertTrue(VanExpressions.isTruthy(List.of("a")));
            assertTrue(VanExpressions.isTruthy(Map.of("k", "v")));
        }
    }

    // ── Operator precedence ──

    @Nested
    class Precedence {

        @Test
        void arithmeticBeforeComparison() {
            // 2 + 3 > 4 → (2+3) > 4 → 5 > 4 → true
            assertEquals(true, VanExpressions.evaluate("2 + 3 > 4", Map.of()));
        }

        @Test
        void comparisonBeforeLogical() {
            // true && 3 > 2 → true && (3>2) → true && true → true
            assertEquals(true, VanExpressions.evaluate("true && 3 > 2", Map.of()));
        }

        @Test
        void andBeforeOr() {
            // false && true || true → (false && true) || true → false || true → true
            var result = VanExpressions.evaluate("false && true || true", Map.of());
            assertTrue(VanExpressions.isTruthy(result));
        }

        @Test
        void multiplicationBeforeAddition() {
            // 2 + 3 * 4 → 2 + 12 → 14
            assertEquals(14, VanExpressions.evaluate("2 + 3 * 4", Map.of()));
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
            assertEquals(true, VanExpressions.evaluate("ctx.packages.length > 0", scope));
        }

        @Test
        void emptyPackageList() {
            var scope = Map.<String, Object>of("ctx", Map.of("packages", List.of()));
            assertEquals(true, VanExpressions.evaluate("ctx.packages.length == 0", scope));
        }

        @Test
        void roleCheck() {
            var scope = Map.<String, Object>of("currentUser", Map.of("role", 4));
            assertEquals(true, VanExpressions.evaluate("currentUser.role >= 3", scope));
        }

        @Test
        void formatBadgeClass() {
            var scope = Map.<String, Object>of("pkg", Map.of("format", "MAVEN"));
            assertEquals(true, VanExpressions.evaluate("pkg.format === 'MAVEN'", scope));
            assertEquals(false, VanExpressions.evaluate("pkg.format === 'NPM'", scope));
        }

        @Test
        void dynamicHref() {
            var scope = Map.<String, Object>of("r", Map.of("id", 5));
            assertEquals("/ui/registries/5", VanExpressions.evaluate("'/ui/registries/' + r.id", scope));
        }

        @Test
        void selectedOption() {
            var scope = Map.<String, Object>of("ctx", Map.of("format", "MAVEN"));
            assertEquals(true, VanExpressions.evaluate("ctx.format === 'MAVEN'", scope));
        }

        @Test
        void combinedCondition() {
            var scope = Map.<String, Object>of(
                    "packages", List.of(Map.of("name", "foo")),
                    "format", "MAVEN"
            );
            assertEquals(true, VanExpressions.evaluate("packages.length > 0 && format === 'MAVEN'", scope));
        }

        @Test
        void paginationCheck() {
            var scope = Map.<String, Object>of("ctx", Map.of("totalPages", 5, "currentPage", 2));
            assertEquals(true, VanExpressions.evaluate("ctx.totalPages > 1", scope));
            assertEquals(3, VanExpressions.evaluate("ctx.currentPage + 1", scope));
        }
    }

    // ── Error tolerance ──

    @Nested
    class ErrorTolerance {

        @Test
        void nullExpression() {
            assertNull(VanExpressions.evaluate(null, Map.of()));
            assertNull(VanExpressions.evaluate("", Map.of()));
            assertNull(VanExpressions.evaluate("   ", Map.of()));
        }

        @Test
        void undefinedVariable() {
            assertNull(VanExpressions.evaluate("missing", Map.of()));
            assertFalse(VanExpressions.isTruthy("missing", Map.of()));
        }

        @Test
        void undefinedInComparison() {
            // missing === 'MAVEN' → null === 'MAVEN' → false
            assertEquals(false, VanExpressions.evaluate("missing === 'MAVEN'", Map.of()));
        }
    }
}
