package io.floci.aws.core.storage;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
                    // One-element lookahead: a key in the snapshot may be deleted before
                    // we reach it, so skip keys whose value is gone instead of yielding null.
                    private String nextKey;
                    private V nextValue;

                    @Override
                    public boolean hasNext()
                    {
                        while (nextValue == null && keys.hasNext()) {
                            nextKey = keys.next();
                            nextValue = StorageBackedMap.this.get(nextKey);
                        }
                        return nextValue != null;
                    }

                    @Override
                    public Entry<String, V> next()
                    {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        currentKey = nextKey;
                        String entryKey = nextKey;
                        V entryValue = nextValue;
                        nextKey = null;
                        nextValue = null;
                        return new Entry<>()
                        {
                            private V value = entryValue;

                            @Override
                            public String getKey()
                            {
                                return entryKey;
                            }

                            @Override
                            public V getValue()
                            {
                                return value;
                            }

                            @Override
                            public V setValue(V newValue)
                            {
                                V previous = StorageBackedMap.this.put(entryKey, newValue);
                                value = newValue;
                                return previous;
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

            @Override
            public Spliterator<Entry<String, V>> spliterator()
            {
                return unsized(this::iterator);
            }
        };
    }

    @Override
    public Set<String> keySet()
    {
        return new AbstractSet<>()
        {
            @Override
            public Iterator<String> iterator()
            {
                Iterator<Entry<String, V>> entries = entrySet().iterator();
                return new Iterator<>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return entries.hasNext();
                    }

                    @Override
                    public String next()
                    {
                        return entries.next().getKey();
                    }

                    @Override
                    public void remove()
                    {
                        entries.remove();
                    }
                };
            }

            @Override
            public int size()
            {
                return StorageBackedMap.this.size();
            }

            @Override
            public boolean contains(Object key)
            {
                return containsKey(key);
            }

            @Override
            public Spliterator<String> spliterator()
            {
                return unsized(this::iterator);
            }
        };
    }

    @Override
    public Collection<V> values()
    {
        return new AbstractCollection<>()
        {
            @Override
            public Iterator<V> iterator()
            {
                Iterator<Entry<String, V>> entries = entrySet().iterator();
                return new Iterator<>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return entries.hasNext();
                    }

                    @Override
                    public V next()
                    {
                        return entries.next().getValue();
                    }

                    @Override
                    public void remove()
                    {
                        entries.remove();
                    }
                };
            }

            @Override
            public int size()
            {
                return StorageBackedMap.this.size();
            }

            @Override
            public Spliterator<V> spliterator()
            {
                return unsized(this::iterator);
            }
        };
    }

    // size() is a separate key snapshot from the one an iterator walks, and the
    // iterator skips keys deleted in between, so the views must not report SIZED:
    // a sized stream throws "End size N is less than fixed size M" on a mismatch.
    // The iterator is taken on first traversal or split, not here, so the
    // spliterator stays late-binding like the Collection.spliterator() default.
    private static <T> Spliterator<T> unsized(Supplier<Iterator<T>> iterators)
    {
        return new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.NONNULL)
        {
            private Iterator<T> iterator;

            @Override
            public boolean tryAdvance(Consumer<? super T> action)
            {
                if (iterator == null) {
                    iterator = iterators.get();
                }
                if (!iterator.hasNext()) {
                    return false;
                }
                action.accept(iterator.next());
                return true;
            }
        };
    }

    private List<String> snapshotKeys()
    {
        return new ArrayList<>(storage.keys());
    }
}
