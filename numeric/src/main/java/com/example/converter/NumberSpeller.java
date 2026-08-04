package com.example.converter;

import java.math.BigDecimal;

/**
 * Spells a number out in words for one language.
 *
 * <p>Implementations are stateless singletons and safe to share across platform
 * and virtual threads. They do no I/O, so there is nothing to make reactive:
 * calling one from a Reactor pipeline with {@code map(...)} is correct and cheap.
 *
 * <p>To add a language, implement this interface and annotate the class with
 * {@code @Component}; {@link NumberSpellerService} discovers it automatically.
 */
public interface NumberSpeller {

    /**
     * Largest magnitude that can be spelled: 1_000_000_000_000_000
     * ("satu kuadriliun" / "one quadrillion"). Values beyond this — in either
     * direction — are rejected with {@link NumberOutOfRangeException}.
     */
    BigDecimal MAX_VALUE = new BigDecimal("1000000000000000");

    /** The language this speller handles. */
    SpellLanguage language();

    /**
     * Spells {@code value} in words.
     *
     * <p>Negatives get a leading "minus". A non-zero fractional part is read
     * digit by digit after the decimal word, preserving the scale of the input:
     * {@code new BigDecimal("12.50")} becomes "dua belas koma lima nol".
     * A fractional part that is all zeros is dropped, so {@code "12.00"}
     * becomes plain "dua belas".
     *
     * @throws NullPointerException        if {@code value} is null
     * @throws NumberOutOfRangeException   if {@code |value| > }{@link #MAX_VALUE}
     */
    String spell(BigDecimal value);

    /** Convenience overload for primitive input. */
    default String spell(long value) {
        return spell(BigDecimal.valueOf(value));
    }

    /**
     * Spells {@code amount} as money. The amount is rounded to 2 decimal places
     * with {@link java.math.RoundingMode#HALF_UP} first; a zero minor part is omitted.
     *
     * @throws NullPointerException      if either argument is null
     * @throws NumberOutOfRangeException if the rounded magnitude exceeds {@link #MAX_VALUE}
     */
    String spellMoney(BigDecimal amount, MoneyUnit unit);

    /** Spells {@code amount} using this language's default currency. */
    default String spellMoney(BigDecimal amount) {
        return spellMoney(amount, defaultMoneyUnit());
    }

    /** Currency assumed when none is given — rupiah for Indonesian, dollars for English. */
    MoneyUnit defaultMoneyUnit();
}
