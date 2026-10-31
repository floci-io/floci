package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class CloudWatchMetricsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsService.class);

    private static final List<String> ALARM_TYPES = List.of("CompositeAlarm", "MetricAlarm", "LogAlarm");
    /** DescribeAlarms' StateValue enum, in the order AWS lists it when it refuses another value. */
    private static final List<String> STATE_VALUES = List.of("INSUFFICIENT_DATA", "ALARM", "OK");
    private static final List<String> HISTORY_ALARM_TYPES = List.of("MetricAlarm", "CompositeAlarm");
    private static final List<String> HISTORY_ITEM_TYPES = List.of("AlarmContributorAction",
            "AlarmContributorStateUpdate", "Action", "StateUpdate", "ConfigurationUpdate");
    private static final List<String> SCAN_BY = List.of("TimestampAscending", "TimestampDescending");
    private static final Duration HISTORY_RETENTION = Duration.ofDays(30);
    /** The form of {@code java.util.Date#toString} in UTC, which AWS's date range error prints. */
    private static final DateTimeFormatter HISTORY_DATE_FORMAT = DateTimeFormatter
            .ofPattern("EEE MMM dd HH:mm:ss 'UTC' yyyy", Locale.ROOT).withZone(ZoneOffset.UTC);
    /** A child's transition time in a composite alarm's StateReason: "Monday 14 September, 2026 06:29:58 UTC". */
    private static final DateTimeFormatter REASON_TIME = DateTimeFormatter
            .ofPattern("EEEE dd MMMM, yyyy HH:mm:ss 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    /** The most StateReasonData can hold, in Unicode code points as SetAlarmState's length check counts them. */
    private static final int MAX_STATE_REASON_DATA = 4000;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final StorageBackend<String, MetricDatum> metricStore;
    private final StorageBackend<String, Alarm> alarmStore;
    private final StorageBackend<String, AlarmHistoryItem> historyStore;
    private final RegionResolver regionResolver;
    private final AlarmSnsNotifier alarmSnsNotifier;
    private long lastHistorySequence;
    private Instant nextHistoryPrune = Instant.MIN;

    public CloudWatchMetricsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory, regionResolver, null);
    }

    @Inject
    public CloudWatchMetricsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                    AlarmSnsNotifier alarmSnsNotifier) {
        // Services publish into this store every minute, SQS for every active queue, so under
        // persistent mode it is journaled rather than rewritten in full on every datum.
        this.metricStore = storageFactory.create("cloudwatchmetrics", "cwmetrics.json",
                new TypeReference<Map<String, MetricDatum>>() {}, WriteProfile.APPEND_HEAVY);
        this.alarmStore = storageFactory.create("cloudwatchmetrics", "cwalarms.json",
                new TypeReference<Map<String, Alarm>>() {});
        this.historyStore = storageFactory.create("cloudwatchmetrics", "cwalarmhistory.json",
                new TypeReference<Map<String, AlarmHistoryItem>>() {}, WriteProfile.APPEND_HEAVY);
        this.regionResolver = regionResolver;
        this.alarmSnsNotifier = alarmSnsNotifier;
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, Alarm> alarmStore,
                             RegionResolver regionResolver) {
        this(metricStore, alarmStore, regionResolver, null);
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, Alarm> alarmStore,
                             RegionResolver regionResolver, AlarmSnsNotifier alarmSnsNotifier) {
        this(metricStore, alarmStore, new InMemoryStorage<>(), regionResolver, alarmSnsNotifier);
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, Alarm> alarmStore,
                             StorageBackend<String, AlarmHistoryItem> historyStore,
                             RegionResolver regionResolver, AlarmSnsNotifier alarmSnsNotifier) {
        this.metricStore = metricStore;
        this.alarmStore = alarmStore;
        this.historyStore = historyStore;
        this.regionResolver = regionResolver;
        this.alarmSnsNotifier = alarmSnsNotifier;
    }

    public void putMetricData(String namespace, List<MetricDatum> datums, String region) {
        putMetricDataForAccount(null, namespace, datums, region);
    }

    /**
     * Stores the datums in {@code accountId}'s partition rather than the caller's, for writers that
     * run outside a request, such as a metric filter publishing for a log batch a container
     * streamed on another account's behalf. A null account is the caller's own.
     */
    public void putMetricDataForAccount(String accountId, String namespace, List<MetricDatum> datums, String region) {
        long nowSeconds = Instant.now().getEpochSecond();
        for (MetricDatum datum : datums) {
            datum.setNamespace(namespace);
            if (datum.getTimestamp() == 0) {
                datum.setTimestamp(nowSeconds);
            }
            // Synthesize StatisticValues if only a scalar value was provided
            if (datum.getSampleCount() == 0 && datum.getSum() == 0) {
                datum.setSampleCount(1);
                datum.setSum(datum.getValue());
                datum.setMinimum(datum.getValue());
                datum.setMaximum(datum.getValue());
            }

            storeDatum(accountId, namespace, datum, region, UUID.randomUUID().toString());
        }
        LOG.debugv("PutMetricData: {0} datums for namespace {1}", datums.size(), namespace);
    }

    /**
     * Internal scalar publication, not an AWS PutMetricData operation. The publisher supplies a
     * canonical account, an explicit event timestamp (including epoch zero), and a stable ID for
     * this one contribution. Retrying a partially/ambiguously committed write replaces the same
     * key instead of appending another sample. Distinct contributions must have distinct IDs.
     * The caller's snapshot is never mutated or retained by the store.
     */
    public void publishMetricForAccount(String accountId, String namespace, MetricDatum datum,
                                        String region, String publicationId) {
        if (accountId == null || accountId.isBlank() || publicationId == null || publicationId.isBlank()) {
            throw new IllegalArgumentException("Internal publication requires an explicit account and publication ID");
        }
        MetricDatum sample = new MetricDatum();
        sample.setNamespace(namespace);
        sample.setMetricName(datum.getMetricName());
        sample.setUnit(datum.getUnit());
        sample.setDimensions(List.copyOf(datum.getDimensions()));
        sample.setTimestamp(datum.getTimestamp());
        sample.setValue(datum.getValue());
        sample.setSampleCount(1);
        sample.setSum(datum.getValue());
        sample.setMinimum(datum.getValue());
        sample.setMaximum(datum.getValue());
        storeDatum(accountId, namespace, sample, region, "publication-" + publicationId);
    }

    private void storeDatum(String accountId, String namespace, MetricDatum datum, String region, String id) {
        String key = region + "::" + namespace + "::" + datum.getMetricName()
                + "::" + buildDimKey(datum.getDimensions()) + "::"
                + String.format("%013d", datum.getTimestamp()) + "::" + id;
        if (accountId != null && metricStore instanceof AccountAwareStorageBackend<?> rawAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<MetricDatum> aware = (AccountAwareStorageBackend<MetricDatum>) rawAware;
            aware.putForAccount(accountId, key, datum);
        } else {
            metricStore.put(key, datum);
        }
    }

    /**
     * Drops the {@code namespace} datapoints stamped before {@code cutoff}, in every account. A service
     * that writes its own metrics each minute, such as SQS, calls it so that history stays bounded.
     */
    public void deleteMetricDataBefore(String namespace, Instant cutoff) {
        long cutoffSeconds = cutoff.getEpochSecond();
        deleteInEveryAccount(metricStore, d -> namespace.equals(d.getNamespace()) && d.getTimestamp() < cutoffSeconds);
    }

    private static <V> void deleteInEveryAccount(StorageBackend<String, V> store, Predicate<V> expired) {
        if (store instanceof AccountAwareStorageBackend<V> aware) {
            aware.deleteAllAccountsMatching(expired);
            return;
        }
        for (String key : List.copyOf(store.keys())) {
            store.get(key).filter(expired).ifPresent(ignored -> store.delete(key));
        }
    }

    public record MetricIdentity(String namespace, String metricName, List<Dimension> dimensions) {}

    public List<MetricIdentity> listMetrics(String namespace, String metricName,
                                             List<Dimension> dimensions, String region) {
        String prefix = region + "::";
        if (namespace != null && !namespace.isBlank()) {
            prefix += namespace + "::";
        }

        final String finalPrefix = prefix;
        List<MetricDatum> all = metricStore.scan(k -> k.startsWith(finalPrefix));

        // De-duplicate by (namespace, metricName, dimKey)
        Map<String, MetricIdentity> deduped = new LinkedHashMap<>();
        for (MetricDatum d : all) {
            if (metricName != null && !metricName.isBlank() && !metricName.equals(d.getMetricName())) {
                continue;
            }
            if (dimensions != null && !dimensions.isEmpty() && !matchesDimensions(d.getDimensions(), dimensions)) {
                continue;
            }
            String identity = d.getNamespace() + "::" + d.getMetricName() + "::" + buildDimKey(d.getDimensions());
            deduped.putIfAbsent(identity, new MetricIdentity(d.getNamespace(), d.getMetricName(), d.getDimensions()));
        }
        return new ArrayList<>(deduped.values());
    }

    public record Datapoint(Instant timestamp, double sampleCount, double sum,
                             double average, double minimum, double maximum, String unit) {}

    public record MetricStat(
            String namespace,
            String metricName,
            List<Dimension> dimensions,
            int period,
            String stat,
            String unit
    ) {}

    public record MetricDataQuery(
            String id,
            MetricStat metricStat,
            String expression,
            String label,
            boolean returnData
    ) {}

    public record MetricDataResult(
            String id,
            String label,
            List<Instant> timestamps,
            List<Double> values,
            String statusCode
    ) {}

    public List<Datapoint> getMetricStatistics(String namespace, String metricName,
                                                List<Dimension> dimensions,
                                                Instant startTime, Instant endTime,
                                                int periodSeconds,
                                                List<String> statistics,
                                                String unit, String region) {
        String dimKey = dimensions != null ? buildDimKey(dimensions) : "";
        String prefix = region + "::" + namespace + "::" + metricName + "::" + dimKey + "::";

        long startEpoch = startTime != null ? startTime.getEpochSecond() : 0;
        long endEpoch = endTime != null ? endTime.getEpochSecond() : Long.MAX_VALUE;

        List<MetricDatum> matching = metricStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            // Extract timestamp from key segment
            String[] parts = k.split("::");
            if (parts.length < 6) return false;
            try {
                long ts = Long.parseLong(parts[parts.length - 2]);
                return ts >= startEpoch && ts <= endEpoch;
            } catch (NumberFormatException e) {
                return false;
            }
        });

        if (unit != null && !unit.isBlank() && !"None".equals(unit)) {
            matching = matching.stream()
                    .filter(d -> unit.equals(d.getUnit()))
                    .collect(Collectors.toList());
        }

        // Group by period bucket
        Map<Long, List<MetricDatum>> buckets = new LinkedHashMap<>();
        for (MetricDatum d : matching) {
            long bucket = (d.getTimestamp() / periodSeconds) * periodSeconds;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(d);
        }

        List<Datapoint> result = new ArrayList<>();
        for (Map.Entry<Long, List<MetricDatum>> entry : buckets.entrySet()) {
            List<MetricDatum> group = entry.getValue();
            double sc = group.stream().mapToDouble(MetricDatum::getSampleCount).sum();
            double sum = group.stream().mapToDouble(MetricDatum::getSum).sum();
            double min = group.stream().mapToDouble(MetricDatum::getMinimum).min().orElse(0);
            double max = group.stream().mapToDouble(MetricDatum::getMaximum).max().orElse(0);
            double avg = sc > 0 ? sum / sc : 0;
            String resolvedUnit = group.stream()
                    .map(MetricDatum::getUnit)
                    .filter(u -> u != null && !u.isBlank())
                    .findFirst().orElse("None");
            result.add(new Datapoint(
                    Instant.ofEpochSecond(entry.getKey()),
                    sc, sum, avg, min, max, resolvedUnit
            ));
        }
        result.sort(Comparator.comparing(Datapoint::timestamp));
        return result;
    }

    public List<MetricDataResult> getMetricData(
            List<MetricDataQuery> queries,
            Instant startTime,
            Instant endTime,
            String region) {

        List<MetricDataResult> results = new ArrayList<>();

        for (MetricDataQuery query : queries) {
            if (!query.returnData()) {
                continue;
            }
            if (query.metricStat() != null) {
                MetricStat stat = query.metricStat();
                int period = stat.period() > 0 ? stat.period() : 60;

                List<Datapoint> datapoints = getMetricStatistics(
                        stat.namespace(), stat.metricName(), stat.dimensions(),
                        startTime, endTime, period,
                        List.of(stat.stat()), stat.unit(), region);

                List<Instant> timestamps = new ArrayList<>();
                List<Double> values = new ArrayList<>();
                for (Datapoint dp : datapoints) {
                    timestamps.add(dp.timestamp());
                    values.add(resolveStatValue(dp, stat.stat()));
                }

                String label = query.label() != null ? query.label() : stat.metricName();
                results.add(new MetricDataResult(query.id(), label, timestamps, values, "Complete"));
            }
            // Expression-based queries are out of scope for this implementation
        }
        return results;
    }

    /** Shared with {@link AlarmEvaluator}, which resolves the same statistic against
     * freshly-fetched datapoints when evaluating an alarm's threshold. */
    public static double resolveStatValue(Datapoint dp, String stat) {
        return switch (stat) {
            case "Average" -> dp.average();
            case "Sum" -> dp.sum();
            case "Minimum" -> dp.minimum();
            case "Maximum" -> dp.maximum();
            case "SampleCount" -> dp.sampleCount();
            default -> {
                if (stat.startsWith("p")) yield dp.maximum();
                else yield dp.average();
            }
        };
    }

    /**
     * Creates or updates a metric alarm. An update keeps the alarm's state, so composite alarms
     * above it are not evaluated: one set with SetAlarmState returns to its actual state only once
     * a child changes state.
     */
    public synchronized void putMetricAlarm(MetricAlarm alarm, String region) {
        boolean created = alarmStore.get(region + "::" + alarm.getAlarmName()).isEmpty();
        putAlarm(alarm, region);
        if (created) {
            evaluateComposites(region, Set.of(alarm.getAlarmName()));
        }
    }

    public void putCompositeAlarm(CompositeAlarm alarm, String region) {
        putCompositeAlarm(alarm, region, true);
    }

    public synchronized void putCompositeAlarm(CompositeAlarm alarm, String region, boolean replaceExisting) {
        if (!replaceExisting && alarmStore.get(region + "::" + alarm.getAlarmName()).isPresent()) {
            throw new AwsException("AlreadyExists", "Alarm already exists: " + alarm.getAlarmName(), 400);
        }
        Set<String> references = alarmReferences(alarm, region);
        if (references.size() > 100) {
            throw new AwsException("LimitExceeded", "AlarmRule references more than 100 child alarms", 400);
        }
        List<CompositeAlarm> existing = describeCompositeAlarms(null, null, region);
        for (String reference : references) {
            long parents = existing.stream().filter(parent -> !parent.getAlarmName().equals(alarm.getAlarmName()))
                    .filter(parent -> alarmReferences(parent, region).contains(reference))
                    .count();
            if (parents >= 150) {
                throw new AwsException("LimitExceeded", "An alarm can be referenced by at most 150 composite alarms", 400);
            }
        }
        Map<String, Alarm> alarms = alarmIndex(region);
        List<String> missing = references.stream().filter(reference -> !alarms.containsKey(reference)).toList();
        if (!missing.isEmpty()) {
            throw new AwsException("ValidationError", "Could not save the composite alarm as alarms " + missing
                    + " in the alarm rule do not exist", 400);
        }
        boolean created = alarmStore.get(region + "::" + alarm.getAlarmName()).isEmpty();
        putAlarm(alarm, region);
        evaluateComposites(region, Set.of(), alarm.getAlarmName(), created);
    }

    /** The ARN of each alarm the rule names, in the order it first names them. */
    private Set<String> alarmReferences(CompositeAlarm alarm, String region) {
        return CompositeAlarmRule.parse(alarm.getAlarmRule()).references().stream()
                .map(reference -> AwsArnUtils.isArn(reference) ? reference
                        : regionResolver.buildArn("cloudwatch", region, "alarm:" + reference))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void putAlarm(Alarm alarm, String region) {
        String name = alarm.getAlarmName();
        String violation = name == null ? SmithyValidationErrors.notNull("alarmName")
                : SmithyValidationErrors.lengthOutOfRange("alarmName", name, 1, 255);
        if (violation == null && name.isBlank()) {
            violation = SmithyValidationErrors.lengthTooShort("alarmName", name, 1);
        }
        if (violation != null) {
            throw SmithyValidationErrors.validationError(violation);
        }
        Alarm previous = alarmStore.get(region + "::" + name).orElse(null);
        ObjectNode before = previous == null ? null : snapshot(previous);
        if (previous != null) {
            if (!previous.getClass().equals(alarm.getClass())) {
                throw new AwsException("ValidationError", "An alarm of another type already has this name", 400);
            }
            alarm.setStateValue(previous.getStateValue());
            alarm.setStateReason(previous.getStateReason());
            alarm.setStateReasonData(previous.getStateReasonData());
            alarm.setStateUpdatedTimestamp(previous.getStateUpdatedTimestamp());
            alarm.setStateTransitionedTimestamp(previous.getStateTransitionedTimestamp());
            alarm.setTags(new HashMap<>(previous.getTags()));
        } else {
            alarm.setStateTransitionedTimestamp(alarm.getStateUpdatedTimestamp());
        }
        if (alarm.getAlarmArn() == null) {
            alarm.setAlarmArn(regionResolver.buildArn("cloudwatch", region, "alarm:" + name));
        }
        alarm.setRegion(region);
        alarm.setAlarmConfigurationUpdatedTimestamp(Instant.now().getEpochSecond());
        alarmStore.put(region + "::" + name, alarm);
        ObjectNode after = snapshot(alarm);
        ObjectNode data = MAPPER.createObjectNode().put("version", "1.0");
        if (before == null) {
            data.put("type", "Create").set("createdAlarm", after);
            recordHistory(alarm, region, "ConfigurationUpdate", createdSummary(name), data);
        } else {
            data.put("type", "Update").set("updatedAlarm", after);
            ObjectNode original = data.putObject("originalUpdatedFields");
            Set<String> fields = new LinkedHashSet<>();
            before.fieldNames().forEachRemaining(fields::add);
            after.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (!Objects.equals(before.get(field), after.get(field))) {
                    original.set(field, before.has(field) ? before.get(field) : NullNode.getInstance());
                }
            }
            recordHistory(alarm, region, "ConfigurationUpdate", "Alarm \"" + name + "\" updated", data);
        }
        LOG.infov("Put {0}: {1} in {2}", alarm.getClass().getSimpleName(), name, region);
    }

    /** The alarm as history items show it: AWS's timestamp strings, without state reasons, tags or Floci's bookkeeping. */
    private static ObjectNode snapshot(Alarm alarm) {
        ObjectNode node = MAPPER.valueToTree(alarm);
        node.remove(List.of("region", "tags", "stateReason", "stateReasonData", "alarmType",
                "stateTransitionedTimestamp"));
        node.put("alarmConfigurationUpdatedTimestamp", AlarmSnsNotifier.TIMESTAMP_FORMAT.format(
                Instant.ofEpochSecond(alarm.getAlarmConfigurationUpdatedTimestamp())));
        node.put("stateUpdatedTimestamp", AlarmSnsNotifier.TIMESTAMP_FORMAT.format(
                Instant.ofEpochSecond(alarm.getStateUpdatedTimestamp())));
        return node;
    }

    /** Metric alarms evaluated by the existing metric evaluator. */
    public List<MetricAlarm> allAlarms() {
        return alarmStore.scan(k -> true).stream().filter(MetricAlarm.class::isInstance)
                .map(MetricAlarm.class::cast).toList();
    }

    public List<MetricAlarm> describeAlarms(List<String> names, String prefix, String region) {
        return matchingAlarms(names, prefix, region).stream().filter(MetricAlarm.class::isInstance)
                .map(MetricAlarm.class::cast).toList();
    }

    /** The DescribeAlarms members that choose the alarms it lists: a null string or an empty list when left out. */
    public record AlarmFilter(List<String> alarmNames, String alarmNamePrefix, List<String> alarmTypes,
                              String stateValue, String actionPrefix, String childrenOfAlarmName,
                              String parentsOfAlarmName) {}

    /** The metric and composite alarms a DescribeAlarms request lists. */
    public record DescribedAlarms(List<MetricAlarm> metricAlarms, List<CompositeAlarm> compositeAlarms) {}

    /**
     * The alarms a DescribeAlarms request lists: the kinds {@code AlarmTypes} names, metric alarms when it
     * names none, narrowed by name, by {@code StateValue} and by {@code ActionPrefix}, which an alarm, OK or
     * insufficient-data action may start with. {@code ChildrenOfAlarmName} lists instead the alarms a
     * composite alarm's rule names, and {@code ParentsOfAlarmName} the composite alarms whose rules name the
     * alarm; neither lists the alarm it names, and either one is refused with any other member except
     * {@code MaxRecords} and {@code NextToken}.
     */
    public DescribedAlarms describeAlarms(AlarmFilter filter, String region) {
        validate(filter);
        if (filter.childrenOfAlarmName() != null) {
            return childrenOf(filter.childrenOfAlarmName(), region);
        }
        if (filter.parentsOfAlarmName() != null) {
            return parentsOf(filter.parentsOfAlarmName(), region);
        }
        List<String> types = filter.alarmTypes().isEmpty() ? List.of("MetricAlarm") : filter.alarmTypes();
        String actionPrefix = filter.actionPrefix();
        return byKind(matchingAlarms(filter.alarmNames(), filter.alarmNamePrefix(), region).stream()
                .filter(alarm -> types.contains(alarm.getAlarmType()))
                .filter(alarm -> filter.stateValue() == null || filter.stateValue().equals(alarm.getStateValue()))
                .filter(alarm -> actionPrefix == null || Stream.of(alarm.getAlarmActions(), alarm.getOkActions(),
                        alarm.getInsufficientDataActions()).flatMap(List::stream)
                        .anyMatch(action -> action.startsWith(actionPrefix)))
                .toList());
    }

    /**
     * Refuses an {@code AlarmTypes}, {@code ChildrenOfAlarmName}, {@code ParentsOfAlarmName}, {@code StateValue}
     * or {@code ActionPrefix} outside its modeled shape, all reported together as Smithy does, and then
     * ChildrenOfAlarmName or ParentsOfAlarmName given with another filter.
     */
    private static void validate(AlarmFilter filter) {
        List<String> violations = Stream.of(
                ALARM_TYPES.containsAll(filter.alarmTypes()) ? null
                        : SmithyValidationErrors.notAllInEnum(filter.alarmTypes(), "alarmTypes", ALARM_TYPES),
                filter.childrenOfAlarmName() == null ? null : SmithyValidationErrors.lengthOutOfRange(
                        "childrenOfAlarmName", filter.childrenOfAlarmName(), 1, 255),
                filter.parentsOfAlarmName() == null ? null : SmithyValidationErrors.lengthOutOfRange(
                        "parentsOfAlarmName", filter.parentsOfAlarmName(), 1, 255),
                filter.stateValue() == null || STATE_VALUES.contains(filter.stateValue()) ? null
                        : SmithyValidationErrors.notInEnum(filter.stateValue(), "stateValue", STATE_VALUES),
                filter.actionPrefix() == null ? null
                        : SmithyValidationErrors.lengthOutOfRange("actionPrefix", filter.actionPrefix(), 1, 1024))
                .filter(Objects::nonNull).toList();
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
        String related = filter.childrenOfAlarmName() != null ? "ChildrenOfAlarmName"
                : filter.parentsOfAlarmName() != null ? "ParentsOfAlarmName" : null;
        boolean combined = filter.childrenOfAlarmName() != null && filter.parentsOfAlarmName() != null
                || !filter.alarmNames().isEmpty() || filter.alarmNamePrefix() != null
                || !filter.alarmTypes().isEmpty() || filter.stateValue() != null || filter.actionPrefix() != null;
        if (related != null && combined) {
            throw new AwsException("ValidationError", "If you specify " + related + ", you cannot specify any other "
                    + "parameters in the request except for MaxRecords and NextToken.", 400);
        }
    }

    /** The alarms a composite alarm's rule names, by name or ARN; none for a metric alarm or a missing one. */
    private DescribedAlarms childrenOf(String name, String region) {
        if (!(alarmStore.get(region + "::" + name).orElse(null) instanceof CompositeAlarm parent)) {
            return new DescribedAlarms(List.of(), List.of());
        }
        Set<String> children = alarmReferences(parent, region);
        return byKind(alarmStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(alarm -> !alarm.getAlarmName().equals(name) && children.contains(alarm.getAlarmArn()))
                .toList());
    }

    /** The composite alarms whose rules name the alarm, by name or ARN, whether or not it still exists. */
    private DescribedAlarms parentsOf(String name, String region) {
        String arn = regionResolver.buildArn("cloudwatch", region, "alarm:" + name);
        return byKind(alarmStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(alarm -> alarm instanceof CompositeAlarm composite && !composite.getAlarmName().equals(name)
                        && alarmReferences(composite, region).contains(arn))
                .toList());
    }

    private static DescribedAlarms byKind(List<Alarm> alarms) {
        return new DescribedAlarms(
                alarms.stream().filter(MetricAlarm.class::isInstance).map(MetricAlarm.class::cast).toList(),
                alarms.stream().filter(CompositeAlarm.class::isInstance).map(CompositeAlarm.class::cast).toList());
    }

    public List<CompositeAlarm> describeCompositeAlarms(List<String> names, String prefix, String region) {
        return matchingAlarms(names, prefix, region).stream().filter(CompositeAlarm.class::isInstance)
                .map(CompositeAlarm.class::cast).toList();
    }

    private List<Alarm> matchingAlarms(List<String> names, String prefix, String region) {
        return alarmStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(a -> names == null || names.isEmpty() || names.contains(a.getAlarmName()))
                .filter(a -> prefix == null || a.getAlarmName().startsWith(prefix)).toList();
    }

    private Map<String, Alarm> alarmIndex(String region) {
        Map<String, Alarm> alarms = new HashMap<>();
        for (Alarm alarm : alarmStore.scan(k -> k.startsWith(region + "::"))) {
            alarms.put(alarm.getAlarmName(), alarm);
            alarms.put(alarm.getAlarmArn(), alarm);
        }
        return alarms;
    }

    private void evaluateComposites(String region, Set<String> changedAlarmNames) {
        evaluateComposites(region, changedAlarmNames, null, false);
    }

    /**
     * Evaluates {@code newAlarm}, a composite alarm just created or updated, and the composite alarms
     * above it and above {@code changedAlarmNames}, each after its composite children and only when
     * one of its children changed. A composite alarm on a cycle is not evaluated; those above it
     * still are. A change of state carries the reason and the triggering children CloudWatch reports.
     */
    private void evaluateComposites(String region, Set<String> changedAlarmNames, String newAlarm, boolean created) {
        if (changedAlarmNames.isEmpty() && newAlarm == null) {
            return;
        }
        Map<String, Alarm> alarms = alarmIndex(region);
        Set<String> changed = new HashSet<>(changedAlarmNames);
        for (String name : changedAlarmNames) {
            changed.add(regionResolver.buildArn("cloudwatch", region, "alarm:" + name));
        }
        Map<String, CompositeAlarmRule.Rule> candidates = new HashMap<>();
        for (Alarm alarm : new HashSet<>(alarms.values())) {
            if (alarm instanceof CompositeAlarm composite && !changedAlarmNames.contains(alarm.getAlarmName())) {
                candidates.put(alarm.getAlarmName(), CompositeAlarmRule.parse(composite.getAlarmRule()));
            }
        }
        Set<String> affected = new HashSet<>(changed);
        Map<String, CompositeAlarmRule.Rule> pending = new HashMap<>();
        boolean discovered;
        do {
            discovered = false;
            Iterator<Map.Entry<String, CompositeAlarmRule.Rule>> iterator = candidates.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, CompositeAlarmRule.Rule> next = iterator.next();
                if (next.getKey().equals(newAlarm) || next.getValue().references().stream().anyMatch(affected::contains)) {
                    pending.put(next.getKey(), next.getValue());
                    affected.add(next.getKey());
                    affected.add(alarms.get(next.getKey()).getAlarmArn());
                    iterator.remove();
                    discovered = true;
                }
            }
        } while (discovered);
        Set<String> cycles = pending.keySet().stream()
                .filter(name -> isCyclic(name, alarms)).collect(Collectors.toSet());
        if (!cycles.isEmpty()) {
            // Not evaluated, and no longer pending, so the composite alarms above them still are.
            LOG.debugv("Composite alarms on a cycle are not evaluated: {0}", cycles);
            pending.keySet().removeAll(cycles);
        }
        Function<String, String> states = reference -> {
            Alarm child = alarms.get(reference);
            return child == null ? null : child.getStateValue();
        };
        Map<String, Long> stateOrder = null;
        boolean progress;
        do {
            progress = false;
            Iterator<Map.Entry<String, CompositeAlarmRule.Rule>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, CompositeAlarmRule.Rule> next = iterator.next();
                boolean blocked = next.getValue().references().stream().map(alarms::get)
                        .anyMatch(child -> child != null && pending.containsKey(child.getAlarmName()));
                if (blocked) {
                    continue;
                }
                if (!next.getKey().equals(newAlarm)
                        && next.getValue().references().stream().noneMatch(changed::contains)) {
                    iterator.remove();
                    progress = true;
                    continue;
                }
                Alarm alarm = alarms.get(next.getKey());
                CompositeAlarmRule.Rule rule = next.getValue();
                boolean triggered = rule.evaluate(states);
                String state = triggered ? "ALARM" : "OK";
                if (!state.equals(alarm.getStateValue())) {
                    if (stateOrder == null) {
                        stateOrder = stateOrder(region);
                    }
                    String reason = null;
                    Long since = null;
                    if (next.getKey().equals(newAlarm)) {
                        reason = alarm.getAlarmArn() + (created ? " was created and its alarm rule evaluates to "
                                : " was updated and its new alarm rule evaluates to ") + state;
                    } else {
                        Alarm trigger = latestChangedChild(rule, alarms, changed, stateOrder);
                        if (trigger != null) {
                            reason = trigger.getAlarmArn() + " transitioned to " + trigger.getStateValue() + " at "
                                    + REASON_TIME.format(Instant.ofEpochSecond(trigger.lastTransitionTimestamp()));
                            since = orderOf(alarm, stateOrder);
                        }
                    }
                    // Only a changed child stored under another ARN, which no API call writes, fails to
                    // resolve: nothing then explains a change, so the alarm keeps its state.
                    if (reason != null) {
                        String data = triggeringAlarms(rule.agreeing(states, triggered), alarms, stateOrder, since);
                        changeState(alarm, region, state, reason, data);
                        // The transition is now the newest history entry.
                        stateOrder.put(alarm.getAlarmName(), lastHistorySequence);
                        changed.add(alarm.getAlarmName());
                        changed.add(alarm.getAlarmArn());
                    }
                }
                iterator.remove();
                progress = true;
            }
        } while (progress && !pending.isEmpty());
        if (!pending.isEmpty()) {
            // A cycle the name lookup above misses, as when an alarm is named like another's ARN.
            LOG.debugv("Composite alarm evaluation stopped with alarms still pending: {0}", pending.keySet());
        }
    }

    /** The child {@code rule} names that changed state most recently in this evaluation, or null if none resolves. */
    private static Alarm latestChangedChild(CompositeAlarmRule.Rule rule, Map<String, Alarm> alarms, Set<String> changed,
                                            Map<String, Long> stateOrder) {
        return rule.references().stream().map(alarms::get).filter(Objects::nonNull)
                .filter(child -> changed.contains(child.getAlarmName()) || changed.contains(child.getAlarmArn()))
                .max(Comparator.comparingLong(child -> orderOf(child, stateOrder))).orElse(null);
    }

    /**
     * The StateReasonData of a composite alarm's change of state: the {@code agreeing} children,
     * the one whose state is newest first, each with its state and when it took it, within what
     * StateReasonData can hold. For a change a child caused, CloudWatch lists only the children that
     * changed state after the composite alarm last did, which was at {@code since}.
     */
    private static String triggeringAlarms(Set<String> agreeing, Map<String, Alarm> alarms,
                                           Map<String, Long> stateOrder, Long since) {
        Map<String, Alarm> children = new LinkedHashMap<>();
        for (String reference : agreeing) {
            Alarm child = alarms.get(reference);
            if (child != null && (since == null || orderOf(child, stateOrder) > since)) {
                children.putIfAbsent(child.getAlarmName(), child);
            }
        }
        List<Alarm> newestFirst = new ArrayList<>(children.values());
        newestFirst.sort(Comparator.comparingLong((Alarm child) -> orderOf(child, stateOrder)).reversed());
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode list = data.putArray("triggeringAlarms");
        for (Alarm child : newestFirst) {
            ObjectNode entry = list.addObject().put("arn", child.getAlarmArn());
            entry.putObject("state").put("value", child.getStateValue()).put("timestamp",
                    AlarmSnsNotifier.TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(child.lastTransitionTimestamp())));
            String serialized = data.toString();
            if (serialized.codePointCount(0, serialized.length()) > MAX_STATE_REASON_DATA) {
                list.remove(list.size() - 1);
                break;
            }
        }
        return data.toString();
    }

    /**
     * The order in which the region's alarms took their current states, as alarm history sequences:
     * each alarm's latest StateUpdate entry, or its creation if its state never changed. History
     * orders state changes to the microsecond, where alarms keep their state times to the second.
     */
    private Map<String, Long> stateOrder(String region) {
        Map<String, Long> order = new HashMap<>();
        for (AlarmHistoryItem item : historyStore.scan(k -> k.startsWith(region + "::"))) {
            if ("StateUpdate".equals(item.historyItemType())
                    || createdSummary(item.alarmName()).equals(item.historySummary())) {
                order.merge(item.alarmName(), item.sequence(), Math::max);
            }
        }
        return order;
    }

    /** Where {@code alarm} falls in {@link #stateOrder}; one whose entries aged out of history, at its last transition. */
    private static long orderOf(Alarm alarm, Map<String, Long> stateOrder) {
        Long sequence = stateOrder.get(alarm.getAlarmName());
        return sequence != null ? sequence : epochMicros(Instant.ofEpochSecond(alarm.lastTransitionTimestamp()));
    }

    private static String createdSummary(String name) {
        return "Alarm \"" + name + "\" created";
    }

    /**
     * Deletes the named alarms, or none of them: CloudWatch refuses a request that names more than one
     * composite alarm, or an alarm that a composite alarm's rule names, even when the request deletes
     * that composite alarm too. Names that match no alarm are ignored.
     */
    public synchronized void deleteAlarms(List<String> alarmNames, String region) {
        List<Alarm> existing = alarmNames.stream().distinct()
                .map(name -> alarmStore.get(region + "::" + name).orElse(null)).filter(Objects::nonNull).toList();
        long composites = existing.stream().filter(CompositeAlarm.class::isInstance).count();
        if (composites > 1) {
            throw new AwsException("ValidationError", "Cannot delete [" + composites
                    + "] Composite Alarms as only one composite alarm delete is allowed in single request", 400);
        }
        Map<String, Alarm> alarms = alarmIndex(region);
        Set<String> referenced = new HashSet<>();
        for (Alarm alarm : new HashSet<>(alarms.values())) {
            if (alarm instanceof CompositeAlarm composite) {
                CompositeAlarmRule.parse(composite.getAlarmRule()).references().stream().map(alarms::get)
                        .filter(Objects::nonNull).forEach(child -> referenced.add(child.getAlarmName()));
            }
        }
        List<String> blocked = existing.stream().map(Alarm::getAlarmName).filter(referenced::contains).toList();
        if (!blocked.isEmpty()) {
            throw new AwsException("ValidationError", "Cannot delete " + String.join(",", blocked)
                    + " as there are composite alarm(s) depending on " + (blocked.size() == 1 ? "it." : "them."), 400);
        }
        for (Alarm alarm : existing) {
            ObjectNode data = MAPPER.createObjectNode().put("version", "1.0").put("type", "Delete");
            data.set("deletedAlarm", snapshot(alarm));
            recordHistory(alarm, region, "ConfigurationUpdate", "Alarm \"" + alarm.getAlarmName() + "\" deleted", data);
            alarmStore.delete(region + "::" + alarm.getAlarmName());
        }
        LOG.infov("Deleted alarms: {0} in {1}", alarmNames, region);
    }

    private boolean isCyclic(String name, Map<String, Alarm> alarms) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(name);
        while (!pending.isEmpty()) {
            Alarm next = alarms.get(pending.remove());
            if (next instanceof CompositeAlarm composite && visited.add(next.getAlarmName())) {
                for (String reference : CompositeAlarmRule.parse(composite.getAlarmRule()).references()) {
                    Alarm child = alarms.get(reference);
                    if (child != null && child.getAlarmName().equals(name)) {
                        return true;
                    }
                    if (child != null) {
                        pending.add(child.getAlarmName());
                    }
                }
            }
        }
        return false;
    }

    public void setAlarmState(String alarmName, String stateValue, String stateReason, String stateReasonData, String region) {
        List<String> states = List.of("INSUFFICIENT_DATA", "ALARM", "OK");
        if (stateValue == null || !states.contains(stateValue)) {
            throw SmithyValidationErrors.validationError(stateValue == null ? SmithyValidationErrors.notNull("stateValue")
                    : SmithyValidationErrors.notInEnum(stateValue, "stateValue", states));
        }
        if (stateReason == null) {
            throw SmithyValidationErrors.validationError(SmithyValidationErrors.notNull("stateReason"));
        }
        String reasonViolation = stateReason == null ? null
                : SmithyValidationErrors.lengthOutOfRange("stateReason", stateReason, 0, 1023);
        if (reasonViolation != null) {
            throw SmithyValidationErrors.validationError(reasonViolation);
        }
        String dataViolation = stateReasonData == null ? null
                : SmithyValidationErrors.lengthOutOfRange("stateReasonData", stateReasonData, 0, MAX_STATE_REASON_DATA);
        if (dataViolation != null) {
            throw SmithyValidationErrors.validationError(dataViolation);
        }
        List<AlarmSnsNotifier.Notification> notifications = List.of();
        synchronized (this) {
            String key = region + "::" + alarmName;
            Alarm alarm = alarmStore.get(key)
                    .orElseThrow(() -> new AwsException("ResourceNotFound", "Alarm not found: " + alarmName, 404));

            String oldState = alarm.getStateValue();
            changeState(alarm, region, stateValue, stateReason, stateReasonData);
            if (!stateValue.equals(oldState)) {
                evaluateComposites(region, Set.of(alarmName));
            }
            LOG.infov("SetAlarmState: {0} -> {1}", alarmName, stateValue);
            if (alarmSnsNotifier != null && alarm instanceof MetricAlarm metricAlarm) {
                notifications = alarmSnsNotifier.prepareTransition(metricAlarm, oldState);
            }
        }
        if (alarmSnsNotifier != null) {
            alarmSnsNotifier.publish(notifications);
        }
    }

    private void changeState(Alarm alarm, String region, String state, String reason, String reasonData) {
        String oldState = alarm.getStateValue();
        ObjectNode data = MAPPER.createObjectNode().put("version", "1.0");
        data.set("oldState", stateNode(oldState, alarm.getStateReason(), alarm.getStateReasonData()));
        data.set("newState", stateNode(state, reason, reasonData));
        Instant now = Instant.now();
        alarm.setStateValue(state);
        alarm.setStateReason(reason);
        alarm.setStateReasonData(reasonData);
        if (alarm.getStateTransitionedTimestamp() == 0) {
            // Stored before transitions were recorded: its last state update is the best known transition.
            alarm.setStateTransitionedTimestamp(alarm.getStateUpdatedTimestamp());
        }
        alarm.setStateUpdatedTimestamp(now.getEpochSecond());
        if (!state.equals(oldState)) {
            alarm.setStateTransitionedTimestamp(now.getEpochSecond());
        }
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
        if (!state.equals(oldState)) {
            recordHistory(alarm, region, "StateUpdate", "Alarm updated from " + oldState + " to " + state, data, now);
        }
    }

    private static ObjectNode stateNode(String value, String reason, String reasonData) {
        ObjectNode node = MAPPER.createObjectNode().put("stateValue", value).put("stateReason", reason);
        if (reasonData != null) {
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(reasonData);
            } catch (JsonProcessingException ignored) {
                // SetAlarmState does not require JSON here, so text that is not JSON is kept as a string.
                parsed = null;
            }
            node.set("stateReasonData", parsed == null || parsed.isMissingNode() ? TextNode.valueOf(reasonData) : parsed);
        }
        return node;
    }

    /** Called only under this service's monitor, which also guards the sequence and the prune deadline. */
    private void recordHistory(Alarm alarm, String region, String type, String summary, ObjectNode data) {
        recordHistory(alarm, region, type, summary, data, Instant.now());
    }

    /** As above, stamped with {@code now}: a state change passes the instant it gave the alarm. */
    private void recordHistory(Alarm alarm, String region, String type, String summary, ObjectNode data, Instant now) {
        if (!now.isBefore(nextHistoryPrune)) {
            long cutoffMillis = now.minus(HISTORY_RETENTION).toEpochMilli();
            deleteInEveryAccount(historyStore, item -> item.timestamp() < cutoffMillis);
            nextHistoryPrune = now.plus(Duration.ofHours(1));
        }
        lastHistorySequence = Math.max(epochMicros(now), lastHistorySequence + 1);
        historyStore.put(region + "::" + String.format("%020d", lastHistorySequence),
                new AlarmHistoryItem(lastHistorySequence, alarm.getAlarmName(), alarm.getAlarmType(),
                        now.toEpochMilli(), type, summary, data.toString()));
    }

    private static long epochMicros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000 + instant.getNano() / 1_000;
    }

    public synchronized PaginatedResult<AlarmHistoryItem> describeAlarmHistory(String alarmName, List<String> alarmTypes,
            String historyItemType, Instant startDate, Instant endDate, Integer maxRecords, String nextToken,
            String scanBy, String region) {
        List<String> violations = Stream.of(
                alarmName == null ? null : SmithyValidationErrors.lengthOutOfRange("alarmName", alarmName, 1, 255),
                alarmTypes == null || HISTORY_ALARM_TYPES.containsAll(alarmTypes) ? null
                        : SmithyValidationErrors.notAllInEnum(alarmTypes, "alarmTypes", HISTORY_ALARM_TYPES),
                historyItemType == null || HISTORY_ITEM_TYPES.contains(historyItemType) ? null
                        : SmithyValidationErrors.notInEnum(historyItemType, "historyItemType", HISTORY_ITEM_TYPES),
                maxRecords == null ? null : SmithyValidationErrors.outOfRange("maxRecords", maxRecords, 1, 100),
                scanBy == null || SCAN_BY.contains(scanBy) ? null
                        : SmithyValidationErrors.notInEnum(scanBy, "scanBy", SCAN_BY))
                .filter(Objects::nonNull).toList();
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
        if (alarmName == null && historyItemType != null && historyItemType.startsWith("AlarmContributor")) {
            throw new AwsException("ValidationError",
                    "Alarm name must be provided to query history for alarm contributors.", 400);
        }
        if (startDate != null && endDate != null && startDate.toEpochMilli() > endDate.toEpochMilli()) {
            throw CloudWatchMetricsException.invalidParameterValue("Start date " + HISTORY_DATE_FORMAT.format(startDate)
                    + " must be before end date " + HISTORY_DATE_FORMAT.format(endDate));
        }
        boolean ascending = "TimestampAscending".equals(scanBy);
        if (nextToken != null && Pagination.tokenStartsWith(nextToken,
                (ascending ? "alarm-history-desc" : "alarm-history-asc") + ":")) {
            return new PaginatedResult<>(List.of(), null);
        }
        List<String> types = alarmTypes == null || alarmTypes.isEmpty() ? List.of("MetricAlarm") : alarmTypes;
        long retained = Instant.now().minus(HISTORY_RETENTION).toEpochMilli();
        long start = startDate == null ? retained : Math.max(retained, startDate.toEpochMilli());
        long end = endDate == null ? Long.MAX_VALUE : endDate.toEpochMilli();
        List<AlarmHistoryItem> matching = historyStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(item -> alarmName == null || alarmName.equals(item.alarmName()))
                .filter(item -> types.contains(item.alarmType()))
                .filter(item -> historyItemType == null || historyItemType.equals(item.historyItemType()))
                .filter(item -> item.timestamp() >= start && item.timestamp() <= end)
                .toList();
        return Pagination.paginate(matching,
                item -> String.format("%020d", ascending ? item.sequence() : Long.MAX_VALUE - item.sequence()),
                maxRecords == null ? 50 : maxRecords, nextToken, ascending ? "alarm-history-asc" : "alarm-history-desc",
                token -> new AwsException("InvalidNextToken", "Invalid NextToken : " + token, 400));
    }

    /**
     * Resolves the alarm an ARN names, or reports that nothing does.
     *
     * <p>CloudWatch's three tag operations each declare {@code ResourceNotFoundException},
     * which is a different shape from the {@code ResourceNotFound} that {@code SetAlarmState}
     * and {@code GetDashboard} declare; the SDK maps the two codes to two exception classes,
     * so the tag path uses the longer one rather than the code used elsewhere in this service.
     *
     * <p>The message names the ARN rather than asserting it was an alarm, because both tag
     * handlers route every ARN that is not a dashboard and not a metric stream here. That
     * includes kinds AWS considers taggable and Floci does not serve, a Contributor Insights
     * {@code insight-rule/} ARN being the one AWS documents; reporting that no resource
     * matches the ARN is true of those, where "alarm not found" would not be.
     */
    private Alarm requireAlarm(String resourceArn, String region) {
        return alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> a.getAlarmArn() != null && a.getAlarmArn().equals(resourceArn))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No CloudWatch resource matches the ARN " + resourceArn + ".", 404));
    }

    public synchronized Map<String, String> listTagsForResource(String resourceArn, String region) {
        return new HashMap<>(requireAlarm(resourceArn, region).getTags());
    }

    public synchronized void tagResource(String resourceArn, Map<String, String> tags, String region) {
        Alarm alarm = requireAlarm(resourceArn, region);
        alarm.getTags().putAll(tags);
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys, String region) {
        Alarm alarm = requireAlarm(resourceArn, region);
        tagKeys.forEach(alarm.getTags()::remove);
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    static String buildDimKey(List<Dimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "";
        }
        return dimensions.stream()
                .sorted(Comparator.comparing(Dimension::name))
                .map(d -> d.name() + "=" + d.value())
                .collect(Collectors.joining(","));
    }

    private boolean matchesDimensions(List<Dimension> actual, List<Dimension> required) {
        String requiredKey = buildDimKey(required);
        String actualKey = buildDimKey(actual);
        return actualKey.contains(requiredKey);
    }
}
