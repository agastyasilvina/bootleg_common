package com.example.crud;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One element of the request body.
 *
 * <p><b>There is deliberately no {@code id} field.</b> That is what makes it impossible for a
 * client — or a careless mapper — to hand the persistence layer a primary key that did not come
 * out of the database. Every id used by the reconcile is read from {@code findByApplicationId}
 * inside the same transaction, so it always refers to a row that exists.
 *
 * <p>Rename {@code a}/{@code b}/{@code c} to whatever they actually are; they are carried through
 * from the sketch so the column names and this record stay in step.
 */
public record AccountDto(

        @NotBlank(message = "accountNumber is required")
        @Size(max = 64)
        String accountNumber,

        @Size(max = 255) String a,
        @Size(max = 255) String b,
        @Size(max = 255) String c) {
}
