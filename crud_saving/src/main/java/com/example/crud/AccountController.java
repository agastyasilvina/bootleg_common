package com.example.crud;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

/**
 * PUT rather than POST: sending the same array twice produces the same rows, so the call is
 * idempotent and safe for a client to retry after a timeout — which matters here, because a
 * retried POST would otherwise duplicate accounts.
 *
 * <p>{@code @Validated} on the class is not decoration: without it the {@code @Valid} on the
 * list's element type is accepted and silently ignored, so per-element constraints never run.
 */
@Validated
@RestController
@RequestMapping("/applications/current/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<AccountDto>> save(@RequestBody @Valid List<@Valid AccountDto> body) {
        return service.save(body);
    }
}
