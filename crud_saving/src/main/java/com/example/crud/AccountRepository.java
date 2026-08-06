package com.example.crud;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;

@Repository
public interface AccountRepository extends ReactiveCrudRepository<AccountEntity, Long> {

    /**
     * Current state for one application. Every {@code account_tt_id} the service later writes or
     * deletes comes from here, which is what guarantees those ids refer to real rows.
     */
    Flux<AccountEntity> findByApplicationTtId(Long applicationTtId);
}
