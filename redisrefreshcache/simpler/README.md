# Redis refresh-ahead cache (Spring WebFlux / Reactor)

Stores JSON strings in Redis with a TTL and rewrites them shortly before they expire, so hot
keys never go cold. Values are stored and returned **verbatim** — nothing parses them.

Four files, ~450 lines including comments:

| File | Role |
|---|---|
| `CacheProperties` | `cache.*` config — groups, TTLs, interval |
| `CacheLoader` | interface you implement, one per group |
| `RedisJsonCache` | get / put / evict plus the `@Scheduled` refresh pass |
| `RedisCacheConfig` | bean wiring |

Rename `com.example.redis`, drop `src/main/java/...` in, merge the YAML.
Needs `spring-boot-starter-data-redis-reactive`.

## Usage

```java
@Component
class ProfileLoader implements CacheLoader {

    private final WebClient client;

    ProfileLoader(WebClient client) { this.client = client; }

    @Override public String group() { return "profile"; }   // matches cache.groups.profile

    @Override public Mono<String> load(String id) {
        return client.get().uri("/profiles/{id}", id)
                     .retrieve().bodyToMono(String.class);   // JSON in, JSON out
    }
}
```

Your API endpoint:

```java
@GetMapping(value = "/profiles/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
Mono<String> profile(@PathVariable String id) {
    return cache.getOrLoad("profile", id);
}
```

## Which keys participate

1. **Group not listed** in `cache.groups` → untouched by any of this.
2. **`refresh-ahead: false`** → TTL only, expires and reloads lazily. No `CacheLoader` needed.
3. **`put(group, id, json, false)`** → per-entry opt-out inside a refreshed group.

`preload-ids` marks specific ids as immediately due at startup so the first pass warms them:

```yaml
preload-ids: ${HOT_PROFILE_IDS:}     # comma-separated; empty or absent is fine
```

Only ids that aren't already cached get seeded, so a rolling restart doesn't stampede the origin.

## Important: writes must go through `put`

The refresh pass finds work via a per-group sorted set (`cache:refresh:due:{group}`) scored by
when each entry falls due. `put` maintains that index. **A value written to Redis directly —
by another service, by a migration script, by `redis-cli` — is never registered, so it will
expire instead of refreshing.**

If a separate service populates these keys, it must either call `put`, or `ZADD` the due-index
itself with score `now + ttl - refreshBefore` (epoch seconds).

## Two things that break silently if changed

**The scheduled method blocks, on purpose.** `fixedDelay` measures from the end of the previous
invocation. A method that fires `subscribe()` and returns looks instantaneous, so the next pass
starts immediately and passes overlap. Blocking is correct here — it runs on a `TaskScheduler`
thread, not a Netty event loop. Set `spring.task.scheduling.pool.size` above its default of 1.

**`refresh-interval` must be well under `refresh-before`.** An entry is eligible only during the
last `refresh-before` of its life; the effective cycle is `refresh-interval + pass duration`. If
that exceeds the window, passes step over it and entries expire. Aim for `refresh-before` ≥ 2 ×
`refresh-interval` — `checkTimings()` warns at startup if you drift.

## Failure behaviour

- **Loader errors** → existing value is left alone to expire. The TTL is never extended on data
  that couldn't be verified. One failing id doesn't abort the batch.
- **Loader returns empty** → treated as "gone upstream": key deleted, dropped from the index.
- **Two pods** → `SET NX` lock, TTL = the refresh interval, so it lapses on its own right about
  when the next pass is due. Nothing to release, so no chance of deleting a successor's lock.

## Known gaps

- `getOrLoad` doesn't dedupe concurrent cold misses on the same key. Under `maxmemory` eviction
  every in-flight request calls the loader. Add a per-key in-flight guard if traffic warrants.
- An id whose loader always errors stays in the due-index and retries every pass forever. Add a
  consecutive-failure counter if that matters.
