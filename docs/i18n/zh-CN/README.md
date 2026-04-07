# van-java-core

<p>
  <a href="https://github.com/vanengine/van"><img src="https://img.shields.io/badge/van-template%20engine-steelblue" alt="Van" /></a>
  <a href="https://central.sonatype.com/artifact/dev.vanengine/van-java-core"><img src="https://img.shields.io/maven-central/v/dev.vanengine/van-java-core" alt="Maven Central" /></a>
  <a href="../../../LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License" /></a>
</p>

[Van](https://github.com/vanengine/van) 模板引擎的纯 Java SDK — 在 JVM 上将 `.van` 文件（Vue SFC 语法）编译为 HTML，零 Node.js 依赖。

<p>
  <a href="../../../README.md">English</a> · <a href="README.md">简体中文</a>
</p>

## 特性

- **纯 Java** — 无 WASM、无子进程、无原生二进制。一个 JAR 搞定。
- **Vue SFC 语法** — `<template>`、`<script setup>`、`<style scoped>`、组件导入、插槽
- **完整 SSR** — `v-for`、`v-if`/`v-else-if`/`v-else`、`v-show`、`:class`、`:style`、`v-html`、`v-text`、`{{ }}`
- **客户端 Hydration** — 自动生成基于 Signal 的 JS（`ref`、`computed`、`watch`、`effect`），页面具备交互能力
- **表达式引擎** — 三元运算、逻辑运算、算术运算、可选链（`?.`）、空值合并（`??`）、方法调用、数组/对象字面量
- **国际化** — JSON 翻译文件、语言回退链、复数形式
- **线程安全** — `VanTemplate` 不可变，可在任意线程并发调用 evaluate
- **AST 管线** — 单次 HTML 树遍历处理所有指令，零正则匹配 HTML 结构

## 快速开始

### 添加依赖

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

### 编译和渲染

```java
VanEngine engine = VanEngine.builder()
        .basePath(Path.of("/themes/default"))
        .build();

// 编译一次，多次渲染（线程安全）
VanTemplate template = engine.getTemplate("pages/index.van");
String html = template.evaluate(Map.of("title", "Hello", "items", List.of("a", "b")));

// 或一步到位
String html = engine.compile("pages/index.van", Map.of("title", "Hello"));

// 内联模板
String html = engine.compileLiteral("""
        <template>
          <h1>{{ title }}</h1>
          <ul><li v-for="item in items">{{ item }}</li></ul>
        </template>
        """,
        Map.of("title", "Hello", "items", List.of("a", "b", "c")));
```

### 内存文件（classpath / 测试）

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

## 模板语法

### 指令

| 指令 | 示例 |
|---|---|
| `v-for` | `<li v-for="item in items">`、`<li v-for="(item, i) in items">`、`<li v-for="{ name, id } in items">` |
| `v-if` / `v-else-if` / `v-else` | `<div v-if="show">A</div><div v-else>B</div>` |
| `v-show` | `<p v-show="visible">`（设置 `display:none`） |
| `v-html` | `<div v-html="rawContent">`（原始 HTML，不转义） |
| `v-text` | `<span v-text="msg">`（转义文本） |
| `:class` | `<div :class="{ active: isActive }">`、`<div :class="[a, 'static']">` |
| `:style` | `<div :style="{ color: textColor }">` |
| `:attr` | `<a :href="url">`、`<input :disabled="isOff">` |
| `{{ }}` | `<p>{{ user.name }}</p>`（HTML 转义） |
| `{{{ }}}` | `<p>{{{ rawHtml }}}</p>`（原始输出，不转义） |

### 表达式

```
user.name                    // 点访问
items[0].name                // 方括号访问
user?.profile?.email         // 可选链
value ?? 'default'           // 空值合并
active ? 'yes' : 'no'       // 三元运算
items.length > 0 && show    // 逻辑 + 比较
str.toUpperCase()            // 方法调用
items.includes('x')          // 列表方法
[1, 2, 3]                   // 数组字面量
{ key: value }               // 对象字面量
```

### 组件和插槽

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
    <template #header><h1>标题</h1></template>
    <p>默认插槽内容</p>
  </layout>
</template>
<script setup>
import Layout from './layout.van'
</script>
```

### 国际化

```java
VanEngine engine = VanEngine.builder()
        .basePath(Path.of("/app"))  // 加载 /app/i18n/*.json
        .defaultLocale("en")
        .build();

// 模板中: {{ $t('nav.home') }}
// Java 中: engine.getMessage("nav.home", "zh-CN")
```

## API 参考

### VanEngine（主门面）

| 方法 | 说明 |
|---|---|
| `getTemplate(path)` | 编译 `.van` 文件 → 可复用的 `VanTemplate` |
| `getTemplate(path, files)` | 从内存文件映射编译 |
| `getLiteralTemplate(content)` | 编译内联模板字符串 |
| `compile(path, model)` | 编译 + 渲染一步到位 |
| `compileLiteral(content, model)` | 编译内联模板 + 渲染 |
| `getMessage(key, locale, params)` | 获取国际化消息并替换参数 |

### VanTemplate（不可变，线程安全）

| 方法 | 说明 |
|---|---|
| `evaluate(model)` | 填充数据到模板 → 最终 HTML |
| `evaluate(model, locale)` | 带国际化语言环境的渲染 |
| `getHtml()` | 原始编译 HTML（含 `{{ }}` 占位符） |

### VanCompiler（底层 API）

| 方法 | 说明 |
|---|---|
| `compile(vanFile, basePath)` | 带 mtime 缓存的文件系统编译 |
| `compile(entryPath, files)` | 从内存文件映射编译 |
| `renderToString(entry, files, json)` | 编译 + 绑定 JSON 数据一步到位 |

## 架构

```
.van 文件
  → VanParser        提取 template/script/style 块
  → VanResolver      解析组件导入、插槽、v-for（基于 AST）
  → VanSignalGen     生成客户端 signal JS
  → VanCompiler      组装 HTML 外壳 + 样式 + 脚本
  → VanTemplate      不可变，缓存 AST，线程安全
       ↓
  evaluate(model)
  → VanRuntime       单次 AST 遍历：v-for/v-if/v-show/:class/{{ }}
  → 最终 HTML
```

所有 HTML 处理使用 `VanAst` — 单一可变 HTML AST，支持解析与序列化。零正则匹配 HTML 结构。

## 安全

| 路径 | 转义 |
|---|---|
| `{{ expr }}` | HTML 转义 |
| `:attr="expr"` | HTML 转义 |
| `v-text="expr"` | HTML 转义 |
| `{{{ expr }}}` | **原始输出 — XSS 风险** |
| `v-html="expr"` | **原始输出 — XSS 风险** |

**切勿将用户输入绑定到 `v-html` 或 `{{{ }}}`，除非已在服务端做过净化处理。**

## 环境要求

- Java 17+

## 许可证

[MIT](../../../LICENSE)
