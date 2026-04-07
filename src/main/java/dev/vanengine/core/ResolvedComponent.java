package dev.vanengine.core;

import java.util.List;

/**
 * The result of resolving a {@code .van} file (with or without imports).
 *
 * @param html          the fully rendered HTML content
 * @param styles        collected CSS styles from this component and all descendants
 * @param scriptSetup   the merged {@code <script setup>} content (for signal generation)
 * @param moduleImports resolved non-component module imports (.ts/.js files)
 */
public record ResolvedComponent(
        String html,
        List<String> styles,
        String scriptSetup,
        List<ResolvedModule> moduleImports
) {
}
