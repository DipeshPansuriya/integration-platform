package io.integrationplatform.tenancy.internal.web;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.support.AbstractValueAdaptingCache;

/**
 * R13 fallback (ADR-007 Decision 4 and the S-h outage gate): the identity provider's key set, kept for
 * {@code key-set-ttl} and then fetched again. When that fetch fails - the provider is down, or its breaker is open -
 * the last fetched key set keeps validating for up to {@code key-set-fallback-max-age}, so tokens signed with a known
 * key are still accepted and no valid token meets a 5xx. A token with an unknown key id asks for a refresh
 * ({@link #invalidate()}); while the provider is down that refresh fails, the old set stays, and the token is refused.
 */
final class OutageTolerantKeySetCache extends AbstractValueAdaptingCache {

    private static final Logger LOG = LoggerFactory.getLogger(OutageTolerantKeySetCache.class);

    private record Entry(Object value, long fetchedAt, boolean refresh) {
    }

    private final ConcurrentMap<Object, Entry> store = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final long fallbackMaxAgeNanos;

    OutageTolerantKeySetCache(Duration ttl, Duration fallbackMaxAge) {
        super(false);
        this.ttlNanos = ttl.toNanos();
        this.fallbackMaxAgeNanos = fallbackMaxAge.toNanos();
    }

    @Override
    public String getName() {
        return IdentityProviderClient.NAME + "-keys";
    }

    @Override
    public Object getNativeCache() {
        return store;
    }

    @Override
    protected Object lookup(Object key) {
        Entry entry = store.get(key);
        return entry == null ? null : entry.value();
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Object key, Callable<T> loader) {
        Entry entry = store.get(key);
        long now = System.nanoTime();
        if (entry != null && !entry.refresh() && now - entry.fetchedAt() < ttlNanos) {
            return (T) entry.value();
        }
        try {
            T fresh = loader.call();
            store.put(key, new Entry(fresh, now, false));
            return fresh;
        } catch (Exception unavailable) {
            if (entry != null && now - entry.fetchedAt() < fallbackMaxAgeNanos) {
                LOG.warn("event=identity.provider.fallback reason=\"{}\" - validating with the last fetched key set",
                        unavailable.getMessage());
                return (T) entry.value();
            }
            throw new ValueRetrievalException(key, loader, unavailable);
        }
    }

    @Override
    public void put(Object key, Object value) {
        store.put(key, new Entry(value, System.nanoTime(), false));
    }

    @Override
    public void evict(Object key) {
        store.remove(key);
    }

    @Override
    public void clear() {
        store.clear();
    }

    /** A refresh request (unknown key id): fetch again on the next read, but keep the old set as the fallback. */
    @Override
    public boolean invalidate() {
        store.replaceAll((key, entry) -> new Entry(entry.value(), entry.fetchedAt(), true));
        return !store.isEmpty();
    }
}
