package dev.vanengine.core.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * Evaluates JavaScript-like expressions against a Java data model.
 *
 * <p>Two-phase design: expressions are <b>compiled once</b> into an {@link Expr} tree,
 * then <b>evaluated many times</b> against different scopes. Compiled expressions are
 * cached globally — the same expression string is never re-parsed.
 *
 * <p>Thread-safe and stateless.
 */
public class VanExpressions {

    private VanExpressions() {}

    // ═══════════════════════════════════════════════════════════════
    // Compiled expression cache
    // ═══════════════════════════════════════════════════════════════

    /** A compiled expression that can be evaluated against any scope. */
    @FunctionalInterface
    public interface Expr {
        Object eval(Map<String, ?> scope);
    }

    private static final int MAX_CACHE_SIZE = 4096;
    @SuppressWarnings("serial")
    private static final Map<String, Expr> EXPR_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Expr> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });
    private static final Expr NULL_EXPR = scope -> null;

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    public static Object evaluate(String expr, Map<String, ?> scope) {
        if (expr == null || expr.isBlank()) return null;
        String trimmed = expr.trim();
        Expr compiled;
        synchronized (EXPR_CACHE) {
            compiled = EXPR_CACHE.computeIfAbsent(trimmed, VanExpressions::compile);
        }
        return compiled.eval(scope);
    }

    public static boolean isTruthy(String expr, Map<String, ?> scope) {
        return isTruthy(evaluate(expr, scope));
    }

    public static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) return !s.isEmpty();
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value.getClass().isArray()) return Array.getLength(value) > 0;
        return true;
    }

    public static Object resolvePath(String path, Map<String, ?> scope) {
        String[] parts = path.split("\\.");
        Object current = scope.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            current = resolveProperty(current, parts[i]);
        }
        return current;
    }

    /** Compile an expression string into a reusable Expr (for direct use). */
    public static Expr compile(String expr) {
        if (expr == null || expr.isBlank()) return NULL_EXPR;
        return new ExprCompiler(expr.trim()).compileTernary();
    }

    // ═══════════════════════════════════════════════════════════════
    // Property resolution (shared by Expr.eval and resolvePath)
    // ═══════════════════════════════════════════════════════════════

    static Object resolveProperty(Object target, String property) {
        if (target == null) return null;
        if (target instanceof Collection<?> c) {
            if ("length".equals(property)) return c.size();
            if ("isEmpty".equals(property)) return c.isEmpty();
        }
        if (target.getClass().isArray()) {
            if ("length".equals(property)) return Array.getLength(target);
        }
        if (target instanceof Map<?, ?> map) return map.get(property);
        if (target instanceof List<?> list) {
            try { int i = Integer.parseInt(property); return i >= 0 && i < list.size() ? list.get(i) : null; }
            catch (NumberFormatException ignored) {}
        }
        if (target.getClass().isArray()) {
            try { int i = Integer.parseInt(property); return i >= 0 && i < Array.getLength(target) ? Array.get(target, i) : null; }
            catch (NumberFormatException ignored) {}
        }
        return invokeAccessor(target, property);
    }

    // ── Reflection cache ──

    @SuppressWarnings("serial")
    private static final Map<Long, MethodHandle> ACCESSOR_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, MethodHandle> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static long accessorKey(Class<?> clazz, String property) {
        return ((long) System.identityHashCode(clazz) << 32) | (property.hashCode() & 0xFFFFFFFFL);
    }

    private static Object invokeAccessor(Object target, String property) {
        if (target == null || property.isEmpty()) return null;
        long key = accessorKey(target.getClass(), property);
        MethodHandle mh;
        synchronized (ACCESSOR_CACHE) {
            mh = ACCESSOR_CACHE.computeIfAbsent(key, k -> findAccessor(target.getClass(), property));
        }
        if (mh == null) return null;
        try { return mh.invoke(target); } catch (Throwable e) { return null; }
    }

    private static MethodHandle findAccessor(Class<?> clazz, String property) {
        try {
            if (clazz.isRecord()) {
                for (RecordComponent rc : clazz.getRecordComponents()) {
                    if (rc.getName().equals(property)) {
                        Method m = rc.getAccessor(); m.setAccessible(true);
                        return LOOKUP.unreflect(m);
                    }
                }
            }
            String cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
            for (String prefix : new String[]{"get", "is", ""}) {
                String mn = prefix.isEmpty() ? property : prefix + cap;
                try { Method m = clazz.getMethod(mn); m.setAccessible(true); return LOOKUP.unreflect(m); }
                catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Expression compiler — parses string → Expr tree (no scope)
    // ═══════════════════════════════════════════════════════════════

    private static final class ExprCompiler {
        private final String input;
        private int pos;

        ExprCompiler(String input) { this.input = input; this.pos = 0; }

        // ── Ternary ──
        Expr compileTernary() {
            Expr left = compileNullishCoalescing();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '?'
                    && (pos + 1 >= input.length() || input.charAt(pos + 1) != '?')
                    && (pos + 1 >= input.length() || input.charAt(pos + 1) != '.')) {
                pos++;
                Expr trueVal = compileTernary();
                skipWhitespace(); expect(':');
                Expr falseVal = compileTernary();
                return scope -> isTruthy(left.eval(scope)) ? trueVal.eval(scope) : falseVal.eval(scope);
            }
            return left;
        }

        // ── Nullish coalescing ──
        Expr compileNullishCoalescing() {
            Expr left = compileOr();
            skipWhitespace();
            while (matchStr("??")) {
                Expr right = compileOr();
                Expr prev = left;
                left = scope -> { Object l = prev.eval(scope); return l != null ? l : right.eval(scope); };
                skipWhitespace();
            }
            return left;
        }

        // ── Or ──
        Expr compileOr() {
            Expr left = compileAnd();
            skipWhitespace();
            while (matchStr("||")) {
                Expr right = compileAnd();
                Expr prev = left;
                left = scope -> { Object l = prev.eval(scope); return isTruthy(l) ? l : right.eval(scope); };
                skipWhitespace();
            }
            return left;
        }

        // ── And ──
        Expr compileAnd() {
            Expr left = compileEquality();
            skipWhitespace();
            while (matchStr("&&")) {
                Expr right = compileEquality();
                Expr prev = left;
                left = scope -> { Object l = prev.eval(scope); return isTruthy(l) ? right.eval(scope) : l; };
                skipWhitespace();
            }
            return left;
        }

        // ── Equality ──
        Expr compileEquality() {
            Expr left = compileComparison();
            skipWhitespace();
            while (true) {
                String op;
                if (matchStr("===")) op = "===";
                else if (matchStr("!==")) op = "!==";
                else if (matchStr("==")) op = "==";
                else if (matchStr("!=")) op = "!=";
                else break;
                Expr right = compileComparison();
                Expr prev = left;
                String finalOp = op;
                left = scope -> evalEquality(prev.eval(scope), right.eval(scope), finalOp);
                skipWhitespace();
            }
            return left;
        }

        // ── Comparison ──
        Expr compileComparison() {
            Expr left = compileAddition();
            skipWhitespace();
            while (true) {
                String op;
                if (matchStr(">=")) op = ">=";
                else if (matchStr("<=")) op = "<=";
                else if (match('>')) op = ">";
                else if (match('<')) op = "<";
                else break;
                Expr right = compileAddition();
                Expr prev = left;
                String finalOp = op;
                left = scope -> evalComparison(prev.eval(scope), right.eval(scope), finalOp);
                skipWhitespace();
            }
            return left;
        }

        // ── Addition ──
        Expr compileAddition() {
            Expr left = compileMultiplication();
            skipWhitespace();
            while (true) {
                if (match('+')) {
                    Expr right = compileMultiplication();
                    Expr prev = left;
                    left = scope -> evalAdd(prev.eval(scope), right.eval(scope));
                } else if (match('-')) {
                    Expr right = compileMultiplication();
                    Expr prev = left;
                    left = scope -> evalArithmetic(prev.eval(scope), right.eval(scope), '-');
                } else break;
                skipWhitespace();
            }
            return left;
        }

        // ── Multiplication ──
        Expr compileMultiplication() {
            Expr left = compileUnary();
            skipWhitespace();
            while (true) {
                char op;
                if (match('*')) op = '*';
                else if (match('/')) op = '/';
                else if (match('%')) op = '%';
                else break;
                Expr right = compileUnary();
                Expr prev = left;
                char finalOp = op;
                left = scope -> evalArithmetic(prev.eval(scope), right.eval(scope), finalOp);
                skipWhitespace();
            }
            return left;
        }

        // ── Unary ──
        Expr compileUnary() {
            skipWhitespace();
            if (match('!')) {
                Expr operand = compileUnary();
                return scope -> !isTruthy(operand.eval(scope));
            }
            if (pos < input.length() && input.charAt(pos) == '-'
                    && pos + 1 < input.length() && !Character.isDigit(input.charAt(pos + 1))) {
                pos++;
                Expr operand = compileUnary();
                return scope -> evalArithmetic(0, operand.eval(scope), '-');
            }
            return compilePrimary();
        }

        // ── Primary ──
        Expr compilePrimary() {
            skipWhitespace();
            if (pos >= input.length()) return NULL_EXPR;
            char ch = input.charAt(pos);

            if (ch == '(') {
                pos++;
                Expr inner = compileTernary();
                skipWhitespace(); expect(')');
                return compilePostfix(inner);
            }
            if (ch == '[') {
                pos++;
                List<Expr> items = new ArrayList<>();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == ']') {
                    pos++;
                    Expr arr = scope -> new ArrayList<>();
                    return compilePostfix(arr);
                }
                items.add(compileTernary()); skipWhitespace();
                while (pos < input.length() && input.charAt(pos) == ',') { pos++; items.add(compileTernary()); skipWhitespace(); }
                if (pos < input.length() && input.charAt(pos) == ']') pos++;
                List<Expr> finalItems = List.copyOf(items);
                Expr arr = scope -> {
                    List<Object> list = new ArrayList<>(finalItems.size());
                    for (Expr e : finalItems) list.add(e.eval(scope));
                    return list;
                };
                return compilePostfix(arr);
            }
            if (ch == '{') {
                pos++;
                List<String> keys = new ArrayList<>();
                List<Expr> values = new ArrayList<>();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '}') {
                    pos++;
                    return compilePostfix(scope -> new LinkedHashMap<>());
                }
                compileObjectEntry(keys, values); skipWhitespace();
                while (pos < input.length() && input.charAt(pos) == ',') { pos++; compileObjectEntry(keys, values); skipWhitespace(); }
                if (pos < input.length() && input.charAt(pos) == '}') pos++;
                List<String> fk = List.copyOf(keys); List<Expr> fv = List.copyOf(values);
                Expr obj = scope -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < fk.size(); i++) map.put(fk.get(i), fv.get(i).eval(scope));
                    return map;
                };
                return compilePostfix(obj);
            }
            if (ch == '\'' || ch == '"') {
                String s = parseStringLiteral(ch);
                Expr lit = scope -> s;
                return compilePostfix(lit);
            }
            if (Character.isDigit(ch) || (ch == '-' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1)))) {
                Object num = parseNumberLiteral();
                return scope -> num;
            }

            String word = readIdentifier();
            if (word.isEmpty()) return NULL_EXPR;

            Expr base = switch (word) {
                case "true" -> scope -> Boolean.TRUE;
                case "false" -> scope -> Boolean.FALSE;
                case "null" -> NULL_EXPR;
                default -> scope -> scope.get(word);
            };
            return compilePostfix(base);
        }

        // ── Postfix: .prop, [expr], ?.prop, .method() ──
        Expr compilePostfix(Expr base) {
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (ch == '?' && pos + 1 < input.length() && input.charAt(pos + 1) == '.') {
                    pos += 2;
                    if (pos < input.length() && input.charAt(pos) == '[') {
                        pos++;
                        Expr index = compileTernary();
                        skipWhitespace();
                        if (pos < input.length() && input.charAt(pos) == ']') pos++;
                        Expr prev = base;
                        base = scope -> { Object t = prev.eval(scope); if (t == null) return null; Object i = index.eval(scope); return i != null ? resolveProperty(t, i.toString()) : null; };
                    } else {
                        String prop = readIdentifier();
                        if (prop.isEmpty()) break;
                        Expr prev = base;
                        base = scope -> { Object t = prev.eval(scope); return t == null ? null : resolveProperty(t, prop); };
                    }
                } else if (ch == '.') {
                    pos++;
                    String prop = readIdentifier();
                    if (prop.isEmpty()) break;
                    if (pos < input.length() && input.charAt(pos) == '(') {
                        base = compileMethodCall(base, prop);
                    } else {
                        Expr prev = base;
                        base = scope -> resolveProperty(prev.eval(scope), prop);
                    }
                } else if (ch == '[') {
                    pos++;
                    Expr index = compileTernary();
                    skipWhitespace();
                    if (pos < input.length() && input.charAt(pos) == ']') pos++;
                    Expr prev = base;
                    base = scope -> { Object t = prev.eval(scope); Object i = index.eval(scope); return i != null ? resolveProperty(t, i.toString()) : null; };
                } else {
                    break;
                }
            }
            return base;
        }

        Expr compileMethodCall(Expr target, String methodName) {
            pos++; // skip '('
            List<Expr> args = new ArrayList<>();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) != ')') {
                args.add(compileTernary()); skipWhitespace();
                while (pos < input.length() && input.charAt(pos) == ',') { pos++; args.add(compileTernary()); skipWhitespace(); }
            }
            if (pos < input.length() && input.charAt(pos) == ')') pos++;
            List<Expr> finalArgs = List.copyOf(args);
            return scope -> {
                Object t = target.eval(scope);
                List<Object> evalArgs = new ArrayList<>(finalArgs.size());
                for (Expr a : finalArgs) evalArgs.add(a.eval(scope));
                return invokeBuiltin(t, methodName, evalArgs);
            };
        }

        void compileObjectEntry(List<String> keys, List<Expr> values) {
            skipWhitespace();
            String key;
            if (pos < input.length() && (input.charAt(pos) == '\'' || input.charAt(pos) == '"')) {
                key = parseStringLiteral(input.charAt(pos));
            } else {
                key = readIdentifier();
            }
            if (key.isEmpty()) return;
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ':') {
                pos++;
                keys.add(key); values.add(compileTernary());
            } else {
                String k = key;
                keys.add(k); values.add(scope -> scope.get(k));
            }
        }

        // ── Token helpers ──
        String readIdentifier() {
            int start = pos;
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_' || input.charAt(pos) == '$')) pos++;
            return input.substring(start, pos);
        }

        String parseStringLiteral(char quote) {
            pos++;
            var sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == quote) return sb.toString();
                if (c == '\\' && pos < input.length()) sb.append(input.charAt(pos++));
                else sb.append(c);
            }
            return sb.toString();
        }

        Object parseNumberLiteral() {
            int start = pos;
            if (input.charAt(pos) == '-') pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            boolean isFloat = false;
            if (pos < input.length() && input.charAt(pos) == '.') { isFloat = true; pos++; while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++; }
            String num = input.substring(start, pos);
            if (isFloat) return Double.parseDouble(num);
            try { return Integer.parseInt(num); } catch (NumberFormatException e) { return Long.parseLong(num); }
        }

        void skipWhitespace() { while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++; }
        boolean match(char c) { skipWhitespace(); if (pos < input.length() && input.charAt(pos) == c) { pos++; return true; } return false; }
        boolean matchStr(String s) { skipWhitespace(); if (input.startsWith(s, pos)) { pos += s.length(); return true; } return false; }
        void expect(char c) { skipWhitespace(); if (pos < input.length() && input.charAt(pos) == c) pos++; }
    }

    // ═══════════════════════════════════════════════════════════════
    // Built-in method dispatch
    // ═══════════════════════════════════════════════════════════════

    private static Object invokeBuiltin(Object target, String method, List<Object> args) {
        if (target == null) return null;
        if (target instanceof String s) {
            return switch (method) {
                case "toUpperCase" -> s.toUpperCase();
                case "toLowerCase" -> s.toLowerCase();
                case "trim" -> s.trim();
                case "startsWith" -> !args.isEmpty() && s.startsWith(String.valueOf(args.get(0)));
                case "endsWith" -> !args.isEmpty() && s.endsWith(String.valueOf(args.get(0)));
                case "includes", "contains" -> !args.isEmpty() && s.contains(String.valueOf(args.get(0)));
                case "indexOf" -> !args.isEmpty() ? s.indexOf(String.valueOf(args.get(0))) : -1;
                case "slice", "substring" -> {
                    int start = !args.isEmpty() ? toInt(args.get(0)) : 0;
                    int end = args.size() > 1 ? toInt(args.get(1)) : s.length();
                    if (start < 0) start = Math.max(0, s.length() + start);
                    if (end < 0) end = Math.max(0, s.length() + end);
                    yield s.substring(Math.min(start, s.length()), Math.min(end, s.length()));
                }
                case "replace" -> args.size() >= 2 ? s.replace(String.valueOf(args.get(0)), String.valueOf(args.get(1))) : s;
                case "split" -> !args.isEmpty() ? List.of(s.split(String.valueOf(args.get(0)))) : List.of(s);
                case "length" -> s.length();
                default -> null;
            };
        }
        if (target instanceof List<?> list) {
            return switch (method) {
                case "includes", "contains" -> !args.isEmpty() && list.contains(args.get(0));
                case "indexOf" -> !args.isEmpty() ? list.indexOf(args.get(0)) : -1;
                case "join" -> {
                    String sep = args.isEmpty() ? "," : String.valueOf(args.get(0));
                    var sb = new StringBuilder();
                    for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(sep); sb.append(list.get(i)); }
                    yield sb.toString();
                }
                case "slice" -> {
                    int start = !args.isEmpty() ? toInt(args.get(0)) : 0;
                    int end = args.size() > 1 ? toInt(args.get(1)) : list.size();
                    if (start < 0) start = Math.max(0, list.size() + start);
                    if (end < 0) end = Math.max(0, list.size() + end);
                    yield list.subList(Math.min(start, list.size()), Math.min(end, list.size()));
                }
                default -> null;
            };
        }
        if (target instanceof Map<?,?> map) {
            return switch (method) {
                case "keys" -> new ArrayList<>(map.keySet());
                case "values" -> new ArrayList<>(map.values());
                case "hasOwnProperty", "has" -> !args.isEmpty() && map.containsKey(String.valueOf(args.get(0)));
                default -> null;
            };
        }
        return null;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    // ═══════════════════════════════════════════════════════════════
    // Evaluation helpers
    // ═══════════════════════════════════════════════════════════════

    private static Object evalEquality(Object left, Object right, String op) {
        return switch (op) {
            case "===" -> strictEquals(left, right);
            case "!==" -> !strictEquals(left, right);
            case "==" -> looseEquals(left, right);
            case "!=" -> !looseEquals(left, right);
            default -> false;
        };
    }

    private static boolean strictEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number na && b instanceof Number nb) return na.doubleValue() == nb.doubleValue();
        if (a instanceof String && b instanceof String) return a.equals(b);
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        return a == b;
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number na && b instanceof Number nb) return na.doubleValue() == nb.doubleValue();
        if (a instanceof Number && b instanceof String s) { try { return ((Number) a).doubleValue() == Double.parseDouble(s); } catch (NumberFormatException e) { return false; } }
        if (a instanceof String s && b instanceof Number) { try { return Double.parseDouble(s) == ((Number) b).doubleValue(); } catch (NumberFormatException e) { return false; } }
        if (a instanceof Boolean) return looseEquals(((Boolean) a) ? 1 : 0, b);
        if (b instanceof Boolean) return looseEquals(a, ((Boolean) b) ? 1 : 0);
        return a.toString().equals(b.toString());
    }

    private static Object evalComparison(Object left, Object right, String op) {
        double l = toNumber(left), r = toNumber(right);
        return switch (op) { case ">" -> l > r; case ">=" -> l >= r; case "<" -> l < r; case "<=" -> l <= r; default -> false; };
    }

    private static Object evalAdd(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return (left != null ? left.toString() : "null") + (right != null ? right.toString() : "null");
        }
        double result = toNumber(left) + toNumber(right);
        if (result == (int) result) return (int) result;
        return result;
    }

    private static Object evalArithmetic(Object left, Object right, char op) {
        double l = toNumber(left), r = toNumber(right);
        double result = switch (op) { case '-' -> l - r; case '*' -> l * r; case '/' -> r != 0 ? l / r : 0; case '%' -> r != 0 ? l % r : 0; default -> 0; };
        if (result == (int) result) return (int) result;
        return result;
    }

    private static double toNumber(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }
}
