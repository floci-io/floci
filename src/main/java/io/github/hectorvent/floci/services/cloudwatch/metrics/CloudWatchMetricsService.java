package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CloudWatchMetricsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsService.class);

    private final StorageBackend<String, MetricDatum> metricStore;
    private final StorageBackend<String, MetricAlarm> alarmStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchMetricsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.metricStore = storageFactory.create("cloudwatchmetrics", "cwmetrics.json",
                new TypeReference<Map<String, MetricDatum>>() {});
        this.alarmStore = storageFactory.create("cloudwatchmetrics", "cwalarms.json",
                new TypeReference<Map<String, MetricAlarm>>() {});
        this.regionResolver = regionResolver;
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, MetricAlarm> alarmStore,
                             RegionResolver regionResolver) {
        this.metricStore = metricStore;
        this.alarmStore = alarmStore;
        this.regionResolver = regionResolver;
    }

    public void putMetricData(String namespace, List<MetricDatum> datums, String region) {
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

            String dimKey = buildDimKey(datum.getDimensions());
            String key = region + "::" + namespace + "::" + datum.getMetricName()
                    + "::" + dimKey + "::"
                    + String.format("%013d", datum.getTimestamp()) + "::" + UUID.randomUUID();
            metricStore.put(key, datum);
        }
        LOG.debugv("PutMetricData: {0} datums for namespace {1}", datums.size(), namespace);
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

    private double resolveStatValue(Datapoint dp, String stat) {
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

    public void putMetricAlarm(MetricAlarm alarm, String region) {
        if (alarm.getAlarmArn() == null) {
            alarm.setAlarmArn(regionResolver.buildArn("cloudwatch", region, "alarm:" + alarm.getAlarmName()));
        }
        alarm.setAlarmConfigurationUpdatedTimestamp(Instant.now().getEpochSecond());
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
        LOG.infov("PutMetricAlarm: {0} in {1}", alarm.getAlarmName(), region);
    }

    public List<MetricAlarm> describeAlarms(List<String> alarmNames, String alarmNamePrefix, String region) {
        String prefix = region + "::";
        List<MetricAlarm> all = alarmStore.scan(k -> k.startsWith(prefix));

        if (alarmNames != null && !alarmNames.isEmpty()) {
            return all.stream().filter(a -> alarmNames.contains(a.getAlarmName())).toList();
        }
        if (alarmNamePrefix != null && !alarmNamePrefix.isBlank()) {
            return all.stream().filter(a -> a.getAlarmName().startsWith(alarmNamePrefix)).toList();
        }
        return all;
    }

    public void deleteAlarms(List<String> alarmNames, String region) {
        for (String name : alarmNames) {
            alarmStore.delete(region + "::" + name);
        }
        LOG.infov("Deleted alarms: {0} in {1}", alarmNames, region);
    }

    public void setAlarmState(String alarmName, String stateValue, String stateReason, String stateReasonData, String region) {
        String key = region + "::" + alarmName;
        MetricAlarm alarm = alarmStore.get(key)
                .orElseThrow(() -> new RuntimeException("Alarm not found: " + alarmName));

        alarm.setStateValue(stateValue);
        alarm.setStateReason(stateReason);
        alarm.setStateReasonData(stateReasonData);
        alarm.setStateUpdatedTimestamp(Instant.now().getEpochSecond());

        alarmStore.put(key, alarm);
        LOG.infov("SetAlarmState: {0} -> {1}", alarmName, stateValue);
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst()
                .map(MetricAlarm::getTags)
                .orElse(Map.of());
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst()
                .ifPresent(alarm -> {
                    alarm.getTags().putAll(tags);
                    alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
                });
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> resourceArn.equals(a.getAlarmArn()))
                .findFirst()
                .ifPresent(alarm -> {
                    tagKeys.forEach(alarm.getTags()::remove);
                    alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
                });
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
