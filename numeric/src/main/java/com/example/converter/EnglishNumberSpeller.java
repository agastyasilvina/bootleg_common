package com.example.converter;

import org.springframework.stereotype.Component;

/**
 * English speller, short scale (US/modern-UK: 10^9 = billion), tens hyphenated,
 * no "and" between hundreds and tens.
 *
 * <pre>
 *          1_000 -> one thousand
 *          1_534 -> one thousand five hundred thirty-four
 *      1_000_000 -> one million
 *   -1_534.50    -> minus one thousand five hundred thirty-four point five zero
 * </pre>
 */
@Component
public class EnglishNumberSpeller extends AbstractNumberSpeller {

    private static final String[] ONES = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
    };

    /** Index by tens digit: [2] = twenty ... [9] = ninety. */
    private static final String[] TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };

    /** Index matches the scale index: [1] = 10^3, [5] = 10^15. */
    private static final String[] SCALES = {
            "", "thousand", "million", "billion", "trillion", "quadrillion"
    };

    @Override
    public SpellLanguage language() {
        return SpellLanguage.ENGLISH;
    }

    @Override
    public MoneyUnit defaultMoneyUnit() {
        return MoneyUnit.US_DOLLAR;
    }

    @Override
    protected String zeroWord() {
        return "zero";
    }

    @Override
    protected String minusWord() {
        return "minus";
    }

    @Override
    protected String decimalWord() {
        return "point";
    }

    @Override
    protected String digitWord(int digit) {
        return ONES[digit];
    }

    @Override
    protected String scaleWord(int scaleIndex) {
        return SCALES[scaleIndex];
    }

    @Override
    protected String spellBelowThousand(int value) {
        StringBuilder out = new StringBuilder(32);

        int hundreds = value / 100;
        if (hundreds > 0) {
            out.append(ONES[hundreds]).append(" hundred");
        }

        int rest = value % 100;
        if (rest == 0) {
            return out.toString();
        }
        if (!out.isEmpty()) {
            out.append(' ');
        }

        if (rest < 20) {
            out.append(ONES[rest]);
        } else {
            out.append(TENS[rest / 10]);
            if (rest % 10 != 0) {
                out.append('-').append(ONES[rest % 10]);
            }
        }
        return out.toString();
    }

    /** "one thousand five hundred thirty-four dollars and fifty cents" */
    @Override
    protected String composeMoney(String majorWords, String majorUnit,
                                  String minorWords, String minorUnit) {
        StringBuilder out = new StringBuilder(80)
                .append(majorWords).append(' ').append(majorUnit);
        if (minorWords != null) {
            out.append(" and ").append(minorWords).append(' ').append(minorUnit);
        }
        return out.toString();
    }
}
