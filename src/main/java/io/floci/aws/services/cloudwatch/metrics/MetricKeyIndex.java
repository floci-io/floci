package io.floci.aws.services.cloudwatch.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * The metric store's keys per account, sorted, so a read of one metric's datapoints between two
 * times walks that key range instead of every key in the store. Values stay in the store.
 *
 * <p>A key is its series, {@code region::namespace::metric::dimensions::}, followed by the datum's
 * zero-padded timestamp ({@link #timestampSegment}) and id, which never contains {@code ::}. Each
 * series is held once, with its keys' {@code timestamp::id} endings sorted by time.</p>
 *
 * <p>The index serves reads once {@link #markBuilt} runs on a store that has finished loading;
 * until then {@link #isBuilt} is false and callers scan the store. Only {@link #clear} removes keys
 * other than by retention, so a key added while the store is being read in is kept.</p>
 */
final class MetricKeyIndex {

    private final Map<String, Map<String, NavigableSet<String>>> endingsBySeriesByAccount = new ConcurrentHashMap<>();
    private volatile boolean built;

    /** The timestamp segment of a store key: zero-padded so keys sort by time within a metric. */
    static String timestampSegment(long epochSeconds) {
        return String.format("%013d", epochSeconds);
    }

    boolean isBuilt() {
        return built;
    }

    void markBuilt() {
        built = true;
    }

    /** Removes every key and stops serving reads until the next {@link #markBuilt}. */
    void clear() {
        built = false;
        endingsBySeriesByAccount.clear();
    }

    void add(String account, String key) {
        int endingStart = key.lastIndexOf("::", key.lastIndexOf("::") - 1) + 2;
        endingsBySeriesByAccount.computeIfAbsent(account, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(key.substring(0, endingStart), ignored -> new ConcurrentSkipListSet<>())
                .add(key.substring(endingStart));
    }

    /** Removes the keys of every {@code namespace} series, in every account, stamped before {@code cutoffSeconds}. */
    void removeBefore(String namespace, long cutoffSeconds) {
        String namespaceSegment = "::" + namespace + "::";
        String cutoff = timestampSegment(cutoffSeconds);
        for (Map<String, NavigableSet<String>> endingsBySeries : endingsBySeriesByAccount.values()) {
            endingsBySeries.forEach((series, endings) -> {
                if (isInNamespace(series, namespaceSegment)) {
                    endings.headSet(cutoff).clear();
                }
            });
        }
    }

    /**
     * In every account holding more than {@code ceiling} keys of {@code namespace}, removes the oldest of
     * them across its series until {@code keep} are left. Returns the removed keys by account.
     */
    Map<String, Set<String>> removeOldest(String namespace, int ceiling, int keep) {
        String namespaceSegment = "::" + namespace + "::";
        Map<String, Set<String>> removedByAccount = new HashMap<>();
        endingsBySeriesByAccount.forEach((account, endingsBySeries) -> {
            List<String> namespaceSeries = new ArrayList<>();
            long held = 0;
            for (Map.Entry<String, NavigableSet<String>> endings : endingsBySeries.entrySet()) {
                if (isInNamespace(endings.getKey(), namespaceSegment)) {
                    namespaceSeries.add(endings.getKey());
                    held += endings.getValue().size();
                }
            }
            if (held <= ceiling) {
                return;
            }
            List<SeriesEnding> oldestFirst = new ArrayList<>();
            for (String series : namespaceSeries) {
                for (String ending : endingsBySeries.get(series)) {
                    oldestFirst.add(new SeriesEnding(series, ending));
                }
            }
            oldestFirst.sort(Comparator.comparing(SeriesEnding::ending).thenComparing(SeriesEnding::series));
            Set<String> removed = new HashSet<>();
            for (SeriesEnding oldest : oldestFirst.subList(0, Math.max(0, oldestFirst.size() - keep))) {
                endingsBySeries.get(oldest.series()).remove(oldest.ending());
                removed.add(oldest.series() + oldest.ending());
            }
            removedByAccount.put(account, removed);
        });
        return removedByAccount;
    }

    private static boolean isInNamespace(String series, String namespaceSegment) {
        return series.startsWith(namespaceSegment, series.indexOf("::"));
    }

    /** One key split as the index holds it; an ending starts with its timestamp, so endings sort by time. */
    private record SeriesEnding(String series, String ending) {}

    /** The account's keys of {@code series} stamped in {@code [fromSeconds, toSeconds)}, oldest first. */
    List<String> keysBetween(String account, String series, long fromSeconds, long toSeconds) {
        Map<String, NavigableSet<String>> endingsBySeries = endingsBySeriesByAccount.get(account);
        NavigableSet<String> endings = endingsBySeries == null ? null : endingsBySeries.get(series);
        if (endings == null || fromSeconds >= toSeconds) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String ending : endings.subSet(timestampSegment(fromSeconds), true, timestampSegment(toSeconds), false)) {
            keys.add(series + ending);
        }
        return keys;
    }
}
