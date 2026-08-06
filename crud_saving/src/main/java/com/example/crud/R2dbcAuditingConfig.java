package com.example.crud;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;

/**
 * Without this, {@code @CreatedDate} on {@link AccountEntity} is inert and {@code created_at} goes
 * out null — which the NOT NULL column then rejects. Loud rather than silent, at least.
 *
 * <p>Only {@code @CreatedDate} is in play: rows are inserted and never updated, so there is no
 * {@code @LastModifiedDate} to populate.
 */
@Configuration
@EnableR2dbcAuditing
public class R2dbcAuditingConfig {
}
