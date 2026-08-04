package com.example.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Periodically rewrites cache entries that are inside their refresh window, so a hot key is
 * renewed shortly before it would have expired rather than expiring and causing a miss.
 *
 * <p>Two details in here are load-bearing:
 *
 * <ol>
 *   <li>The scheduled method <em>blocks</em>. {@code fixedDelay} measures from the end of the
 *       previous invocation, so a method that fires {@code subscribe()} and returns looks
 *       instantaneous to the scheduler and the next run starts immediately — overlapping
 *       passes hammering the origin. Blocking here is safe and correct: this runs on a
 *       dedicated {@code TaskScheduler} thread, not a Netty event loop.
 *   <li>The interval must be well under {@code refresh-before}. An entry is only eligible
 *       during the last {@code refresh-before} of its life; if a pass does not land inside
 *       that window the entry just expires. See {@link #checkTimings()}.
 * </ol>
 */
public class CacheRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(CacheRefreshScheduler.class);

    /** Releases the lock only if we still own it, so a slow pass cannot delete a successor's lock. */
    private static final RedisScript<Long> UNLOCK = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final RedisJsonCache cache;
    private final ReactiveStringRedisTemplate redis;
    private final CacheRefreshProperties props;

    public CacheRefreshScheduler(RedisJsonCache cache,
                                 ReactiveStringRedisTemplate redis,
                                 CacheRefreshProperties props) {
        this.cache = cache;
        this.redis = redis;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${cache.refresh.interval:PT5M}")
    public void refreshDueEntries() {
        if (props.getGroups().isEmpty()) {
            return;
        }
        String token = UUID.randomUUID().toString();
        try {
            Long refreshed = Mono.usingWhen(
                            acquireLock(token),
                            acquired -> acquired ? refreshAllGroups() : Mono.just(-1L),
                            acquired -> acquired ? releaseLock(token) : Mono.empty())
                    .block(props.getBatchTimeout());

            if (refreshed == null || refreshed < 0) {
                log.debug("Refresh pass skipped — another instance holds {}", props.getLockKey());
            } else if (refreshed > 0) {
                log.info("Refreshed {} cache entries", refreshed);
            }
        } catch (IllegalStateException e) {
            // block() timed out. The lock stays until its own TTL expires, which is why
            // lock-ttl must be longer than batch-timeout.
            log.error("Refresh pass exceeded batch-timeout of {}", props.getBatchTimeout(), e);
        } catch (RuntimeException e) {
            log.error("Refresh pass failed", e);
        }
    }

    private Mono<Long> refreshAllGroups() {
        Instant now = Instant.now();
        // concatMap, not flatMap: groups run one after another so the concurrency cap below
        // is a global ceiling on upstream load rather than a per-group one.
        return Flux.fromIterable(props.getGroups().entrySet())
                .filter(entry -> entry.getValue().isRefreshAhead())
                .concatMap(entry -> refreshGroup(entry.getKey(), now))
                .reduce(0L, Long::sum);
    }

    private Mono<Long> refreshGroup(String group, Instant now) {
        return cache.dueIds(group, now)
                .flatMap(id -> cache.refresh(group, id)
                                .thenReturn(1L)
                                .onErrorResume(e -> {
                                    // One unreachable id must not abort the rest of the batch.
                                    log.warn("Refresh failed for {}/{} — leaving the existing value to expire",
                                            group, id, e);
                                    return Mono.just(0L);
                                }),
                        props.getConcurrency())
                .reduce(0L, Long::sum);
    }

    private Mono<Boolean> acquireLock(String token) {
        return redis.opsForValue()
                .setIfAbsent(props.getLockKey(), token, props.getLockTtl())
                .defaultIfEmpty(false);
    }

    private Mono<Long> releaseLock(String token) {
        return redis.execute(UNLOCK, List.of(props.getLockKey()), List.of(token))
                .singleOrEmpty()
                .defaultIfEmpty(0L)
                .onErrorResume(e -> {
                    log.warn("Could not release {}; it will expire on its own", props.getLockKey(), e);
                    return Mono.just(0L);
                });
    }

    // ------------------------------------------------------------- startup

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        checkTimings();
        seedPreloads();
    }

    /**
     * The effective cycle is {@code interval + pass duration}. If that is not comfortably
     * shorter than a group's refresh window, passes will step over the window and entries
     * will expire instead of being refreshed — silently, which is why this warns loudly.
     */
    private void checkTimings() {
        Duration interval = props.getInterval();
        if (props.getBatchTimeout().compareTo(props.getLockTtl()) >= 0) {
            log.warn("cache.refresh.batch-timeout ({}) >= lock-ttl ({}): a slow pass can outlive its own lock "
                    + "and let a second instance start a concurrent pass",
                    props.getBatchTimeout(), props.getLockTtl());
        }
        props.getGroups().forEach((name, group) -> {
            if (!group.isRefreshAhead()) {
                return;
            }
            if (group.getRefreshBefore().compareTo(interval.multipliedBy(2)) < 0) {
                log.warn("Group '{}' has refresh-before {} but the job runs every {}. Entries get roughly "
                                + "{} chance(s) to be refreshed before expiry — aim for at least 2 by lowering "
                                + "cache.refresh.interval or raising refresh-before.",
                        name, group.getRefreshBefore(), interval,
                        group.getRefreshBefore().toMillis() / Math.max(1, interval.toMillis()));
            }
        });
    }

    /**
     * Marks the deployment-supplied ids as immediately due so the first pass warms them,
     * rather than waiting for someone to take a cache miss.
     */
    private void seedPreloads() {
        for (Map.Entry<String, CacheRefreshProperties.Group> entry : props.getGroups().entrySet()) {
            try {
                Long seeded = cache.seedPreloadIds(entry.getKey(), entry.getValue())
                        .block(Duration.ofSeconds(30));
                if (seeded != null && seeded > 0) {
                    log.info("Queued {} preload id(s) for group '{}'", seeded, entry.getKey());
                }
            } catch (RuntimeException e) {
                // Never let cache warming stop the application from starting.
                log.warn("Could not seed preload ids for group '{}'", entry.getKey(), e);
            }
        }
    }
}
