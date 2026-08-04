package com.example.redis;

import reactor.core.publisher.Mono;

/**
 * Fetches the current JSON for one cache group from wherever it actually lives.
 *
 * <p>Register one bean per group you want refreshed. A group with no loader is simply never
 * refreshed early — its entries expire and reload on demand.
 *
 * <pre>{@code
 * @Component
 * class ProfileLoader implements CacheLoader {
 *     private final WebClient client;
 *
 *     public String group() { return "profile"; }
 *
 *     public Mono<String> load(String id) {
 *         return client.get().uri("/profiles/{id}", id)
 *                      .retrieve().bodyToMono(String.class);
 *     }
 * }
 * }</pre>
 */
public interface CacheLoader {

    /** Group name, matching a key under {@code cache.groups}. */
    String group();

    /**
     * Returns the JSON to store, verbatim. Nothing parses it, so whatever the origin returns
     * is what your API will later hand back.
     *
     * <p>Empty means the id no longer exists — the entry is evicted. An error means a
     * transient failure — the existing entry is left alone to expire on its own.
     *
     * <p>If the call underneath blocks (JDBC, RestTemplate), wrap it here with
     * {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} so the blocking
     * never lands on a Netty event loop.
     */
    Mono<String> load(String id);
}
