package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RegionResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Region view of a factory-owned account store, for a service whose resources are Regional: each key is
 * stored under the request's Region ({@code us-east-1/key}), so a Region sees only its own. Older,
 * regionless keys belong only to the configured default region; new writes always record their region
 * explicitly. A logical key may hold any character, a slash and a leading Region included, since every key
 * this view writes starts with its own Region. Only a stored key without one is read as regionless, so a
 * regionless key is one whose first label is not a Region: one {@link RegionResolver#isKnownRegion} admits,
 * the rule {@code AccountContextFilter} serves requests by. With
 * {@code floci.partitions.allow-unknown-regions}, a key stored for a Region no partition knows therefore reads
 * as a regionless one, and a regionless key stored before this view under a name starting with a Region
 * reads as that Region's.
 */
public final class RegionScopedStorage<V> implements StorageBackend<String, V> {

    private final StorageBackend<String, V> delegate;
    private final RegionResolver regions;

    public RegionScopedStorage(StorageFactory factory, RegionResolver regions, String service, String file,
                               TypeReference<Map<String, V>> type) {
        this(factory.create(service, file, type), regions);
    }

    /** The Region view of {@code delegate}, a store the caller made with options of its own. */
    public RegionScopedStorage(StorageBackend<String, V> delegate, RegionResolver regions) {
        this.delegate = delegate;
        this.regions = regions;
    }

    @Override
    public void put(String key, V value) {
        delegate.put(prefix() + key, value);
        if (regionlessHere(key) && delegate.get(key).isPresent()) {
            delegate.delete(key);
        }
    }

    @Override
    public Optional<V> get(String key) {
        return delegate.get(prefix() + key)
                .or(() -> regionlessHere(key) ? delegate.get(key) : Optional.empty());
    }

    @Override
    public void delete(String key) {
        if (regionlessHere(key)) {
            delegate.delete(key);
        }
        delegate.delete(prefix() + key);
    }

    @Override
    public List<V> scan(Predicate<String> keyFilter) {
        List<V> values = new ArrayList<>();
        for (String key : keys()) {
            if (keyFilter.test(key)) {
                get(key).ifPresent(values::add);
            }
        }
        return values;
    }

    @Override
    public Set<String> keys() {
        String prefix = prefix();
        boolean includeLegacy = defaultRegion();
        Set<String> keys = new HashSet<>();
        for (String stored : delegate.keys()) {
            String key = logicalKeyOf(stored, prefix, includeLegacy);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Deletes, in one commit, the request's Region's values that {@code valueFilter} accepts, among the
     * logical keys {@code keyFilter} accepts. In the default Region the regionless keys go too, an account
     * store's unprefixed ones included, as {@link StorageOperations#deleteMatchingAtomically} deletes them
     * from the account store itself.
     */
    void deleteMatchingAtomically(Predicate<String> keyFilter, Predicate<V> valueFilter) {
        String prefix = prefix();
        boolean includeLegacy = defaultRegion();
        StorageOperations.deleteMatchingAtomically(delegate, stored -> {
            String key = logicalKeyOf(stored, prefix, includeLegacy);
            return key != null && keyFilter.test(key);
        }, valueFilter);
    }

    /**
     * The logical key {@code stored} holds in the Region whose keys start with {@code prefix}, or null when
     * it is another Region's. A regionless key is the default Region's, so it counts with {@code includeLegacy}.
     */
    private static String logicalKeyOf(String stored, String prefix, boolean includeLegacy) {
        if (stored.startsWith(prefix)) {
            return stored.substring(prefix.length());
        }
        return includeLegacy && !regionPrefixed(stored) ? stored : null;
    }

    @Override
    public void clear() {
        keys().forEach(this::delete);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void load() {
        delegate.load();
    }

    private String prefix() {
        return regions.getRegion() + "/";
    }

    private boolean defaultRegion() {
        return regions.getRegion().equals(regions.getDefaultRegion());
    }

    /**
     * Whether {@code key} may also be stored regionless, as it was before this view, and is read here that way:
     * only in the default Region, and only when it doesn't start with a Region, as a stored key of this view does.
     */
    private boolean regionlessHere(String key) {
        return key != null && defaultRegion() && !regionPrefixed(key);
    }

    /** Whether {@code key} starts with a Region and a slash, as every key this view stores does. */
    private static boolean regionPrefixed(String key) {
        int slash = key.indexOf('/');
        return slash > 0 && RegionResolver.isKnownRegion(key.substring(0, slash));
    }
}
