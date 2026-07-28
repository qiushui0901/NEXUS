package com.example.requirementrag.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Small process-local cache with explicit TTL and capacity bounds. */
public final class BoundedTtlCache<K, V> {
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final Clock clock;

    public BoundedTtlCache(Duration ttl, int maxEntries) {
        this(ttl, maxEntries, Clock.systemUTC());
    }

    BoundedTtlCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttlMillis = Math.max(0, ttl == null ? 0 : ttl.toMillis());
        this.maxEntries = Math.max(0, maxEntries);
        this.clock = clock;
    }

    public Optional<V> get(K key) {
        if (!enabled() || key == null) return Optional.empty();
        Entry<V> entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (entry.expiresAtMillis() <= clock.millis()) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void put(K key, V value) {
        if (!enabled() || key == null || value == null) return;
        long now = clock.millis();
        removeExpired(now);
        entries.put(key, new Entry<>(value, now, now + ttlMillis));
        trimToCapacity();
    }

    public void invalidate(K key) {
        if (key != null) entries.remove(key);
    }

    public void invalidateWhere(java.util.function.Predicate<K> predicate) {
        if (predicate != null) entries.keySet().removeIf(predicate);
    }

    public void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private boolean enabled() {
        return ttlMillis > 0 && maxEntries > 0;
    }

    private void removeExpired(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private void trimToCapacity() {
        while (entries.size() > maxEntries) {
            entries.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().createdAtMillis()))
                    .map(Map.Entry::getKey)
                    .ifPresent(entries::remove);
        }
    }

    private record Entry<V>(V value, long createdAtMillis, long expiresAtMillis) {
    }
}
