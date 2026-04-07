package dev.vanengine.core.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vanengine.core.support.VanUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Built-in JSON parser for i18n translation files.
 */
public class JsonI18nFileParser implements I18nFileParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper objectMapper = VanUtil.MAPPER;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("json");
    }

    @Override
    public Map<String, Object> parse(InputStream inputStream) throws IOException {
        return objectMapper.readValue(inputStream, MAP_TYPE);
    }
}
