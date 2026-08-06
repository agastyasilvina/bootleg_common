package com.example.crud;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface AccountRepository extends ReactiveCrudRepository<AccountEntity, Long> {

    /** Clears the application's accounts before the payload is reinserted. */
    Mono<Void> deleteByApplicationTtId(Long applicationTtId);

    /** Not used by the service — only by tests and by anything that reads the accounts back. */
    Flux<AccountEntity> findByApplicationTtId(Long applicationTtId);
}
