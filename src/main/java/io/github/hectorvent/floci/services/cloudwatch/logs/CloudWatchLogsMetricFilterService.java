package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.cloudwatch.logs.filter.FilterMatch;
import io.github.hectorvent.floci.services.cloudwatch.logs.filter.FilterPattern;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService.requireFilterPattern;
import static io.github.hectorvent.floci.services.cloudwatch.logs.MetricFilterRules.MAX_DIMENSIONS;
import static io.github.hectorvent.floci.services.cloudwatch.logs.MetricFilterRules.NUMBER;
import static io.github.hectorvent.floci.services.cloudwatch.logs.MetricFilterRules.invalid;
import static io.github.hectorvent.floci.services.cloudwatch.logs.MetricFilterRules.number;
import static io.github.hectorvent.floci.services.cloudwatch.logs.MetricFilterRules.selects;
import static io.github.hectorvent.floci.services.cloudwatch.logs.MetricFilterRules.validateFieldSelectionCriteria;
import static io.github.hectorvent.floci.services.cloudwatch.logs.MetricFilterRules.validateSystemFields;
import static io.github.hectorvent.floci.services.cloudwatch.logs.MetricFilterRules.withSystemDimensions;

/**
 * Metric filters of CloudWatch Logs: the PutMetricFilter, DescribeMetricFilters, DeleteMetricFilter
 * and TestMetricFilter operations, and the part AWS does at ingestion, publishing a metric value for
 * every log event a filter matches. The filters live in their own store, keyed by Region, log group
 * and name. Both filter families share storage coordination with the log service, which tells
 * this one about ingestion and deleted groups through CDI events.
 */
@ApplicationScoped
public class CloudWatchLogsMetricFilterService implements Resettable {

    private static final Logger LOG = Logger.getLogger(CloudWatchLogsMetricFilterService.class);

    /** AWS's cap on metric filters per log group. */
    public static final int MAX_FILTERS_PER_LOG_GROUP = 100;
    private static final int MAX_METRIC_VALUE_LENGTH = 100;
    private static final int MAX_DIMENSION_LENGTH = 255;
    private static final int MAX_TEST_MESSAGES = 50;
    private static final int MAX_DESCRIBE_LIMIT = 50;
    private static final Pattern FILTER_NAME = Pattern.compile("[^:*]{1,512}");
    private static final Pattern METRIC_NAME = Pattern.compile("[^:*$]{1,255}");
    private static final Set<String> UNITS = Set.of("Seconds", "Microseconds", "Milliseconds", "Bytes", "Kilobytes",
            "Megabytes", "Gigabytes", "Terabytes", "Bits", "Kilobits", "Megabits", "Gigabits", "Terabits", "Percent",
            "Count", "Bytes/Second", "Kilobytes/Second", "Megabytes/Second", "Gigabytes/Second", "Terabytes/Second",
            "Bits/Second", "Kilobits/Second", "Megabits/Second", "Gigabits/Second", "Terabits/Second", "Count/Second",
            "None");

    private final StorageBackend<String, MetricFilter> store;
    private final CloudWatchLogsService logsService;
    private final CloudWatchMetricsService metricsService;
    private final RegionResolver regionResolver;

    /** Process-local best effort, bounded by samples, not ingestion batches. No durable outbox. */
    private static final int MAX_PENDING_SAMPLES = 10_000;
    private final Map<String, Publication> pending = new LinkedHashMap<>();
    // One detail per owning filter's pending-work outage, then one aggregate warning per 60
    // failed worker ticks (at least a minute at the production cadence). No exceptions retained.
    private static final int RETRY_FAILURE_SUMMARY_TICKS = 60;
    private final Set<Owner> reportedOutages = new HashSet<>();
    private int failedRetryTicks;
    // The canonical filter backend is the only monitor, shared with quotas and group deletion.
    // Never acquire StorageFactory's monitor while holding a separate runtime-state monitor.
    private ScheduledExecutorService retryWorker;
    private boolean enabled = true;
    private boolean paused;
    private boolean stopped;

    private record Owner(String account, String region, String group, String filter) {}

