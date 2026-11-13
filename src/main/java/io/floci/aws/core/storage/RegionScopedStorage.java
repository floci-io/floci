package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.RegionResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Region view of a factory-owned account store, for a service whose resources are Regional: each key is
 * stored under the request's Region ({@code us-east-1/key}), so a Region sees only its own. Older,
 * regionless keys belong only to the configured default region; new writes always record their region
 * explicitly. A logical key may hold any character, a slash included, except that it cannot itself start
 * with a Region and a slash: that is how a stored key's Region is told apart.
 */
public final class RegionScopedStorage<V> implements StorageBackend<String, V> {

    /** The shape of an AWS Region name, for Regions newer than Floci's partition data. */
    private static final Pattern REGION_NAME = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");

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
        if (!logicalKey(key)) {
            throw new IllegalArgumentException("Region-scoped storage keys cannot start with a region prefix");
        }
        delegate.put(prefix() + key, value);
        if (defaultRegion() && delegate.get(key).isPresent()) {
            delegate.delete(key);
        }
    }

    @Override
    public void putAll(Map<String, V> entries) {
        delegate.putAll(scoped(entries));
        dropLegacyCopies(entries.keySet());
    }

    @Override
    public void putAllAtomically(Map<String, V> entries) {
        delegate.putAllAtomically(scoped(entries));
        dropLegacyCopies(entries.keySet());
    }

    @Override
    public void deleteAllAtomically(Set<String> keys) {
        Set<String> stored = new HashSet<>();
        for (String key : keys) {
            if (logicalKey(key)) {
                stored.add(prefix() + key);
                if (defaultRegion() && delegate.get(key).isPresent()) {
                    stored.add(key);
                }
            }
        }
        delegate.deleteAllAtomically(stored);
    }

    /** {@code entries} under the request's Region, each key checked as {@link #put} checks it. */
    private Map<String, V> scoped(Map<String, V> entries) {
        Map<String, V> scoped = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            if (!logicalKey(key)) {
                throw new IllegalArgumentException("Region-scoped storage keys cannot start with a region prefix");
            }
            scoped.put(prefix() + key, value);
        });
        return scoped;
    }

    /** Once written under the default Region, a key's regionless copy is dropped, as {@link #put} drops it. */
    private void dropLegacyCopies(Set<String> keys) {
        if (defaultRegion()) {
            keys.stream().filter(key -> delegate.get(key).isPresent()).forEach(delegate::delete);
        }
    }

    @Override
    public Optional<V> get(String key) {
        if (!logicalKey(key)) {
            return Optional.empty();
        }
        return delegate.get(prefix() + key)
                .or(() -> defaultRegion() ? delegate.get(key) : Optional.empty());
    }

    @Override
    public void delete(String key) {
        if (logicalKey(key)) {
            if (defaultRegion()) {
                delegate.delete(key);
            }
            delegate.delete(prefix() + key);
        }
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
            if (stored.startsWith(prefix)) {
                String key = stored.substring(prefix.length());
                if (logicalKey(key)) {
                    keys.add(key);
                }
            } else if (includeLegacy && !regionPrefixed(stored)) {
                keys.add(stored);
            }
        }
        return keys;
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

    private static boolean logicalKey(String key) {
        return key != null && !regionPrefixed(key);
    }

    /** Whether {@code key} starts with a Region and a slash, as every key this view stores does. */
    private static boolean regionPrefixed(String key) {
        int slash = key.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String region = key.substring(0, slash);
        return AwsPartitions.isPublishedRegion(region) || REGION_NAME.matcher(region).matches();
    }
}
