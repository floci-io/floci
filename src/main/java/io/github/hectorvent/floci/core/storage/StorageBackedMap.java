package io.github.hectorvent.floci.core.storage;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

/**
 * A mutable {@link ConcurrentMap} facade over {@link StorageBackend}.
 *
 * <p>This is intended for emulator services that already model their control
 * plane as string-keyed maps. The facade preserves that API shape while routing
 * mutations through the configured storage backend.
 */
public class StorageBackedMap<V>
        extends AbstractMap<String, V>
        implements ConcurrentMap<String, V>
{
    private final StorageBackend<String, V> storage;

    public StorageBackedMap(StorageBackend<String, V> storage)
    {
        this.storage = Objects.requireNonNull(storage, "storage is null");
    }

    @Override
    public V put(String key, V value)
    {
        V previous = get(key);
        storage.put(key, value);
        return previous;
    }

    @Override
    public V get(Object key)
    {
        if (!(key instanceof String stringKey)) {
            return null;
        }
        return storage.get(stringKey).orElse(null);
    }

    @Override
    public boolean containsKey(Object key)
    {
        return get(key) != null;
    }

    @Override
    public V remove(Object key)
    {
        if (!(key instanceof String stringKey)) {
            return null;
        }
        V previous = get(stringKey);
        storage.delete(stringKey);
        return previous;
    }

    @Override
    public void clear()
    {
        storage.clear();
    }

    @Override
    public Set<Entry<String, V>> entrySet()
    {
        return new AbstractSet<>()
        {
            @Override
            public Iterator<Entry<String, V>> iterator()
            {
                Iterator<String> keys = new ArrayList<>(storage.keys()).iterator();
                return new Iterator<>()
                {
                    private String currentKey;

                    @Override
                    public boolean hasNext()
                    {
                        return keys.hasNext();
                    }

                    @Override
                    public Entry<String, V> next()
                    {
                        currentKey = keys.next();
                        return new SimpleEntry<>(currentKey, StorageBackedMap.this.get(currentKey));
                    }

                    @Override
                    public void remove()
                    {
                        if (currentKey == null) {
                            throw new IllegalStateException("next has not been called");
                        }
                        storage.delete(currentKey);
                        currentKey = null;
                    }
                };
            }

            @Override
            public int size()
            {
                return storage.keys().size();
            }
        };
    }

    @Override
    public V putIfAbsent(String key, V value)
    {
        V existing = get(key);
        if (existing == null) {
            storage.put(key, value);
            return null;
        }
        return existing;
    }

    @Override
    public boolean remove(Object key, Object value)
    {
        V existing = get(key);
        if (!Objects.equals(existing, value)) {
            return false;
        }
        remove(key);
        return true;
    }

    @Override
    public boolean replace(String key, V oldValue, V newValue)
    {
        V existing = get(key);
        if (!Objects.equals(existing, oldValue)) {
            return false;
        }
        storage.put(key, newValue);
        return true;
    }

    @Override
    public V replace(String key, V value)
    {
        V existing = get(key);
        if (existing == null) {
            return null;
        }
        storage.put(key, value);
        return existing;
    }
}
