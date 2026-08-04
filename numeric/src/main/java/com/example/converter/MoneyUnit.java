package com.example.converter;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Names of a currency's major and minor unit, used by
 * {@link NumberSpeller#spellMoney(java.math.BigDecimal, MoneyUnit)}.
 *
 * <p>Singular and plural are separate because English needs both
 * ("one dollar" vs "two dollars"); Indonesian passes the same word twice.
 *
 * @param majorSingular e.g. {@code "dollar"}
 * @param majorPlural   e.g. {@code "dollars"}
 * @param minorSingular e.g. {@code "cent"}
 * @param minorPlural   e.g. {@code "cents"}
 */
public record MoneyUnit(String majorSingular,
                        String majorPlural,
                        String minorSingular,
                        String minorPlural) {

    public static final MoneyUnit RUPIAH = uninflected("rupiah", "sen");
    public static final MoneyUnit US_DOLLAR = new MoneyUnit("dollar", "dollars", "cent", "cents");
    public static final MoneyUnit EURO = new MoneyUnit("euro", "euros", "cent", "cents");
    public static final MoneyUnit POUND = new MoneyUnit("pound", "pounds", "penny", "pence");

    public MoneyUnit {
        Objects.requireNonNull(majorSingular, "majorSingular");
        Objects.requireNonNull(majorPlural, "majorPlural");
        Objects.requireNonNull(minorSingular, "minorSingular");
        Objects.requireNonNull(minorPlural, "minorPlural");
    }

    /** For languages without number inflection (Indonesian, Japanese, ...). */
    public static MoneyUnit uninflected(String major, String minor) {
        return new MoneyUnit(major, major, minor, minor);
    }

    public String major(BigInteger amount) {
        return BigInteger.ONE.equals(amount) ? majorSingular : majorPlural;
    }

    public String minor(int amount) {
        return amount == 1 ? minorSingular : minorPlural;
    }
}
