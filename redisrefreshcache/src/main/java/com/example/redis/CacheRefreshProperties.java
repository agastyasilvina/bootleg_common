package com.example.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-time configuration for the cache-refresh mechanism.
 *
 * <p>Only the groups declared under {@code cache.refresh.groups} participate. Any other
 * Redis key your application writes is untouched by this machinery — that is the
 * opt-in switch. Within a declared group, {@code refresh-ahead: false} keeps the TTL
 * but skips the early-refresh behaviour, so the key simply expires on schedule.
 *
 * <pre>
 * cache:
 *   refresh:
 *     interval: PT5M
 *     groups:
 *       profile:
 *         key-prefix: "profile:"
 *         ttl: P1D
 *         refresh-before: PT10M
 * </pre>
 */
@ConfigurationProperties(prefix = "cache.refresh")
public class CacheRefreshProperties {

    /** Master switch. When false the scheduler bean is not registered at all. */
    private boolean enabled = true;

    /**
     * How often the refresh job runs. Referenced by the scheduler's fixedDelayString,
     * so changing it here changes both the schedule and the startup sanity check.
     */
    private Duration interval = Duration.ofMinutes(5);

    /** Redis key holding the cross-instance leader lock for the refresh job. */
    private String lockKey = "cache:refresh:lock";

    /** Lock lifetime. Must exceed the worst-case duration of one full refresh pass. */
    private Duration lockTtl = Duration.ofMinutes(10);

    /** Hard ceiling on one refresh pass, so a wedged upstream cannot pin the scheduler thread. */
    private Duration batchTimeout = Duration.ofMinutes(5);

    /** Maximum simultaneous upstream loads. Protects the origin from a large due-batch. */
    private int concurrency = 8;

    /** Logical cache groups, keyed by group name. The name is what CacheLoader.group() returns. */
    private Map<String, Group> groups = new LinkedHashMap<>();

    public static class Group {

        /** Redis key prefix for this group, e.g. "profile:". The full key is prefix + id. */
        private String keyPrefix;

        /** Time-to-live written with every value. */
        private Duration ttl = Duration.ofDays(1);

        /** How far before expiry a key becomes eligible for refresh. */
        private Duration refreshBefore = Duration.ofMinutes(10);

        /**
         * When false, entries in this group are written with a TTL but never registered
         * for early refresh — they just expire and reload on demand.
         */
        private boolean refreshAhead = true;

        /**
         * Ids seeded into the due-index at startup so they are warmed on the first pass,
         * before anyone reads them. Intended to be supplied at deploy time, e.g.
         * {@code preload-ids: ${HOT_PROFILE_IDS:}} bound from a generated env var.
         */
        private List<String> preloadIds = new ArrayList<>();

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getRefreshBefore() {
            return refreshBefore;
        }

        public void setRefreshBefore(Duration refreshBefore) {
            this.refreshBefore = refreshBefore;
        }

        public boolean isRefreshAhead() {
            return refreshAhead;
        }

        public void setRefreshAhead(boolean refreshAhead) {
            this.refreshAhead = refreshAhead;
        }

        public List<String> getPreloadIds() {
            return preloadIds;
        }

        public void setPreloadIds(List<String> preloadIds) {
            this.preloadIds = preloadIds;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public String getLockKey() {
        return lockKey;
    }

    public void setLockKey(String lockKey) {
        this.lockKey = lockKey;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public Duration getBatchTimeout() {
        return batchTimeout;
    }

    public void setBatchTimeout(Duration batchTimeout) {
        this.batchTimeout = batchTimeout;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public Map<String, Group> getGroups() {
        return groups;
    }

    public void setGroups(Map<String, Group> groups) {
        this.groups = groups;
    }

    /** Looks up a group, failing loudly rather than silently caching under a bad prefix. */
    public Group group(String name) {
        Group g = groups.get(name);
        if (g == null) {
            throw new IllegalArgumentException(
                    "No cache group '" + name + "' configured under cache.refresh.groups. Known groups: "
                            + groups.keySet());
        }
        return g;
    }
}
