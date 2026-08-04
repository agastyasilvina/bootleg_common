package com.example.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <pre>
 * cache:
 *   refresh-interval: PT5M
 *   groups:
 *     profile:
 *       key-prefix: "profile:"
 *       ttl: P1D
 *       refresh-before: PT10M
 * </pre>
 *
 * Only the groups listed here are managed. Every other Redis key in your application is
 * untouched by any of this.
 */
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    /** Turns the scheduled refresh pass off without removing any code. */
    private boolean refreshEnabled = true;

    /**
     * How often the refresh pass runs. Also doubles as the lock TTL and the pass timeout, so
     * there is only one timing knob to get wrong.
     */
    private Duration refreshInterval = Duration.ofMinutes(5);

    /** Redis key for the cross-instance lock, so only one pod runs a pass at a time. */
    private String lockKey = "cache:refresh:lock";

    /** Max simultaneous loader calls. Keeps a large due-batch from flooding the origin. */
    private int concurrency = 8;

    private Map<String, Group> groups = new LinkedHashMap<>();

    public static class Group {

        /** Prefix for this group's keys, e.g. "profile:". Full key is prefix + id. */
        private String keyPrefix;

        /** TTL written with every value. */
        private Duration ttl = Duration.ofDays(1);

        /** How long before expiry an entry becomes eligible for refresh. */
        private Duration refreshBefore = Duration.ofMinutes(10);

        /** False keeps the TTL but never refreshes early — entries just expire. */
        private boolean refreshAhead = true;

        /**
         * Ids marked due at startup so the first pass warms them before anyone reads them.
         * Meant to be supplied at deploy time, e.g. {@code preload-ids: ${HOT_IDS:}}.
         */
        private List<String> preloadIds = new ArrayList<>();

        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }

        public Duration getRefreshBefore() { return refreshBefore; }
        public void setRefreshBefore(Duration refreshBefore) { this.refreshBefore = refreshBefore; }

        public boolean isRefreshAhead() { return refreshAhead; }
        public void setRefreshAhead(boolean refreshAhead) { this.refreshAhead = refreshAhead; }

        public List<String> getPreloadIds() { return preloadIds; }
        public void setPreloadIds(List<String> preloadIds) { this.preloadIds = preloadIds; }
    }

    public boolean isRefreshEnabled() { return refreshEnabled; }
    public void setRefreshEnabled(boolean refreshEnabled) { this.refreshEnabled = refreshEnabled; }

    public Duration getRefreshInterval() { return refreshInterval; }
    public void setRefreshInterval(Duration refreshInterval) { this.refreshInterval = refreshInterval; }

    public String getLockKey() { return lockKey; }
    public void setLockKey(String lockKey) { this.lockKey = lockKey; }

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }

    public Map<String, Group> getGroups() { return groups; }
    public void setGroups(Map<String, Group> groups) { this.groups = groups; }

    /** Fails loudly on an unknown group rather than caching under a null prefix. */
    public Group group(String name) {
        Group g = groups.get(name);
        if (g == null) {
            throw new IllegalArgumentException(
                    "No cache group '" + name + "' under cache.groups. Known: " + groups.keySet());
        }
        return g;
    }
}
