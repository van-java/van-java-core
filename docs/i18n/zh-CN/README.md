# van-java-core

<p>
  <a href="https://github.com/vanengine/van"><img src="https://img.shields.io/badge/van-template%20engine-steelblue" alt="Van" /></a>
  <a href="https://central.sonatype.com/artifact/dev.vanengine/van-java-core"><img src="https://img.shields.io/maven-central/v/dev.vanengine/van-java-core" alt="Maven Central" /></a>
  <a href="../../../LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License" /></a>
</p>

[Van](https://github.com/vanengine/van) 模板引擎的纯 Java SDK — 在 JVM 上将 `.van` 文件（Vue SFC 语法）编译为 HTML，无 Node.js 依赖。

<p>
  <a href="../../../README.md">English</a> · <a href="README.md">简体中文</a>
</p>

## 特性

- **Vue SFC 语法** — `<template>`、`<script setup>`、`<style scoped>`
- **WASM 驱动** — 编译委托给基于 Rust 的 van-compiler 守护进程
- **编译 / 渲染分离** — `VanCompiler`（昂贵，WASM 调用）生成 `VanTemplate`（廉价，正则插值）
- **mtime 缓存** — 文件系统模板仅在修改后重新编译
- **自动下载** — 编译器二进制文件首次使用时从 [GitHub Releases](https://github.com/vanengine/van/releases) 自动下载并缓存至 `~/.van/bin/`
- **线程安全** — `VanTemplate` 不可变，可在线程间安全共享

## 快速开始

### 添加依赖

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

### 使用方式

```java
// 1. 创建并启动编译器守护进程
VanCompiler compiler = new VanCompiler();
compiler.init();

// 2. 创建引擎
VanEngine engine = new VanEngine(compiler);
engine.setBasePath(Path.of("/path/to/themes/default"));

// 3a. 编译文件并渲染
VanTemplate template = engine.getTemplate("pages/index.van");
String html = template.evaluate(Map.of("title", "Hello", "message", "World"));

// 3b. 或使用一步到位的便捷方法
String html = engine.compile("pages/index.van",
        Map.of("title", "Hello", "message", "World"));

// 3c. 编译内联模板
String html = engine.compileLiteral("""
        <template>
          <h1>{{ title }}</h1>
        </template>
        """,
        Map.of("title", "Hello"));

// 4. 完成后关闭
compiler.close();
```

## API

### VanEngine

| 方法 | 说明 |
|---|---|
| `getTemplate(path)` | 从文件系统编译 `.van` 文件 |
| `getTemplate(path, files)` | 从内存文件映射编译 |
| `getLiteralTemplate(content)` | 编译内联模板字符串 |
| `compile(path, model)` | 编译 + 渲染一步到位 |
| `compileLiteral(content, model)` | 编译内联模板 + 渲染一步到位 |
| `setBasePath(path)` | 设置模板解析的根目录 |

### VanTemplate

| 方法 | 说明 |
|---|---|
| `evaluate(model)` | 将 `{{ expr }}` 占位符替换为模型数据 |
| `getHtml()` | 返回原始编译后的 HTML |

支持点号表示法访问嵌套值（如 `{{ user.name }}`）。所有插值均进行 HTML 转义。

### VanCompiler

| 方法 | 说明 |
|---|---|
| `init()` | 启动 WASM 守护进程 |
| `close()` | 停止守护进程 |
| `compile(vanFile, basePath)` | 带 mtime 缓存的文件系统编译 |
| `compile(entryPath, files)` | 从内存文件映射编译 |

## 架构

```
.van 文件 → [VanCompiler]      → 带 {{ expr }} 的编译后 HTML
              WASM 守护进程          ↓
              mtime 缓存       [VanTemplate]
                                    ↓
                                evaluate(model) → 最终 HTML
```

- **VanCompiler** 管理长驻的 `van-compiler-wasi --daemon` 子进程，通过 stdin/stdout 的 JSON Lines 协议通信
- **VanTemplate** 持有编译后的 HTML 并执行 `{{ expr }}` 插值 — 不可变、线程安全、可复用
- **VanEngine** 是主要门面，串联编译与渲染

## 环境要求

- Java 25+

## 相关项目

- [**Van**](https://github.com/vanengine/van) — 核心模板引擎（Rust / WASM）
- [**van-spring-boot-starter**](https://github.com/van-java/van-spring-boot-starter) — Spring Boot 集成

## 许可证

[MIT](../../../LICENSE)
