package io.floci.aws.services.cloudwatch.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
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
                if (series.startsWith(namespaceSegment, series.indexOf("::"))) {
                    endings.headSet(cutoff).clear();
                }
            });
        }
    }

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
