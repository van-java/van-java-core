package dev.vanengine.core;

/**
 * A resolved non-component module import (.ts/.js file).
 *
 * @param path       resolved virtual path
 * @param content    file content from the files map
 * @param isTypeOnly whether this is a type-only import (should be erased)
 */
public record ResolvedModule(String path, String content, boolean isTypeOnly) {
}
