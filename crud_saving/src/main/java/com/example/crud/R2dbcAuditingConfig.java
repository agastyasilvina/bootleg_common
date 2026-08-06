package com.example.crud;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;

/**
 * Without this, {@code @CreatedDate} and {@code @LastModifiedDate} on the entity are inert and
 * both columns go out null — which the NOT NULL constraints in the DDL then reject. The failure
 * is at least loud rather than silent, but only if this is missing from the start; adding the
 * columns later without enabling auditing produces rows with null timestamps instead.
 *
 * <p>{@code Persistable.isNew()} also drives which of the two dates is populated, so this and the
 * entity's {@code isNew} flag have to stay consistent.
 */
@Configuration
@EnableR2dbcAuditing
public class R2dbcAuditingConfig {
}
