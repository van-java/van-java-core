package dev.vanengine.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Built-in JSON parser for i18n translation files.
 */
class JsonI18nFileParser implements I18nFileParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("json");
    }

    @Override
    public Map<String, Object> parse(InputStream inputStream) throws IOException {
        return objectMapper.readValue(inputStream, MAP_TYPE);
    }
}
