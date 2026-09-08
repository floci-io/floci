package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.filter.FilterMatch;
import io.github.hectorvent.floci.services.cloudwatch.logs.filter.FilterPattern;
import io.github.hectorvent.floci.services.cloudwatch.logs.filter.FilterPatternException;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Metric filters of CloudWatch Logs: the PutMetricFilter, DescribeMetricFilters, DeleteMetricFilter
 * and TestMetricFilter operations, and the part AWS does at ingestion, publishing a metric value for
 * every log event a filter matches. The filters live in their own store, keyed by Region, log group
 * and name; the log service tells this one about ingested events and deleted groups through CDI
 * events, so neither depends on the other's internals.
 */
@ApplicationScoped
public class CloudWatchLogsMetricFilterService {

    private static final Logger LOG = Logger.getLogger(CloudWatchLogsMetricFilterService.class);

    /** AWS's cap on metric filters per log group. */
    public static final int MAX_FILTERS_PER_LOG_GROUP = 100;
    /** AWS's cap on dimensions per metric filter. */
    public static final int MAX_DIMENSIONS = 3;
    private static final int MAX_FILTER_PATTERN_LENGTH = 1024;
    private static final int MAX_METRIC_VALUE_LENGTH = 100;
    private static final int MAX_DIMENSION_LENGTH = 255;
    private static final int MAX_TEST_MESSAGES = 50;
    private static final int MAX_DESCRIBE_LIMIT = 50;
    private static final Pattern FILTER_NAME = Pattern.compile("[^:*]{1,512}");
    private static final Pattern METRIC_NAME = Pattern.compile("[^:*$]{1,255}");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Set<String> SYSTEM_FIELDS = Set.of("@aws.account", "@aws.region");
    private static final Set<String> UNITS = Set.of("Seconds", "Microseconds", "Milliseconds", "Bytes", "Kilobytes",
            "Megabytes", "Gigabytes", "Terabytes", "Bits", "Kilobits", "Megabits", "Gigabits", "Terabits", "Percent",
            "Count", "Bytes/Second", "Kilobytes/Second", "Megabytes/Second", "Gigabytes/Second", "Terabytes/Second",
            "Bits/Second", "Kilobits/Second", "Megabits/Second", "Gigabits/Second", "Terabits/Second", "Count/Second",
            "None");

    private final StorageBackend<String, MetricFilter> store;
    private final CloudWatchLogsService logsService;
    private final CloudWatchMetricsService metricsService;

    @Inject
    public CloudWatchLogsMetricFilterService(StorageFactory storageFactory, CloudWatchLogsService logsService,
                                             CloudWatchMetricsService metricsService) {
        this(storageFactory.create("cloudwatchlogs", "cwlogs-metric-filters.json",
                new TypeReference<Map<String, MetricFilter>>() {}), logsService, metricsService);
    }

