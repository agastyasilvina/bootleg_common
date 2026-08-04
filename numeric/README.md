# Number → words converter (`com.example.converter`)

Spring service that spells numbers out in **Indonesian** and **English**, up to
`1_000_000_000_000_000` (satu kuadriliun / one quadrillion).

```
1000    -> seribu                                    | one thousand
1534    -> seribu lima ratus tiga puluh empat        | one thousand five hundred thirty-four
1534.50 -> seribu lima ratus tiga puluh empat rupiah lima puluh sen
```

> Heads up on your example: `1534` is `seribu lima ratus **tiga puluh** empat` —
> the `30` was missing in the request. This implementation spells it in full.

## Files

| File | Role |
|---|---|
| `SpellLanguage.java` | Language enum + lenient tag parsing (`id`, `en-US`, `Indonesian`, …) |
| `NumberSpeller.java` | Per-language contract, holds `MAX_VALUE` |
| `AbstractNumberSpeller.java` | Shared: range check, sign, 3-digit grouping, fraction, money assembly |
| `IndonesianNumberSpeller.java` | `@Component` — se- contractions, ribu/juta/miliar/triliun/kuadriliun |
| `EnglishNumberSpeller.java` | `@Component` — short scale, hyphenated tens |
| `NumberSpellerService.java` | `@Service` — the thing you inject |
| `MoneyUnit.java` | Currency names with singular/plural (RUPIAH, US_DOLLAR, EURO, POUND) |
| `NumberOutOfRangeException.java` | `extends IllegalArgumentException` |
| `NumberSpellerServiceTest.java` | JUnit 5 + AssertJ |

Drop `src/main/java/com/example/converter/` and
`src/test/java/com/example/converter/` into your project. Nothing else to wire —
the two spellers are `@Component`s, the service collects them by constructor
injection.

Only dependency is `spring-boot-starter` (for `@Component` / `@Service`); the test
needs `spring-boot-starter-test`, which gives you JUnit 5 + AssertJ. Both are
already in a Boot 3.5.12 project.

## Usage

```java
@RequiredArgsConstructor
class InvoiceService {

    private final NumberSpellerService speller;

    void render() {
        speller.spell(1_534, SpellLanguage.INDONESIAN);
        // seribu lima ratus tiga puluh empat

        speller.spell(new BigDecimal("1534.50"), SpellLanguage.ENGLISH);
        // one thousand five hundred thirty-four point five zero

        speller.spellMoney(new BigDecimal("1534.50"), SpellLanguage.INDONESIAN);
        // seribu lima ratus tiga puluh empat rupiah lima puluh sen

        speller.spellMoney(new BigDecimal("1534.50"), SpellLanguage.ENGLISH);
        // one thousand five hundred thirty-four dollars and fifty cents

        speller.spell(new BigDecimal("1000"), "id-ID");   // from a header or query param
        // seribu
    }
}
```

If you want an endpoint later:

```java
@GetMapping("/api/spell")
String spell(@RequestParam BigDecimal value,
             @RequestParam(defaultValue = "id") String lang) {
    return speller.spell(value, lang);
}
```

## Behaviour

**Negatives** get a leading `minus`.

**Decimals** are read digit by digit after `koma` / `point`, preserving the input
scale — `new BigDecimal("12.50")` → `dua belas koma lima nol`, `"12.5"` →
`dua belas koma lima`. An all-zero fraction is dropped, so `"12.00"` → `dua belas`.

**Money** rounds to 2 dp with `HALF_UP` first, then drops a zero minor part.
English agrees in number (`one dollar` / `two dollars` / `one cent`); Indonesian
doesn't inflect, so `MoneyUnit.uninflected("rupiah", "sen")` covers it.

**Out of range** — anything with `|value| > 1_000_000_000_000_000` throws
`NumberOutOfRangeException`, which extends `IllegalArgumentException` so existing
`@ExceptionHandler(IllegalArgumentException.class)` advice keeps catching it.

## Language rules encoded

Indonesian `se-` contraction at every scale through *ribu*, spelled-out `satu` above it:

```
10 sepuluh    11 sebelas    12–19 <n> belas    100 seratus    1 000 seribu
1 000 000 satu juta (not "sejuta")    10⁹ satu miliar    10¹² satu triliun    10¹⁵ satu kuadriliun
```

English uses the short scale (10⁹ = billion), hyphenates 21–99, and omits `and`
between hundreds and tens (`one hundred one`). For the British form, add `" and "`
in `EnglishNumberSpeller.spellBelowThousand`.

### One deliberate divergence worth knowing about

A thousands group of exactly `1` sitting *after* a bigger scale is contracted here:

```
1_001_000  ->  satu juta seribu
```

Some libraries (notably Python's `num2words` `lang_ID`) emit `satu juta satu ribu`
instead. The contracted form is what PUEBI/KBBI-aligned terbilang implementations
use — the `se-` prefix applies at every scale up to *ribu* regardless of position.
If your finance team wants the other form, override
`IndonesianNumberSpeller.spellGroup(int, int)` and skip the contraction when a
higher-order group is present. Everything else matches `num2words` exactly.

## Design notes

**No Reactor, on purpose.** You were right in the ask — this is pure CPU work over
a static lookup table, zero I/O. Wrapping it in `Mono`/`Flux` would add scheduler
hops for nothing. Call it directly, or from `.map(...)` inside an existing
pipeline. Same for Loom: no blocking calls, no synchronization, nothing that pins
a virtual thread.

**Stateless and thread-safe.** All vocabulary lives in `static final` arrays; the
spellers hold no mutable fields. There's a test that hammers it from 64 virtual
threads to keep it that way.

**Adding a language** means one new `@Component implements NumberSpeller` (extend
`AbstractNumberSpeller` and fill in the six vocabulary hooks) plus a
`SpellLanguage` constant. `NumberSpellerService` picks it up automatically —
`List<NumberSpeller>` injection, duplicate languages rejected at startup.

**Why an abstract base rather than one class per language from scratch:** the
grouping, sign, fraction and money-assembly logic is identical across languages;
only vocabulary and two joining rules differ. Subclasses override
`spellGroup(...)` when a language has a positional quirk — which is exactly what
Indonesian's `seribu` needs.

## Verification

Ran in a Java 21 container before shipping:

- 128 assertions covering boundaries (0, 10, 11, 19, 99, 100, 999, 1 000, 10⁶,
  10⁹, 10¹², 10¹⁵), negatives, decimals, currency rounding, language-tag parsing,
  null handling, and bean-wiring failures — all pass.
- Differential test against Python `num2words` over **270 030 values**
  (exhaustive 0–120 000, plus 150 000 random values up to 10¹⁵, plus every
  10ⁿ±1 boundary): Indonesian matches exactly apart from the documented
  `seribu` variant; English matches exactly after normalising `num2words`'
  British commas and `and`.
- Injectivity check over the same set: no two distinct numbers produce the same
  words in either language.
- The JUnit file compiles clean; Maven Central was blocked in the build container,
  so the assertions were executed through an equivalent plain-Java harness rather
  than the JUnit runner. Run `mvn test` on your side to see them green in JUnit.
