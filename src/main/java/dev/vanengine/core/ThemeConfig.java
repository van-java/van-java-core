package dev.vanengine.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration loaded from theme.json in a theme directory.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ThemeConfig {

    @JsonProperty("global_name")
    private String globalName;

    public String getGlobalName() {
        return globalName;
    }

    public void setGlobalName(String globalName) {
        this.globalName = globalName;
    }
}
