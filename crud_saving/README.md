# crud_saving — replace an application's accounts

`POST /applications/current/accounts` takes `[{accountNumber, a, b, c}]` and makes it the complete
set of `account_tt` rows for the application: **delete everything for that application, insert the
payload**, in one transaction.

No matching on `account_number`, no update path. That is the business decision, and it is what
keeps this to ~90 lines of service.

Spring Boot 3.5.x · Java 21 · Project Reactor · Spring Data R2DBC.

| File | Role |
|---|---|
| `AccountDto` | request/response element |
| `AccountEntity` | `account_tt` row — always new, so no id handling at all |
| `AccountRepository` | `deleteByApplicationTtId` + `saveAll` |
| `AccountService` | validate → delete → insert, one transaction |
| `AccountController` | `POST`, `@Validated` for element-level constraints |
| `AccountExceptionHandler` | 400 / 404 / 409 |
| `R2dbcAuditingConfig` | makes `@CreatedDate` fire |
| `db/migration/V1__account_tt.sql` | two constraints + one check to run before shipping |

## The one thing to check before shipping

**Replace-all deletes every account row on every call, so `account_tt_id` changes each time.**
If anything references those ids, this breaks — either loudly (FK blocks the delete and the
endpoint starts failing) or silently (`ON DELETE CASCADE` quietly drops the child rows).

Run this against the real database:

```sql
SELECT conrelid::regclass AS referencing_table, conname
FROM   pg_constraint
WHERE  confrelid = 'account_tt'::regclass;
```

Empty result → replace-all is safe. Anything else → it isn't, and the reconcile-by-account_number
version from earlier in this thread is what you want instead. Same question applies to anything
outside the database holding an account id: a client that cached one, a report keyed on it, an
audit trail.

`accountIdsAreNotStableAcrossPosts` in the test suite pins this deliberately — if that test ever
becomes inconvenient, something has started depending on stable ids.

## Before it runs

**`ApplicationProvider`** stands in for your `getApplicationMono()`. Delete it and inject yours.
`ApplicationEntity` is likewise a placeholder — the service only calls `getId()` on it, used as
`application_tt_id`.

**Apply the two constraints** in `V1__account_tt.sql`. The unique one still matters even under
replace-all: two concurrent posts each delete only what their own snapshot sees, then both
insert, so without it you get the union of both payloads rather than the last one.

**Check which Postgres driver you have.** 42.7.12 is the **JDBC** driver; R2DBC's is
`org.postgresql:r2dbc-postgresql` (1.x). Both together is normal (Flyway needs JDBC). If JDBC is
your *runtime* path, see the last section.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>r2dbc-postgresql</artifactId><scope>runtime</scope>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- test -->
<dependency>
  <groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope>
</dependency>
<dependency>
  <groupId>io.projectreactor</groupId><artifactId>reactor-test</artifactId><scope>test</scope>
</dependency>
```

**Package.** These are `com.example.crud`. Component scanning only reaches below your
`@SpringBootApplication` package — move them under yours or none of the beans exist.

## What is left that still matters

Most of the earlier complexity went away with the reconcile. Two things did not:

**The transaction is now more important, not less.** Delete-then-insert without one is strictly
worse than doing nothing — a failed insert leaves the application with *no* accounts. Hence
`@Transactional("connectionFactoryTransactionManager")`, named explicitly so that adding JPA or
JDBC to the classpath cannot silently bind it to the blocking manager. It also no-ops on
self-invocation.
→ `rollsBackToThePreviousAccountsWhenAnInsertFails`

**Validation must run before the delete, as a signal not a throw.**
`Mono.fromCallable(() -> validate(...))` — a throw before the chain escapes at assembly time,
bypasses `@RestControllerAdvice` and becomes a 500 instead of a 400.
→ `rejectsDuplicatesAsAnErrorSignalNotAnAssemblyTimeThrow`, whose first assertion calls the method
*without subscribing* — the only way to distinguish the two. Also
`rejectsBadPayloadWithoutDeletingAnything`.

Gone with the reconcile: `Persistable`, the `isNew` flag, the mutate-don't-rebuild rule, the
deferred constraint, and the prune-missing config. All of it existed to make in-place updates
safe, and there are no in-place updates any more.

## Smaller notes

**Empty array is rejected.** Under replace-all it coherently means "no accounts", but it is also
one stray call from wiping them. One `if` in `validate(...)` to remove.

**No `updated_at`** on the entity — rows are never updated, so it would always equal
`created_at`. Add the field back if your table declares that column NOT NULL.

**`a` / `b` / `c`** are carried from the sketch. Rename in `AccountDto`, `AccountEntity` and both
SQL files together.

**The rollback test** provokes a `VARCHAR(255)` overflow and expects
`DataIntegrityViolationException`. If your driver maps SQLSTATE 22001 differently, widen it to
`DataAccessException` — what matters is the row state afterwards, not the exception type.

## If your runtime is actually JDBC, not R2DBC

`@Transactional` on a `Mono` does nothing and `ReactiveCrudRepository` is unavailable. Keep
`validate(...)` and the DDL as they are; change the edges:

```java
@Transactional                                    // plain blocking — correct here
public List<AccountDto> replaceBlocking(Long applicationTtId, List<AccountDto> incoming) {
    validate(incoming);
    repo.deleteByApplicationTtId(applicationTtId);
    return repo.saveAll(incoming.stream()
                   .map(dto -> AccountEntity.create(applicationTtId, dto)).toList())
               .stream().map(AccountEntity::toDto).toList();
}

public Mono<List<AccountDto>> replace(List<AccountDto> incoming) {
    return applications.getApplicationMono()
            .switchIfEmpty(Mono.error(new ApplicationNotFoundException()))
            .flatMap(app -> Mono.fromCallable(() -> replaceBlocking(app.getId(), incoming))
                                .subscribeOn(Schedulers.boundedElastic()));
}
```

`boundedElastic` is not optional — without it the JDBC call runs on a Netty event loop and stalls
every other request on that thread. And `replaceBlocking` must be reached through the proxy, so if
it stays in the same class the transaction does not apply; move it to a collaborating bean.
