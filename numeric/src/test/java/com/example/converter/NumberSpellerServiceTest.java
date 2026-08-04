package com.example.converter;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Plain unit test — no Spring context needed, the service is just a constructor call.
 *
 * <p>The Indonesian expectations were cross-checked against 270 000 values from the
 * {@code num2words} reference implementation; see the README for the one deliberate
 * divergence ("satu juta seribu").
 */
class NumberSpellerServiceTest {

    private static final SpellLanguage ID = SpellLanguage.INDONESIAN;
    private static final SpellLanguage EN = SpellLanguage.ENGLISH;

    private final NumberSpellerService service = new NumberSpellerService(
            List.of(new IndonesianNumberSpeller(), new EnglishNumberSpeller()));

    @Nested
    @DisplayName("Indonesian")
    class Indonesian {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "0, nol",
                "1, satu",
                "7, tujuh",
                "9, sembilan",
                "10, sepuluh",
                "11, sebelas",
                "12, dua belas",
                "15, lima belas",
                "19, sembilan belas",
                "20, dua puluh",
                "21, dua puluh satu",
                "50, lima puluh",
                "99, sembilan puluh sembilan",
        })
        void spellsUnitsTeensAndTens(long value, String expected) {
            assertThat(service.spell(value, ID)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "100, seratus",
                "101, seratus satu",
                "110, seratus sepuluh",
                "111, seratus sebelas",
                "200, dua ratus",
                "250, dua ratus lima puluh",
                "999, sembilan ratus sembilan puluh sembilan",
        })
        void spellsHundredsWithSeratusContraction(long value, String expected) {
            assertThat(service.spell(value, ID)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "1000, seribu",
                "1001, seribu satu",
                "1100, seribu seratus",
                "1534, seribu lima ratus tiga puluh empat",
                "2000, dua ribu",
                "10000, sepuluh ribu",
                "11000, sebelas ribu",
                "100000, seratus ribu",
                "101000, seratus satu ribu",
        })
        void spellsThousandsWithSeribuContraction(long value, String expected) {
            assertThat(service.spell(value, ID)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "1000000,          satu juta",
                "21000000,         dua puluh satu juta",
                "1000000000,       satu miliar",
                "1000000001,       satu miliar satu",
                "1000000000000,    satu triliun",
                "1000000000000000, satu kuadriliun",
        })
        void spellsLargeScales(long value, String expected) {
            assertThat(service.spell(value, ID)).isEqualTo(expected);
        }

        @Test
        void contractsAThousandsGroupOfOneEvenAfterAHigherScale() {
            assertThat(service.spell(1_001_000L, ID)).isEqualTo("satu juta seribu");
            assertThat(service.spell(2_001_000L, ID)).isEqualTo("dua juta seribu");
        }

        @Test
        void spellsTheFullSixGroupMaximum() {
            assertThat(service.spell(1_234_567L, ID))
                    .isEqualTo("satu juta dua ratus tiga puluh empat ribu lima ratus enam puluh tujuh");
            assertThat(service.spell(999_999_999_999_999L, ID)).isEqualTo(
                    "sembilan ratus sembilan puluh sembilan triliun "
                            + "sembilan ratus sembilan puluh sembilan miliar "
                            + "sembilan ratus sembilan puluh sembilan juta "
                            + "sembilan ratus sembilan puluh sembilan ribu "
                            + "sembilan ratus sembilan puluh sembilan");
        }
    }

    @Nested
    @DisplayName("English")
    class English {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "0, zero",
                "1, one",
                "9, nine",
                "10, ten",
                "11, eleven",
                "13, thirteen",
                "15, fifteen",
                "19, nineteen",
                "20, twenty",
                "21, twenty-one",
                "40, forty",
                "99, ninety-nine",
        })
        void spellsUnitsTeensAndTens(long value, String expected) {
            assertThat(service.spell(value, EN)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "100, one hundred",
                "101, one hundred one",
                "115, one hundred fifteen",
                "250, two hundred fifty",
                "999, nine hundred ninety-nine",
        })
        void spellsHundredsWithoutAnd(long value, String expected) {
            assertThat(service.spell(value, EN)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "1000,             one thousand",
                "1534,             one thousand five hundred thirty-four",
                "10000,            ten thousand",
                "100000,           one hundred thousand",
                "1000000,          one million",
                "1000000000,       one billion",
                "1000000001,       one billion one",
                "1000000000000,    one trillion",
                "1000000000000000, one quadrillion",
        })
        void spellsThousandsAndLargeScales(long value, String expected) {
            assertThat(service.spell(value, EN)).isEqualTo(expected);
        }

        @Test
        void spellsTheFullSixGroupMaximum() {
            assertThat(service.spell(1_234_567L, EN))
                    .isEqualTo("one million two hundred thirty-four thousand five hundred sixty-seven");
            assertThat(service.spell(999_999_999_999_999L, EN)).isEqualTo(
                    "nine hundred ninety-nine trillion "
                            + "nine hundred ninety-nine billion "
                            + "nine hundred ninety-nine million "
                            + "nine hundred ninety-nine thousand "
                            + "nine hundred ninety-nine");
        }
    }

    @Nested
    @DisplayName("sign and decimals")
    class SignAndDecimals {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "-1,               minus satu",
                "-1534,            minus seribu lima ratus tiga puluh empat",
                "-1000000000000000, minus satu kuadriliun",
        })
        void prefixesNegativesWithMinus(long value, String expected) {
            assertThat(service.spell(value, ID)).isEqualTo(expected);
        }

        @Test
        void readsTheFractionDigitByDigitPreservingScale() {
            assertThat(service.spell(new BigDecimal("12.50"), ID)).isEqualTo("dua belas koma lima nol");
            assertThat(service.spell(new BigDecimal("12.50"), EN)).isEqualTo("twelve point five zero");
            assertThat(service.spell(new BigDecimal("12.5"), ID)).isEqualTo("dua belas koma lima");
            assertThat(service.spell(new BigDecimal("0.05"), ID)).isEqualTo("nol koma nol lima");
            assertThat(service.spell(new BigDecimal("0.05"), EN)).isEqualTo("zero point zero five");
            assertThat(service.spell(new BigDecimal("1534.505"), EN))
                    .isEqualTo("one thousand five hundred thirty-four point five zero five");
        }

        @Test
        void dropsAnAllZeroFraction() {
            assertThat(service.spell(new BigDecimal("12.00"), ID)).isEqualTo("dua belas");
            assertThat(service.spell(new BigDecimal("12.00"), EN)).isEqualTo("twelve");
            assertThat(service.spell(new BigDecimal("0.0"), ID)).isEqualTo("nol");
        }

        @Test
        void combinesSignAndFraction() {
            assertThat(service.spell(new BigDecimal("-1534.5"), ID))
                    .isEqualTo("minus seribu lima ratus tiga puluh empat koma lima");
        }
    }

    @Nested
    @DisplayName("currency")
    class Currency {

        @Test
        void spellsRupiahAndSen() {
            assertThat(service.spellMoney(new BigDecimal("1534.50"), ID))
                    .isEqualTo("seribu lima ratus tiga puluh empat rupiah lima puluh sen");
            assertThat(service.spellMoney(new BigDecimal("1000"), ID)).isEqualTo("seribu rupiah");
            assertThat(service.spellMoney(BigDecimal.ZERO, ID)).isEqualTo("nol rupiah");
            assertThat(service.spellMoney(new BigDecimal("-20.25"), ID))
                    .isEqualTo("minus dua puluh rupiah dua puluh lima sen");
        }

        @Test
        void spellsDollarsAndCentsWithSingularAgreement() {
            assertThat(service.spellMoney(new BigDecimal("1534.50"), EN))
                    .isEqualTo("one thousand five hundred thirty-four dollars and fifty cents");
            assertThat(service.spellMoney(new BigDecimal("1.00"), EN)).isEqualTo("one dollar");
            assertThat(service.spellMoney(new BigDecimal("2.00"), EN)).isEqualTo("two dollars");
            assertThat(service.spellMoney(new BigDecimal("1.01"), EN)).isEqualTo("one dollar and one cent");
            assertThat(service.spellMoney(BigDecimal.ZERO, EN)).isEqualTo("zero dollars");
            assertThat(service.spellMoney(new BigDecimal("-20.25"), EN))
                    .isEqualTo("minus twenty dollars and twenty-five cents");
        }

        @Test
        void roundsHalfUpToTwoDecimals() {
            assertThat(service.spellMoney(new BigDecimal("1.005"), EN)).isEqualTo("one dollar and one cent");
            assertThat(service.spellMoney(new BigDecimal("1.004"), EN)).isEqualTo("one dollar");
            assertThat(service.spellMoney(new BigDecimal("12.999"), ID)).isEqualTo("tiga belas rupiah");
        }

        @Test
        void acceptsAnExplicitCurrency() {
            assertThat(service.spellMoney(new BigDecimal("3.10"), EN, MoneyUnit.POUND))
                    .isEqualTo("three pounds and ten pence");
            assertThat(service.spellMoney(new BigDecimal("1.00"), EN, MoneyUnit.EURO))
                    .isEqualTo("one euro");
            assertThat(service.spellMoney(new BigDecimal("5"), ID, MoneyUnit.uninflected("dolar", "sen")))
                    .isEqualTo("lima dolar");
        }
    }

    @Nested
    @DisplayName("range and input validation")
    class Validation {

        @Test
        void acceptsTheMaximumExactly() {
            assertThat(service.spell(NumberSpeller.MAX_VALUE, ID)).isEqualTo("satu kuadriliun");
            assertThat(service.spell(NumberSpeller.MAX_VALUE.negate(), EN)).isEqualTo("minus one quadrillion");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "1000000000000001",
                "1000000000000000.01",
                "-1000000000000001",
                "9223372036854775807",
        })
        void rejectsAnythingLarger(String value) {
            assertThatExceptionOfType(NumberOutOfRangeException.class)
                    .isThrownBy(() -> service.spell(new BigDecimal(value), ID))
                    .withMessageContaining("out of range");
        }

        @Test
        void rejectsMoneyThatRoundsPastTheMaximum() {
            assertThatExceptionOfType(NumberOutOfRangeException.class)
                    .isThrownBy(() -> service.spellMoney(new BigDecimal("1000000000000000.005"), EN));
        }

        @Test
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() -> service.spell(null, ID));
            assertThatNullPointerException().isThrownBy(() -> service.spellMoney(new BigDecimal("1"), ID, null));
            assertThatNullPointerException().isThrownBy(() -> service.spell(BigDecimal.ONE, (SpellLanguage) null));
        }
    }

    @Nested
    @DisplayName("language resolution")
    class LanguageResolution {

        @ParameterizedTest
        @ValueSource(strings = {"id", "ID", "id-ID", "Indonesian", "  id  "})
        void acceptsIndonesianTags(String tag) {
            assertThat(service.spell(new BigDecimal("1000"), tag)).isEqualTo("seribu");
        }

        @ParameterizedTest
        @ValueSource(strings = {"en", "EN", "en-US", "en-GB", "English"})
        void acceptsEnglishTags(String tag) {
            assertThat(service.spell(new BigDecimal("1000"), tag)).isEqualTo("one thousand");
        }

        @ParameterizedTest
        @ValueSource(strings = {"fr", "jp", "", "   ", "xx-YY"})
        void rejectsUnknownTags(String tag) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.spell(new BigDecimal("1"), tag));
        }

        @Test
        void exposesWhatIsWiredIn() {
            assertThat(service.supportedLanguages()).containsExactlyInAnyOrder(ID, EN);
            assertThat(service.speller(ID)).isInstanceOf(IndonesianNumberSpeller.class);
        }

        @Test
        void rejectsTwoBeansClaimingTheSameLanguage() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new NumberSpellerService(
                            List.of(new IndonesianNumberSpeller(), new IndonesianNumberSpeller())))
                    .withMessageContaining("INDONESIAN");
        }

        @Test
        void rejectsAnEmptyBeanList() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new NumberSpellerService(List.of()));
        }
    }

    @Test
    @DisplayName("stateless: concurrent callers on virtual threads agree")
    void isThreadSafe() throws Exception {
        String expected = service.spell(987_654_321_098_765L, ID);
        AtomicBoolean mismatch = new AtomicBoolean(false);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 64; i++) {
                executor.submit(() -> {
                    for (int j = 0; j < 500; j++) {
                        if (!expected.equals(service.spell(987_654_321_098_765L, ID))) {
                            mismatch.set(true);
                        }
                    }
                });
            }
        }
        assertThat(mismatch).isFalse();
    }
}
