package com.example.crud;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

/**
 * POST, as specified. Worth knowing that the operation is idempotent anyway — posting the same
 * array twice leaves the same accounts — so a client retrying after a timeout cannot duplicate
 * anything, which is the usual reason to prefer PUT here.
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

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<AccountDto>> replace(@RequestBody @Valid List<@Valid AccountDto> body) {
        return service.replace(body);
    }
}
