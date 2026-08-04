package com.example.converter;

import org.springframework.stereotype.Component;

/**
 * Indonesian ("terbilang") speller.
 *
 * <p>Handles the {@code se-} contractions Indonesian uses for a leading one:
 * <em>sepuluh</em> (10), <em>sebelas</em> (11), <em>seratus</em> (100),
 * <em>seribu</em> (1 000). From <em>juta</em> upward the formal form spells the
 * one out — "satu juta", not "sejuta".
 *
 * <pre>
 *          1_000 -> seribu
 *          1_534 -> seribu lima ratus tiga puluh empat
 *         11_000 -> sebelas ribu
 *      1_000_000 -> satu juta
 *      1_001_000 -> satu juta seribu
 *   -1_534.50    -> minus seribu lima ratus tiga puluh empat koma lima nol
 * </pre>
 *
 * <p><b>Note on a thousands group of 1 in the middle of a number.</b> This class
 * always contracts it — 1 001 000 reads "satu juta seribu". That matches the
 * common PUEBI/KBBI-aligned terbilang implementations, where the {@code se-}
 * prefix applies at every scale up to and including <em>ribu</em> regardless of
 * position. Some libraries instead emit "satu juta satu ribu". If your finance
 * team wants that variant, override {@link #spellGroup(int, int)} to drop the
 * contraction when the number has any higher-order group.
 */
@Component
public class IndonesianNumberSpeller extends AbstractNumberSpeller {

    private static final String[] UNITS = {
            "nol", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan"
    };

    /** Index matches the scale index: [1] = 10^3, [5] = 10^15. */
    private static final String[] SCALES = {
            "", "ribu", "juta", "miliar", "triliun", "kuadriliun"
    };

    private static final int THOUSAND_SCALE_INDEX = 1;

    @Override
    public SpellLanguage language() {
        return SpellLanguage.INDONESIAN;
    }

    @Override
    public MoneyUnit defaultMoneyUnit() {
        return MoneyUnit.RUPIAH;
    }

    @Override
    protected String zeroWord() {
        return "nol";
    }

    @Override
    protected String minusWord() {
        return "minus";
    }

    @Override
    protected String decimalWord() {
        return "koma";
    }

    @Override
    protected String digitWord(int digit) {
        return UNITS[digit];
    }

    @Override
    protected String scaleWord(int scaleIndex) {
        return SCALES[scaleIndex];
    }

    /** 1 000 is "seribu", never "satu ribu"; every other scale reads normally. */
    @Override
    protected String spellGroup(int group, int scaleIndex) {
        if (scaleIndex == THOUSAND_SCALE_INDEX && group == 1) {
            return "seribu";
        }
        return super.spellGroup(group, scaleIndex);
    }

    @Override
    protected String spellBelowThousand(int value) {
        StringBuilder out = new StringBuilder(32);

        int hundreds = value / 100;
        if (hundreds == 1) {
            out.append("seratus");
        } else if (hundreds > 1) {
            out.append(UNITS[hundreds]).append(" ratus");
        }

        int rest = value % 100;
        if (rest == 0) {
            return out.toString();
        }
        if (!out.isEmpty()) {
            out.append(' ');
        }

        if (rest < 10) {
            out.append(UNITS[rest]);
        } else if (rest == 10) {
            out.append("sepuluh");
        } else if (rest == 11) {
            out.append("sebelas");
        } else if (rest < 20) {
            out.append(UNITS[rest - 10]).append(" belas");
        } else {
            out.append(UNITS[rest / 10]).append(" puluh");
            if (rest % 10 != 0) {
                out.append(' ').append(UNITS[rest % 10]);
            }
        }
        return out.toString();
    }

    /** "seribu lima ratus tiga puluh empat rupiah lima puluh sen" */
    @Override
    protected String composeMoney(String majorWords, String majorUnit,
                                  String minorWords, String minorUnit) {
        StringBuilder out = new StringBuilder(80)
                .append(majorWords).append(' ').append(majorUnit);
        if (minorWords != null) {
            out.append(' ').append(minorWords).append(' ').append(minorUnit);
        }
        return out.toString();
    }
}
