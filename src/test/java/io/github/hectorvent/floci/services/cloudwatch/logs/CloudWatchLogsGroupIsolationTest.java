package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudWatchLogsGroupIsolationTest {

    private final InMemoryStorage<String, LogGroup> groups = new InMemoryStorage<>();
    private final InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>();
    private final InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
    private final InMemoryStorage<String, SubscriptionFilter> subscriptions = new InMemoryStorage<>();
    private final InMemoryStorage<String, MetricFilter> metrics = new InMemoryStorage<>();
    private CloudWatchLogsService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchLogsService(groups, streams, events, subscriptions, metrics,
                10_000, new RegionResolver("us-east-1", "000000000000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DeleteLogGroup", "PutRetentionPolicy", "DeleteRetentionPolicy"})
    void collidingRegionAndNameCannotMutateAnotherStoredGroup(String action) {
        LogGroup protectedGroup = new LogGroup();
        protectedGroup.setLogGroupName("x::victim");
        protectedGroup.setRetentionInDays(7);
        groups.put("r::x::victim", protectedGroup);
        assertNotNull(groups.get("r::x::victim").orElse(null));
        assertAll("reject mismatched stored identity and preserve group",
                () -> assertThrows(AwsException.class, () -> {
                    switch (action) {
                        case "DeleteLogGroup" -> service.deleteLogGroup("victim", "r::x");
                        case "PutRetentionPolicy" -> service.putRetentionPolicy("victim", 14, "r::x");
                        case "DeleteRetentionPolicy" -> service.deleteRetentionPolicy("victim", "r::x");
                        default -> throw new AssertionError(action);
                    }
                }),
                () -> assertEquals(protectedGroup, groups.get("r::x::victim").orElse(null)),
                () -> assertEquals(7, protectedGroup.getRetentionInDays()));
    }

    @Test
    void deletingGroupRetainsHistoricalPrefixChildResources() {
        service.createLogGroup("g", null, null, "us-east-1");
        LogGroup historical = new LogGroup();
        historical.setLogGroupName("g::child");
        groups.put("us-east-1::g::child", historical);
        for (String group : new String[]{"g", "g::child"}) {
            LogStream stream = new LogStream();
            stream.setLogGroupName(group);
            stream.setLogStreamName("s");
            streams.put("us-east-1::" + group + "::s", stream);
            MetricFilter metric = new MetricFilter();
            metric.setLogGroupName(group);
            metric.setFilterName("m");
            metrics.put("us-east-1::" + group + "::m", metric);
            SubscriptionFilter subscription = new SubscriptionFilter();
            subscription.setLogGroupName(group);
            subscription.setFilterName("f");
            subscriptions.put("us-east-1::" + group + "::filter::f", subscription);
        }
        service.deleteLogGroup("g", "us-east-1");
        assertAll("remove owned resources but retain historical child",
                () -> assertNull(groups.get("us-east-1::g").orElse(null)),
                () -> assertNull(streams.get("us-east-1::g::s").orElse(null)),
                () -> assertNull(metrics.get("us-east-1::g::m").orElse(null)),
                () -> assertNull(subscriptions.get("us-east-1::g::filter::f").orElse(null)),
                () -> assertEquals(historical, groups.get("us-east-1::g::child").orElse(null)),
                () -> assertNotNull(streams.get("us-east-1::g::child::s").orElse(null)),
                () -> assertNotNull(metrics.get("us-east-1::g::child::m").orElse(null)),
                () -> assertNotNull(subscriptions.get("us-east-1::g::child::filter::f").orElse(null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"::g::x", "::g::filter::x"})
    void deletingGroupRetainsSameNamedGroupInAliasingRegion(String regionSuffix) {
        String region = "us-east-1";
        String otherRegion = region + regionSuffix;
        for (String ownerRegion : new String[]{region, otherRegion}) {
            service.createLogGroup("g", null, null, ownerRegion);
            LogStream stream = new LogStream();
            stream.setLogGroupName("g");
            stream.setLogStreamName("s");
            streams.put(ownerRegion + "::g::s", stream);
            MetricFilter metric = new MetricFilter();
            metric.setLogGroupName("g");
            metric.setFilterName("m");
            metrics.put(ownerRegion + "::g::m", metric);
            SubscriptionFilter subscription = new SubscriptionFilter();
            subscription.setLogGroupName("g");
            subscription.setFilterName("f");
            subscriptions.put(ownerRegion + "::g::filter::f", subscription);
        }
        service.deleteLogGroup("g", region);
        assertAll("cleanup keeps the same named group's other region",
                () -> assertNull(groups.get(region + "::g").orElse(null)),
                () -> assertNull(streams.get(region + "::g::s").orElse(null)),
                () -> assertNull(metrics.get(region + "::g::m").orElse(null)),
                () -> assertNull(subscriptions.get(region + "::g::filter::f").orElse(null)),
                () -> assertNotNull(groups.get(otherRegion + "::g").orElse(null)),
                () -> assertNotNull(streams.get(otherRegion + "::g::s").orElse(null)),
                () -> assertNotNull(metrics.get(otherRegion + "::g::m").orElse(null)),
                () -> assertNotNull(subscriptions.get(otherRegion + "::g::filter::f").orElse(null)));
    }

}
