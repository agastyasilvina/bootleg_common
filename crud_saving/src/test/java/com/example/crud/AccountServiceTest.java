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
 * One test per failure mode the design prevents. Each is written so that removing the
 * corresponding safeguard turns it red — these are regression pins, not coverage.
 */
@Testcontainers
@SpringBootTest
@Import(AccountServiceTest.StubApplication.class)
class AccountServiceTest {

    private static final Long APP_ID = 42L;

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
        // The application row always exists before this endpoint is called; the FK enforces it.
        db.sql("INSERT INTO application_tt (application_tt_id) VALUES (:id) "
                        + "ON CONFLICT DO NOTHING")
                .bind("id", APP_ID)
                .fetch().rowsUpdated().block();
    }

    private static AccountDto acct(String number, String a) {
        return new AccountDto(number, a, "b", "c");
    }

    // ------------------------------------------------- 1. not found -> new row; found -> update

    /** The core requirement: an unseen account_number becomes a new account_tt row. */
    @Test
    void insertsAccountNumbersThatAreNotYetOnTheApplication() {
        service.save(List.of(acct("111", "a"))).block();
        service.save(List.of(acct("111", "a"), acct("222", "b"))).block();

        List<AccountEntity> rows = repo.findByApplicationTtId(APP_ID).collectList().block();
        assertThat(rows).extracting(AccountEntity::getAccountNumber)
                .containsExactlyInAnyOrder("111", "222");
        assertThat(rows).allMatch(r -> r.getApplicationTtId().equals(APP_ID));
    }

    /**
     * Fails if an existing row is rebuilt rather than mutated: a rebuilt entity either duplicates
     * the row (null id → INSERT) or writes nothing (stale id → UPDATE matching zero rows). Pinning
     * account_tt_id exposes the first; pinning {@code a} exposes the second, which a row-count
     * assertion alone would miss.
     */
    @Test
    void updatesInPlaceRatherThanReinserting() {
        service.save(List.of(acct("111", "x"))).block();
        AccountEntity first = repo.findByApplicationTtId(APP_ID).blockFirst();

        service.save(List.of(acct("111", "y"))).block();

        StepVerifier.create(repo.findByApplicationTtId(APP_ID))
                .assertNext(row -> {
                    assertThat(row.getAccountTtId()).isEqualTo(first.getAccountTtId());
                    assertThat(row.getA()).isEqualTo("y");
                    assertThat(row.getCreatedAt()).isEqualTo(first.getCreatedAt());
                })
                .verifyComplete();                                    // and still only one row
    }

    /** Default is upsert: an account already stored but absent from the payload is left alone. */
    @Test
    void leavesAccountsMissingFromThePayloadAlone() {
        service.save(List.of(acct("111", "a"), acct("222", "b"))).block();

        service.save(List.of(acct("111", "updated"))).block();

        assertThat(repo.findByApplicationTtId(APP_ID).collectList().block())
                .extracting(AccountEntity::getAccountNumber)
                .containsExactlyInAnyOrder("111", "222");
    }

    // ------------------------------------------------------------------ 2. transactions

    /**
     * The only real proof {@code @Transactional} is active. If the annotation is inert — wrong
     * transaction manager, self-invocation, or a non-reactive repository — the first element's
     * update commits before the second fails, and the assertion below catches it.
     */
    @Test
    void rollsBackTheWholeBatchWhenOneWriteFails() {
        service.save(List.of(acct("111", "original"))).block();

        StepVerifier.create(service.save(List.of(
                        acct("111", "should-not-survive"),
                        new AccountDto("222", "x", "y", "z".repeat(300)))))  // exceeds VARCHAR(255)
                .expectError(DataIntegrityViolationException.class)
                .verify();

        assertThat(repo.findByApplicationTtId(APP_ID).blockFirst().getA()).isEqualTo("original");
        assertThat(repo.findByApplicationTtId(APP_ID).count().block()).isEqualTo(1L);
    }

    // ------------------------------------------------- 3. errors are signals, not throws

    /**
     * The first assertion is the one almost every test omits: it calls the method <b>without
     * subscribing</b>. If validation ran eagerly instead of inside {@code Mono.fromCallable}, the
     * exception would escape here — bypassing the advice and surfacing as a 500 rather than a 400.
     * A test that subscribes immediately cannot tell the two apart.
     */
    @Test
    void rejectsDuplicatesAsAnErrorSignalNotAnAssemblyTimeThrow() {
        List<AccountDto> payload = List.of(acct("111", "a"), acct("111", "b"));

        assertThatNoException().isThrownBy(() -> service.save(payload));

        StepVerifier.create(service.save(payload))
                .expectErrorMessage("Duplicate accountNumber: 111")
                .verify();

        assertThat(repo.findByApplicationTtId(APP_ID).count().block()).isZero();
    }

    // ------------------------------------------------------------------ 4. idempotence

    @Test
    void isIdempotent() {
        List<AccountDto> payload = List.of(acct("111", "a"), acct("222", "b"));

        service.save(payload).block();
        List<Long> idsAfterFirst = repo.findByApplicationTtId(APP_ID)
                .map(AccountEntity::getAccountTtId).collectList().block();

        service.save(payload).block();

        assertThat(repo.findByApplicationTtId(APP_ID)
                .map(AccountEntity::getAccountTtId).collectList().block())
                .containsExactlyInAnyOrderElementsOf(idsAfterFirst);
    }
}
