package com.example.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stores JSON strings in Redis with a TTL, and rewrites them shortly before they expire so hot
 * keys never go cold. Values are stored and returned verbatim — nothing here parses them.
 *
 * <p>A sorted set per group records when each entry becomes due for refresh, so the scheduled
 * pass is a single ZRANGEBYSCORE rather than a SCAN of the keyspace plus a TTL call per key.
 *
 * <p><b>Everything that writes these keys must go through {@link #put}.</b> A value written to
 * Redis directly is never registered in the due-index, so it will expire instead of refreshing.
 */
public class RedisJsonCache {

    private static final Logger log = LoggerFactory.getLogger(RedisJsonCache.class);

    private final ReactiveStringRedisTemplate redis;
    private final CacheProperties props;
    private final Map<String, CacheLoader> loaders = new HashMap<>();

    public RedisJsonCache(ReactiveStringRedisTemplate redis, CacheProperties props,
                          List<CacheLoader> loaders) {
        this.redis = redis;
        this.props = props;
        for (CacheLoader loader : loaders) {
            if (this.loaders.put(loader.group(), loader) != null) {
                throw new IllegalStateException("Two CacheLoader beans claim group '" + loader.group() + "'");
            }
        }
        validate();
    }

    /** Catches config mistakes at startup instead of at 3am in production. */
    private void validate() {
        props.getGroups().forEach((name, g) -> {
            if (g.getKeyPrefix() == null || g.getKeyPrefix().isBlank()) {
                throw new IllegalStateException("cache.groups." + name + ".key-prefix is required");
            }
            if (g.getRefreshBefore().compareTo(g.getTtl()) >= 0) {
                throw new IllegalStateException("cache.groups." + name + ": refresh-before ("
                        + g.getRefreshBefore() + ") must be shorter than ttl (" + g.getTtl()
                        + "), or every entry is due the moment it is written");
            }
            if (g.isRefreshAhead() && !loaders.containsKey(name)) {
                throw new IllegalStateException("cache.groups." + name + " has refresh-ahead enabled but no"
                        + " CacheLoader bean returns group() == \"" + name + "\". Add one, or set"
                        + " refresh-ahead: false.");
            }
        });
    }

    // ----------------------------------------------------------------- API

    /** The cached JSON, or empty on a miss. */
    public Mono<String> get(String group, String id) {
        return redis.opsForValue().get(key(group, id));
    }

    /** The cached JSON, falling back to the loader (and caching the result) on a miss. */
    public Mono<String> getOrLoad(String group, String id) {
        return get(group, id)
                .switchIfEmpty(Mono.defer(() -> loader(group).load(id)
                        .flatMap(json -> put(group, id, json).thenReturn(json))));
    }

    /** Writes the JSON with the group's TTL and registers it for refresh. */
    public Mono<Void> put(String group, String id, String json) {
        return put(group, id, json, true);
    }

    /**
     * As {@link #put}, but {@code trackForRefresh = false} writes the TTL without registering the
     * entry for early refresh — for one-off entries inside an otherwise refreshed group.
     *
     * <p>The TTL goes out with the value in a single {@code SET key value PX ...}. Note there is
     * deliberately no call to the no-expiry {@code set} overload anywhere in this class: a bare
     * {@code SET} clears the existing TTL and leaves the key immortal.
     */
    public Mono<Void> put(String group, String id, String json, boolean trackForRefresh) {
        CacheProperties.Group cfg = props.group(group);
        return redis.opsForValue().set(key(group, id), json, cfg.getTtl())
                .flatMap(ok -> trackForRefresh && cfg.isRefreshAhead()
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

    public String key(String group, String id) {
        return props.group(group).getKeyPrefix() + id;
    }

    /** One sorted set per group, outside the group's own prefix so SCANs stay clean. */
    public String dueIndex(String group) {
        return "cache:refresh:due:" + group;
    }

    // ----------------------------------------------------------- refreshing

    /**
     * Rewrites every entry whose refresh window has opened.
     *
     * <p>This method blocks on purpose. {@code fixedDelay} measures from the end of the previous
     * invocation, so a method that fires {@code subscribe()} and returns looks instantaneous to
     * the scheduler — the next pass starts immediately and passes overlap. Blocking is fine here:
     * this runs on a {@code TaskScheduler} thread, not a Netty event loop. Do raise
     * {@code spring.task.scheduling.pool.size} above its default of 1.
     */
    @Scheduled(fixedDelayString = "${cache.refresh-interval:PT5M}")
    public void refreshDueEntries() {
        if (!props.isRefreshEnabled() || props.getGroups().isEmpty()) {
            return;
        }
        try {
            Long count = acquireLock()
                    .flatMap(locked -> locked ? refreshAllGroups() : Mono.just(-1L))
                    .block(props.getRefreshInterval());

            if (count == null || count < 0) {
                log.debug("Refresh pass skipped — another instance holds {}", props.getLockKey());
            } else if (count > 0) {
                log.info("Refreshed {} cache entries", count);
            }
        } catch (RuntimeException e) {
            log.error("Refresh pass failed", e);
        }
    }

    /**
     * The lock TTL is the refresh interval, so it lapses on its own right about when the next
     * pass is due. That removes the need to release it — and with it the whole class of bugs
     * where a slow pass deletes its successor's lock.
     */
    private Mono<Boolean> acquireLock() {
        return redis.opsForValue()
                .setIfAbsent(props.getLockKey(), "1", props.getRefreshInterval())
                .defaultIfEmpty(false);
    }

    private Mono<Long> refreshAllGroups() {
        double now = Instant.now().getEpochSecond();
        // concatMap, not flatMap: groups run one after another, so the concurrency cap below is
        // a global ceiling on load rather than a per-group one.
        return Flux.fromIterable(props.getGroups().entrySet())
                .filter(e -> e.getValue().isRefreshAhead())
                .concatMap(e -> refreshGroup(e.getKey(), now))
                .reduce(0L, Long::sum);
    }

    private Mono<Long> refreshGroup(String group, double now) {
        return redis.opsForZSet().rangeByScore(dueIndex(group), Range.closed(0d, now))
                .flatMap(id -> refresh(group, id)
                                .thenReturn(1L)
                                .onErrorResume(e -> {
                                    // One unreachable id must not abort the rest of the batch.
                                    log.warn("Refresh failed for {}{} — leaving the existing value to expire",
                                            group, id, e);
                                    return Mono.just(0L);
                                }),
                        props.getConcurrency())
                .reduce(0L, Long::sum);
    }

    /**
     * Reloads one id and rewrites it with a fresh TTL.
     *
     * <p>The {@code thenReturn(true)} is load-bearing. {@code put} returns {@code Mono<Void>},
     * which always completes empty, so chaining {@code switchIfEmpty} straight onto it would fire
     * on every successful write and evict the entry that was just stored. Mapping to a value
     * first means the fallback only runs when the loader itself was empty.
     */
    private Mono<Void> refresh(String group, String id) {
        return loader(group).load(id)
                .flatMap(json -> put(group, id, json).thenReturn(true))
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Loader for '{}' no longer has id {} — evicting", group, id);
                    return evict(group, id).thenReturn(false);
                }))
                .then();
    }

    // -------------------------------------------------------------- startup

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        checkTimings();
        props.getGroups().forEach(this::seedPreloadIds);
    }

    /**
     * An entry is only eligible during the last {@code refresh-before} of its life, and the
     * effective cycle is {@code refresh-interval + pass duration}. If that exceeds the window,
     * passes step straight over it and entries expire — silently, hence the warning.
     */
    private void checkTimings() {
        Duration interval = props.getRefreshInterval();
        props.getGroups().forEach((name, g) -> {
            if (g.isRefreshAhead() && g.getRefreshBefore().compareTo(interval.multipliedBy(2)) < 0) {
                log.warn("Group '{}': refresh-before is {} but the pass runs every {}, giving each entry"
                                + " only ~{} chance(s) before expiry. Aim for at least 2 — lower"
                                + " cache.refresh-interval or raise refresh-before.",
                        name, g.getRefreshBefore(), interval,
                        g.getRefreshBefore().toMillis() / Math.max(1, interval.toMillis()));
            }
        });
    }

    /**
     * Marks deploy-supplied ids as immediately due, but only those not already cached, so a
     * rolling restart does not stampede the origin for entries that are already warm.
     */
    private void seedPreloadIds(String group, CacheProperties.Group cfg) {
        if (!cfg.isRefreshAhead() || cfg.getPreloadIds().isEmpty()) {
            return;
        }
        try {
            Long seeded = Flux.fromIterable(cfg.getPreloadIds())
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .filterWhen(id -> redis.hasKey(key(group, id)).map(exists -> !exists))
                    .flatMap(id -> redis.opsForZSet().add(dueIndex(group), id, 0d), 8)
                    .filter(Boolean::booleanValue)
                    .count()
                    .block(Duration.ofSeconds(30));
            if (seeded != null && seeded > 0) {
                log.info("Queued {} preload id(s) for group '{}'", seeded, group);
            }
        } catch (RuntimeException e) {
            // Never let cache warming stop the application from starting.
            log.warn("Could not seed preload ids for group '{}'", group, e);
        }
    }

    private CacheLoader loader(String group) {
        CacheLoader loader = loaders.get(group);
        if (loader == null) {
            throw new IllegalStateException("No CacheLoader registered for group '" + group + "'");
        }
        return loader;
    }

    private Mono<Boolean> markDue(String group, String id, CacheProperties.Group cfg) {
        Duration untilDue = cfg.getTtl().minus(cfg.getRefreshBefore());
        return redis.opsForZSet()
                .add(dueIndex(group), id, Instant.now().plus(untilDue).getEpochSecond());
    }
}
