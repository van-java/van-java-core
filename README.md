# van-java-core

<p>
  <a href="https://github.com/vanengine/van"><img src="https://img.shields.io/badge/van-template%20engine-steelblue" alt="Van" /></a>
  <a href="https://central.sonatype.com/artifact/dev.vanengine/van-java-core"><img src="https://img.shields.io/maven-central/v/dev.vanengine/van-java-core" alt="Maven Central" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License" /></a>
</p>

Pure Java SDK for the [Van](https://github.com/vanengine/van) template engine — compile `.van` files (Vue SFC syntax) to HTML on the JVM, with zero Node.js dependency.

## Features

- **Pure Java** — No WASM, no subprocess, no native binary. One JAR.
- **Vue SFC Syntax** — `<template>`, `<script setup>`, `<style scoped>`, component imports, slots
- **Full SSR** — `v-for`, `v-if`/`v-else-if`/`v-else`, `v-show`, `:class`, `:style`, `v-html`, `v-text`, `{{ }}`
- **Client Hydration** — Signal-based JS (`ref`, `computed`, `watch`, `effect`) auto-generated for interactive pages
- **Expression Engine** — Ternary, logical, arithmetic, optional chaining (`?.`), nullish coalescing (`??`), method calls, array/object literals
- **i18n** — JSON translation files, locale fallback chains, plural forms
- **Thread-safe** — `VanTemplate` is immutable; evaluate concurrently from any thread
- **AST Pipeline** — Single-pass HTML tree processing, no regex on HTML structure

## Quick Start

### Add dependency

**Gradle**

```groovy
dependencies {
    implementation 'dev.vanengine:van-java-core:0.1.32'
}
```

**Maven**

```xml
<dependency>
    <groupId>dev.vanengine</groupId>
    <artifactId>van-java-core</artifactId>
    <version>0.1.32</version>
</dependency>
```

### Compile and Evaluate

```java
VanEngine engine = VanEngine.builder()
        .basePath(Path.of("/themes/default"))
        .build();

// Compile once, evaluate many times (thread-safe)
VanTemplate template = engine.getTemplate("pages/index.van");
String html = template.evaluate(Map.of("title", "Hello", "items", List.of("a", "b")));

// Or one-shot
String html = engine.compile("pages/index.van", Map.of("title", "Hello"));

// Inline templates
String html = engine.compileLiteral("""
        <template>
          <h1>{{ title }}</h1>
          <ul><li v-for="item in items">{{ item }}</li></ul>
        </template>
        """,
        Map.of("title", "Hello", "items", List.of("a", "b", "c")));
```

### In-Memory Files (classpath / testing)

```java
VanCompiler compiler = new VanCompiler();

Map<String, String> files = Map.of(
    "index.van", """
        <template><layout><h1>{{ title }}</h1></layout></template>
        <script setup>import Layout from './layout.van'</script>
        """,
    "layout.van", """
        <template><html><body><slot /></body></html></template>
        """
);

String html = compiler.renderToString("index.van", files, "{\"title\":\"Hello\"}");
```

## Template Syntax

### Directives

| Directive | Example |
|---|---|
| `v-for` | `<li v-for="item in items">`, `<li v-for="(item, i) in items">`, `<li v-for="{ name, id } in items">` |
| `v-if` / `v-else-if` / `v-else` | `<div v-if="show">A</div><div v-else>B</div>` |
| `v-show` | `<p v-show="visible">` (sets `display:none`) |
| `v-html` | `<div v-html="rawContent">` (raw HTML, no escaping) |
| `v-text` | `<span v-text="msg">` (escaped text) |
| `:class` | `<div :class="{ active: isActive }">`, `<div :class="[a, 'static']">` |
| `:style` | `<div :style="{ color: textColor }">` |
| `:attr` | `<a :href="url">`, `<input :disabled="isOff">` |
| `{{ }}` | `<p>{{ user.name }}</p>` (HTML-escaped) |
| `{{{ }}}` | `<p>{{{ rawHtml }}}</p>` (raw, no escaping) |

### Expressions

```
user.name                    // dot access
items[0].name                // bracket access
user?.profile?.email         // optional chaining
value ?? 'default'           // nullish coalescing
active ? 'yes' : 'no'       // ternary
items.length > 0 && show    // logical + comparison
str.toUpperCase()            // method calls
items.includes('x')          // list methods
[1, 2, 3]                   // array literal
{ key: value }               // object literal
```

### Components and Slots

```vue
<!-- layout.van -->
<template>
  <div class="layout">
    <header><slot name="header" /></header>
    <main><slot /></main>
  </div>
</template>

<!-- page.van -->
<template>
  <layout>
    <template #header><h1>Title</h1></template>
    <p>Default slot content</p>
  </layout>
</template>
<script setup>
import Layout from './layout.van'
</script>
```

### i18n

```java
VanEngine engine = VanEngine.builder()
        .basePath(Path.of("/app"))  // loads /app/i18n/*.json
        .defaultLocale("en")
        .build();

// In templates: {{ $t('nav.home') }}
// In Java: engine.getMessage("nav.home", "zh-CN")
```

## API Reference

### VanEngine (Main Facade)

| Method | Description |
|---|---|
| `getTemplate(path)` | Compile a `.van` file → reusable `VanTemplate` |
| `getTemplate(path, files)` | Compile from in-memory files map |
| `getLiteralTemplate(content)` | Compile inline template string |
| `compile(path, model)` | Compile + evaluate in one step |
| `compileLiteral(content, model)` | Compile inline + evaluate |
| `getMessage(key, locale, params)` | Get i18n message with parameter substitution |

### VanTemplate (Immutable, Thread-Safe)

| Method | Description |
|---|---|
| `evaluate(model)` | Fill data into template → final HTML |
| `evaluate(model, locale)` | Evaluate with i18n locale |
| `getHtml()` | Raw compiled HTML with `{{ }}` placeholders |

### VanCompiler (Low-Level)

| Method | Description |
|---|---|
| `compile(vanFile, basePath)` | Compile with mtime caching (filesystem) |
| `compile(entryPath, files)` | Compile from in-memory files map |
| `renderToString(entry, files, json)` | Compile + bind JSON data in one shot |

## Architecture

```
.van file
  → VanParser        extract template/script/style blocks
  → VanResolver      resolve imports, slots, v-for (AST-based)
  → VanSignalGen     generate client-side signal JS
  → VanCompiler      assemble HTML shell + styles + scripts
  → VanTemplate      immutable, cached AST, thread-safe
       ↓
  evaluate(model)
  → VanRuntime       single-pass AST walk: v-for/v-if/v-show/:class/{{ }}
  → Final HTML
```

All HTML processing uses `VanAst` — a single mutable HTML AST with parse/serialize. No regex on HTML structure.

## Security

| Path | Escaping |
|---|---|
| `{{ expr }}` | HTML-escaped |
| `:attr="expr"` | HTML-escaped |
| `v-text="expr"` | HTML-escaped |
| `{{{ expr }}}` | **Raw — XSS risk** |
| `v-html="expr"` | **Raw — XSS risk** |

**Never bind user input to `v-html` or `{{{ }}}` without server-side sanitization.**

## Requirements

- Java 17+

## License

[MIT](LICENSE)
