package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.LogsMetricFilterCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.cloudwatch.logs.PublicationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Graceful factory shutdown/reload, not a durability claim for the process-local retry queue. */
class CloudWatchLogsMetricFilterStorageReloadTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void definitionsQuotasDeletionIsolationAndPublishedSamplesSurviveAccordingToMode(String mode) {
        long created;
        try (Services first = open(mode, ACCOUNT)) {
            first.group(GROUP, REGION);
            first.group(GROUP, "us-east-1");
            MetricFilter definition = definition("kept", "{ $.kind = %ERROR% }", "$.value", 7.0);
            definition.setApplyOnTransformedLogs(true);
            definition.setFieldSelectionCriteria("@aws.region = \"" + REGION + "\"");
            definition.setEmitSystemFieldDimensions(List.of("@aws.region"));
            created = first.filters.putMetricFilter(definition, REGION).getCreationTime();
            for (int i = 0; i < 3; i++) {
                first.filters.putMetricFilter(definition("regex" + i, "%WARN%", "1", null), REGION);
            }
            first.logs.putSubscriptionFilter(GROUP, "subscription", "%INFO%", "arn:aws:lambda:" + REGION
                    + ":" + ACCOUNT + ":function:target", null, REGION);
            first.filters.putMetricFilter(definition("deleted", "ERROR", "3", 7.0), REGION);
            first.filters.deleteMetricFilter(GROUP, "deleted", REGION);
            MetricFilter dimensioned = definition("dimensioned", "{ $.kind = * }", "$.value", null);
            dimensioned.getMetricTransformations().getFirst().setDimensions(Map.of("Kind", "$.kind"));
            first.filters.putMetricFilter(dimensioned, REGION);
            first.filters.putMetricFilter(definition("regional", "ERROR", "1", null), "us-east-1");
            first.logs.putLogEvents(GROUP, "s", List.of(Map.of("timestamp", TIME, "message", "INFO")), REGION);
            assertEquals(5, first.filters.countMetricFilters(GROUP, REGION));
            assertEquals(1, first.logs.describeLogGroups(GROUP, REGION).size());
            first.factory.flushAll();
        }
        // Different accounts use the very same factory path, with no concurrent writers.
        try (Services other = open(mode, OTHER_ACCOUNT)) {
            assertTrue(other.logs.describeLogGroups(GROUP, REGION).isEmpty());
            other.group(GROUP, REGION);
            other.filters.putMetricFilter(definition("other", "ERROR", "9", null), REGION);
        }
        try (Services second = open(mode, ACCOUNT)) {
            if (mode.equals("memory")) {
                assertTrue(second.logs.describeLogGroups(null, REGION).isEmpty());
                assertTrue(second.metrics.listMetrics(null, null, null, REGION).isEmpty());
                return;
            }
            List<MetricFilter> loaded = second.filters.describeMetricFilters(
                    GROUP, null, null, null, null, 50, REGION).metricFilters();
            assertEquals(5, loaded.size());
            assertFalse(loaded.stream().anyMatch(f -> List.of("deleted", "other", "regional").contains(f.getFilterName())));
            MetricFilter kept = loaded.stream().filter(f -> f.getFilterName().equals("kept")).findFirst().orElseThrow();
            assertEquals(created, kept.getCreationTime());
            assertEquals("{ $.kind = %ERROR% }", kept.getFilterPattern());
            assertEquals(true, kept.getApplyOnTransformedLogs());
            assertEquals("@aws.region = \"" + REGION + "\"", kept.getFieldSelectionCriteria());
            assertEquals(List.of("@aws.region"), kept.getEmitSystemFieldDimensions());
            assertEquals("$.value", kept.getMetricTransformations().getFirst().getMetricValue());
            assertEquals("Value", kept.getMetricTransformations().getFirst().getMetricName());
            assertEquals("kept", kept.getMetricTransformations().getFirst().getMetricNamespace());
            assertEquals(7.0, kept.getMetricTransformations().getFirst().getDefaultValue());
            assertEquals("Count", kept.getMetricTransformations().getFirst().getUnit());
            assertEquals(Map.of("Kind", "$.kind"), loaded.stream().filter(f -> f.getFilterName().equals("dimensioned"))
                    .findFirst().orElseThrow().getMetricTransformations().getFirst().getDimensions());
            assertEquals(5, second.filters.countMetricFilters(GROUP, REGION));
            assertEquals(1, second.filters.countMetricFilters(GROUP, "us-east-1"));
            AwsException quota = assertThrows(AwsException.class, () ->
                    second.filters.putMetricFilter(definition("over", "%FATAL%", "1", null), REGION));
            assertEquals("LimitExceededException", quota.getErrorCode());
            second.logs.deleteSubscriptionFilter(GROUP, "subscription", REGION);
            second.filters.putMetricFilter(definition("over", "%FATAL%", "1", null), REGION);
            assertEquals(6, second.filters.countMetricFilters(GROUP, REGION));
            List<CloudWatchMetricsService.Datapoint> samples = second.metrics.getMetricStatistics("kept", "Value",
                    List.of(), Instant.ofEpochMilli(TIME), Instant.ofEpochMilli(TIME).plusSeconds(60),
                    60, List.of("Sum", "SampleCount"), null, REGION);
            assertEquals(1, samples.size());
            assertEquals(7, samples.getFirst().sum());
            assertEquals(1, samples.getFirst().sampleCount());
            assertEquals(Instant.ofEpochMilli(TIME), samples.getFirst().timestamp());
            assertTrue(second.metrics.listMetrics(null, null, null, "us-east-1").isEmpty());
            second.logs.deleteLogGroup(GROUP, REGION);
        }
        try (Services third = open(mode, ACCOUNT)) {
            assertTrue(third.logs.describeLogGroups(GROUP, REGION).isEmpty());
            third.group(GROUP, REGION);
            assertEquals(0, third.filters.countMetricFilters(GROUP, REGION), "group deletion cannot resurrect filters");
        }
        try (Services other = open(mode, OTHER_ACCOUNT)) {
            assertEquals(1, other.filters.countMetricFilters(GROUP, REGION));
            assertTrue(other.metrics.listMetrics(null, null, null, REGION).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void reloadedMetricFilterCountStillEnforcesTheHundredDefinitionLimit(String mode) {
        try (Services first = open(mode, ACCOUNT)) {
            first.group(GROUP, REGION);
            for (int i = 0; i < 100; i++) {
                first.filters.putMetricFilter(definition("filter" + i, "ERROR", "1", null), REGION);
            }
        }
        try (Services second = open(mode, ACCOUNT)) {
            assertEquals(100, second.filters.countMetricFilters(GROUP, REGION));
            AwsException quota = assertThrows(AwsException.class, () ->
                    second.filters.putMetricFilter(definition("overflow", "ERROR", "1", null), REGION));
            assertEquals("LimitExceededException", quota.getErrorCode());
            second.filters.putMetricFilter(definition("filter0", "WARN", "2", null), REGION);
            assertEquals(100, second.filters.countMetricFilters(GROUP, REGION));
            second.filters.deleteMetricFilter(GROUP, "filter1", REGION);
            second.filters.putMetricFilter(definition("replacement", "ERROR", "1", null), REGION);
            assertEquals(100, second.filters.countMetricFilters(GROUP, REGION));
        }
        try (Services third = open(mode, ACCOUNT)) {
            assertEquals(100, third.filters.countMetricFilters(GROUP, REGION));
            assertTrue(third.filters.describeMetricFilters(GROUP, "filter1", null, null, null, 50, REGION)
                    .metricFilters().stream().noneMatch(f -> f.getFilterName().equals("filter1")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void actualCfnOwnershipMetadataRoundTripsAndStillDeletesOnlyItsNamedFilter(String mode) throws Exception {
        Map<String, String> attributes;
        try (Services first = open(mode, ACCOUNT)) {
            first.group(GROUP, REGION);
            StackResource resource = new StackResource();
            resource.setLogicalId("Filter");
            resource.setResourceType("AWS::Logs::MetricFilter");
            CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
            when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
            new LogsMetricFilterCfnProvisioner(first.filters).provision(resource, new ObjectMapper().readTree("""
                    {"LogGroupName":"/publication","FilterName":"owned","FilterPattern":"ERROR",
                     "MetricTransformations":[{"MetricNamespace":"CfnReload","MetricName":"Count","MetricValue":"1"}]}
                    """), new ProvisionContext(engine, REGION, ACCOUNT, "reload"));
            assertEquals("owned", resource.getPhysicalId());
            attributes = Map.copyOf(resource.getAttributes());
            assertTrue(attributes.get("__FlociMetricFilterState").contains("\"ownership\":\"OWNED\""));
            first.resources().put("filter", resource);
        }
        try (Services second = open(mode, ACCOUNT)) {
            StackResource loaded = second.resources().get("filter").orElseThrow();
            assertEquals(attributes, loaded.getAttributes());
            assertEquals("owned", loaded.getPhysicalId());
            second.filters.putMetricFilter(definition("unrelated", "ERROR", "1", null), REGION);
            new LogsMetricFilterCfnProvisioner(second.filters).delete(loaded, REGION);
            assertEquals(List.of("unrelated"), second.filters.describeMetricFilters(
                    GROUP, null, null, null, null, 50, REGION).metricFilters().stream().map(MetricFilter::getFilterName).toList());
        }
    }

    private Services open(String mode, String account) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(account);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3_600_000L);
        when(config.services().cloudwatchlogs().maxEventsPerQuery()).thenReturn(10_000);
        when(config.services().cloudwatchlogs().maxStoredEvents()).thenReturn(10_000);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(3_600_000L);
        return new Services(new StorageFactory(config, access), config, account);
    }

    private static class Services implements AutoCloseable {
        final StorageFactory factory;
        final CloudWatchLogsService logs;
        final CloudWatchMetricsService metrics;
        final CloudWatchLogsMetricFilterService filters;

        @SuppressWarnings("unchecked")
        Services(StorageFactory factory, EmulatorConfig config, String account) {
            this.factory = factory;
            RegionResolver resolver = new RegionResolver(REGION, account);
            Event<LogEventsIngested> ingested = mock(Event.class);
            Event<LogGroupDeleted> deleted = mock(Event.class);
            logs = new CloudWatchLogsService(factory, config, resolver, ingested, deleted);
            metrics = new CloudWatchMetricsService(factory, resolver);
            filters = new CloudWatchLogsMetricFilterService(logs, metrics, resolver);
            doAnswer(call -> { filters.onLogEventsIngested(call.getArgument(0)); return null; }).when(ingested).fire(any());
            doAnswer(call -> { filters.onLogGroupDeleted(call.getArgument(0)); return null; }).when(deleted).fire(any());
        }

        void group(String group, String region) {
            logs.createLogGroup(group, null, null, region);
            logs.createLogStream(group, "s", region);
        }

        AccountAwareStorageBackend<StackResource> resources() {
            return factory.create("cloudformation", "reload-resources.json", new TypeReference<>() {});
        }

        @Override public void close() {
            factory.shutdownAll();
        }
    }
}
