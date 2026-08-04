package com.example.redis;

import reactor.core.publisher.Mono;

/**
 * Supplies fresh values for one cache group from the system of record.
 *
 * <p>Implement one per group and register it as a bean; {@link CacheRefreshScheduler}
 * discovers them automatically and matches them to configured groups by {@link #group()}.
 *
 * <p>A group configured without a matching loader fails fast at startup rather than
 * quietly never refreshing.
 *
 * <pre>{@code
 * @Component
 * class ProfileLoader implements CacheLoader<Profile> {
 *     private final ProfileClient client;
 *
 *     public String group() { return "profile"; }
 *
 *     public Mono<Profile> load(String id) {
 *         return client.fetch(id);       // empty Mono => id no longer exists, entry is evicted
 *     }
 * }
 * }</pre>
 */
public interface CacheLoader<T> {

    /** Group name, matching a key under {@code cache.refresh.groups}. */
    String group();

    /**
     * Fetches the current value for {@code id}.
     *
     * <p>Return an empty {@code Mono} to signal the id no longer exists — the cache entry
     * and its refresh registration are then removed. Signal an error for a transient
     * failure; the existing cached value is left alone to expire naturally.
     *
     * <p>If the underlying call blocks (JDBC, RestTemplate), wrap it here with
     * {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} so the
     * blocking never escapes onto a Netty event loop.
     */
    Mono<T> load(String id);
}
