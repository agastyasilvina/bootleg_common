package com.example.redis;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the cache. {@link ReactiveStringRedisTemplate} and {@link ObjectMapper} come from
 * Boot's autoconfiguration, so nothing extra is needed beyond the Redis connection settings.
 *
 * <p>The scheduler bean only exists when {@code cache.refresh.enabled} is true, which makes
 * it easy to run the refresher in one deployment (say, a worker) while other deployments
 * still read and write through the same cache.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheRefreshProperties.class)
@EnableScheduling
public class RedisCacheConfig {

    /**
     * ObjectProvider rather than {@code List<CacheLoader<?>>}: a plain List parameter is a
     * required dependency, so an application that declares no loaders at all — every group
     * on {@code refresh-ahead: false} — would fail to start.
     */
    @Bean
    public RedisJsonCache redisJsonCache(ReactiveStringRedisTemplate redis,
                                         ObjectMapper mapper,
                                         CacheRefreshProperties props,
                                         ObjectProvider<CacheLoader<?>> loaders) {
        return new RedisJsonCache(redis, mapper, props, loaders.stream().toList());
    }

    @Bean
    @ConditionalOnProperty(prefix = "cache.refresh", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public CacheRefreshScheduler cacheRefreshScheduler(RedisJsonCache cache,
                                                       ReactiveStringRedisTemplate redis,
                                                       CacheRefreshProperties props) {
        return new CacheRefreshScheduler(cache, redis, props);
    }
}
