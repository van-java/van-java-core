package dev.vanengine.core;

import dev.vanengine.core.runtime.VanExpressions;
import dev.vanengine.core.runtime.VanRuntime;
import dev.vanengine.core.support.VanAst;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for Van Java Core hot paths.
 * Run: gradle test --tests "dev.vanengine.core.VanBenchmark" (via main method)
 * Or standalone: java -jar build/libs/... dev.vanengine.core.VanBenchmark
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class VanBenchmark {

    // ── Test data ──

    private VanTemplate simpleTemplate;
    private VanTemplate listTemplate;
    private VanTemplate complexTemplate;
    private Map<String, Object> simpleModel;
    private Map<String, Object> listModel;
    private Map<String, Object> complexModel;
    private String htmlToParse;

    @Setup
    public void setup() {
        VanEngine engine = VanEngine.builder().build();

        simpleTemplate = engine.getLiteralTemplate(
                "<template><h1>{{ title }}</h1><p>{{ description }}</p></template>");
        simpleModel = Map.of("title", "Hello", "description", "World");

        listTemplate = engine.getLiteralTemplate("""
                <template>
                  <ul>
                    <li v-for="item in items" :class="{ active: item.active }">
                      {{ item.name }} - {{ item.role }}
                    </li>
                  </ul>
                </template>
                """);
        listModel = new HashMap<>();
        listModel.put("items", List.of(
                Map.of("name", "Alice", "role", "dev", "active", true),
                Map.of("name", "Bob", "role", "pm", "active", false),
                Map.of("name", "Charlie", "role", "qa", "active", true),
                Map.of("name", "Diana", "role", "dev", "active", true),
                Map.of("name", "Eve", "role", "ops", "active", false)
        ));

        complexTemplate = engine.getLiteralTemplate("""
                <template>
                  <div>
                    <h1>{{ title }}</h1>
                    <div v-if="showList">
                      <ul>
                        <li v-for="(item, i) in items">
                          {{ i }}: {{ item.name }} ({{ item.role }})
                          <span v-show="item.active" :class="{ badge: true }">Active</span>
                        </li>
                      </ul>
                      <p>Total: {{ items.length }}</p>
                    </div>
                    <div v-else>No items</div>
                  </div>
                </template>
                """);
        complexModel = new HashMap<>(listModel);
        complexModel.put("title", "Dashboard");
        complexModel.put("showList", true);

        htmlToParse = "<div class=\"container\"><h1>Title</h1><ul><li>Item 1</li><li>Item 2</li><li>Item 3</li></ul><p>Footer</p></div>";
    }

    // ── Benchmarks ──

    @Benchmark
    public String evaluateSimple() {
        return simpleTemplate.evaluate(simpleModel);
    }

    @Benchmark
    public String evaluateList() {
        return listTemplate.evaluate(listModel);
    }

    @Benchmark
    public String evaluateComplex() {
        return complexTemplate.evaluate(complexModel);
    }

    @Benchmark
    public Object expressionSimplePath() {
        return VanExpressions.evaluate("item.name", Map.of("item", Map.of("name", "Alice")));
    }

    @Benchmark
    public Object expressionComplex() {
        return VanExpressions.evaluate("items.length > 0 && showList === true", complexModel);
    }

    @Benchmark
    public Object expressionTernary() {
        return VanExpressions.evaluate("active ? 'yes' : 'no'", Map.of("active", true));
    }

    @Benchmark
    public List<VanAst.Node> astParse() {
        return VanAst.parse(htmlToParse);
    }

    @Benchmark
    public String astParseSerialize() {
        return VanAst.toHtml(VanAst.parse(htmlToParse));
    }

    // ── Main entry point ──

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder()
                .include(VanBenchmark.class.getSimpleName())
                .build())
                .run();
    }
}
