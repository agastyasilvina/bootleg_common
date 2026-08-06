package com.example.crud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Replace-all semantics, plus the two failure modes that survive the simplification: the
 * transaction, and validation running as a signal rather than an assembly-time throw.
 */
@Testcontainers
@SpringBootTest
@Import(AccountServiceTest.StubApplication.class)
class AccountServiceTest {

    private static final Long APP_ID = 42L;
    private static final Long OTHER_APP_ID = 99L;

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class StubApplication {
        @Bean
        ApplicationProvider applicationProvider() {
            return () -> Mono.just(new ApplicationEntity(APP_ID));
        }
    }

    @Autowired AccountService service;
    @Autowired AccountRepository repo;
    @Autowired DatabaseClient db;

    @BeforeEach
    void clean() {
        repo.deleteAll().block();
        // Both application rows always exist before the endpoint is called; the FK enforces it.
        db.sql("INSERT INTO application_tt (application_tt_id) VALUES (:a), (:b) "
                        + "ON CONFLICT DO NOTHING")
                .bind("a", APP_ID).bind("b", OTHER_APP_ID)
                .fetch().rowsUpdated().block();
    }

    private static AccountDto acct(String number, String a) {
        return new AccountDto(number, a, "b", "c");
    }

    // ------------------------------------------------------------------ replace semantics

    @Test
    void insertsThePostedAccounts() {
        service.replace(List.of(acct("111", "a"), acct("222", "b"))).block();

        assertThat(repo.findByApplicationTtId(APP_ID).collectList().block())
                .extracting(AccountEntity::getAccountNumber)
                .containsExactlyInAnyOrder("111", "222");
    }

    /** The whole point: the second post is the new truth, not a merge with the first. */
    @Test
    void secondPostReplacesTheFirstEntirely() {
        service.replace(List.of(acct("111", "a"), acct("222", "b"))).block();

        service.replace(List.of(acct("333", "c"))).block();

        assertThat(repo.findByApplicationTtId(APP_ID).collectList().block())
                .extracting(AccountEntity::getAccountNumber)
                .containsExactly("333");
    }

    /**
     * Pins the cost of replace-all so nobody is surprised by it later: resending an identical
     * payload produces a <b>different</b> account_tt_id, because the row was deleted and remade.
     * If this test ever needs to change, something outside is depending on the id being stable.
     */
    @Test
    void accountIdsAreNotStableAcrossPosts() {
        service.replace(List.of(acct("111", "a"))).block();
        Long firstId = repo.findByApplicationTtId(APP_ID).blockFirst().getAccountTtId();

        service.replace(List.of(acct("111", "a"))).block();
        Long secondId = repo.findByApplicationTtId(APP_ID).blockFirst().getAccountTtId();

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(repo.findByApplicationTtId(APP_ID).count().block()).isEqualTo(1L);
    }

    /** The delete is scoped to one application — a replace must not touch anyone else's rows. */
    @Test
    void doesNotTouchOtherApplications() {
        repo.save(AccountEntity.create(OTHER_APP_ID, acct("999", "other"))).block();

        service.replace(List.of(acct("111", "a"))).block();

        assertThat(repo.findByApplicationTtId(OTHER_APP_ID).collectList().block())
                .extracting(AccountEntity::getAccountNumber)
                .containsExactly("999");
    }

    // ------------------------------------------------------------------ the transaction

    /**
     * The one that matters most here. Delete-then-insert without a transaction is strictly worse
     * than doing nothing: a failed insert leaves the application with <b>no</b> accounts at all.
     * If {@code @Transactional} is inert — wrong manager, self-invocation, non-reactive repo —
     * the delete commits and this assertion catches it.
     */
    @Test
    void rollsBackToThePreviousAccountsWhenAnInsertFails() {
        service.replace(List.of(acct("111", "original"))).block();

        StepVerifier.create(service.replace(List.of(
                        acct("222", "x"),
                        new AccountDto("333", "y", "z", "w".repeat(300)))))  // exceeds VARCHAR(255)
                .expectError(DataIntegrityViolationException.class)
                .verify();

        assertThat(repo.findByApplicationTtId(APP_ID).collectList().block())
                .extracting(AccountEntity::getAccountNumber)
                .containsExactly("111");
    }

    // ------------------------------------------------- errors are signals, not throws

    /**
     * The first assertion is the one almost every test omits: it calls the method <b>without
     * subscribing</b>. If validation ran eagerly instead of inside {@code Mono.fromCallable}, the
     * exception would escape here — bypassing the advice and surfacing as a 500 rather than a 400.
     * A test that subscribes immediately cannot tell the two apart.
     */
    @Test
    void rejectsDuplicatesAsAnErrorSignalNotAnAssemblyTimeThrow() {
        List<AccountDto> payload = List.of(acct("111", "a"), acct("111", "b"));

        assertThatNoException().isThrownBy(() -> service.replace(payload));

        StepVerifier.create(service.replace(payload))
                .expectErrorMessage("Duplicate accountNumber: 111")
                .verify();
    }

    /** Validation must reject before the delete runs, or a bad payload still wipes the rows. */
    @Test
    void rejectsBadPayloadWithoutDeletingAnything() {
        service.replace(List.of(acct("111", "a"))).block();

        StepVerifier.create(service.replace(List.of()))
                .expectError(IllegalArgumentException.class)
                .verify();

        assertThat(repo.findByApplicationTtId(APP_ID).count().block()).isEqualTo(1L);
    }
}
