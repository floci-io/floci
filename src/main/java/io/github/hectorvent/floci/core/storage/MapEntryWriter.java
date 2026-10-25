package io.github.hectorvent.floci.core.storage;

/**
 * A store whose values are maps and that can persist one entry of a value without rewriting the
 * rest of it. A caller that changes a single entry of a large value (one DynamoDB item of a table)
 * uses these instead of {@link StorageBackend#put}, which hands the backend the whole value.
 *
 * @param <E> the type of one map entry's value
 */
public interface MapEntryWriter<E> {

    /** Sets {@code entryKey} in the map stored under {@code key}, creating the map when absent. */
    void putEntry(String key, String entryKey, E entryValue);

    /** Removes {@code entryKey} from the map stored under {@code key}; a missing map stays missing. */
    void removeEntry(String key, String entryKey);
}
