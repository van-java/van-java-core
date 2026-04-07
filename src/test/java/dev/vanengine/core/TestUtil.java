package dev.vanengine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TestUtil {

    private TestUtil() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonNode json(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static JsonNode emptyObj() { return MAPPER.createObjectNode(); }

    public static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
