package dev.vanengine.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class VanEngine {

    private final VanCompiler compiler;
    private Path basePath;

    public VanEngine(VanCompiler compiler) {
        this.compiler = compiler;
    }

    public void setBasePath(Path basePath) {
        this.basePath = basePath;
    }

    /**
     * Compile a .van file from the filesystem and return a reusable template.
     */
    public VanTemplate getTemplate(String templatePath) throws IOException {
        Path vanFile = basePath.resolve(templatePath);
        String html = compiler.compile(vanFile, basePath).html();
        return new VanTemplate(html);
    }

    /**
     * Compile from an explicit files map (for classpath resources) and return a reusable template.
     */
    public VanTemplate getTemplate(String entryPath, Map<String, String> files) throws IOException {
        String html = compiler.compile(entryPath, files).html();
        return new VanTemplate(html);
    }

    /**
     * Compile an inline template string and return a reusable template.
     */
    public VanTemplate getLiteralTemplate(String templateContent) throws IOException {
        String html = compiler.compile("literal.van", Map.of("literal.van", templateContent)).html();
        return new VanTemplate(html);
    }

    // ── convenience methods (対標 TwigRenderEngine) ──

    /**
     * Compile a .van file and evaluate with model data in one step.
     */
    public String compile(String templatePath, Map<String, ?> model) throws IOException {
        return getTemplate(templatePath).evaluate(model);
    }

    /**
     * Compile an inline template and evaluate with model data in one step.
     */
    public String compileLiteral(String templateContent, Map<String, ?> model) throws IOException {
        return getLiteralTemplate(templateContent).evaluate(model);
    }
}