    private record Publication(String id, Owner owner, String namespace, String metricName, String unit,
                               List<Dimension> dimensions, double value, long timestamp) {
        Publication {
            dimensions = List.copyOf(dimensions);
        }

        MetricDatum datum() {
            MetricDatum datum = new MetricDatum();
            datum.setMetricName(metricName);
            datum.setUnit(unit);
            datum.setDimensions(dimensions);
            datum.setValue(value);
            datum.setTimestamp(timestamp);
            return datum;
        }
    }

    @Inject
    public CloudWatchLogsMetricFilterService(CloudWatchLogsService logsService,
                                             CloudWatchMetricsService metricsService, RegionResolver regionResolver) {
        this.store = logsService.metricFilterStore();
        this.logsService = logsService;
        this.metricsService = metricsService;
        this.regionResolver = regionResolver;
    }

    /** A page of DescribeMetricFilters. */
    public record DescribeMetricFiltersResult(List<MetricFilter> metricFilters, String nextToken) {}

    /** One TestMetricFilter match: the event's position in the request, its text and what the pattern extracted. */
    public record MetricFilterMatchRecord(long eventNumber, String eventMessage, Map<String, String> extractedValues) {}

    /**
     * Creates the filter, or replaces it when the log group already has one with that name, keeping
     * the original creation time. The definition is checked the way AWS checks it: the group must
     * exist, the pattern must parse, the one transformation must name a metric and a value that is
     * a number or a field the pattern can supply, dimensions are limited to three field references
     * of a JSON or space-delimited pattern and exclude a default value, and the group holds at
     * most 100 filters.
     */
    public MetricFilter putMetricFilter(MetricFilter definition, String region) {
        return putMetricFilter(definition, region, false, null);
    }

    public enum MutationOutcome { APPLIED, NOT_APPLIED, UNKNOWN }

    /** Internal mutation evidence; presence is null when it could not be inspected. */
    public record MutationResult(MutationOutcome outcome, Boolean present) {}

    /** Internal CFN create: never adopts an existing filter through the public API's upsert. */
    public MetricFilter createMetricFilter(MetricFilter definition, String region,
                                           Consumer<MutationResult> outcome) {
        return putMetricFilter(definition, region, true, outcome);
    }

    /** Internal CFN update of a confirmed-owned identity, including snapshot restoration. */
    public MetricFilter updateMetricFilter(MetricFilter definition, String region,
                                           Consumer<MutationResult> outcome) {
        return putMetricFilter(definition, region, false, outcome);
    }

    private MetricFilter putMetricFilter(MetricFilter definition, String region,
                                         boolean createOnly, Consumer<MutationResult> outcome) {
        synchronized (store) {
            String logGroupName;
            String filterName;
            String key;
            MetricFilter before = null;
            Boolean present = null;
            MetricFilter filter;
            try {
                logGroupName = requireLogGroup(definition.getLogGroupName(), region);
                filterName = requireFilterName(definition.getFilterName());
                key = key(region, logGroupName, filterName);
                if (createOnly) {
                    before = store.get(key).orElse(null);
                    present = before != null;
                    if (before != null) {
                        throw new AwsException("AlreadyExistsException",
                                "Metric filter " + filterName + " already exists in " + logGroupName, 400);
                    }
                }
                FilterPattern pattern = requireFilterPattern(definition.getFilterPattern());
                MetricTransformation transformation = requireTransformation(definition.getMetricTransformations(), pattern);
                validateSystemFields(definition.getEmitSystemFieldDimensions(), transformation);
                validateFieldSelectionCriteria(definition.getFieldSelectionCriteria());
                if (!createOnly) {
                    before = store.get(key).orElse(null);
                    present = before != null;
                }
                if (before == null && countMetricFilters(logGroupName, region) >= MAX_FILTERS_PER_LOG_GROUP) {
                    throw new AwsException("LimitExceededException",
                            "The log group " + logGroupName + " already has the maximum of " + MAX_FILTERS_PER_LOG_GROUP
                                    + " metric filters.", 400);
                }
                logsService.validateFilterRegexQuota(logGroupName, filterName, pattern, true, region);

                filter = new MetricFilter();
                filter.setLogGroupName(logGroupName);
                filter.setFilterName(filterName);
                filter.setFilterPattern(definition.getFilterPattern());
                filter.setMetricTransformations(List.of(copy(transformation)));
                filter.setCreationTime(before == null ? System.currentTimeMillis() : before.getCreationTime());
                filter.setApplyOnTransformedLogs(definition.getApplyOnTransformedLogs());
                filter.setFieldSelectionCriteria(definition.getFieldSelectionCriteria());
                filter.setEmitSystemFieldDimensions(definition.getEmitSystemFieldDimensions() == null
                        ? null : List.copyOf(definition.getEmitSystemFieldDimensions()));
            } catch (RuntimeException failure) {
                completeMutation(new MutationResult(MutationOutcome.NOT_APPLIED, present), outcome, null, failure);
                throw failure;
            }
            mutateStore(key, before, filter, outcome, null);
            LOG.infov("Put metric filter {0} on log group {1}", filterName, logGroupName);
            return filter;
        }
    }

