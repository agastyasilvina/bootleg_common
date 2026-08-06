# crud_saving — save an array of accounts onto an existing application

`PUT /applications/current/accounts` takes `[{accountNumber, a, b, c}]` and writes them to
`account_tt` under an `application_tt` row that already exists.

Matching is by **`account_number` within the application**:

- account number **not yet on the application** → new `account_tt` row
- account number **already there** → that row is updated in place, keeping its `account_tt_id`
- account number **stored but absent from the payload** → **left alone** (see *Pruning* below)

Spring Boot 3.5.x · Java 21 · Project Reactor · Spring Data R2DBC.

| File | Role |
|---|---|
| `AccountDto` | request/response element — **no id field**, deliberately |
| `AccountEntity` | `account_tt` row; `Persistable` so INSERT vs UPDATE is stated, not inferred |
| `AccountRepository` | `findByApplicationTtId` — the only source of ids |
| `AccountService` | validation + upsert, in one transaction |
| `AccountController` | `PUT`, `@Validated` for element-level constraints |
| `AccountExceptionHandler` | 400 / 404 / 409 |
| `R2dbcAuditingConfig` | makes `@CreatedDate` / `@LastModifiedDate` actually fire |
| `db/migration/V1__account_tt.sql` | the two constraints to add to your existing table |

## Pruning — the one decision left open

Your earlier note said you might "remove/update" rows inserted the first time, but this message
described only *insert when not found*. Those are different endpoints, so I defaulted to the
**non-destructive** one: nothing is ever deleted.

If you do want the payload to be the complete set — anything missing from it deleted — flip:

```yaml
account:
  sync:
    prune-missing: true
```

That makes a partial payload silently drop the rest, so only turn it on if the client genuinely
always sends the full list. **Tell me which you want and I'll delete the other path** — a live
config flag on destructive behaviour is worse than a decision.

Either way an empty array is rejected outright rather than treated as "delete everything".

## Before it runs

**`ApplicationProvider`** stands in for your `getApplicationMono()`. Delete it and inject yours.
`ApplicationEntity` is likewise a placeholder — the service only calls `getId()` on it, which it
uses as `application_tt_id`.

**Apply the two constraints** in `V1__account_tt.sql` to the live `account_tt`. The unique one is
load-bearing; see the comments in that file for why application code cannot substitute for it.

**Check which Postgres driver you have.** 42.7.12 is the **JDBC** driver; R2DBC's is
`org.postgresql:r2dbc-postgresql` (1.x). Both together is normal (Flyway needs JDBC). But if JDBC
is your *runtime* path, see the last section — reactive `@Transactional` does nothing there.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>r2dbc-postgresql</artifactId>
  <scope>runtime</scope>
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

## Why it is shaped this way

Four failure modes drove the design. Each has a structural prevention *and* a test that goes red
if the prevention is removed (`AccountServiceTest`).

**No id on the DTO.** A zero-row `UPDATE` only happens when an id reaches `save()` that did not
come from the database. With no id field, none can exist: every `account_tt_id` in the service was
read by `findByApplicationTtId` in the same transaction. `Persistable.isNew()` then makes
insert-vs-update explicit rather than inferred from a null id.
→ `updatesInPlaceRatherThanReinserting`

**`UNIQUE (application_tt_id, account_number) DEFERRABLE INITIALLY DEFERRED`.** Two concurrent
requests can both read "111 is not there yet" before either commits; only the database can stop
the double insert. Deferring the check to COMMIT also means delete-before-insert ordering is not
load-bearing.
→ `insertsAccountNumbersThatAreNotYetOnTheApplication`

**Named transaction manager.** `@Transactional("connectionFactoryTransactionManager")` — with JPA
or JDBC also on the classpath, an unqualified `@Transactional` can bind to the blocking manager
and do nothing. It also silently no-ops on self-invocation.
→ `rollsBackTheWholeBatchWhenOneWriteFails`

**Validation inside the chain.** `Mono.fromCallable(() -> index(...))`, not a bare call. A throw
before the chain escapes at assembly time, bypasses `@RestControllerAdvice`, and becomes a 500
instead of a 400.
→ `rejectsDuplicatesAsAnErrorSignalNotAnAssemblyTimeThrow`, whose first assertion calls the
method *without subscribing* — the only way to tell the two apart.

## Smaller notes

**`a` / `b` / `c`** are carried from the sketch. Rename in `AccountDto`, `AccountEntity` and both
SQL files together.

**The rollback test** provokes a `VARCHAR(255)` overflow and expects
`DataIntegrityViolationException`. If your driver maps SQLSTATE 22001 differently, widen it to
`DataAccessException` — what matters is the row state afterwards, not the exception type.

Consider [BlockHound](https://github.com/reactor/BlockHound) in test scope: it fails any test that
blocks an event-loop thread, catching a JDBC call slipping into a reactive path.

## If your runtime is actually JDBC, not R2DBC

`@Transactional` on a `Mono` does nothing and `ReactiveCrudRepository` is unavailable. Keep
`index(...)`, the upsert logic and the DDL exactly as they are; change only the edges:

```java
@Service
public class AccountService {

    private final AccountJpaRepository repo;   // blocking JpaRepository

    /** Plain blocking @Transactional — correct here, unlike the reactive variant. */
    @Transactional
    public List<AccountDto> saveBlocking(Long applicationTtId, List<AccountDto> incoming) {
        Map<String, AccountDto> desired = index(incoming);
        List<AccountEntity> existing = repo.findByApplicationTtId(applicationTtId);
        // ... identical upsert, minus the Monos
    }

    public Mono<List<AccountDto>> save(List<AccountDto> incoming) {
        return applications.getApplicationMono()
                .switchIfEmpty(Mono.error(new ApplicationNotFoundException()))
                .flatMap(app -> Mono.fromCallable(() -> saveBlocking(app.getId(), incoming))
                                    .subscribeOn(Schedulers.boundedElastic()));
    }
}
```

`boundedElastic` is not optional — without it the JDBC call runs on a Netty event loop and stalls
every other request on that thread. And `saveBlocking` must be reached through the proxy, so if it
stays in this class the transaction does not apply; move it to a collaborating bean.
