package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.PointInTimeChange;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The change history behind point-in-time recovery. A change keeps what it replaced, so a table at
 * a time is the live table with every later change undone. Each change is stored on its own, under
 * {@code <storage key>#<sequence>}.
 */
final class DynamoDbPointInTimeRecovery {

    record RestoreWindow(Instant earliest, Instant latest) {}

    /** {@code oldItem} or {@code newItem} is null when the item did not exist then. */
    record ItemChange(JsonNode oldItem, JsonNode newItem, Instant lastWrite) {}

    /** The changes of one table, oldest first. */
    private static final class Changes {
        private final ArrayDeque<PointInTimeChange> list = new ArrayDeque<>();
        private long nextSequence;
        private boolean dropped;
        // itemsAt leaves out a change recorded after it read the list, so that change must fall after its time.
        private Instant notBefore = Instant.MIN;
    }

    private final StorageBackend<String, PointInTimeChange> store;
    private final Duration latestLag;
    private final Clock clock;
    // Keyed like DynamoDbService.itemsByTable: "<account>/<region>::<table>".
    private final ConcurrentHashMap<String, Changes> changesByTable = new ConcurrentHashMap<>();

    DynamoDbPointInTimeRecovery(StorageBackend<String, PointInTimeChange> store, String defaultAccountId,
                                Duration latestLag, Clock clock) {
        this.store = store;
        this.latestLag = latestLag;
        this.clock = clock;
        Map<String, PointInTimeChange> stored = new HashMap<>();
        if (store instanceof AccountAwareStorageBackend<PointInTimeChange> aware) {
            stored.putAll(aware.scanAllAccountsRaw());
        } else {
            for (String key : store.keys()) {
                store.get(key).ifPresent(change -> stored.put(defaultAccountId + "/" + key, change));
            }
        }
        stored.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().getSequence()))
                .forEach(entry -> changesByTable
                        .computeIfAbsent(entry.getKey().substring(0, entry.getKey().lastIndexOf('#')),
                                ignored -> new Changes())
                        .list.add(entry.getValue()));
        for (Changes changes : changesByTable.values()) {
            changes.nextSequence = changes.list.getLast().getSequence() + 1;
        }
    }

    void clear() {
        changesByTable.clear();
    }

    /** Call after point-in-time recovery of the table is turned off, so no writer records again. */
    void drop(String accountId, String storageKey) {
        Changes changes = changesByTable.remove(key(accountId, storageKey));
        if (changes == null) {
            return;
        }
        synchronized (changes) {
            changes.dropped = true;
            for (PointInTimeChange change : changes.list) {
                delete(accountId, storageKey, change);
            }
        }
    }

    /** Call before the item is written. */
    void recordItem(String accountId, String storageKey, TableDefinition table, String itemKey, JsonNode oldItem) {
        if (!table.isPointInTimeRecoveryEnabled()) {
            return;
        }
        Changes changes = changesByTable.computeIfAbsent(key(accountId, storageKey), ignored -> new Changes());
        synchronized (changes) {
            if (changes.dropped || !table.isPointInTimeRecoveryEnabled()) {
                return;
            }
            Instant now = later(clock.instant().truncatedTo(ChronoUnit.MILLIS), changes.notBefore);
            PointInTimeChange change = new PointInTimeChange(changes.nextSequence++, now, itemKey,
                    oldItem != null ? oldItem.deepCopy() : null);
            changes.list.add(change);
            put(accountId, storageKey, change);
            Instant cutoff = now.minus(recoveryPeriod(table));
            while (changes.list.getFirst().getTime().isBefore(cutoff)) {
                delete(accountId, storageKey, changes.list.removeFirst());
            }
        }
    }

    /** AWS words an InvalidRestoreTimeException with the latest time to the nanosecond. */
    RestoreWindow window(TableDefinition table) {
        Instant now = clock.instant();
        Instant earliest = later(table.getPointInTimeRecoveryStartDateTime(), now.minus(recoveryPeriod(table)));
        return new RestoreWindow(earliest, later(earliest, now.minus(latestLag)));
    }

    /**
     * An item changed after {@code time} takes the value that its first change after it replaced.
     * Writers record a change before they write the item. So the live items are copied first, and an
     * item with no change after {@code time} is read again under {@code underItemLock}, as in
     * {@link #changesBetween}.
     */
    List<JsonNode> itemsAt(String accountId, String storageKey, Map<String, JsonNode> liveItems, Instant time,
                           BiFunction<String, Supplier<JsonNode>, JsonNode> underItemLock) {
        Map<String, JsonNode> items = new HashMap<>(liveItems);
        Changes changes = changesByTable.computeIfAbsent(key(accountId, storageKey), ignored -> new Changes());
        Set<String> undone = new HashSet<>();
        Set<String> written = new HashSet<>();
        long seen;
        synchronized (changes) {
            seen = changes.nextSequence;
            changes.notBefore = later(changes.notBefore, time.truncatedTo(ChronoUnit.MILLIS).plusMillis(1));
            for (PointInTimeChange change : changes.list) {
                String itemKey = change.getItemKey();
                if (!change.getTime().isAfter(time)) {
                    written.add(itemKey);
                    continue;
                }
                if (!undone.add(itemKey)) {
                    continue;
                }
                if (change.getOldItem() == null) {
                    items.remove(itemKey);
                } else {
                    items.put(itemKey, change.getOldItem());
                }
            }
        }
        written.removeAll(undone);
        ValuesBefore valuesBefore = new ValuesBefore(changes, seen, liveItems);
        for (String itemKey : written) {
            JsonNode item = underItemLock.apply(itemKey, () -> valuesBefore.of(itemKey));
            if (item == null) {
                items.remove(itemKey);
            } else {
                items.put(itemKey, item);
            }
        }
        return new ArrayList<>(items.values());
    }

    /**
     * Each item written from {@code from} until before {@code to}, in the order of its first write
     * there, with its values before and after those writes. {@code underItemLock} runs a read under
     * the lock that a writer of the item holds from recording its change until the item is written.
     */
    List<ItemChange> changesBetween(String accountId, String storageKey, Map<String, JsonNode> liveItems,
                                    Instant from, Instant to,
                                    BiFunction<String, Supplier<JsonNode>, JsonNode> underItemLock) {
        Changes changes = changesByTable.get(key(accountId, storageKey));
        if (changes == null) {
            return List.of();
        }
        Map<String, JsonNode> before = new LinkedHashMap<>();
        Map<String, Instant> lastWrites = new HashMap<>();
        Map<String, JsonNode> after = new HashMap<>();
        long seen;
        synchronized (changes) {
            seen = changes.nextSequence;
            for (PointInTimeChange change : changes.list) {
                String itemKey = change.getItemKey();
                if (change.getTime().isBefore(from)) {
                    continue;
                }
                if (change.getTime().isBefore(to)) {
                    if (!before.containsKey(itemKey)) {
                        before.put(itemKey, change.getOldItem());
                    }
                    lastWrites.put(itemKey, change.getTime());
                } else if (before.containsKey(itemKey) && !after.containsKey(itemKey)) {
                    after.put(itemKey, change.getOldItem());
                }
            }
        }
        List<ItemChange> result = new ArrayList<>();
        ValuesBefore valuesBefore = new ValuesBefore(changes, seen, liveItems);
        before.forEach((itemKey, oldItem) -> {
            JsonNode newItem = after.containsKey(itemKey) ? after.get(itemKey)
                    : underItemLock.apply(itemKey, () -> valuesBefore.of(itemKey));
            result.add(new ItemChange(oldItem, newItem, lastWrites.get(itemKey)));
        });
        return result;
    }

    /** The value that the first change of each item from sequence {@code since} replaced, or the live item. */
    private static final class ValuesBefore {
        private final Changes changes;
        private final Map<String, JsonNode> liveItems;
        private final Map<String, JsonNode> replaced = new HashMap<>();
        private long read;

        ValuesBefore(Changes changes, long since, Map<String, JsonNode> liveItems) {
            this.changes = changes;
            this.liveItems = liveItems;
            this.read = since;
        }

        JsonNode of(String itemKey) {
            synchronized (changes) {
                List<PointInTimeChange> unread = new ArrayList<>();
                Iterator<PointInTimeChange> newestFirst = changes.list.descendingIterator();
                while (newestFirst.hasNext()) {
                    PointInTimeChange change = newestFirst.next();
                    if (change.getSequence() < read) {
                        break;
                    }
                    unread.add(change);
                }
                for (PointInTimeChange change : unread.reversed()) {
                    if (!replaced.containsKey(change.getItemKey())) {
                        replaced.put(change.getItemKey(), change.getOldItem());
                    }
                }
                read = changes.nextSequence;
                return replaced.containsKey(itemKey) ? replaced.get(itemKey) : liveItems.get(itemKey);
            }
        }
    }

    private void put(String accountId, String storageKey, PointInTimeChange change) {
        String key = storageKey + "#" + change.getSequence();
        if (store instanceof AccountAwareStorageBackend<PointInTimeChange> aware) {
            aware.putForAccount(accountId, key, change);
        } else {
            store.put(key, change);
        }
    }

    private void delete(String accountId, String storageKey, PointInTimeChange change) {
        String key = storageKey + "#" + change.getSequence();
        if (store instanceof AccountAwareStorageBackend<PointInTimeChange> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            store.delete(key);
        }
    }

    private static Duration recoveryPeriod(TableDefinition table) {
        return Duration.ofDays(table.getPointInTimeRecoveryRecoveryPeriodInDays());
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static String key(String accountId, String storageKey) {
        return accountId + "/" + storageKey;
    }
}
