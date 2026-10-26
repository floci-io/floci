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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
        private final List<PointInTimeChange> list = new ArrayList<>();
        private long nextSequence;
        private boolean dropped;
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
        stored.forEach((key, change) -> changesByTable
                .computeIfAbsent(key.substring(0, key.lastIndexOf('#')), ignored -> new Changes())
                .list.add(change));
        for (Changes changes : changesByTable.values()) {
            changes.list.sort(Comparator.comparingLong(PointInTimeChange::getSequence));
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
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            PointInTimeChange change = new PointInTimeChange(changes.nextSequence++, now, itemKey,
                    oldItem != null ? oldItem.deepCopy() : null);
            changes.list.add(change);
            put(accountId, storageKey, change);
            Instant cutoff = now.minus(recoveryPeriod(table));
            int expired = 0;
            while (expired < changes.list.size() && changes.list.get(expired).getTime().isBefore(cutoff)) {
                delete(accountId, storageKey, changes.list.get(expired));
                expired++;
            }
            changes.list.subList(0, expired).clear();
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
     * Writers record a change before they write the item, so the live items are copied first.
     */
    List<JsonNode> itemsAt(String accountId, String storageKey, Map<String, JsonNode> liveItems, Instant time) {
        Map<String, JsonNode> items = new HashMap<>(liveItems);
        Changes changes = changesByTable.get(key(accountId, storageKey));
        if (changes != null) {
            Set<String> undone = new HashSet<>();
            synchronized (changes) {
                for (PointInTimeChange change : changes.list) {
                    String itemKey = change.getItemKey();
                    if (!change.getTime().isAfter(time) || !undone.add(itemKey)) {
                        continue;
                    }
                    if (change.getOldItem() == null) {
                        items.remove(itemKey);
                    } else {
                        items.put(itemKey, change.getOldItem());
                    }
                }
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
        before.forEach((itemKey, oldItem) -> {
            JsonNode newItem = after.containsKey(itemKey) ? after.get(itemKey)
                    : underItemLock.apply(itemKey, () -> valueBefore(changes, itemKey, seen, liveItems));
            result.add(new ItemChange(oldItem, newItem, lastWrites.get(itemKey)));
        });
        return result;
    }

    /** The value that the first change of the item from sequence {@code since} replaced, or the live item. */
    private static JsonNode valueBefore(Changes changes, String itemKey, long since, Map<String, JsonNode> liveItems) {
        synchronized (changes) {
            JsonNode value = liveItems.get(itemKey);
            for (int i = changes.list.size() - 1; 0 <= i && since <= changes.list.get(i).getSequence(); i--) {
                if (changes.list.get(i).getItemKey().equals(itemKey)) {
                    value = changes.list.get(i).getOldItem();
                }
            }
            return value;
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
