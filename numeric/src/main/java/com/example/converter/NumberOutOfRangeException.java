package com.example.converter;

import java.math.BigDecimal;

/**
 * Thrown when a value's magnitude exceeds {@link NumberSpeller#MAX_VALUE}.
 *
 * <p>Extends {@link IllegalArgumentException} so existing
 * {@code @ExceptionHandler(IllegalArgumentException.class)} advice keeps working.
 */
public class NumberOutOfRangeException extends IllegalArgumentException {

    private final BigDecimal value;

    public NumberOutOfRangeException(BigDecimal value) {
        super("Value out of range: |" + value.toPlainString() + "| > "
                + NumberSpeller.MAX_VALUE.toPlainString());
        this.value = value;
    }

    public BigDecimal value() {
        return value;
    }
}
