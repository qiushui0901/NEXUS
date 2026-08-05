package com.example.requirementrag.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内小型缓存：显式 TTL 过期 + 容量上限，线程安全。
 * 容量超限时按创建时间淘汰最旧条目。
 */
public final class BoundedTtlCache<K, V> {
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final Clock clock;

    /**
     * 创建缓存。
     *
     * @param ttl        条目过期时长；TTL 或容量任一为 0 时缓存自动禁用
     * @param maxEntries 容量上限
     */
    public BoundedTtlCache(Duration ttl, int maxEntries) {
        this(ttl, maxEntries, Clock.systemUTC());
    }

    /** 内部构造：注入 Clock 便于测试，负值钳制为 0。 */
    BoundedTtlCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttlMillis = Math.max(0, ttl == null ? 0 : ttl.toMillis());
        this.maxEntries = Math.max(0, maxEntries);
        this.clock = clock;
    }

    /**
     * 读取键对应值；已过期条目在此处惰性删除。
     *
     * @return 命中且未过期时为值，否则为 empty
     */
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

    /**
     * 写入键值并记录过期时间：写入前先清理过期条目，
     * 写入后超出容量时淘汰最旧条目；缓存禁用时忽略写入。
     */
    public void put(K key, V value) {
        if (!enabled() || key == null || value == null) return;
        long now = clock.millis();
        removeExpired(now);
        entries.put(key, new Entry<>(value, now, now + ttlMillis));
        trimToCapacity();
    }

    /** 删除指定键对应的条目。 */
    public void invalidate(K key) {
        if (key != null) entries.remove(key);
    }

    /** 删除所有满足谓词的键对应的条目。 */
    public void invalidateWhere(java.util.function.Predicate<K> predicate) {
        if (predicate != null) entries.keySet().removeIf(predicate);
    }

    /** 清空全部条目。 */
    public void clear() {
        entries.clear();
    }

    /** 当前条目数（可能含已过期但尚未清理的条目）。 */
    int size() {
        return entries.size();
    }

    /** 缓存是否启用：TTL 与容量上限均需大于 0。 */
    private boolean enabled() {
        return ttlMillis > 0 && maxEntries > 0;
    }

    /** 移除所有已过期的条目。 */
    private void removeExpired(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    /** 超出容量时按创建时间淘汰最旧条目，直到不超限。 */
    private void trimToCapacity() {
        while (entries.size() > maxEntries) {
            entries.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().createdAtMillis()))
                    .map(Map.Entry::getKey)
                    .ifPresent(entries::remove);
        }
    }

    /** 缓存条目：值、创建时间与过期时间戳。 */
    private record Entry<V>(V value, long createdAtMillis, long expiresAtMillis) {
    }
}
