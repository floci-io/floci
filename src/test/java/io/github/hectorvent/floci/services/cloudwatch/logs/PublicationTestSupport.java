package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Logs ingestion and Metrics aggregation, with only CDI wiring supplied by the fixture. */
class PublicationTestSupport {
    static final String ACCOUNT = "111111111111";
    static final String OTHER_ACCOUNT = "222222222222";
    static final String REGION = "eu-west-1";
    static final String GROUP = "/publication";
    static final long TIME = 1_700_000_040_000L;
    final AtomicReference<String> account = new AtomicReference<>(ACCOUNT);
    final RegionResolver resolver = mock(RegionResolver.class);
    final StorageFactory factory = mock(StorageFactory.class);
    final AccountAwareStorageBackend<MetricDatum> metricStore;
    final AccountAwareStorageBackend<MetricFilter> filters;
    final CloudWatchMetricsService metrics;
    final CloudWatchLogsService logs;
    final CloudWatchLogsMetricFilterService service;

    PublicationTestSupport() {
        this(new InMemoryStorage<>());
    }

    @SuppressWarnings("unchecked")
    PublicationTestSupport(StorageBackend<String, MetricDatum> sink) {
        Instance<RequestContext> contexts = mock(Instance.class);
        RequestContext context = mock(RequestContext.class);
        when(contexts.get()).thenReturn(context);
        when(context.getAccountId()).thenAnswer(call -> account.get());
        when(resolver.getAccountId()).thenAnswer(call -> account.get());
        metricStore = new AccountAwareStorageBackend<>(sink, contexts, ACCOUNT);
        filters = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, ACCOUNT);
        doReturn(metricStore).when(factory).create(eq("cloudwatchmetrics"), eq("cwmetrics.json"), any());
        doReturn(AccountAwareStorageBackend.inMemory(ACCOUNT)).when(factory)
                .create(eq("cloudwatchmetrics"), eq("cwalarms.json"), any());
        metrics = new CloudWatchMetricsService(factory, resolver);
        Event<LogEventsIngested> ingested = mock(Event.class);
        Event<LogGroupDeleted> deleted = mock(Event.class);
        logs = new CloudWatchLogsService(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, ACCOUNT),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, ACCOUNT),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, ACCOUNT),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, ACCOUNT), filters,
                new InMemoryStorage<>(), 10_000, Integer.MAX_VALUE, resolver, 0L, System::currentTimeMillis,
                ingested, deleted);
        service = new CloudWatchLogsMetricFilterService(logs, metrics, resolver);
        doAnswer(call -> { service.onLogEventsIngested(call.getArgument(0)); return null; }).when(ingested).fire(any());
        doAnswer(call -> { service.onLogGroupDeleted(call.getArgument(0)); return null; }).when(deleted).fire(any());
        group(GROUP, REGION);
    }

    void group(String group, String region) {
        logs.createLogGroup(group, null, null, region);
        logs.createLogStream(group, "s", region);
    }

    static MetricFilter definition(String name, String pattern, String value, Double fallback) {
        MetricTransformation t = new MetricTransformation();
        t.setMetricName("Value");
        t.setMetricNamespace(name);
        t.setMetricValue(value);
        t.setDefaultValue(fallback);
        t.setUnit("Count");
        MetricFilter f = new MetricFilter();
        f.setFilterName(name);
        f.setLogGroupName(GROUP);
        f.setFilterPattern(pattern);
        f.setMetricTransformations(List.of(t));
        return f;
    }

    void put(String name, String pattern, String value, Double fallback) {
        service.putMetricFilter(definition(name, pattern, value, fallback), REGION);
    }

    void ingest(long time, String... messages) {
        ingest(null, GROUP, REGION, time, List.of(messages));
    }

    void ingest(String owner, String group, String region, long time, List<String> messages) {
        List<Map<String, Object>> events = messages.stream()
                .map(message -> Map.<String, Object>of("timestamp", time, "message", message)).toList();
        assertNotNull(logs.putLogEventsForAccount(owner, group, "s", events, region));
    }

    List<CloudWatchMetricsService.Datapoint> points(String namespace, List<Dimension> dims, long time, String region) {
        return metrics.getMetricStatistics(namespace, "Value", dims, Instant.ofEpochMilli(time),
                Instant.ofEpochMilli(time).plusSeconds(59), 60, List.of("Sum", "SampleCount"), null, region);
    }

    void stats(String namespace, long time, double sum, double count) {
        stats(namespace, List.of(), time, sum, count);
    }

    void stats(String namespace, List<Dimension> dims, long time, double sum, double count) {
        List<CloudWatchMetricsService.Datapoint> points = points(namespace, dims, time, REGION);
        assertEquals(count == 0 ? 0 : 1, points.size());
        assertEquals(sum, points.stream().mapToDouble(CloudWatchMetricsService.Datapoint::sum).sum());
        assertEquals(count, points.stream().mapToDouble(CloudWatchMetricsService.Datapoint::sampleCount).sum());
    }
}
