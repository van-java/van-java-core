package dev.vanengine.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * SPI for parsing i18n translation files.
 */
public interface I18nFileParser {

    /**
     * File extensions this parser can handle (lowercase, without dot), e.g. {"json"}.
     */
    Set<String> supportedExtensions();

    /**
     * Parse an i18n file from the given input stream.
     */
    Map<String, Object> parse(InputStream inputStream) throws IOException;
}
