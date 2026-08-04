package com.example.converter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Everything the languages share: range checking, sign handling, splitting the
 * integer part into groups of three digits, reading the fraction digit by digit,
 * and assembling money.
 *
 * <p>Subclasses supply vocabulary and the two rules that actually differ between
 * languages: how a 1–999 group reads, and how a group joins its scale word.
 */
public abstract class AbstractNumberSpeller implements NumberSpeller {

    /** Highest scale index in use: 0=units, 1=thousand, ... 5=quadrillion. */
    protected static final int MAX_SCALE_INDEX = 5;

    private static final BigInteger THOUSAND = BigInteger.valueOf(1_000);
    private static final int MONEY_SCALE = 2;

    // ---------------------------------------------------------------- public API

    @Override
    public String spell(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        BigDecimal abs = requireInRange(value);

        String fractionDigits = fractionDigitsOf(abs);
        StringBuilder out = new StringBuilder(64);
        appendSign(out, value.signum());
        out.append(spellWhole(abs.toBigInteger()));

        if (!fractionDigits.isEmpty()) {
            out.append(' ').append(decimalWord());
            for (int i = 0; i < fractionDigits.length(); i++) {
                out.append(' ').append(digitWord(fractionDigits.charAt(i) - '0'));
            }
        }
        return out.toString();
    }

    @Override
    public String spellMoney(BigDecimal amount, MoneyUnit unit) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(unit, "unit");

        BigDecimal rounded = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal abs = requireInRange(rounded);

        BigInteger major = abs.toBigInteger();
        int minor = abs.subtract(new BigDecimal(major)).movePointRight(MONEY_SCALE).intValueExact();

        String body = minor == 0
                ? composeMoney(spellWhole(major), unit.major(major), null, null)
                : composeMoney(spellWhole(major), unit.major(major),
                               spellWhole(BigInteger.valueOf(minor)), unit.minor(minor));

        StringBuilder out = new StringBuilder(80);
        appendSign(out, rounded.signum());
        return out.append(body).toString();
    }

    // ------------------------------------------------------------------ internals

    /** Validates magnitude and returns the absolute value. */
    private BigDecimal requireInRange(BigDecimal value) {
        BigDecimal abs = value.abs();
        if (abs.compareTo(MAX_VALUE) > 0) {
            throw new NumberOutOfRangeException(value);
        }
        return abs;
    }

    private void appendSign(StringBuilder out, int signum) {
        if (signum < 0) {
            out.append(minusWord()).append(' ');
        }
    }

    /** Fraction digits as written, or "" when absent or all zeros. */
    private static String fractionDigitsOf(BigDecimal abs) {
        if (abs.scale() <= 0) {
            return "";
        }
        String plain = abs.toPlainString();
        int dot = plain.indexOf('.');
        if (dot < 0) {
            return "";
        }
        String digits = plain.substring(dot + 1);
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) != '0') {
                return digits;
            }
        }
        return "";
    }

    /**
     * Spells a non-negative integer by chunking it into groups of three digits
     * and gluing each non-zero group to its scale word.
     */
    protected String spellWhole(BigInteger whole) {
        if (whole.signum() == 0) {
            return zeroWord();
        }

        int[] groups = new int[MAX_SCALE_INDEX + 1];
        int count = 0;
        BigInteger rest = whole;
        while (rest.signum() > 0) {
            if (count > MAX_SCALE_INDEX) {
                // Unreachable through the public API — spell()/spellMoney() range-check
                // first — but keeps a direct spellWhole() call from silently overflowing.
                throw new NumberOutOfRangeException(new BigDecimal(whole));
            }
            BigInteger[] divRem = rest.divideAndRemainder(THOUSAND);
            groups[count++] = divRem[1].intValue();
            rest = divRem[0];
        }

        StringBuilder out = new StringBuilder(64);
        for (int scaleIndex = count - 1; scaleIndex >= 0; scaleIndex--) {
            int group = groups[scaleIndex];
            if (group == 0) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(spellGroup(group, scaleIndex));
        }
        return out.toString();
    }

    /**
     * Spells one 1–999 group together with its scale word. The default is
     * "&lt;group&gt; &lt;scale&gt;"; Indonesian overrides this for "seribu".
     *
     * @param group      1..999
     * @param scaleIndex 0=units, 1=thousand, 2=million, ...
     */
    protected String spellGroup(int group, int scaleIndex) {
        String words = spellBelowThousand(group);
        return scaleIndex == 0 ? words : words + ' ' + scaleWord(scaleIndex);
    }

    // --------------------------------------------------------- language vocabulary

    /** Word for 0. */
    protected abstract String zeroWord();

    /** Word placed before a negative value. */
    protected abstract String minusWord();

    /** Word for the decimal separator ("koma" / "point"). */
    protected abstract String decimalWord();

    /** Word for a single digit 0..9, used when reading out the fraction. */
    protected abstract String digitWord(int digit);

    /** Scale word for index 1..{@link #MAX_SCALE_INDEX} ("ribu" / "thousand", ...). */
    protected abstract String scaleWord(int scaleIndex);

    /** Spells 1..999 without any scale word. */
    protected abstract String spellBelowThousand(int value);

    /**
     * Joins the parts of a money phrase. {@code minorWords} and {@code minorUnit}
     * are null when the minor part is zero.
     */
    protected abstract String composeMoney(String majorWords, String majorUnit,
                                           String minorWords, String minorUnit);
}
