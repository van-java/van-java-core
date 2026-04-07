package dev.vanengine.core;

/**
 * Thrown when a .van template cannot be compiled or evaluated.
 * Carries file path and expression context for diagnostics.
 */
public class VanTemplateException extends IllegalArgumentException {

    private final String templatePath;
    private final String expression;
    private final int line;
    private final int column;

    public VanTemplateException(String message, String templatePath) {
        this(message, templatePath, null, -1, -1);
    }

    public VanTemplateException(String message, String templatePath, String expression) {
        this(message, templatePath, expression, -1, -1);
    }

    public VanTemplateException(String message, String templatePath, Throwable cause) {
        super(formatMessage(message, templatePath, null, -1, -1), cause);
        this.templatePath = templatePath;
        this.expression = null;
        this.line = -1;
        this.column = -1;
    }

    public VanTemplateException(String message, String templatePath, String expression,
                                 int line, int column) {
        super(formatMessage(message, templatePath, expression, line, column));
        this.templatePath = templatePath;
        this.expression = expression;
        this.line = line;
        this.column = column;
    }

    public String getTemplatePath() { return templatePath; }
    public String getExpression() { return expression; }
    public int getLine() { return line; }
    public int getColumn() { return column; }

    private static String formatMessage(String message, String path, String expression,
                                         int line, int column) {
        StringBuilder sb = new StringBuilder();
        if (path != null) {
            sb.append('[').append(path);
            if (line > 0) {
                sb.append(':').append(line);
                if (column > 0) sb.append(':').append(column);
            }
            sb.append("] ");
        }
        sb.append(message);
        if (expression != null) sb.append(" (expression: ").append(expression).append(')');
        return sb.toString();
    }

    /**
     * Convert a character offset in source text to line:column.
     * @return int[]{line, column} (1-based)
     */
    public static int[] offsetToLineCol(String source, int offset) {
        if (source == null || offset < 0) return new int[]{-1, -1};
        int line = 1, col = 1;
        for (int i = 0; i < Math.min(offset, source.length()); i++) {
            if (source.charAt(i) == '\n') { line++; col = 1; }
            else col++;
        }
        return new int[]{line, col};
    }
}
