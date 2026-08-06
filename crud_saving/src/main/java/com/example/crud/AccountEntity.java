package com.example.crud;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A row of {@code account_tt}.
 *
 * <p>Every instance is new. The endpoint deletes the application's accounts and reinserts them, so
 * no row is ever loaded and updated — which is why there is no {@code Persistable}, no
 * {@code isNew} flag and no mutator here. A null {@code account_tt_id} is all Spring Data R2DBC
 * needs to choose INSERT, and it is null by construction.
 *
 * <p>There is deliberately no {@code updated_at}: rows are never updated, so it would only ever
 * equal {@code created_at}. Add the field back if your table declares that column NOT NULL.
 */
@Table("account_tt")
public class AccountEntity {

    @Id
    @Column("account_tt_id")
    private Long accountTtId;

    @Column("application_tt_id")
    private Long applicationTtId;

    @Column("account_number")
    private String accountNumber;

    @Column("a") private String a;
    @Column("b") private String b;
    @Column("c") private String c;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    public static AccountEntity create(Long applicationTtId, AccountDto dto) {
        AccountEntity e = new AccountEntity();
        e.applicationTtId = applicationTtId;      // account_tt_id stays null -> INSERT
        e.accountNumber = dto.accountNumber();
        e.a = dto.a();
        e.b = dto.b();
        e.c = dto.c();
        return e;
    }

    public AccountDto toDto() {
        return new AccountDto(accountNumber, a, b, c);
    }

    public Long getAccountTtId()     { return accountTtId; }
    public Long getApplicationTtId() { return applicationTtId; }
    public String getAccountNumber() { return accountNumber; }
    public String getA() { return a; }
    public String getB() { return b; }
    public String getC() { return c; }
    public Instant getCreatedAt() { return createdAt; }
}
