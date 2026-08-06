package com.example.crud;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

/**
 * Saves an array of accounts against an {@code application_tt} row that already exists.
 *
 * <p>Matching is by {@code account_number} <i>within the application</i>: an account number
 * already on that application is updated in place, one that is not yet there becomes a new
 * {@code account_tt} row. Calling it twice with the same body changes nothing.
 *
 * <p><b>By default nothing is deleted.</b> An account already stored but missing from the payload
 * is left alone. See {@link #pruneMissing} — that is the one behaviour here you may want to flip,
 * and it is off because the wrong choice in this direction destroys data.
 */
@Service
public class AccountService {

    private final AccountRepository repo;
    private final ApplicationProvider applications;

    /**
     * When true, accounts absent from the payload are deleted, making the request a full
     * replace of the application's accounts rather than an upsert.
     *
     * <p>Off by default. With it on, a client that sends a partial list silently loses the rest,
     * and a client that sends {@code []} loses everything — which is why {@code index(...)} also
     * rejects an empty payload outright.
     */
    private final boolean pruneMissing;

    public AccountService(AccountRepository repo,
                          ApplicationProvider applications,
                          @Value("${account.sync.prune-missing:false}") boolean pruneMissing) {
        this.repo = repo;
        this.applications = applications;
        this.pruneMissing = pruneMissing;
    }

    /**
     * The transaction manager is named explicitly. Spring Boot's R2DBC autoconfiguration registers
     * it as {@code connectionFactoryTransactionManager}; naming it means that if JPA or plain JDBC
     * joins the classpath, this keeps binding to the reactive manager rather than silently picking
     * the blocking one and doing nothing. A wrong name fails at startup, which is the point.
     *
     * <p>This only works because the repository is R2DBC — reactive {@code @Transactional} binds
     * to the subscriber context and has no effect at all on a JDBC/JPA repository.
     */
    @Transactional("connectionFactoryTransactionManager")
    public Mono<List<AccountDto>> save(List<AccountDto> incoming) {
        // fromCallable, not a plain call: index() throws, and throwing here rather than inside the
        // chain escapes at assembly time — before any subscription — which bypasses onError and
        // surfaces as a 500 instead of the 400 the advice maps it to.
        return Mono.fromCallable(() -> index(incoming))
                .flatMap(desired -> applications.getApplicationMono()
                        .switchIfEmpty(Mono.error(new ApplicationNotFoundException()))
                        .flatMap(app -> upsert(app.getId(), desired)));
    }

    private Mono<List<AccountDto>> upsert(Long applicationTtId, Map<String, AccountDto> desired) {
        return repo.findByApplicationTtId(applicationTtId)
                .collectList()
                .flatMap(existing -> {

                    Map<String, AccountEntity> byAccountNumber = existing.stream()
                            .collect(Collectors.toMap(AccountEntity::getAccountNumber, e -> e));

                    // Found  -> mutate the loaded row, keeping account_tt_id and created_at.
                    // Absent -> a brand new account_tt row under the same application_tt_id.
                    List<AccountEntity> toWrite = desired.values().stream()
                            .map(dto -> {
                                AccountEntity row = byAccountNumber.get(dto.accountNumber());
                                return row == null
                                        ? AccountEntity.create(applicationTtId, dto)
                                        : row.apply(dto);
                            })
                            .toList();

                    List<Long> toDelete = pruneMissing
                            ? existing.stream()
                                    .filter(row -> !desired.containsKey(row.getAccountNumber()))
                                    .map(AccountEntity::getAccountTtId)
                                    .toList()
                            : List.of();

                    // Deletes first. The unique constraint is DEFERRABLE INITIALLY DEFERRED so the
                    // ordering is not load-bearing, but this order is correct with or without the
                    // deferral. Guarded because deleteAllById on an empty collection would build
                    // an "IN ()" predicate.
                    Mono<Void> deletes = toDelete.isEmpty()
                            ? Mono.empty()
                            : repo.deleteAllById(toDelete);

                    return deletes.thenMany(repo.saveAll(toWrite))
                            .map(AccountEntity::toDto)
                            .collectList();
                });
    }

    /**
     * Validates and de-duplicates the payload in one pass.
     *
     * <p>A {@code Collectors.toMap} here would throw a bare {@code IllegalStateException} naming
     * neither the field nor the offending value, so the explicit loop is deliberate.
     * {@code LinkedHashMap} preserves request order so the response echoes back in the order sent.
     */
    private static Map<String, AccountDto> index(List<AccountDto> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            throw new IllegalArgumentException("At least one account is required");
        }

        Map<String, AccountDto> desired = new LinkedHashMap<>();
        for (AccountDto dto : incoming) {
            if (dto == null || dto.accountNumber() == null || dto.accountNumber().isBlank()) {
                throw new IllegalArgumentException("accountNumber is required on every element");
            }
            if (desired.put(dto.accountNumber(), dto) != null) {
                // Keeping the last silently would make the request's effect depend on element
                // order, and the resulting row count would quietly disagree with the payload size.
                throw new IllegalArgumentException("Duplicate accountNumber: " + dto.accountNumber());
            }
        }
        return desired;
    }

    /** No application for the session — a 404, not an insert against a null application_tt_id. */
    public static class ApplicationNotFoundException extends RuntimeException {
        public ApplicationNotFoundException() {
            super("No application resolved for this session");
        }
    }
}
