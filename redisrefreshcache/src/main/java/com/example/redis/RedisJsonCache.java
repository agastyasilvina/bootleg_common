package com.example.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stores JSON values in Redis under a configured group, always with a TTL, and keeps a
 * per-group sorted-set index of when each entry becomes eligible for early refresh.
 *
 * <p>The index is the reason the scheduler does not need to SCAN the keyspace: one
 * ZRANGEBYSCORE returns exactly the ids that are due, instead of scanning every key and
 * issuing a TTL call per key.
 *
 * <p>Every write goes through {@link #put}, which sets value and expiry in a single
 * {@code SET key value PX ...}. Note that a bare {@code SET} would clear the TTL and leave
 * the key immortal — that is why no code path here calls the no-expiry overload.
 */
public class RedisJsonCache {

    private static final Logger log = LoggerFactory.getLogger(RedisJsonCache.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CacheRefreshProperties props;
    private final Map<String, CacheLoader<Object>> loaders;

    @SuppressWarnings("unchecked")
    public RedisJsonCache(ReactiveStringRedisTemplate redis,
                          ObjectMapper mapper,
                          CacheRefreshProperties props,
                          List<CacheLoader<?>> loaders) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
        this.loaders = new HashMap<>();
        for (CacheLoader<?> loader : loaders) {
            CacheLoader<Object> previous = this.loaders.put(loader.group(), (CacheLoader<Object>) loader);
            if (previous != null) {
                throw new IllegalStateException("Two CacheLoader beans both claim group '" + loader.group() + "'");
            }
        }
        validateGroups();
    }

    /**
     * A group that wants refresh-ahead but has no loader would sit in the due-index forever
     * without ever being refreshed. Catch that at startup, not in production at 3am.
     */
    private void validateGroups() {
        props.getGroups().forEach((name, group) -> {
            if (group.getKeyPrefix() == null || group.getKeyPrefix().isBlank()) {
                throw new IllegalStateException("cache.refresh.groups." + name + ".key-prefix is required");
            }
            if (group.getRefreshBefore().compareTo(group.getTtl()) >= 0) {
                throw new IllegalStateException("cache.refresh.groups." + name
                        + ": refresh-before (" + group.getRefreshBefore() + ") must be shorter than ttl ("
                        + group.getTtl() + "), otherwise every entry is due the moment it is written");
            }
            if (group.isRefreshAhead() && !loaders.containsKey(name)) {
                throw new IllegalStateException("cache.refresh.groups." + name
                        + " has refresh-ahead enabled but no CacheLoader bean returns group() == \"" + name
                        + "\". Register one, or set refresh-ahead: false.");
            }
        });
    }

    // ---------------------------------------------------------------- reads

    /** Returns the cached value, or empty on a miss. Never consults the loader. */
    public <T> Mono<T> get(String group, String id, Class<T> type) {
        String key = key(group, id);
        return redis.opsForValue().get(key)
                .flatMap(json -> deserialize(json, type)
                        .onErrorResume(e -> {
                            // A poison entry would otherwise fail every read until it expires.
                            log.warn("Discarding unreadable cache entry {}: {}", key, e.toString());
                            return evict(group, id).then(Mono.empty());
                        }));
    }

    /** Returns the cached value, falling back to the loader (and caching the result) on a miss. */
    public <T> Mono<T> getOrLoad(String group, String id, Class<T> type) {
        return get(group, id, type)
                .switchIfEmpty(Mono.defer(() -> loadAndStore(group, id, type)));
    }

    private <T> Mono<T> loadAndStore(String group, String id, Class<T> type) {
        return loader(group).load(id)
                .flatMap(value -> put(group, id, value).thenReturn(type.cast(value)));
    }

    // --------------------------------------------------------------- writes

    /**
     * Writes the value with the group's TTL and, when the group has refresh-ahead enabled,
     * registers it as due at {@code now + ttl - refreshBefore}.
     */
    public Mono<Void> put(String group, String id, Object value) {
        return put(group, id, value, true);
    }

    /**
     * As {@link #put}, but {@code trackForRefresh = false} writes the TTL without registering
     * the entry for early refresh — use it for one-off or low-value entries inside an
     * otherwise refreshed group.
     */
    public Mono<Void> put(String group, String id, Object value, boolean trackForRefresh) {
        CacheRefreshProperties.Group cfg = props.group(group);
        return serialize(value)
                .flatMap(json -> redis.opsForValue().set(key(group, id), json, cfg.getTtl()))
                .flatMap(ok -> (trackForRefresh && cfg.isRefreshAhead())
                        ? markDue(group, id, cfg)
                        : Mono.empty())
                .then();
    }

    /** Removes the entry and its refresh registration. */
    public Mono<Void> evict(String group, String id) {
        return redis.delete(key(group, id))
                .then(redis.opsForZSet().remove(dueIndex(group), id))
                .then();
    }

    // ------------------------------------------------- used by the scheduler

    /** Ids in this group whose refresh window has opened, oldest-due first. */
    Flux<String> dueIds(String group, Instant now) {
        return redis.opsForZSet()
                .rangeByScore(dueIndex(group), Range.closed(0d, (double) now.getEpochSecond()));
    }

    /**
     * Reloads one id from its loader and rewrites it with a fresh TTL.
     *
     * <p>An empty load means the id is gone upstream, so the entry is evicted and drops out
     * of the index. An error propagates: the caller logs it and leaves the existing value in
     * place to expire on its own rather than extending the TTL on data we could not verify.
     */
    Mono<Void> refresh(String group, String id) {
        return loader(group).load(id)
                .flatMap(value -> put(group, id, value))
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Loader for group '{}' no longer has id {} — evicting", group, id);
                    return evict(group, id);
                }))
                .then();
    }

    /**
     * Marks configured preload ids as immediately due, but only when they are not already
     * cached, so a rolling restart does not stampede the origin for entries that are warm.
     */
    Mono<Long> seedPreloadIds(String group, CacheRefreshProperties.Group cfg) {
        if (!cfg.isRefreshAhead() || cfg.getPreloadIds().isEmpty()) {
            return Mono.just(0L);
        }
        return Flux.fromIterable(cfg.getPreloadIds())
                .filter(id -> !id.isBlank())
                .map(String::trim)
                .filterWhen(id -> redis.hasKey(key(group, id)).map(exists -> !exists))
                .flatMap(id -> redis.opsForZSet().add(dueIndex(group), id, 0d), 8)
                .filter(Boolean::booleanValue)
                .count();
    }

    // --------------------------------------------------------------- helpers

    private Mono<Boolean> markDue(String group, String id, CacheRefreshProperties.Group cfg) {
        Duration untilDue = cfg.getTtl().minus(cfg.getRefreshBefore());
        double dueAt = Instant.now().plus(untilDue).getEpochSecond();
        return redis.opsForZSet().add(dueIndex(group), id, dueAt);
    }

    private CacheLoader<Object> loader(String group) {
        CacheLoader<Object> loader = loaders.get(group);
        if (loader == null) {
            throw new IllegalStateException("No CacheLoader registered for group '" + group + "'");
        }
        return loader;
    }

    private Mono<String> serialize(Object value) {
        return Mono.fromCallable(() -> mapper.writeValueAsString(value));
    }

    private <T> Mono<T> deserialize(String json, Class<T> type) {
        return Mono.fromCallable(() -> mapper.readValue(json, type));
    }

    public String key(String group, String id) {
        return props.group(group).getKeyPrefix() + id;
    }

    /** One sorted set per group, kept out of the group's own prefix so SCANs stay clean. */
    public String dueIndex(String group) {
        return "cache:refresh:due:" + group;
    }
}
