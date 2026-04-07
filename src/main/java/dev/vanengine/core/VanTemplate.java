package dev.vanengine.core;

import dev.vanengine.core.runtime.VanRuntime;
import dev.vanengine.core.support.VanAst;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VanTemplate {

    private final String compiledHtml;
    private final List<VanAst.Node> cachedAst;
    private final VanEngine engine;

    VanTemplate(String compiledHtml, VanEngine engine) {
        this.compiledHtml = compiledHtml;
        this.cachedAst = VanAst.parse(compiledHtml);
        VanAst.markDynamic(this.cachedAst);
        this.engine = engine;
    }

    public String evaluate(Map<String, ?> model, String locale) {
        if (engine == null || !engine.hasI18nMessages()) {
            return evaluate(model);
        }
        Map<String, Object> merged = new HashMap<>(model);
        merged.put("$i18n", engine.getI18nMessages(locale));
        return evaluate(merged);
    }

    public String evaluate(Map<String, ?> model) {
        List<VanAst.Node> nodes = VanAst.smartCopy(cachedAst);
        return VanRuntime.renderAst(nodes, new HashMap<>(model));
    }

    public String getHtml() {
        return compiledHtml;
    }

}