    CloudWatchLogsMetricFilterService(StorageBackend<String, MetricFilter> store, CloudWatchLogsService logsService,
                                      CloudWatchMetricsService metricsService) {
        this.store = store;
        this.logsService = logsService;
        this.metricsService = metricsService;
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
    public synchronized MetricFilter putMetricFilter(MetricFilter definition, String region) {
        String logGroupName = requireLogGroup(definition.getLogGroupName(), region);
        String filterName = requireFilterName(definition.getFilterName());
        FilterPattern pattern = requirePattern(definition.getFilterPattern());
        MetricTransformation transformation = requireTransformation(definition.getMetricTransformations(), pattern);
        validateSystemFields(definition.getEmitSystemFieldDimensions(), transformation);

        String key = key(region, logGroupName, filterName);
        Optional<MetricFilter> existing = store.get(key);
        if (existing.isEmpty() && countMetricFilters(logGroupName, region) >= MAX_FILTERS_PER_LOG_GROUP) {
            throw new AwsException("LimitExceededException",
                    "The log group " + logGroupName + " already has the maximum of " + MAX_FILTERS_PER_LOG_GROUP
                            + " metric filters.", 400);
        }

        MetricFilter filter = new MetricFilter();
        filter.setLogGroupName(logGroupName);
        filter.setFilterName(filterName);
        filter.setFilterPattern(definition.getFilterPattern());
        filter.setMetricTransformations(List.of(copy(transformation)));
        filter.setCreationTime(existing.map(MetricFilter::getCreationTime).orElseGet(System::currentTimeMillis));
        filter.setApplyOnTransformedLogs(definition.getApplyOnTransformedLogs());
        filter.setFieldSelectionCriteria(definition.getFieldSelectionCriteria());
        filter.setEmitSystemFieldDimensions(definition.getEmitSystemFieldDimensions() == null
                ? null : List.copyOf(definition.getEmitSystemFieldDimensions()));
        store.put(key, filter);
        LOG.infov("Put metric filter {0} on log group {1}", filterName, logGroupName);
        return filter;
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

    private static FilterPattern requirePattern(String filterPattern) {
        if (filterPattern == null) {
            throw invalid("filterPattern is required.");
        }
        if (filterPattern.length() > MAX_FILTER_PATTERN_LENGTH) {
            throw invalid("filterPattern must be at most " + MAX_FILTER_PATTERN_LENGTH + " characters.");
        }
        try {
            return FilterPattern.parse(filterPattern);
        } catch (FilterPatternException e) {
            throw invalid(e.getMessage());
        }
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
        if (!NUMBER.matcher(value).matches() && !pattern.declaresField(value)) {
            throw invalid("metricValue must be a number or a field of the filter pattern such as $.field or $field, got '"
                    + value + "'.");
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

    private static void validateSystemFields(List<String> systemFields, MetricTransformation t) {
        if (systemFields == null) {
            return;
        }
        for (String field : systemFields) {
            if (!SYSTEM_FIELDS.contains(field)) {
                throw invalid("emitSystemFieldDimensions must contain only @aws.account and @aws.region, got '"
                        + field + "'.");
            }
        }
        int dimensions = t.getDimensions() == null ? 0 : t.getDimensions().size();
        if (dimensions + systemFields.size() > MAX_DIMENSIONS) {
            throw invalid("System field dimensions count toward the limit of " + MAX_DIMENSIONS
                    + " dimensions per metric filter.");
        }
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
     * {@code metricName} needs {@code metricNamespace}.
     */
    public DescribeMetricFiltersResult describeMetricFilters(String logGroupName, String filterNamePrefix,
                                                             String metricName, String metricNamespace,
                                                             String nextToken, Integer limit, String region) {
        int pageSize = limit == null ? MAX_DESCRIBE_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_DESCRIBE_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_DESCRIBE_LIMIT + ".");
        }
        if (metricName != null && metricNamespace == null) {
            throw invalid("metricNamespace is required when metricName is specified.");
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
                metricNamespace.equals(t.getMetricNamespace()) && (metricName == null || metricName.equals(t.getMetricName())));
    }

    public synchronized void deleteMetricFilter(String logGroupName, String filterName, String region) {
        requireLogGroup(logGroupName, region);
        if (filterName == null || filterName.isBlank()) {
            throw invalid("filterName is required.");
        }
        String key = key(region, logGroupName, filterName);
        if (store.get(key).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The specified metric filter does not exist.", 400);
        }
        store.delete(key);
        LOG.infov("Deleted metric filter {0} on log group {1}", filterName, logGroupName);
    }

    public Optional<MetricFilter> findMetricFilter(String logGroupName, String filterName, String region) {
        return store.get(key(region, logGroupName, filterName));
    }

    public int countMetricFilters(String logGroupName, String region) {
        return filtersOf(logGroupName, region).size();
    }

    /** Runs a pattern over sample messages, the TestMetricFilter operation; event numbers start at zero. */
    public List<MetricFilterMatchRecord> testMetricFilter(String filterPattern, List<String> messages) {
        FilterPattern pattern = requirePattern(filterPattern);
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
                matches.add(new MetricFilterMatchRecord(i, message, match.extractedValues()));
            }
        }
        return matches;
    }

    /**
     * Metric filters go with their log group, as on AWS. Synchronized with the puts so a put that
     * saw the group before its deletion cannot slip its filter in behind the cascade.
     */
    synchronized void onLogGroupDeleted(@Observes LogGroupDeleted event) {
        String prefix = groupPrefix(event.region(), event.logGroupName());
        List<String> keys = store.keys().stream().filter(key -> key.startsWith(prefix)).toList();
        keys.forEach(store::delete);
        if (!keys.isEmpty()) {
            LOG.debugv("Deleted {0} metric filter(s) with log group {1}", keys.size(), event.logGroupName());
        }
    }

    /**
     * Publishes the metrics of the group's filters for a stored batch: one value per matching
     * event, at the event's timestamp, with the dimensions whose fields the event carries, and the
     * default value once when nothing in the batch matched. The filters and the metrics are those
     * of the account the batch was written for, the caller's own unless the writer named one. A
     * filter that cannot publish is logged and skipped: AWS never fails an ingestion because of a
     * metric filter.
     */
    void onLogEventsIngested(@Observes LogEventsIngested event) {
        for (MetricFilter filter : filtersOf(event.logGroupName(), event.region(), event.accountId())) {
            try {
                publish(filter, event);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Metric filter {0} on log group {1} could not publish its metric",
                        filter.getFilterName(), event.logGroupName());
            }
        }
    }

