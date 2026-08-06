package com.example.crud;

import reactor.core.publisher.Mono;

/**
 * Stands in for however you resolve the application from the session header — the
 * {@code Mono<ApplicationEntity> appMono = getApplicationMono()} you already have.
 *
 * <p>Kept as an interface so the service depends on the signature rather than on your session
 * plumbing, which is also what lets the tests substitute a fixed application. When you paste this
 * into the real project, delete it and inject your own component instead.
 */
public interface ApplicationProvider {

    /**
     * Empty means "no application for this session". The service turns that into a 404 rather
     * than writing rows against a null id.
     */
    Mono<ApplicationEntity> getApplicationMono();
}
