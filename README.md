# van-java-core

<p>
  <a href="https://github.com/vanengine/van"><img src="https://img.shields.io/badge/van-template%20engine-steelblue" alt="Van" /></a>
  <a href="https://central.sonatype.com/artifact/dev.vanengine/van-java-core"><img src="https://img.shields.io/maven-central/v/dev.vanengine/van-java-core" alt="Maven Central" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License" /></a>
</p>

Pure Java SDK for the [Van](https://github.com/vanengine/van) template engine — compile `.van` files (Vue SFC syntax) to HTML on the JVM, with zero Node.js dependency.

<p>
  <a href="README.md">English</a> · <a href="docs/i18n/zh-CN/README.md">简体中文</a>
</p>

## Features

- **Vue SFC Syntax** — `<template>`, `<script setup>`, `<style scoped>`
- **WASM-powered** — Compilation delegated to the Rust-based van-compiler via a long-lived daemon process
- **Compile / Render separation** — `VanCompiler` (expensive, WASM) produces `VanTemplate` (cheap, regex interpolation)
- **mtime-based caching** — Filesystem templates are recompiled only when modified
- **Auto-download** — The compiler binary is fetched from [GitHub Releases](https://github.com/vanengine/van/releases) on first use and cached at `~/.van/bin/`
- **Thread-safe** — `VanTemplate` is immutable and safe to share across threads

## Quick Start

### Add dependency

**Gradle**

```groovy
dependencies {
    implementation 'dev.vanengine:van-java-core:0.1.16'
}
```

**Maven**

```xml
<dependency>
    <groupId>dev.vanengine</groupId>
    <artifactId>van-java-core</artifactId>
    <version>0.1.16</version>
</dependency>
```

### Usage

```java
// 1. Create and start the compiler daemon
VanCompiler compiler = new VanCompiler();
compiler.init();

// 2. Create the engine
VanEngine engine = new VanEngine(compiler);
engine.setBasePath(Path.of("/path/to/themes/default"));

// 3a. Compile a file and evaluate
VanTemplate template = engine.getTemplate("pages/index.van");
String html = template.evaluate(Map.of("title", "Hello", "message", "World"));

// 3b. Or use the one-step convenience method
String html = engine.compile("pages/index.van",
        Map.of("title", "Hello", "message", "World"));

// 3c. Compile an inline template
String html = engine.compileLiteral("""
        <template>
          <h1>{{ title }}</h1>
        </template>
        """,
        Map.of("title", "Hello"));

// 4. Shut down when done
compiler.close();
```

## API

### VanEngine

| Method | Description |
|---|---|
| `getTemplate(path)` | Compile a `.van` file from the filesystem |
| `getTemplate(path, files)` | Compile from an in-memory files map |
| `getLiteralTemplate(content)` | Compile an inline template string |
| `compile(path, model)` | Compile + evaluate in one step |
| `compileLiteral(content, model)` | Compile inline + evaluate in one step |
| `setBasePath(path)` | Set the base directory for template resolution |

### VanTemplate

| Method | Description |
|---|---|
| `evaluate(model)` | Interpolate `{{ expr }}` placeholders with model data |
| `getHtml()` | Return the raw compiled HTML |

Supports dot-notation for nested values (e.g., `{{ user.name }}`). All interpolated values are HTML-escaped.

### VanCompiler

| Method | Description |
|---|---|
| `init()` | Start the WASM daemon process |
| `close()` | Stop the daemon process |
| `compile(vanFile, basePath)` | Compile with mtime caching (filesystem) |
| `compile(entryPath, files)` | Compile from an in-memory files map |

## Architecture

```
.van file → [VanCompiler]     → compiled HTML with {{ expr }}
              WASM daemon          ↓
              mtime caching    [VanTemplate]
                                   ↓
                               evaluate(model) → Final HTML
```

- **VanCompiler** manages a long-lived `van-compiler-wasi --daemon` subprocess, communicating via JSON Lines over stdin/stdout
- **VanTemplate** holds the compiled HTML and performs `{{ expr }}` interpolation — immutable, thread-safe, reusable
- **VanEngine** is the main facade wiring compilation and evaluation together

## Requirements

- Java 25+

## Related

- [**Van**](https://github.com/vanengine/van) — Core template engine (Rust / WASM)
- [**van-spring-boot-starter**](https://github.com/van-java/van-spring-boot-starter) — Spring Boot integration

## License

[MIT](LICENSE)
