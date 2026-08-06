package com.example.crud;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A row of {@code account_tt}, hanging off an {@code application_tt} row that already exists by
 * the time this endpoint is called.
 *
 * <p>Implements {@link Persistable} so INSERT vs UPDATE is <b>stated</b> rather than inferred.
 * By default Spring Data R2DBC decides from whether the id is null, which fails in two directions:
 * a rebuilt entity with a null id gets INSERTed and duplicates the row, and an entity carrying an
 * id that is not in the table issues an UPDATE matching zero rows. Depending on the Spring Data
 * version the second case either throws {@code TransientDataAccessResourceException} or passes
 * silently — {@code AccountServiceTest.updatesInPlaceRatherThanReinserting} pins it either way.
 *
 * <p>{@link #create} is the only place {@code isNew} is set true and {@link #apply} is the only
 * mutator. Rows loaded through the mapper keep the {@code false} default, so anything read from
 * the database updates in place and retains its {@code account_tt_id} and {@code created_at}.
 */
@Table("account_tt")
public class AccountEntity implements Persistable<Long> {

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

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    /** Not a column. Entities are built per request and never reused across them. */
    @Transient
    private boolean isNew = false;

    /** The only way to build a row that will be INSERTed. */
    public static AccountEntity create(Long applicationTtId, AccountDto dto) {
        AccountEntity e = new AccountEntity();
        e.isNew = true;
        e.applicationTtId = applicationTtId;
        return e.apply(dto);
    }

    /**
     * Copies the mutable fields across. Note {@code applicationTtId} and {@code accountTtId} are
     * deliberately not touched — an existing row is edited, never re-parented or re-keyed.
     */
    public AccountEntity apply(AccountDto dto) {
        this.accountNumber = dto.accountNumber();
        this.a = dto.a();
        this.b = dto.b();
        this.c = dto.c();
        return this;
    }

    public AccountDto toDto() {
        return new AccountDto(accountNumber, a, b, c);
    }

    /** {@code Persistable<Long>} — the id here is {@code account_tt_id}. */
    @Override public Long getId() { return accountTtId; }
    @Override public boolean isNew() { return isNew; }

    public Long getAccountTtId()     { return accountTtId; }
    public Long getApplicationTtId() { return applicationTtId; }
    public String getAccountNumber() { return accountNumber; }
    public String getA() { return a; }
    public String getB() { return b; }
    public String getC() { return c; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
