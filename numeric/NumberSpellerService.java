package com.example.converter;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * Entry point for spelling numbers out in words.
 *
 * <p>Resolves the right {@link NumberSpeller} for a language and delegates.
 * Every {@code NumberSpeller} bean in the context is picked up at construction,
 * so adding a language needs no change here.
 *
 * <pre>{@code
 * service.spell(1_534, SpellLanguage.INDONESIAN);
 * // seribu lima ratus tiga puluh empat
 *
 * service.spell(new BigDecimal("1534.50"), SpellLanguage.ENGLISH);
 * // one thousand five hundred thirty-four point five zero
 *
 * service.spellMoney(new BigDecimal("1534.50"), SpellLanguage.INDONESIAN);
 * // seribu lima ratus tiga puluh empat rupiah lima puluh sen
 * }</pre>
 *
 * <p>Stateless and thread-safe. Pure CPU work over an in-memory lookup table —
 * no I/O, so there is nothing to offload; call it straight from a virtual thread
 * or from inside {@code Mono.map(...)}.
 */
@Service
public class NumberSpellerService {

    private final Map<SpellLanguage, NumberSpeller> spellers;

    public NumberSpellerService(List<NumberSpeller> spellers) {
        Objects.requireNonNull(spellers, "spellers");
        if (spellers.isEmpty()) {
            throw new IllegalStateException("No NumberSpeller beans found");
        }

        Map<SpellLanguage, NumberSpeller> byLanguage = new EnumMap<>(SpellLanguage.class);
        for (NumberSpeller speller : spellers) {
            NumberSpeller previous = byLanguage.put(speller.language(), speller);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two NumberSpeller beans claim " + speller.language() + ": "
                                + previous.getClass().getName() + " and " + speller.getClass().getName());
            }
        }
        this.spellers = Collections.unmodifiableMap(byLanguage);
    }

    // ------------------------------------------------------------------- spelling

    /**
     * Spells {@code value} in {@code language}.
     *
     * @throws NumberOutOfRangeException if {@code |value| > }{@link NumberSpeller#MAX_VALUE}
     * @throws IllegalArgumentException  if the language has no registered speller
     */
    public String spell(BigDecimal value, SpellLanguage language) {
        return speller(language).spell(value);
    }

    /** Convenience overload for whole numbers. */
    public String spell(long value, SpellLanguage language) {
        return speller(language).spell(value);
    }

    /** Overload for a language tag off a request param or {@code Accept-Language} header. */
    public String spell(BigDecimal value, String languageTag) {
        return spell(value, SpellLanguage.fromTag(languageTag));
    }

    /** Overload for a language tag off a request param or {@code Accept-Language} header. */
    public String spell(long value, String languageTag) {
        return spell(value, SpellLanguage.fromTag(languageTag));
    }

    // ------------------------------------------------------------------- currency

    /** Spells {@code amount} as money using the language's default currency. */
    public String spellMoney(BigDecimal amount, SpellLanguage language) {
        return speller(language).spellMoney(amount);
    }

    /** Spells {@code amount} as money using an explicit currency. */
    public String spellMoney(BigDecimal amount, SpellLanguage language, MoneyUnit unit) {
        return speller(language).spellMoney(amount, unit);
    }

    // ------------------------------------------------------------------- registry

    /** The speller registered for {@code language}. */
    public NumberSpeller speller(SpellLanguage language) {
        Objects.requireNonNull(language, "language");
        NumberSpeller speller = spellers.get(language);
        if (speller == null) {
            throw new IllegalArgumentException("No NumberSpeller registered for " + language
                    + "; available: " + supportedLanguages().stream()
                    .map(SpellLanguage::tag).collect(Collectors.joining(", ")));
        }
        return speller;
    }

    /** Languages that currently have a speller wired in. */
    public Set<SpellLanguage> supportedLanguages() {
        return spellers.keySet();
    }
}
