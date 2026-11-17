package io.floci.aws.services.appsync;

import io.floci.aws.core.storage.StorageBackend;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The request's Region's view of a store of GraphQL APIs' resources (data sources, resolvers, functions, types, API
 * keys, channel namespaces), each keyed {@code <apiId>::...}. A resource of an API of another Region is absent here,
 * as a deleted API's is, so every operation answers for it as for one that doesn't exist. Writes go through: an
 * operation finds its API, in the request's Region, before it stores one.
 */
final class RegionalApiResources<V> implements StorageBackend<String, V> {

    private static final String SEPARATOR = "::";

    private final StorageBackend<String, V> delegate;
    private final Predicate<String> apiInRequestRegion;

    /** {@code apiInRequestRegion} tells whether an API id is of the request's Region, or of no stored API. */
    RegionalApiResources(StorageBackend<String, V> delegate, Predicate<String> apiInRequestRegion) {
        this.delegate = delegate;
        this.apiInRequestRegion = apiInRequestRegion;
    }

    @Override
    public void put(String key, V value) {
        delegate.put(key, value);
    }

    @Override
    public void putAll(Map<String, V> entries) {
        delegate.putAll(entries);
    }

    @Override
    public void putAllAtomically(Map<String, V> entries) {
        delegate.putAllAtomically(entries);
    }

    @Override
    public Optional<V> get(String key) {
        return visible(key) ? delegate.get(key) : Optional.empty();
    }

    @Override
    public void delete(String key) {
        if (visible(key)) {
            delegate.delete(key);
        }
    }

    @Override
    public void deleteAllAtomically(Set<String> keys) {
        delegate.deleteAllAtomically(keys.stream().filter(this::visible).collect(Collectors.toSet()));
    }

    @Override
    public List<V> scan(Predicate<String> keyFilter) {
        return delegate.scan(key -> keyFilter.test(key) && visible(key));
    }

    @Override
    public Set<String> keys() {
        return delegate.keys().stream().filter(this::visible).collect(Collectors.toSet());
    }

    /** Clears what this view holds, the request's Region's APIs' resources, as {@code RegionScopedStorage} does. */
    @Override
    public void clear() {
        keys().forEach(delegate::delete);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void load() {
        delegate.load();
    }

    private boolean visible(String key) {
        int separator = key.indexOf(SEPARATOR);
        return separator < 0 || apiInRequestRegion.test(key.substring(0, separator));
    }
}
