package dev.vanengine.core.i18n;

/**
 * Thrown when a translation key is missing and strategy is {@link MissingKeyStrategy#THROW_EXCEPTION}.
 */
public class MissingTranslationException extends RuntimeException {

    private final String key;
    private final String locale;

    public MissingTranslationException(String key, String locale) {
        super("Missing i18n key '" + key + "' for locale '" + locale + "'");
        this.key = key;
        this.locale = locale;
    }

    public String getKey() {
        return key;
    }

    public String getLocale() {
        return locale;
    }
}
