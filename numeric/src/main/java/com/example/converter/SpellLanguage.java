package com.example.converter;

import java.util.Locale;

/**
 * Languages supported by {@link NumberSpellerService}.
 *
 * <p>Adding a language means adding a constant here and a matching
 * {@link NumberSpeller} bean — nothing else has to change.
 */
public enum SpellLanguage {

    INDONESIAN("id"),
    ENGLISH("en");

    private final String tag;

    SpellLanguage(String tag) {
        this.tag = tag;
    }

    /** ISO 639-1 tag, e.g. {@code "id"}. */
    public String tag() {
        return tag;
    }

    public Locale locale() {
        return Locale.forLanguageTag(tag);
    }

    /**
     * Lenient lookup for values arriving from HTTP params, headers or config.
     * Accepts {@code "id"}, {@code "ID"}, {@code "id-ID"}, {@code "indonesian"},
     * {@code "en"}, {@code "en-US"}, {@code "english"}.
     *
     * @throws IllegalArgumentException if the tag maps to no supported language
     */
    public static SpellLanguage fromTag(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Language must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int dash = normalized.indexOf('-');
        String primary = dash < 0 ? normalized : normalized.substring(0, dash);
        for (SpellLanguage language : values()) {
            if (language.tag.equals(primary) || language.name().toLowerCase(Locale.ROOT).equals(primary)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unsupported language: " + value);
    }
}
