package dev.vanengine.core.i18n;

/**
 * Strategy for handling missing i18n translation keys.
 */
public enum MissingKeyStrategy {

    /** Return the key path as the value (e.g. "nav.home"). */
    RETURN_KEY,

    /** Log a warning and return the key path. */
    WARN_AND_RETURN_KEY,

    /** Throw a {@link MissingTranslationException}. */
    THROW_EXCEPTION,

    /** Return a placeholder string like "[missing: nav.home]". */
    RETURN_PLACEHOLDER
}
