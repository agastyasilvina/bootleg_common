package com.example.redis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link ReactiveStringRedisTemplate} comes from Boot's autoconfiguration, so nothing is needed
 * here beyond the usual {@code spring.data.redis.*} connection settings.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
@EnableScheduling
public class RedisCacheConfig {

    /**
     * ObjectProvider rather than {@code List<CacheLoader>}: a plain List parameter is a required
     * dependency, so an application declaring no loaders at all would fail to start.
     */
    @Bean
    public RedisJsonCache redisJsonCache(ReactiveStringRedisTemplate redis,
                                         CacheProperties props,
                                         ObjectProvider<CacheLoader> loaders) {
        return new RedisJsonCache(redis, props, loaders.stream().toList());
    }
}
