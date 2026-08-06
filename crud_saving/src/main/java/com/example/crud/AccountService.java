package com.example.crud;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

/**
 * Replaces an application's accounts with the posted array.
 *
 * <p>Every call deletes all {@code account_tt} rows for the application and reinserts the payload.
 * There is no matching on {@code account_number} and no update path — that is the business
 * decision, and it is what keeps this short.
 *
 * <p>Consequence worth knowing: <b>{@code account_tt_id} changes on every call.</b> The old rows
 * are gone, not amended, so anything holding an account id — another table's foreign key, a
 * client that cached one, a report keyed on it — is invalidated each time this runs. See the
 * README if that is a problem; it is the one thing replace-all costs you.
 */
@Service
public class AccountService {

    private final AccountRepository repo;
    private final ApplicationProvider applications;

    public AccountService(AccountRepository repo, ApplicationProvider applications) {
        this.repo = repo;
        this.applications = applications;
    }

    /**
     * Delete and reinsert must be one transaction, or a failed insert leaves the application with
     * no accounts at all — strictly worse than the state it started in.
     *
     * <p>The transaction manager is named explicitly. Boot's R2DBC autoconfiguration registers it
     * as {@code connectionFactoryTransactionManager}; naming it means that if JPA or plain JDBC
     * joins the classpath, this keeps binding to the reactive manager rather than silently picking
     * the blocking one and doing nothing. Reactive {@code @Transactional} has no effect whatsoever
     * on a JDBC/JPA repository.
     */
    @Transactional("connectionFactoryTransactionManager")
    public Mono<List<AccountDto>> replace(List<AccountDto> incoming) {
        // fromCallable, not a plain call: validate() throws, and throwing here rather than inside
        // the chain escapes at assembly time — before any subscription — which bypasses onError
        // and surfaces as a 500 instead of the 400 the advice maps it to.
        return Mono.fromCallable(() -> validate(incoming))
                .flatMap(accounts -> applications.getApplicationMono()
                        .switchIfEmpty(Mono.error(new ApplicationNotFoundException()))
                        .flatMap(app -> replaceAll(app.getId(), accounts)));
    }

    private Mono<List<AccountDto>> replaceAll(Long applicationTtId, List<AccountDto> accounts) {
        List<AccountEntity> rows = accounts.stream()
                .map(dto -> AccountEntity.create(applicationTtId, dto))
                .toList();

        return repo.deleteByApplicationTtId(applicationTtId)
                .thenMany(repo.saveAll(rows))
                .map(AccountEntity::toDto)
                .collectList();
    }

    /**
     * Rejects payloads the unique constraint would otherwise reject at commit, so the client gets
     * a 400 naming the offending value instead of an opaque 409.
     */
    private static List<AccountDto> validate(List<AccountDto> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            // Under replace-all an empty array coherently means "this application has no
            // accounts" — but it is also one stray client call from wiping them. Delete this
            // block if you want that to be allowed.
            throw new IllegalArgumentException("At least one account is required");
        }

        Set<String> seen = new HashSet<>();
        for (AccountDto dto : incoming) {
            if (dto == null || dto.accountNumber() == null || dto.accountNumber().isBlank()) {
                throw new IllegalArgumentException("accountNumber is required on every element");
            }
            if (!seen.add(dto.accountNumber())) {
                throw new IllegalArgumentException("Duplicate accountNumber: " + dto.accountNumber());
            }
        }
        return incoming;
    }

    /** No application for the session — a 404, not an insert against a null application_tt_id. */
    public static class ApplicationNotFoundException extends RuntimeException {
        public ApplicationNotFoundException() {
            super("No application resolved for this session");
        }
    }
}
