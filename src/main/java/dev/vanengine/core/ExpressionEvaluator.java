package dev.vanengine.core;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;

/**
 * Evaluates JavaScript-like expressions against a Java data model.
 * Supports property access (Map/record/POJO/Collection.length), comparison,
 * logical, ternary, arithmetic operators, and parentheses.
 *
 * <p>Thread-safe and stateless — create one instance and reuse.
 */
public class ExpressionEvaluator {

    /**
     * Evaluate an expression string against the given scope.
     *
     * @param expr  the expression (e.g. {@code ctx.packages.length > 0 && format === 'MAVEN'})
     * @param scope the data scope (model map, may include loop variables)
     * @return the result (Boolean, Number, String, null, or Object)
     */
    public Object evaluate(String expr, Map<String, ?> scope) {
        if (expr == null || expr.isBlank()) return null;
        var parser = new Parser(expr.trim(), scope);
        Object result = parser.parseTernary();
        if (parser.pos < parser.input.length()) {
            // Unexpected trailing characters — return what we got
            return result;
        }
        return result;
    }

    /**
     * Evaluate an expression and return its truthiness (matching JavaScript semantics).
     */
    public boolean isTruthy(String expr, Map<String, ?> scope) {
        return isTruthy(evaluate(expr, scope));
    }