    /** The caller holds the canonical monitor through inspection and metadata recording. */
    private void mutateStore(String key, MetricFilter before, MetricFilter after,
                             Consumer<MutationResult> outcome, Runnable onAbsent) {
        RuntimeException failure = null;
        MutationResult result = new MutationResult(MutationOutcome.APPLIED, after != null);
        try {
            if (after == null) {
                store.delete(key);
            } else {
                store.put(key, after);
            }
        } catch (RuntimeException storageFailure) {
            failure = storageFailure;
            result = new MutationResult(MutationOutcome.UNKNOWN, null);
            if (outcome != null) {
                try {
                    MetricFilter current = store.get(key).orElse(null);
                    if (current == after) {
                        result = new MutationResult(MutationOutcome.APPLIED, current != null);
                    } else if (current == before) {
                        result = new MutationResult(MutationOutcome.NOT_APPLIED, current != null);
                    }
                } catch (RuntimeException inspectionFailure) {
                    if (storageFailure != inspectionFailure) {
                        storageFailure.addSuppressed(inspectionFailure);
                    }
                }
            }
        }
        completeMutation(result, outcome, onAbsent, failure);
    }

    private static void completeMutation(MutationResult result, Consumer<MutationResult> outcome,
                                         Runnable onAbsent, RuntimeException failure) {
        if (outcome != null) {
            try {
                outcome.accept(result);
            } catch (RuntimeException recordingFailure) {
                if (failure == null) {
                    failure = recordingFailure;
                } else if (failure != recordingFailure) {
                    failure.addSuppressed(recordingFailure);
                }
            }
        }
        if (onAbsent != null && Boolean.FALSE.equals(result.present())) {
            try {
                onAbsent.run();
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private String requireLogGroup(String logGroupName, String region) {
        if (logGroupName == null || logGroupName.isBlank()) {
            throw invalid("logGroupName is required.");
        }
        if (!logsService.logGroupExists(logGroupName, region)) {
            throw new AwsException("ResourceNotFoundException", "The specified log group does not exist.", 400);
        }
        return logGroupName;
    }

    private static String requireFilterName(String filterName) {
        if (filterName == null || filterName.isBlank()) {
            throw invalid("filterName is required.");
        }
        if (!FILTER_NAME.matcher(filterName).matches()) {
            throw invalid("filterName must be 1 to 512 characters and must not contain ':' or '*'.");
        }
        return filterName;
    }

    private static MetricTransformation requireTransformation(List<MetricTransformation> transformations,
                                                              FilterPattern pattern) {
        if (transformations == null || transformations.size() != 1 || transformations.getFirst() == null) {
            throw invalid("metricTransformations must contain exactly one transformation.");
        }
        MetricTransformation t = transformations.getFirst();
        if (t.getMetricName() == null || !METRIC_NAME.matcher(t.getMetricName()).matches()) {
            throw invalid("metricName is required, must be at most 255 characters and must not contain ':', '*' or '$'.");
        }
        if (t.getMetricNamespace() == null || !METRIC_NAME.matcher(t.getMetricNamespace()).matches()) {
            throw invalid("metricNamespace is required, must be at most 255 characters and must not contain ':', '*' or '$'.");
        }
        String value = t.getMetricValue();
        if (value == null || value.isBlank() || value.length() > MAX_METRIC_VALUE_LENGTH) {
            throw invalid("metricValue is required and must be at most " + MAX_METRIC_VALUE_LENGTH + " characters.");
        }
        if (NUMBER.matcher(value).matches() ? number(value) == null : !pattern.declaresSingleValueField(value)) {
            throw invalid("Invalid metric transformation: metric value " + value + " must be a valid number");
        }
        if (t.getDefaultValue() != null && !Double.isFinite(t.getDefaultValue())) {
            throw invalid("defaultValue must be a finite number.");
        }
        Map<String, String> dimensions = t.getDimensions();
        if (dimensions != null && !dimensions.isEmpty()) {
            if (dimensions.size() > MAX_DIMENSIONS) {
                throw invalid("A metric filter can include at most " + MAX_DIMENSIONS + " dimensions.");
            }
            if (pattern.kind() != FilterPattern.Kind.JSON && pattern.kind() != FilterPattern.Kind.SPACE_DELIMITED) {
                throw invalid("Dimensions are only available for JSON and space-delimited filter patterns.");
            }
            if (t.getDefaultValue() != null) {
                throw invalid("A metric filter with dimensions cannot have a default value.");
            }
            for (Map.Entry<String, String> dimension : dimensions.entrySet()) {
                String name = dimension.getKey();
                String reference = dimension.getValue();
                if (name == null || name.isBlank() || name.length() > MAX_DIMENSION_LENGTH || name.startsWith(":")) {
                    throw invalid("Dimension names must be 1 to 255 characters and must not start with ':'.");
                }
                if (reference == null || reference.length() > MAX_DIMENSION_LENGTH || !pattern.declaresField(reference)) {
                    throw invalid("Dimension " + name + " must refer to a field of the filter pattern such as $.field or"
                            + " $field, got '" + reference + "'.");
                }
            }
        }
        if (t.getUnit() != null && !UNITS.contains(t.getUnit())) {
            throw invalid("unit must be one of the CloudWatch standard units, got '" + t.getUnit() + "'.");
        }
        return t;
    }

    private static MetricTransformation copy(MetricTransformation t) {
        MetricTransformation copy = new MetricTransformation();
        copy.setMetricName(t.getMetricName());
        copy.setMetricNamespace(t.getMetricNamespace());
        copy.setMetricValue(t.getMetricValue());
        copy.setDefaultValue(t.getDefaultValue());
        copy.setDimensions(t.getDimensions() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(t.getDimensions()));
        copy.setUnit(t.getUnit());
        return copy;
    }

    /**
     * Lists filters, sorted by name as AWS does, for one log group or for the whole Region. As on
     * AWS, {@code filterNamePrefix} applies only together with {@code logGroupName}, and
     * {@code metricName} and {@code metricNamespace} must be given together or not at all.
     */
    public DescribeMetricFiltersResult describeMetricFilters(String logGroupName, String filterNamePrefix,
                                                             String metricName, String metricNamespace,
                                                             String nextToken, Integer limit, String region) {
        int pageSize = limit == null ? MAX_DESCRIBE_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_DESCRIBE_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_DESCRIBE_LIMIT + ".");
        }
        if ((metricName == null) != (metricNamespace == null)) {
            throw invalid("Describe Metric Filters request must contain both MetricName and MetricNamespace");
        }
        boolean byGroup = logGroupName != null && !logGroupName.isBlank();
        if (byGroup) {
            requireLogGroup(logGroupName, region);
        }
        String prefix = byGroup ? groupPrefix(region, logGroupName) : region + "::";
        List<MetricFilter> all = store.scan(key -> key.startsWith(prefix));
        List<MetricFilter> filtered = all.stream()
                .filter(f -> !byGroup || filterNamePrefix == null || filterNamePrefix.isBlank()
                        || f.getFilterName().startsWith(filterNamePrefix))
                .filter(f -> metricNamespace == null || transformationMatches(f, metricName, metricNamespace))
                .sorted(Comparator.comparing(MetricFilter::getFilterName).thenComparing(MetricFilter::getLogGroupName))
                .toList();

        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw invalid("The specified nextToken is invalid.");
            }
            if (offset < 0 || offset > filtered.size()) {
                throw invalid("The specified nextToken is invalid.");
            }
        }
        int end = Math.min(offset + pageSize, filtered.size());
        String token = end < filtered.size() ? String.valueOf(end) : null;
        return new DescribeMetricFiltersResult(filtered.subList(offset, end), token);
    }

    private static boolean transformationMatches(MetricFilter filter, String metricName, String metricNamespace) {
        return filter.getMetricTransformations().stream().anyMatch(t ->
                metricNamespace.equals(t.getMetricNamespace()) && metricName.equals(t.getMetricName()));
    }

    public void deleteMetricFilter(String logGroupName, String filterName, String region) {
        synchronized (store) {
            requireLogGroup(logGroupName, region);
            if (filterName == null || filterName.isBlank()) {
                throw invalid("filterName is required.");
            }
            String key = key(region, logGroupName, filterName);
            if (store.get(key).isEmpty()) {
                throw new AwsException("ResourceNotFoundException", "The specified metric filter does not exist.", 400);
            }
            store.delete(key);
            cancelPublications(logGroupName, filterName, region);
            LOG.infov("Deleted metric filter {0} on log group {1}", filterName, logGroupName);
        }
    }

    /** Internal CFN delete with evidence recorded before another creator can acquire the monitor. */
    public void deleteMetricFilter(String logGroupName, String filterName, String region,
                                   Consumer<MutationResult> outcome) {
        synchronized (store) {
            String key = key(region, logGroupName, filterName);
            MetricFilter before;
            try {
                before = store.get(key).orElse(null);
            } catch (RuntimeException failure) {
                completeMutation(new MutationResult(MutationOutcome.NOT_APPLIED, null), outcome, null, failure);
                throw failure;
            }
            Runnable cancel = () -> cancelPublications(logGroupName, filterName, region);
            if (before == null) {
                completeMutation(new MutationResult(MutationOutcome.NOT_APPLIED, false), outcome, cancel, null);
                return;
            }
            mutateStore(key, before, null, outcome, cancel);
            LOG.infov("Deleted metric filter {0} on log group {1}", filterName, logGroupName);
        }
    }

    /** Confirms absence and records relinquished ownership atomically, without touching a surviving row. */
    public boolean confirmMetricFilterAbsent(String logGroupName, String filterName, String region, Runnable onAbsent) {
        synchronized (store) {
            if (store.get(key(region, logGroupName, filterName)).isPresent()) {
                return false;
            }
            completeMutation(new MutationResult(MutationOutcome.NOT_APPLIED, false), ignored -> onAbsent.run(),
                    () -> cancelPublications(logGroupName, filterName, region), null);
            return true;
        }
    }

    private void cancelPublications(String logGroupName, String filterName, String region) {
        Owner owner = new Owner(regionResolver.getAccountId(), region, logGroupName, filterName);
        pending.values().removeIf(p -> p.owner().equals(owner));
        forgetCompletedOutages();
    }

    public Optional<MetricFilter> findMetricFilter(String logGroupName, String filterName, String region) {
        return store.get(key(region, logGroupName, filterName));
    }

    public int countMetricFilters(String logGroupName, String region) {
        return filtersOf(logGroupName, region).size();
    }

    /** Runs a pattern over sample messages, the TestMetricFilter operation; event numbers start at one. */
    public List<MetricFilterMatchRecord> testMetricFilter(String filterPattern, List<String> messages) {
        FilterPattern pattern = requireFilterPattern(filterPattern);
        if (messages == null || messages.isEmpty() || messages.size() > MAX_TEST_MESSAGES) {
            throw invalid("logEventMessages must contain between 1 and " + MAX_TEST_MESSAGES + " messages.");
        }
        List<MetricFilterMatchRecord> matches = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String message = messages.get(i);
            if (message == null || message.isEmpty()) {
                throw invalid("logEventMessages must not contain an empty message.");
            }
            FilterMatch match = pattern.match(message);
            if (match.matched()) {
                matches.add(new MetricFilterMatchRecord(i + 1L, message, match.extractedValues()));
            }
        }
        return matches;
    }

    /** Logs cascades first; retain idempotent cleanup for standalone observer callers as well. */
    void onLogGroupDeleted(@Observes LogGroupDeleted event) {
        synchronized (store) {
            String account = regionResolver.getAccountId();
            String prefix = groupPrefix(event.region(), event.logGroupName());
            store.keys().stream().filter(key -> key.startsWith(prefix)).toList().forEach(store::delete);
            pending.values().removeIf(p -> p.owner().account().equals(account)
                    && p.owner().region().equals(event.region()) && p.owner().group().equals(event.logGroupName()));
            forgetCompletedOutages();
        }
    }

    /** Publishes each event's contribution immediately, independent of other events or later traffic. */
    void onLogEventsIngested(@Observes LogEventsIngested event) {
        synchronized (store) {
            if (!enabled || paused || stopped) {
                return;
            }
            String account = event.accountId() == null || event.accountId().isBlank()
                    ? regionResolver.getAccountId() : event.accountId();
            try {
                for (MetricFilter filter : filtersOf(event.logGroupName(), event.region(), account)) {
                    try {
                        if (selects(filter, account, event.region())) {
                            publish(filter, event, account);
                        }
                    } catch (RuntimeException e) {
                        LOG.errorv(e, "Cannot evaluate metric filter: account={0}, region={1}, group={2}, filter={3}",
                                account, event.region(), event.logGroupName(), filter.getFilterName());
                    }
                }
            } catch (RuntimeException e) {
                // Stored Logs remain successful even if the definition store cannot be read.
                LOG.errorv(e, "Cannot load metric filters; publication not queued: account={0}, region={1}, group={2}",
                        account, event.region(), event.logGroupName());
            }
        }
    }

    private void publish(MetricFilter filter, LogEventsIngested event, String account) {
        FilterPattern pattern = FilterPattern.parse(filter.getFilterPattern());
        MetricTransformation t = filter.getMetricTransformations().getFirst();
        Double literal = number(t.getMetricValue());
        Owner owner = new Owner(account, event.region(), event.logGroupName(), filter.getFilterName());
        int failed = 0;
        int dropped = 0;
        RuntimeException firstFailure = null;
        for (LogEvent logEvent : event.events()) {
            FilterMatch match = pattern.match(logEvent.getMessage());
            Double value = t.getDefaultValue();
            List<Dimension> dimensions = new ArrayList<>();
            if (match.matched()) {
                // Preserve raw text: only missing/null falls back. A present nonnumeric value skips.
                String raw = match.value(t.getMetricValue());
                value = literal != null ? literal : raw == null ? t.getDefaultValue() : number(raw);
                if (t.getDimensions() != null) {
                    for (Map.Entry<String, String> dimension : t.getDimensions().entrySet()) {
                        String extracted = match.value(dimension.getValue());
                        if (extracted == null) {
                            dimensions.clear();
                            break;
                        }
                        dimensions.add(new Dimension(dimension.getKey(), extracted));
                    }
                }
                // Policy for unmeasured combinations: matching fallback and incomplete ordinary
                // dimensions retain system dimensions. Pattern-nonmatch defaults are dimensionless.
                withSystemDimensions(filter, account, event.region(), dimensions);
            }
            if (value != null) {
                Publication publication = new Publication(UUID.randomUUID().toString(), owner, t.getMetricNamespace(),
                        t.getMetricName(), t.getUnit() == null ? "None" : t.getUnit(), dimensions,
                        value, logEvent.getTimestamp() / 1000);
                try {
                    write(publication);
                } catch (RuntimeException e) {
                    failed++;
                    if (firstFailure == null) {
                        firstFailure = e;
                    }
                    if (pending.size() < MAX_PENDING_SAMPLES) {
                        pending.put(publication.id(), publication);
                    } else {
                        dropped++;
                    }
                }
            }
        }
        if (failed > dropped) {
            // Only retained work owns suppression state. Dropped-only work gets the ERROR below.
            logPublicationFailureOnce(owner, firstFailure);
        }
        if (dropped > 0) {
            LOG.errorv("Metric publication retry queue overflow: account={0}, region={1}, group={2}, filter={3}, dropped={4}, capacity={5}",
                    account, event.region(), event.logGroupName(), filter.getFilterName(), dropped, MAX_PENDING_SAMPLES);
        }
    }

    private void write(Publication publication) {
        metricsService.publishMetricForAccount(publication.owner().account(), publication.namespace(),
                publication.datum(), publication.owner().region(), publication.id());
    }

    /** The production worker's entry point, also used by deterministic fault/race tests. */
    void retryPending() {
        try {
            synchronized (store) {
                if (!enabled || paused || stopped) {
                    return;
                }
                for (Publication publication : List.copyOf(pending.values())) {
                    try {
                        write(publication);
                        pending.remove(publication.id());
                    } catch (RuntimeException e) {
                        // Retain this exact snapshot/ID. Other filters still get their attempt.
                        logPublicationFailureOnce(publication.owner(), e);
                    }
                }
                forgetCompletedOutages();
                if (!pending.isEmpty() && ++failedRetryTicks >= RETRY_FAILURE_SUMMARY_TICKS) {
                    LOG.warnv("Metric publication retries still failing: filters={0}, failedSamples={1}",
                            reportedOutages.size(), pending.size());
                    failedRetryTicks = 0;
                }
            }
        } catch (RuntimeException e) {
            // Scheduled executors suppress every future tick if any invocation escapes.
            LOG.errorv(e, "Metric publication retry tick failed; pending work retained");
        }
    }

    private void logPublicationFailureOnce(Owner owner, RuntimeException failure) {
        if (reportedOutages.add(owner)) {
            LOG.warnv(failure, "Metric publication failed: account={0}, region={1}, group={2}, filter={3}, pending={4}",
                    owner.account(), owner.region(), owner.group(), owner.filter(), pending.size());
        }
    }

    /** Called under the canonical monitor after drain/cancellation. State is bounded by pending owners. */
    private void forgetCompletedOutages() {
        Set<Owner> activeOwners = new HashSet<>();
        for (Publication publication : pending.values()) {
            activeOwners.add(publication.owner());
        }
        reportedOutages.retainAll(activeOwners);
        if (pending.isEmpty()) {
            failedRetryTicks = 0;
        }
    }

    void onStart(@Observes StartupEvent event, EmulatorConfig config) {
        start(config.services().cloudwatchlogs().enabled(), config.services().cloudwatchmetrics().enabled());
    }

    void start(boolean logsEnabled, boolean metricsEnabled) {
        synchronized (store) {
            if (retryWorker != null || stopped) {
                return;
            }
            enabled = logsEnabled && metricsEnabled;
            if (enabled) {
                retryWorker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "logs-metric-publication-retry"));
                retryWorker.scheduleWithFixedDelay(this::retryPending, 1, 1, TimeUnit.SECONDS);
            }
        }
    }

    // Stop before EmulatorLifecycle's default-priority storage flush/shutdown observer.
    void onStop(@Observes @Priority(1000) ShutdownEvent event) {
        stop();
    }

    @PreDestroy
    void stop() {
        ScheduledExecutorService worker;
        synchronized (store) {
            stopped = true;
            pending.clear();
            forgetCompletedOutages();
            worker = retryWorker;
        }
        if (worker != null) {
            worker.shutdownNow();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.errorv("Metric publication retry worker did not terminate within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnv(e, "Interrupted while stopping metric publication retry worker");
            }
        }
    }

    int pendingSamples() {
        synchronized (store) {
            return pending.size();
        }
    }

    boolean retryWorkerRunning() {
        synchronized (store) {
            return retryWorker != null && !retryWorker.isShutdown();
        }
    }

    @Override
    public void beforeReset() {
        synchronized (store) {
            // Acquiring the canonical monitor drains any active write. Release it before the
            // controller enters StorageFactory.clearAll(), avoiding factory/backend inversion.
            paused = true;
            pending.clear();
            forgetCompletedOutages();
        }
    }

    @Override
    public void afterReset() {
        synchronized (store) {
            paused = false;
        }
    }

    @Override
    public void clear() {
        synchronized (store) {
            pending.clear();
            forgetCompletedOutages();
        }
    }

    private List<MetricFilter> filtersOf(String logGroupName, String region) {
        return filtersOf(logGroupName, region, null);
    }

    /** The group's filters in {@code accountId}'s partition, or in the caller's own when it is null. */
    private List<MetricFilter> filtersOf(String logGroupName, String region, String accountId) {
        String prefix = groupPrefix(region, logGroupName);
        if (accountId != null && store instanceof AccountAwareStorageBackend<?> rawAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<MetricFilter> aware = (AccountAwareStorageBackend<MetricFilter>) rawAware;
            return aware.scanForAccount(accountId, key -> key.startsWith(prefix));
        }
        return store.scan(key -> key.startsWith(prefix));
    }

    private static String groupPrefix(String region, String logGroupName) {
        return region + "::" + logGroupName + "::";
    }

    private static String key(String region, String logGroupName, String filterName) {
        return groupPrefix(region, logGroupName) + filterName;
    }

}