    private void publish(MetricFilter filter, LogEventsIngested event) {
        FilterPattern pattern = FilterPattern.parse(filter.getFilterPattern());
        MetricTransformation t = filter.getMetricTransformations().getFirst();
        Double literal = NUMBER.matcher(t.getMetricValue()).matches() ? Double.parseDouble(t.getMetricValue()) : null;
        List<MetricDatum> datums = new ArrayList<>();
        long lastTimestamp = 0;
        boolean matched = false;
        for (LogEvent logEvent : event.events()) {
            lastTimestamp = Math.max(lastTimestamp, logEvent.getTimestamp());
            FilterMatch match = pattern.match(logEvent.getMessage());
            if (!match.matched()) {
                continue;
            }
            matched = true;
            // A match whose field is missing or not a number publishes nothing, and it is still a
            // match: the default value is for batches the pattern matched nothing in.
            Double value = literal != null ? literal : number(match.value(t.getMetricValue()));
            if (value == null) {
                continue;
            }
            List<Dimension> dimensions = new ArrayList<>();
            if (t.getDimensions() != null) {
                t.getDimensions().forEach((name, reference) -> {
                    String dimensionValue = match.value(reference);
                    if (dimensionValue != null) {
                        dimensions.add(new Dimension(name, dimensionValue));
                    }
                });
            }
            datums.add(datum(t, value, dimensions, logEvent.getTimestamp()));
        }
        if (!matched && t.getDefaultValue() != null) {
            datums.add(datum(t, t.getDefaultValue(), List.of(), lastTimestamp));
        }
        if (!datums.isEmpty()) {
            metricsService.putMetricDataForAccount(event.accountId(), t.getMetricNamespace(), datums, event.region());
        }
    }

    private static MetricDatum datum(MetricTransformation t, double value, List<Dimension> dimensions, long timestampMillis) {
        MetricDatum datum = new MetricDatum();
        datum.setMetricName(t.getMetricName());
        datum.setValue(value);
        datum.setUnit(t.getUnit() == null ? "None" : t.getUnit());
        datum.setDimensions(dimensions);
        datum.setTimestamp(timestampMillis / 1000);
        return datum;
    }

    private static Double number(String text) {
        if (text == null || !NUMBER.matcher(text).matches()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
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

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
