package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

@ApplicationScoped
public class CloudWatchLogsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchLogsService.class);

    private final StorageBackend<String, LogGroup> groupStore;
    private final StorageBackend<String, LogStream> streamStore;
    private final StorageBackend<String, LogEvent> eventStore;
    private final StorageBackend<String, SubscriptionFilter> subscriptionFilterStore;
    private final RegionResolver regionResolver;
    private final int maxEventsPerQuery;
    private final long queryCompletionDelayMs;

    // Wall-clock source of "now" for the query lifecycle, injected so tests can drive it deterministically.
    // Non-monotonic: a backward NTP step could briefly flip a Complete query back to Running — a rare,
    // self-healing emulator artifact we accept rather than complicate the injectable clock with nanoTime.
    private final LongSupplier clock;

    /** Cached Logs Insights queries keyed by queryId, bounded with LRU-style eviction. */
    private static final int MAX_STORED_QUERIES = 100;
    private final Map<String, QueryRecord> insightsQueries = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, QueryRecord> eldest) {
                    return size() > MAX_STORED_QUERIES;
                }
            });

    @Inject
    public CloudWatchLogsService(StorageFactory storageFactory,
                                  EmulatorConfig config,
                                  RegionResolver regionResolver) {
        this(
                storageFactory.create("cloudwatchlogs", "cwlogs-groups.json",
                        new TypeReference<>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-streams.json",
                        new TypeReference<>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-events.json",
                        new TypeReference<>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-subscription-filters.json",
                        new TypeReference<>() {}),
                config.services().cloudwatchlogs().maxEventsPerQuery(),
                regionResolver,
                config.services().cloudwatchlogs().queryCompletionDelayMs(),
                System::currentTimeMillis
        );
    }

    CloudWatchLogsService(StorageBackend<String, LogGroup> groupStore,
                           StorageBackend<String, LogStream> streamStore,
                           StorageBackend<String, LogEvent> eventStore,
                           StorageBackend<String, SubscriptionFilter> subscriptionFilterStore,
                           int maxEventsPerQuery,
                           RegionResolver regionResolver) {
        this(groupStore, streamStore, eventStore, subscriptionFilterStore,
                maxEventsPerQuery, regionResolver, 0L, System::currentTimeMillis);
    }

    CloudWatchLogsService(StorageBackend<String, LogGroup> groupStore,
                           StorageBackend<String, LogStream> streamStore,
                           StorageBackend<String, LogEvent> eventStore,
                           StorageBackend<String, SubscriptionFilter> subscriptionFilterStore,
                           int maxEventsPerQuery,
                           RegionResolver regionResolver,
                           long queryCompletionDelayMs,
                           LongSupplier clock) {
        this.groupStore = groupStore;
        this.streamStore = streamStore;
        this.eventStore = eventStore;
        this.subscriptionFilterStore = subscriptionFilterStore;
        this.maxEventsPerQuery = maxEventsPerQuery;
        this.regionResolver = regionResolver;
        // A negative delay is meaningless; treat it as instant completion.
        this.queryCompletionDelayMs = Math.max(0, queryCompletionDelayMs);
        this.clock = clock;
    }

    // ──────────────────────────── Log Groups ────────────────────────────

    public void createLogGroup(String name, Integer retentionInDays, Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException", "logGroupName is required.", 400);
        }
        String key = groupKey(region, name);
        if (groupStore.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The specified log group already exists: " + name, 400);
        }
        LogGroup group = new LogGroup();
        group.setLogGroupName(name);
        group.setCreatedTime(System.currentTimeMillis());
        group.setRetentionInDays(retentionInDays);
        if (tags != null) {
            group.setTags(new HashMap<>(tags));
        }
        groupStore.put(key, group);
        LOG.infov("Created log group: {0} in region {1}", name, region);
    }

    public void deleteLogGroup(String name, String region) {
        String key = groupKey(region, name);
        groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + name, 400));

        // Cascade: delete all streams and events for this group
        String streamPrefix = streamKeyPrefix(region, name);
        List<String> streamKeys = streamStore.keys().stream()
                .filter(k -> k.startsWith(streamPrefix))
                .toList();
        for (String sk : streamKeys) {
            LogStream stream = streamStore.get(sk).orElse(null);
            if (stream != null) {
                deleteEventsForStream(region, name, stream.getLogStreamName());
                streamStore.delete(sk);
            }
        }
        groupStore.delete(key);
        LOG.infov("Deleted log group: {0}", name);
    }

    public List<LogGroup> describeLogGroups(String prefix, String region) {
        String storagePrefix = groupKeyPrefix(region);
        List<LogGroup> result = groupStore.scan(k -> {
            if (!k.startsWith(storagePrefix)) {
                return false;
            }
            if (prefix == null || prefix.isBlank()) {
                return true;
            }
            String groupName = k.substring(storagePrefix.length());
            return groupName.startsWith(prefix);
        });
        result.sort(Comparator.comparing(LogGroup::getLogGroupName));
        return result;
    }

    public void putRetentionPolicy(String groupName, int days, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        group.setRetentionInDays(days);
        groupStore.put(key, group);
    }

    public void deleteRetentionPolicy(String groupName, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        group.setRetentionInDays(null);
        groupStore.put(key, group);
    }

    public void tagLogGroup(String groupName, Map<String, String> tags, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        group.getTags().putAll(tags);
        groupStore.put(key, group);
    }

    public void untagLogGroup(String groupName, List<String> tagKeys, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        tagKeys.forEach(group.getTags()::remove);
        groupStore.put(key, group);
    }

    public Map<String, String> listTagsLogGroup(String groupName, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        return group.getTags();
    }

    // ──────────────────────────── Log Streams ────────────────────────────

    public void createLogStream(String groupName, String streamName, String region) {
        String groupKey = groupKey(region, groupName);
        groupStore.get(groupKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));

        String streamKey = streamKey(region, groupName, streamName);
        if (streamStore.get(streamKey).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The specified log stream already exists: " + streamName, 400);
        }

        LogStream stream = new LogStream();
        stream.setLogGroupName(groupName);
        stream.setLogStreamName(streamName);
        stream.setCreatedTime(System.currentTimeMillis());
        stream.setUploadSequenceToken(UUID.randomUUID().toString());
        streamStore.put(streamKey, stream);
        LOG.infov("Created log stream: {0}/{1}", groupName, streamName);
    }

    public void deleteLogStream(String groupName, String streamName, String region) {
        String streamKey = streamKey(region, groupName, streamName);
        streamStore.get(streamKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log stream does not exist: " + streamName, 400));

        deleteEventsForStream(region, groupName, streamName);
        streamStore.delete(streamKey);
        LOG.infov("Deleted log stream: {0}/{1}", groupName, streamName);
    }

    public List<LogStream> describeLogStreams(String groupName, String prefix, String region) {
        // Verify group exists
        groupStore.get(groupKey(region, groupName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));

        String storagePrefix = streamKeyPrefix(region, groupName);
        List<LogStream> result = streamStore.scan(k -> {
            if (!k.startsWith(storagePrefix)) {
                return false;
            }
            if (prefix == null || prefix.isBlank()) {
                return true;
            }
            String streamName = k.substring(storagePrefix.length());
            return streamName.startsWith(prefix);
        });
        result.sort(Comparator.comparing(LogStream::getLogStreamName));
        return result;
    }

    // ──────────────────────────── Log Events ────────────────────────────

    public String putLogEvents(String groupName, String streamName,
                               List<Map<String, Object>> events, String region) {
        String streamKey = streamKey(region, groupName, streamName);
        LogStream stream = streamStore.get(streamKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log stream does not exist: " + streamName, 400));

        long now = System.currentTimeMillis();
        long totalBytes = 0;
        Long minTs = null;
        Long maxTs = null;

        for (Map<String, Object> evt : events) {
            long ts = toLong(evt.get("timestamp"), now);
            String msg = (String) evt.getOrDefault("message", "");

            LogEvent logEvent = new LogEvent();
            logEvent.setEventId(UUID.randomUUID().toString());
            logEvent.setTimestamp(ts);
            logEvent.setMessage(msg);
            logEvent.setIngestionTime(now);

            String eventKey = eventKey(region, groupName, streamName, ts, logEvent.getEventId());
            eventStore.put(eventKey, logEvent);

            totalBytes += msg.getBytes().length + 26; // approx overhead
            if (minTs == null || ts < minTs) { minTs = ts; }
            if (maxTs == null || ts > maxTs) { maxTs = ts; }
        }

        // Update stream metadata
        if (minTs != null) {
            if (stream.getFirstEventTimestamp() == null || minTs < stream.getFirstEventTimestamp()) {
                stream.setFirstEventTimestamp(minTs);
            }
        }
        if (maxTs != null) {
            stream.setLastEventTimestamp(maxTs);
        }
        stream.setLastIngestionTime(now);
        stream.setStoredBytes(stream.getStoredBytes() + totalBytes);
        String nextToken = UUID.randomUUID().toString();
        stream.setUploadSequenceToken(nextToken);
        streamStore.put(streamKey, stream);

        return nextToken;
    }

    public record LogEventsResult(List<LogEvent> events, String nextForwardToken, String nextBackwardToken) {}

    public LogEventsResult getLogEvents(String groupName, String streamName,
                                        Long startTime, Long endTime,
                                        int limit, boolean startFromHead, String nextToken, String region) {
        int maxEvents = Math.min(limit > 0 ? limit : Integer.MAX_VALUE,
                maxEventsPerQuery);

        String eventPrefix = eventKeyPrefix(region, groupName, streamName);
        List<LogEvent> all = eventStore.scan(k -> k.startsWith(eventPrefix));
        all.sort(Comparator.comparingLong(LogEvent::getTimestamp));

        List<LogEvent> filtered = all.stream()
                .filter(e -> (startTime == null || e.getTimestamp() >= startTime)
                        && (endTime == null || e.getTimestamp() <= endTime))
                .toList();

        int total = filtered.size();
        int pageStart;
        int pageEnd;

        if (nextToken != null && nextToken.startsWith("f/")) {
            int offset = parseTokenIndex(nextToken, 2);
            pageStart = Math.min(offset, total);
            pageEnd = Math.min(pageStart + maxEvents, total);
        } else if (nextToken != null && nextToken.startsWith("b/")) {
            int end = parseTokenIndex(nextToken, 2);
            pageEnd = Math.min(end, total);
            pageStart = Math.max(pageEnd - maxEvents, 0);
        } else if (!startFromHead) {
            pageEnd = total;
            pageStart = Math.max(total - maxEvents, 0);
        } else {
            pageStart = 0;
            pageEnd = Math.min(maxEvents, total);
        }

        List<LogEvent> page = filtered.subList(pageStart, pageEnd);
        return new LogEventsResult(page, "f/" + pageEnd, "b/" + pageStart);
    }

    private int parseTokenIndex(String token, int prefixLen) {
        try {
            return Integer.parseInt(token.substring(prefixLen));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record FilteredLogEventsResult(List<LogEvent> events, String nextToken) {}

    public FilteredLogEventsResult filterLogEvents(String groupName, List<String> streamNames,
                                                    Long startTime, Long endTime,
                                                    String filterPattern, int limit,
                                                    String region) {
        int maxEvents = Math.min(limit > 0 ? limit : Integer.MAX_VALUE,
                maxEventsPerQuery);

        String groupPrefix = groupKeyPrefix(region) + groupName + "::";
        List<LogEvent> all = new ArrayList<>();

        if (streamNames != null && !streamNames.isEmpty()) {
            for (String sn : streamNames) {
                String eventPrefix = eventKeyPrefix(region, groupName, sn);
                all.addAll(eventStore.scan(k -> k.startsWith(eventPrefix)));
            }
        } else {
            // All streams in group
            all.addAll(eventStore.scan(k -> k.startsWith(groupPrefix)));
        }

        all.sort(Comparator.comparingLong(LogEvent::getTimestamp));

        List<LogEvent> result = all.stream()
                .filter(e -> (startTime == null || e.getTimestamp() >= startTime)
                        && (endTime == null || e.getTimestamp() <= endTime))
                .filter(e -> filterPattern == null || filterPattern.isBlank()
                        || e.getMessage().contains(filterPattern))
                .limit(maxEvents)
                .toList();

        String nextToken = result.size() >= maxEvents ? UUID.randomUUID().toString() : null;
        return new FilteredLogEventsResult(result, nextToken);
    }

    // ──────────────────────────── Logs Insights Queries ────────────────────────────

    /** A query's status and (once Complete) its projected rows — the AWS GetQueryResults shape. */
    public record QueryState(String status, List<LinkedHashMap<String, String>> rows,
                             long recordsScanned, long recordsMatched) {}

    /** Lifecycle state of a stored Insights query; {@link #label()} is the AWS wire form. */
    private enum InsightsQueryStatus {
        RUNNING("Running"),
        COMPLETE("Complete"),
        CANCELLED("Cancelled");

        private final String label;

        InsightsQueryStatus(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * A stored Insights query. Results are computed eagerly at StartQuery, but the query reports
     * {@code Running} until {@code completeAtMs} (an artificial delay emulating AWS's asynchronous
     * execution), then {@code Complete} — unless cancelled by StopQuery, after which it is
     * {@code Cancelled}. State transitions are time-driven and computed on read. {@code recordsMatched}
     * is captured at construction so it survives the Running/Cancelled row masking and the row-drop on cancel.
     */
    private static final class QueryRecord {
        private List<LinkedHashMap<String, String>> rows;
        private final long recordsScanned;
        private final long recordsMatched;
        private final long completeAtMs;
        private boolean cancelled;

        QueryRecord(List<LinkedHashMap<String, String>> rows, long recordsScanned, long completeAtMs) {
            this.rows = rows;
            this.recordsScanned = recordsScanned;
            this.recordsMatched = rows.size();
            this.completeAtMs = completeAtMs;
        }

        private InsightsQueryStatus status(long nowMs) {
            if (cancelled) {
                return InsightsQueryStatus.CANCELLED;
            }
            return nowMs >= completeAtMs ? InsightsQueryStatus.COMPLETE : InsightsQueryStatus.RUNNING;
        }

        /**
         * Snapshot this query in the AWS GetQueryResults shape. Rows are exposed only once
         * {@code Complete}, and always as a defensive copy (the cached list is never handed out); while
         * Running or Cancelled the rows are masked empty, but {@code recordsMatched} still reports the
         * full match count.
         */
        synchronized QueryState snapshot(long nowMs) {
            InsightsQueryStatus status = status(nowMs);
            List<LinkedHashMap<String, String>> visible =
                    status == InsightsQueryStatus.COMPLETE ? List.copyOf(rows) : List.of();
            return new QueryState(status.label(), visible, recordsScanned, recordsMatched);
        }

        /** Cancels the query iff still running, dropping its now-unreachable rows. Returns true if this call stopped it. */
        synchronized boolean stopIfRunning(long nowMs) {
            if (!cancelled && nowMs < completeAtMs) {
                cancelled = true;
                rows = List.of();
                return true;
            }
            return false;
        }
    }

    /**
     * Start a CloudWatch Logs Insights query and cache it under a new queryId. Results are computed
     * eagerly (the scan is in-memory); the query then reports {@code Running} until the configured
     * completion delay elapses (default 0 = immediate), emulating AWS's async execution.
     * {@code startTimeSeconds}/{@code endTimeSeconds} are epoch <em>seconds</em> (the StartQuery
     * contract); {@link LogEvent} timestamps are epoch millis, so they are scaled for comparison.
     */
    public String startQuery(List<String> logGroupNames, long startTimeSeconds, long endTimeSeconds,
                             String queryString, Integer limit, String region) {
        long startMs = startTimeSeconds * 1000L;
        long endMs = endTimeSeconds * 1000L;

        List<LogEvent> gathered = new ArrayList<>();
        for (String groupName : logGroupNames) {
            if (groupName == null || groupName.isBlank()) {
                continue;
            }
            String prefix = region + "::" + groupName + "::";
            for (LogEvent e : eventStore.scan(k -> k.startsWith(prefix))) {
                if (e.getTimestamp() >= startMs && e.getTimestamp() <= endMs) {
                    gathered.add(e);
                }
            }
        }

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, maxEventsPerQuery) : maxEventsPerQuery;
        List<LinkedHashMap<String, String>> rows =
                LogsInsightsQuery.parse(queryString).evaluate(gathered, effectiveLimit);

        String queryId = UUID.randomUUID().toString();
        long completeAtMs = clock.getAsLong() + queryCompletionDelayMs;
        insightsQueries.put(queryId, new QueryRecord(rows, gathered.size(), completeAtMs));
        LOG.infov("Logs Insights query {0}: scanned {1} event(s) across {2} group(s) -> {3} row(s)",
                queryId, gathered.size(), logGroupNames.size(), rows.size());
        return queryId;
    }

    /**
     * Return a query's status and, once {@code Complete}, its rows. Mirrors AWS: while {@code Running}
     * or after a StopQuery ({@code Cancelled}) the result set is empty (though {@code recordsMatched}
     * still reports the full match count); only a Complete query exposes rows. An unknown queryId is an
     * error on real AWS — and a query that has fallen out of the bounded LRU cache 404s the same way.
     */
    public QueryState getQueryResults(String queryId) {
        QueryRecord rec = insightsQueries.get(queryId);
        if (rec == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified query does not exist.", 400);
        }
        return rec.snapshot(clock.getAsLong());
    }

    /**
     * Stop an in-progress query. Mirrors AWS StopQuery: a running query is cancelled and returns
     * {@code success=true}; an already-ended query throws {@code InvalidParameterException} ("not
     * running"); an unknown queryId throws {@code ResourceNotFoundException}.
     */
    public boolean stopQuery(String queryId) {
        QueryRecord rec = insightsQueries.get(queryId);
        if (rec == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified query does not exist.", 400);
        }
        if (rec.stopIfRunning(clock.getAsLong())) {
            return true;
        }
        throw new AwsException("InvalidParameterException",
                "The query you are trying to stop is not running.", 400);
    }

    // ──────────────────────────── Subscription Filters ────────────────────────────

    public void putSubscriptionFilter(String logGroupName, String filterName, String filterPattern,
                                       String destinationArn, String distribution, String region) {
        String groupKey = groupKey(region, logGroupName);
        groupStore.get(groupKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + logGroupName, 400));

        SubscriptionFilter filter = new SubscriptionFilter();
        filter.setFilterName(filterName);
        filter.setLogGroupName(logGroupName);
        filter.setFilterPattern(filterPattern != null ? filterPattern : "");
        filter.setDestinationArn(destinationArn);
        filter.setDistribution(distribution != null ? distribution : "ByLogStream");
        filter.setCreationTime(System.currentTimeMillis());

        String filterKey = subscriptionFilterKey(region, logGroupName, filterName);
        subscriptionFilterStore.put(filterKey, filter);
        LOG.infov("Created subscription filter: {0} on log group: {1}", filterName, logGroupName);
    }

    public record DescribeSubscriptionFiltersResult(List<SubscriptionFilter> subscriptionFilters, String nextToken) {}

    public DescribeSubscriptionFiltersResult describeSubscriptionFilters(String logGroupName, String filterNamePrefix,
                                                                          String nextToken, int limit, String region) {
        String groupKey = groupKey(region, logGroupName);
        groupStore.get(groupKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + logGroupName, 400));

        String prefix = subscriptionFilterKeyPrefix(region, logGroupName);
        List<SubscriptionFilter> all = subscriptionFilterStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            if (filterNamePrefix == null || filterNamePrefix.isBlank()) return true;
            String name = k.substring(prefix.length());
            return name.startsWith(filterNamePrefix);
        });
        all.sort(Comparator.comparing(SubscriptionFilter::getFilterName));

        int maxResults = Math.min(limit > 0 ? limit : 50, 50);
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                offset = 0;
            }
        }

        int end = Math.min(offset + maxResults, all.size());
        List<SubscriptionFilter> page = all.subList(offset, end);
        String token = end < all.size() ? String.valueOf(end) : null;
        return new DescribeSubscriptionFiltersResult(page, token);
    }

    public void deleteSubscriptionFilter(String logGroupName, String filterName, String region) {
        String groupKey = groupKey(region, logGroupName);
        groupStore.get(groupKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + logGroupName, 400));

        String filterKey = subscriptionFilterKey(region, logGroupName, filterName);
        subscriptionFilterStore.get(filterKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified subscription filter does not exist: " + filterName, 400));
        subscriptionFilterStore.delete(filterKey);
        LOG.infov("Deleted subscription filter: {0} on log group: {1}", filterName, logGroupName);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private void deleteEventsForStream(String region, String groupName, String streamName) {
        String eventPrefix = eventKeyPrefix(region, groupName, streamName);
        List<String> keys = eventStore.keys().stream()
                .filter(k -> k.startsWith(eventPrefix))
                .toList();
        keys.forEach(eventStore::delete);
    }

    public String buildArn(String groupName, String region) {
        return regionResolver.buildArn("logs", region, "log-group:" + groupName);
    }

    private static String groupKeyPrefix(String region) {
        return region + "::";
    }

    private static String groupKey(String region, String groupName) {
        return region + "::" + groupName;
    }

    private static String streamKeyPrefix(String region, String groupName) {
        return region + "::" + groupName + "::";
    }

    private static String streamKey(String region, String groupName, String streamName) {
        return region + "::" + groupName + "::" + streamName;
    }

    private static String eventKeyPrefix(String region, String groupName, String streamName) {
        return region + "::" + groupName + "::" + streamName + "::";
    }

    private static String eventKey(String region, String groupName, String streamName,
                                    long timestamp, String uuid) {
        return region + "::" + groupName + "::" + streamName + "::"
                + String.format("%015d", timestamp) + "::" + uuid;
    }

    private static String subscriptionFilterKeyPrefix(String region, String logGroupName) {
        return region + "::" + logGroupName + "::filter::";
    }

    private static String subscriptionFilterKey(String region, String logGroupName, String filterName) {
        return region + "::" + logGroupName + "::filter::" + filterName;
    }

    private static long toLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
