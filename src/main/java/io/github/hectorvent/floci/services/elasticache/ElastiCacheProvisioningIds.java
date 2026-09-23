package io.github.hectorvent.floci.services.elasticache;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ids claimed by in-flight creates: replication groups, standalone cache clusters and Memcached
 * clusters alike. One set, because the three share a namespace in floci whatever AWS does with
 * them, and because none of the create paths persists its record until after container startup.
 * Without a claim held across that window two creates of the same id both read three empty stores
 * and both go on to write, leaving one id in two stores: the redis paths would remove each other's
 * container and orphan a proxy listener, and a describe would report the id twice.
 *
 * <p>A separate bean rather than a field on one service, so both {@link ElastiCacheService} and
 * {@link ElastiCacheMemcachedService} claim against the same instance.
 */
@ApplicationScoped
public class ElastiCacheProvisioningIds {

    private final Set<String> claimed = ConcurrentHashMap.newKeySet();

    /**
     * Claims the id for the calling create. Returns false when another in-flight create already
     * holds it, in which case the caller must not release it.
     */
    public boolean claim(String id) {
        return claimed.add(id);
    }

    /** Releases a claim this caller took. Safe to call on any exit path. */
    public void release(String id) {
        claimed.remove(id);
    }
}