    /**
     * Check truthiness of a value (JavaScript semantics).
     * Falsy: null, false, 0, "", empty collection.
     */
    public static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) return !s.isEmpty();
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value.getClass().isArray()) return Array.getLength(value) > 0;
        return true;
    }

    /**
     * Resolve a dot-separated property path against a scope.
     * Supports Map, record, POJO (getter), Collection.length/isEmpty, Array.length.
     */
    public static Object resolvePath(String path, Map<String, ?> scope) {
        String[] parts = path.split("\\.");
        Object current = scope.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            current = resolveProperty(current, parts[i]);
        }
        return current;
    }

    private static Object resolveProperty(Object target, String property) {
        if (target == null) return null;

        // Collection special properties
        if (target instanceof Collection<?> c) {
            if ("length".equals(property)) return c.size();
            if ("isEmpty".equals(property)) return c.isEmpty();
        }

        // Array special properties
        if (target.getClass().isArray()) {
            if ("length".equals(property)) return Array.getLength(target);
        }

        // Map access
        if (target instanceof Map<?, ?> map) {
            return map.get(property);
        }

        // Record component access
        if (target.getClass().isRecord()) {
            try {
                for (RecordComponent rc : target.getClass().getRecordComponents()) {
                    if (rc.getName().equals(property)) {
                        return rc.getAccessor().invoke(target);
                    }
                }
            } catch (Exception e) {
                return null;
            }
        }

        // POJO getter access: try getProperty() then property()
        try {
            String capitalized = Character.toUpperCase(property.charAt(0)) + property.substring(1);
            try {
                var getter = target.getClass().getMethod("get" + capitalized);
                return getter.invoke(target);
            } catch (NoSuchMethodException e) {
                // try isProperty() for booleans
                try {
                    var isGetter = target.getClass().getMethod("is" + capitalized);
                    return isGetter.invoke(target);
                } catch (NoSuchMethodException e2) {
                    // try property() directly (record-style)
                    var accessor = target.getClass().getMethod(property);
                    return accessor.invoke(target);
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── Recursive descent parser ──

    private class Parser {
        final String input;
        final Map<String, ?> scope;
        int pos;

        Parser(String input, Map<String, ?> scope) {
            this.input = input;
            this.scope = scope;
            this.pos = 0;
        }

        // ── Ternary: or ('?' ternary ':' ternary)?
        Object parseTernary() {
            Object left = parseOr();
            skipWhitespace();
            if (match('?')) {
                Object trueVal = parseTernary();
                skipWhitespace();
                expect(':');
                Object falseVal = parseTernary();
                return isTruthy(left) ? trueVal : falseVal;
            }
            return left;
        }

        // ── Or: and ('||' and)*
        Object parseOr() {
            Object left = parseAnd();
            skipWhitespace();
            while (matchStr("||")) {
                Object right = parseAnd();
                left = isTruthy(left) ? left : right;
                skipWhitespace();
            }
            return left;
        }

        // ── And: equality ('&&' equality)*
        Object parseAnd() {
            Object left = parseEquality();
            skipWhitespace();
            while (matchStr("&&")) {
                Object right = parseEquality();
                left = isTruthy(left) ? right : left;
                skipWhitespace();
            }
            return left;
        }

        // ── Equality: comparison (('=='|'==='|'!='|'!==') comparison)*
        Object parseEquality() {
            Object left = parseComparison();
            skipWhitespace();
            while (true) {
                String op;
                if (matchStr("===")) op = "===";
                else if (matchStr("!==")) op = "!==";
                else if (matchStr("==")) op = "==";
                else if (matchStr("!=")) op = "!=";
                else break;

                Object right = parseComparison();
                left = evalEquality(left, right, op);
                skipWhitespace();
            }
            return left;
        }

        // ── Comparison: addition (('>'|'>='|'<'|'<=') addition)*
        Object parseComparison() {
            Object left = parseAddition();
            skipWhitespace();
            while (true) {
                String op;
                if (matchStr(">=")) op = ">=";
                else if (matchStr("<=")) op = "<=";
                else if (match('>')) op = ">";
                else if (match('<')) op = "<";
                else break;

                Object right = parseAddition();
                left = evalComparison(left, right, op);
                skipWhitespace();
            }
            return left;
        }

        // ── Addition: multiplication (('+'|'-') multiplication)*
        Object parseAddition() {
            Object left = parseMultiplication();
            skipWhitespace();
            while (true) {
                if (match('+')) {
                    Object right = parseMultiplication();
                    left = evalAdd(left, right);
                } else if (match('-')) {
                    Object right = parseMultiplication();
                    left = evalArithmetic(left, right, '-');
                } else {
                    break;
                }
                skipWhitespace();
            }
            return left;
        }

        // ── Multiplication: unary (('*'|'/'|'%') unary)*
        Object parseMultiplication() {
            Object left = parseUnary();
            skipWhitespace();
            while (true) {
                char op;
                if (match('*')) op = '*';
                else if (match('/')) op = '/';
                else if (match('%')) op = '%';
                else break;

                Object right = parseUnary();
                left = evalArithmetic(left, right, op);
                skipWhitespace();
            }
            return left;
        }

        // ── Unary: ('!' unary) | primary
        Object parseUnary() {
            skipWhitespace();
            if (match('!')) {
                Object operand = parseUnary();
                return !isTruthy(operand);
            }
            return parsePrimary();
        }

        // ── Primary: literal | path | '(' expression ')'
        Object parsePrimary() {
            skipWhitespace();
            if (pos >= input.length()) return null;

            char ch = input.charAt(pos);

            // Parenthesized expression
            if (ch == '(') {
                pos++;
                Object result = parseTernary();
                skipWhitespace();
                expect(')');
                return result;
            }

            // String literal
            if (ch == '\'' || ch == '"') {
                return parseString(ch);
            }

            // Numeric literal (including negative)
            if (Character.isDigit(ch) || (ch == '-' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1)))) {
                return parseNumber();
            }

            // Keywords and identifiers
            String word = readIdentifier();
            if (word.isEmpty()) return null;

            return switch (word) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> resolvePath(word, scope);
            };
        }

        // ── Token reading helpers ──

        String readIdentifier() {
            int start = pos;
            // Read dotted path: a.b.c
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                    pos++;
                } else if (ch == '.' && pos + 1 < input.length() && Character.isLetterOrDigit(input.charAt(pos + 1))) {
                    pos++; // consume dot, continue reading
                } else {
                    break;
                }
            }
            return input.substring(start, pos);
        }

        String parseString(char quote) {
            pos++; // skip opening quote
            var sb = new StringBuilder();
            while (pos < input.length()) {
                char ch = input.charAt(pos++);
                if (ch == quote) return sb.toString();
                if (ch == '\\' && pos < input.length()) {
                    sb.append(input.charAt(pos++));
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString(); // unterminated string
        }

        Object parseNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            boolean isFloat = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            String num = input.substring(start, pos);
            if (isFloat) return Double.parseDouble(num);
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException e) {
                return Long.parseLong(num);
            }
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        boolean match(char expected) {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        boolean matchStr(String expected) {
            skipWhitespace();
            if (input.startsWith(expected, pos)) {
                // For operators like '==' vs '===', make sure we match the longest
                pos += expected.length();
                return true;
            }
            return false;
        }

        void expect(char expected) {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == expected) {
                pos++;
            }
            // Silently skip if not found (error tolerance)
        }
    }

    // ── Evaluation helpers ──

    private static Object evalEquality(Object left, Object right, String op) {
        boolean equal = objectsEqual(left, right);
        return switch (op) {
            case "==", "===" -> equal;
            case "!=", "!==" -> !equal;
            default -> false;
        };
    }

    private static boolean objectsEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        // Numeric comparison: convert both to double
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.toString().equals(b.toString());
    }

    private static Object evalComparison(Object left, Object right, String op) {
        double l = toNumber(left);
        double r = toNumber(right);
        return switch (op) {
            case ">" -> l > r;
            case ">=" -> l >= r;
            case "<" -> l < r;
            case "<=" -> l <= r;
            default -> false;
        };
    }

    private static Object evalAdd(Object left, Object right) {
        // String concatenation if either operand is a string
        if (left instanceof String || right instanceof String) {
            String l = left != null ? left.toString() : "null";
            String r = right != null ? right.toString() : "null";
            return l + r;
        }
        double result = toNumber(left) + toNumber(right);
        if (result == (int) result) return (int) result;
        return result;
    }

    private static Object evalArithmetic(Object left, Object right, char op) {
        double l = toNumber(left);
        double r = toNumber(right);
        double result = switch (op) {
            case '-' -> l - r;
            case '*' -> l * r;
            case '/' -> r != 0 ? l / r : 0;
            case '%' -> r != 0 ? l % r : 0;
            default -> 0;
        };
        // Return int if result is whole number
        if (result == (int) result) return (int) result;
        return result;
    }

    private static double toNumber(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
