package io.github.hectorvent.floci.core.storage;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A mutable {@link Map} facade over {@link StorageBackend}.
 *
 * <p>This is intended for emulator services that already model their control
 * plane as string-keyed maps. The facade preserves that API shape while routing
 * mutations through the configured storage backend. Null keys and values are
 * not supported.
 */
public class StorageBackedMap<V>
        extends AbstractMap<String, V>
{
    private final StorageBackend<String, V> storage;

    public StorageBackedMap(StorageBackend<String, V> storage)
    {
        this.storage = Objects.requireNonNull(storage, "storage is null");
    }

    @Override
    public V put(String key, V value)
    {
        Objects.requireNonNull(key, "key is null");
        Objects.requireNonNull(value, "value is null");
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
        if (!(key instanceof String stringKey)) {
            return false;
        }
        return storage.get(stringKey).isPresent();
    }

    @Override
    public synchronized V computeIfAbsent(String key, Function<? super String, ? extends V> mappingFunction)
    {
        Objects.requireNonNull(key, "key is null");
        Objects.requireNonNull(mappingFunction, "mappingFunction is null");

        V value = get(key);
        if (value != null) {
            return value;
        }

        V newValue = mappingFunction.apply(key);
        if (newValue != null) {
            storage.put(key, newValue);
        }
        return newValue;
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
                Iterator<String> keys = snapshotKeys().iterator();
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
                        String entryKey = currentKey;
                        return new Entry<>()
                        {
                            @Override
                            public String getKey()
                            {
                                return entryKey;
                            }

                            @Override
                            public V getValue()
                            {
                                return StorageBackedMap.this.get(entryKey);
                            }

                            @Override
                            public V setValue(V value)
                            {
                                return StorageBackedMap.this.put(entryKey, value);
                            }
                        };
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
                return snapshotKeys().size();
            }
        };
    }

    private List<String> snapshotKeys()
    {
        return new ArrayList<>(storage.keys());
    }
}
