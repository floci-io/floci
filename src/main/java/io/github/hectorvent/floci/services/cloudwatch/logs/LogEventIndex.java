package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * One account's stored event keys, oldest first, so the stored-event ceiling and the retention
 * policy find the events to drop without listing the whole store on every PutLogEvents.
 * Ordered like the store's events are read: timestamp, then ingestion sequence, then event id.
 * Not thread-safe; callers synchronize on the instance. An account keeps one instance, so its
 * monitor is the one lock for that account's writes; a stale index is refilled in place.
 */
final class LogEventIndex {

    private boolean stale = true;

    private record Entry(long timestamp, long sequence, String eventId, String key, String group) {}

    private static final Comparator<Entry> ORDER = Comparator.comparingLong(Entry::timestamp)
            .thenComparingLong(Entry::sequence)
            .thenComparing(Entry::eventId, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
            .thenComparing(Entry::key);

    private final NavigableSet<Entry> oldestFirst = new TreeSet<>(ORDER);
    private final Map<String, NavigableSet<Entry>> byGroup = new HashMap<>();
    private final Map<String, Entry> byKey = new HashMap<>();

    /** Indexes the event under {@code group}, its {@code region::group::}; keys alone cannot tell. */
    void add(String key, String group, LogEvent event) {
        if (byKey.containsKey(key)) {
            return;
        }
        Entry entry = new Entry(event.getTimestamp(), event.getSequence(), event.getEventId(), key, group);
        byKey.put(key, entry);
        oldestFirst.add(entry);
        byGroup.computeIfAbsent(group, g -> new TreeSet<>(ORDER)).add(entry);
    }

    /** True until filled, and again after a write that may not have reached the store. */
    boolean isStale() {
        return stale;
    }

    void markStale() {
        stale = true;
    }

    /** Drops every entry and marks the index stale, for a store that was wiped. */
    void clear() {
        startRefill();
        markStale();
    }

    /** Empties the index for a refill from the store; {@link #markFilled()} ends the refill. */
    void startRefill() {
        oldestFirst.clear();
        byGroup.clear();
        byKey.clear();
    }

    void markFilled() {
        stale = false;
    }

    int size() {
        return oldestFirst.size();
    }

    /** The keys of the {@code count} oldest events, oldest first. Removes nothing. */
    List<String> oldest(int count) {
        List<String> keys = new ArrayList<>(Math.max(0, count));
        for (Entry entry : oldestFirst) {
            if (keys.size() >= count) {
                break;
            }
            keys.add(entry.key());
        }
        return keys;
    }

    /** The keys of the group's events older than {@code cutoff}, oldest first. Removes nothing. */
    List<String> olderThan(String group, long cutoff) {
        List<String> keys = new ArrayList<>();
        NavigableSet<Entry> events = byGroup.get(group);
        if (events != null) {
            for (Entry entry : events) {
                if (entry.timestamp() >= cutoff) {
                    break;
                }
                keys.add(entry.key());
            }
        }
        return keys;
    }

    /** Removes the event stored under {@code key}, if the index holds it. */
    void remove(String key) {
        Entry entry = byKey.remove(key);
        if (entry != null) {
            oldestFirst.remove(entry);
            removeFromGroup(entry);
        }
    }

    private void removeFromGroup(Entry entry) {
        NavigableSet<Entry> events = byGroup.get(entry.group());
        if (events != null) {
            events.remove(entry);
            if (events.isEmpty()) {
                byGroup.remove(entry.group());
            }
        }
    }

    /** Whether {@code key} is the stream's: its {@code region::group::stream::}, then only timestamp and id. */
    static boolean isOfStream(String key, String streamPrefix) {
        if (!key.startsWith(streamPrefix)) {
            return false;
        }
        int separator = key.indexOf("::", streamPrefix.length());
        return separator >= 0 && separator == key.lastIndexOf("::");
    }
}
