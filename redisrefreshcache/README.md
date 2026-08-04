# Redis refresh-ahead cache (Spring WebFlux / Reactor)

Writes JSON to Redis always with a TTL, and rewrites entries shortly before they expire so
hot keys never go cold. Which keys participate is decided entirely by configuration, so you
can turn it on for a couple of prefixes and leave the rest of your keyspace alone.

## Files

| File | Role |
|---|---|
| `CacheRefreshProperties` | `cache.refresh.*` config — groups, TTLs, intervals |
| `CacheLoader` | interface you implement, one per group, to fetch from the origin |
| `RedisJsonCache` | get / put / evict; owns serialization and the due-index |
| `CacheRefreshScheduler` | the `@Scheduled` job — leader lock, batching, timing checks |
| `RedisCacheConfig` | bean wiring |
| `application-cache.yml` | annotated example config |

Change `com.example.redis` to your own package and drop `src/main/java/...` into your project.
Requires `spring-boot-starter-data-redis-reactive`.

## Usage

Implement a loader for each group you want refreshed:

```java
@Component
class ProfileLoader implements CacheLoader<Profile> {

    private final ProfileClient client;

    ProfileLoader(ProfileClient client) { this.client = client; }

    @Override public String group() { return "profile"; }   // matches cache.refresh.groups.profile

    @Override public Mono<Profile> load(String id) {
        return client.fetch(id);
    }
}
```

Then read through the cache:

```java
Mono<Profile> profile = cache.getOrLoad("profile", id, Profile.class);
```

That's it. The scheduler picks up the entry from the due-index and keeps it warm.

## Selecting which keys get refreshed

Three levers, coarse to fine:

1. **Group not listed in `cache.refresh.groups`** — the cache ignores those keys entirely.
2. **`refresh-ahead: false`** — entries get a TTL and expire normally, reloading lazily on the
   next read. No `CacheLoader` needed. Use this to disable refreshing without deleting code.
3. **`put(group, id, value, false)`** — per-entry opt-out inside an otherwise refreshed group.

`preload-ids` seeds specific ids as immediately due at startup, so they are warmed before
anyone reads them. Bind it from a generated env var:

```yaml
preload-ids: ${HOT_PROFILE_IDS:}     # comma-separated; empty or absent is fine
```

Startup only seeds ids that aren't already cached, so a rolling restart doesn't stampede
the origin for entries that are already warm.

## Two things that will silently break if you change them

**The scheduled method blocks, on purpose.** `fixedDelay` measures from the end of the previous
invocation. A method that fires `subscribe()` and returns looks instantaneous to the scheduler,
so the next pass starts immediately and passes overlap. Blocking is correct here — it runs on a
`TaskScheduler` thread, not a Netty event loop. Do set `spring.task.scheduling.pool.size` above
the default of 1, or your other `@Scheduled` tasks queue behind this one.

**`interval` must be well under `refresh-before`.** An entry is only eligible during the last
`refresh-before` of its life. Effective cycle is `interval + pass duration`, so if that exceeds
the window, passes step straight over it and entries expire. Aim for `refresh-before` ≥ 2 ×
`interval`; `CacheRefreshScheduler.checkTimings()` warns at startup if you drift.

## Failure behaviour

- **Loader errors** → the existing cached value is left alone to expire on its own. The TTL is
  never extended on data that couldn't be verified. One failing id doesn't abort the batch.
- **Loader returns empty** → treated as "gone upstream": the key is deleted and drops out of
  the due-index.
- **Unparseable JSON** → the entry is evicted and treated as a miss, so a poison value can't
  fail every read until it expires.
- **Two instances** → a `SET NX` lock means one pass runs at a time. Release is a Lua
  compare-and-delete on a per-run token, so a slow pass can't delete its successor's lock.

## Known gaps

- **Cold-miss stampede.** `getOrLoad` doesn't dedupe concurrent misses on the same key. If a hot
  key is ever evicted under `maxmemory` pressure, every in-flight request calls the loader. Add a
  per-key in-flight `ConcurrentHashMap<String, Mono<?>>` guard if that matters for your traffic.
- **Permanently failing ids linger.** An id whose loader always errors stays in the due-index and
  is retried every pass forever. If that's a concern, track consecutive failures and drop the
  registration past a threshold.
- **Leader election is a plain Redis lock.** Fine for one job. If you grow several scheduled jobs
  needing it, ShedLock handles lock extension for jobs that outlive their own TTL.
