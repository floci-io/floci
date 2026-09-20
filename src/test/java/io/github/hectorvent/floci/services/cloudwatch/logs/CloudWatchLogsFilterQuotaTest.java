package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class CloudWatchLogsFilterQuotaTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String GROUP = "/quota";
    private static final String TWO_REGEXES = "{ $.a = %ERROR% || $.a = %WARN% }";

    @ParameterizedTest
    @CsvSource({"false,true", "false,false", "true,true", "true,false"})
    void fiveRegexBearingFiltersCanEachHoldTwoExpressions(boolean factoryConstructor, boolean metric) {
        Fixture fixture = new Fixture(factoryConstructor);
        for (int i = 0; i < 5; i++) {
            fixture.put(metric, "f" + i, TWO_REGEXES);
        }
        rejected(() -> fixture.put(metric, "sixth", "%INFO%"), "LimitExceededException");
        fixture.put(metric, "f0", TWO_REGEXES);
        fixture.put(metric, "plain", "INFO");
        assertEquals(6, metric ? fixture.metricStore.keys().size() : fixture.subscriptions.keys().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothConstructorPathsShareThePoolAcrossFilterFamilies(boolean factoryConstructor) {
        Fixture fixture = new Fixture(factoryConstructor);
        for (int i = 0; i < 4; i++) {
            fixture.put(true, "m" + i, "%ERROR%");
        }
        fixture.put(false, "s0", "%ERROR%");
        rejected(() -> fixture.put(false, "s1", "%WARN%"), "LimitExceededException");
        rejected(() -> fixture.put(true, "m4", "%WARN%"), "LimitExceededException");
    }

    @Test
    void replacementsAreFamilySpecificAndRejectedUpdatesLeaveTheStoredDefinitionUnchanged() {
        Fixture fixture = new Fixture(false);
        for (int i = 0; i < 3; i++) {
            fixture.put(true, "m" + i, "%ERROR%");
        }
        fixture.put(true, "shared", "%ERROR%");
        fixture.put(false, "shared", "%ERROR%");
        fixture.put(true, "shared", TWO_REGEXES);
        fixture.put(false, "shared", TWO_REGEXES);
        fixture.put(true, "plain", "ORIGINAL");
        fixture.put(false, "plain", "ORIGINAL");
        MetricFilter metric = fixture.metricStore.get(metricKey("plain")).orElseThrow();
        SubscriptionFilter subscription = fixture.subscriptions.get(subscriptionKey("plain")).orElseThrow();

        rejected(() -> fixture.put(true, "plain", "%ERROR%"), "LimitExceededException");
        rejected(() -> fixture.put(false, "plain", "%ERROR%"), "LimitExceededException");
        assertSame(metric, fixture.metricStore.get(metricKey("plain")).orElseThrow());
        assertSame(subscription, fixture.subscriptions.get(subscriptionKey("plain")).orElseThrow());
        assertEquals("ORIGINAL", metric.getFilterPattern());
        assertEquals("ORIGINAL", subscription.getFilterPattern());

        fixture.put(true, "shared", "PLAIN");
        fixture.put(false, "plain", "%ERROR%");
        rejected(() -> fixture.put(true, "plain", "%ERROR%"), "LimitExceededException");
        fixture.put(false, "shared", "PLAIN");
        fixture.put(true, "plain", "%ERROR%");
        fixture.metrics.deleteMetricFilter(GROUP, "plain", REGION);
        fixture.put(false, "new-subscription", "%ERROR%");
        fixture.logs.deleteSubscriptionFilter(GROUP, "plain", REGION);
        fixture.put(true, "new-metric", "%ERROR%");
        rejected(() -> fixture.put(true, "sixth", "%ERROR%"), "LimitExceededException");
    }

    @ParameterizedTest
    @ValueSource(strings = {"%ERROR% %WARN%", "{ $.a = }",
            "{ $.a = %ERROR% || $.a = %WARN% || $.a = %INFO% }"})
    void subscriptionPatternsUseTheSameGrammarAndPerPatternQuota(String pattern) {
        Fixture fixture = new Fixture(false);
        fixture.put(false, "original", "ORIGINAL");
        rejected(() -> fixture.put(false, "original", pattern), "InvalidParameterException");
        assertEquals("ORIGINAL", fixture.subscriptions.get(subscriptionKey("original")).orElseThrow().getFilterPattern());
    }

    @Test
    void regexFiltersBeyondTheFirstSubscriptionPageStillCount() {
        Fixture fixture = new Fixture(false);
        for (int i = 0; i < 51; i++) {
            fixture.put(false, "a" + i, "PLAIN");
        }
        for (int i = 0; i < 5; i++) {
            fixture.put(false, "z" + i, "%ERROR%");
        }
        rejected(() -> fixture.put(true, "sixth", "%ERROR%"), "LimitExceededException");
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void storageFailuresCannotSilentlyFreeQuota(boolean metricStorageFails, boolean metricWriter) {
        Fixture fixture = new Fixture(false);
        IllegalStateException failure = new IllegalStateException("quota storage unavailable");
        if (metricStorageFails) {
            doThrow(failure).when(fixture.metricStore).scan(any());
        } else {
            doThrow(failure).when(fixture.subscriptions).scan(any());
        }
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.put(metricWriter, "candidate", "%ERROR%")));
        assertTrue(fixture.metricStore.keys().isEmpty());
        assertTrue(fixture.subscriptions.keys().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void invalidStoredPatternsCannotSilentlyFreeQuota(boolean invalidMetric, boolean metricWriter) {
        Fixture fixture = new Fixture(false);
        if (invalidMetric) {
            fixture.metricStore.put(metricKey("corrupt"), definition("corrupt", "{ $.a = }"));
        } else {
            SubscriptionFilter corrupt = new SubscriptionFilter();
            corrupt.setLogGroupName(GROUP);
            corrupt.setFilterName("corrupt");
            corrupt.setFilterPattern("{ $.a = }");
            fixture.subscriptions.put(subscriptionKey("corrupt"), corrupt);
        }
        rejected(() -> fixture.put(metricWriter, "candidate", "%ERROR%"), "InvalidParameterException");
        assertTrue(fixture.metricStore.get(metricKey("candidate")).isEmpty());
        assertTrue(fixture.subscriptions.get(subscriptionKey("candidate")).isEmpty());
    }

    @Test
    void quotaAndGroupDeletionAreIsolatedByAccountRegionAndGroup() {
        RequestContext context = new RequestContext();
        context.setAccountId(ACCOUNT);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        Fixture fixture = new Fixture(contexts);
        for (int i = 0; i < 5; i++) {
            fixture.put(i % 2 == 0, "f" + i, "%ERROR%");
        }
        fixture.logs.createLogGroup(GROUP, null, null, "eu-west-1");
        fixture.logs.putSubscriptionFilter(GROUP, "other-region", TWO_REGEXES, "destination", null, "eu-west-1");
        fixture.logs.createLogGroup("/other-group", null, null, REGION);
        fixture.logs.putSubscriptionFilter("/other-group", "other-group", TWO_REGEXES, "destination", null, REGION);

        context.setAccountId("111111111111");
        fixture.logs.createLogGroup(GROUP, null, null, REGION);
        for (int i = 0; i < 5; i++) {
            fixture.put(i % 2 == 0, "f" + i, "%ERROR%");
        }
        rejected(() -> fixture.put(false, "sixth", "%ERROR%"), "LimitExceededException");
        fixture.logs.deleteLogGroup(GROUP, REGION);

        context.setAccountId(ACCOUNT);
        rejected(() -> fixture.put(true, "sixth", "%ERROR%"), "LimitExceededException");
        assertEquals(3, fixture.metrics.countMetricFilters(GROUP, REGION));
        assertEquals(2, fixture.logs.describeSubscriptionFilters(GROUP, null, null, 50, REGION)
                .subscriptionFilters().size());
        assertEquals(1, fixture.logs.describeSubscriptionFilters(GROUP, null, null, 50, "eu-west-1")
                .subscriptionFilters().size());
        assertEquals(1, fixture.logs.describeSubscriptionFilters("/other-group", null, null, 50, REGION)
                .subscriptionFilters().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingAGroupCascadesBothFamiliesWithoutRequiringAnEventObserver(boolean factoryConstructor) {
        Fixture fixture = new Fixture(factoryConstructor);
        fixture.put(true, "metric", "%ERROR%");
        fixture.put(false, "subscription", "%ERROR%");
        fixture.logs.deleteLogGroup(GROUP, REGION);
        assertTrue(fixture.metricStore.keys().isEmpty());
        assertTrue(fixture.subscriptions.keys().isEmpty());
        rejected(() -> fixture.put(true, "orphan", "%ERROR%"), "ResourceNotFoundException");
        rejected(() -> fixture.put(false, "orphan", "%ERROR%"), "ResourceNotFoundException");
        fixture.logs.createLogGroup(GROUP, null, null, REGION);
        for (int i = 0; i < 5; i++) {
            fixture.put(i % 2 == 0, "new" + i, "%ERROR%");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void crossFamilyWritersCannotBothClaimTheLastSlot(boolean metricFirst) throws Exception {
        Fixture fixture = new Fixture(false);
        for (int i = 0; i < 4; i++) {
            fixture.put(true, "existing" + i, "%ERROR%");
        }
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch finishWrite = new CountDownLatch(1);
        if (metricFirst) {
            gateWrite(fixture.metricStore, inWrite, finishWrite);
        } else {
            gateWrite(fixture.subscriptions, inWrite, finishWrite);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> fixture.put(metricFirst, "first", "%ERROR%"));
            assertTrue(inWrite.await(5, TimeUnit.SECONDS));
            CyclicBarrier start = new CyclicBarrier(2);
            Future<?> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                rejected(() -> fixture.put(!metricFirst, "second", "%ERROR%"), "LimitExceededException");
                return null;
            });
            start.await(5, TimeUnit.SECONDS);
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS),
                    "the second writer must wait until the first check-and-write completes");
            finishWrite.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(metricFirst ? 5 : 4, fixture.metricStore.keys().size());
            assertEquals(metricFirst ? 0 : 1, fixture.subscriptions.keys().size());
        } finally {
            finishWrite.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupDeletionCannotLeaveAConcurrentFilterWriteOrphaned(boolean metricWriter) throws Exception {
        Fixture fixture = new Fixture(false);
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch finishWrite = new CountDownLatch(1);
        if (metricWriter) {
            gateWrite(fixture.metricStore, inWrite, finishWrite);
        } else {
            gateWrite(fixture.subscriptions, inWrite, finishWrite);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> fixture.put(metricWriter, "racing", "%ERROR%"));
            assertTrue(inWrite.await(5, TimeUnit.SECONDS));
            CyclicBarrier start = new CyclicBarrier(2);
            Future<?> deletion = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                fixture.logs.deleteLogGroup(GROUP, REGION);
                return null;
            });
            start.await(5, TimeUnit.SECONDS);
            assertThrows(TimeoutException.class, () -> deletion.get(200, TimeUnit.MILLISECONDS),
                    "the cascade must wait for an in-flight filter write");
            finishWrite.countDown();
            writer.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertFalse(fixture.logs.logGroupExists(GROUP, REGION));
            assertTrue(fixture.metricStore.keys().isEmpty());
            assertTrue(fixture.subscriptions.keys().isEmpty());
        } finally {
            finishWrite.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static <T> void gateWrite(StorageBackend<String, T> store, CountDownLatch entered,
                                      CountDownLatch release) {
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(store).put(anyString(), any());
    }

    private static void rejected(Runnable operation, String code) {
        assertEquals(code, assertThrows(AwsException.class, operation::run).getErrorCode());
    }

    private static String metricKey(String name) {
        return REGION + "::" + GROUP + "::" + name;
    }

    private static String subscriptionKey(String name) {
        return REGION + "::" + GROUP + "::filter::" + name;
    }

    private static MetricFilter definition(String name, String pattern) {
        MetricTransformation transformation = new MetricTransformation();
        transformation.setMetricName("Errors");
        transformation.setMetricNamespace("Quota");
        transformation.setMetricValue("1");
        MetricFilter filter = new MetricFilter();
        filter.setLogGroupName(GROUP);
        filter.setFilterName(name);
        filter.setFilterPattern(pattern);
        filter.setMetricTransformations(List.of(transformation));
        return filter;
    }

    private static final class Fixture {
        private final StorageBackend<String, MetricFilter> metricStore;
        private final StorageBackend<String, SubscriptionFilter> subscriptions;
        private final CloudWatchLogsService logs;
        private final CloudWatchLogsMetricFilterService metrics;

        private Fixture(boolean factoryConstructor) {
            this(factoryConstructor, null);
        }

        private Fixture(Instance<RequestContext> contexts) {
            this(false, contexts);
        }

        private Fixture(boolean factoryConstructor, Instance<RequestContext> contexts) {
            RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
            CloudWatchMetricsService publisher = mock(CloudWatchMetricsService.class);
            if (factoryConstructor) {
                EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
                when(config.defaultAccountId()).thenReturn(ACCOUNT);
                when(config.storage().persistentPath()).thenReturn("target/quota-test-storage");
                ServiceConfigAccess access = mock(ServiceConfigAccess.class);
                when(access.storageMode("cloudwatchlogs")).thenReturn("memory");
                StorageFactory factory = new StorageFactory(config, access);
                metricStore = factory.create("cloudwatchlogs", "cwlogs-metric-filters.json",
                        new TypeReference<Map<String, MetricFilter>>() {});
                subscriptions = factory.create("cloudwatchlogs", "cwlogs-subscription-filters.json",
                        new TypeReference<Map<String, SubscriptionFilter>>() {});
                logs = new CloudWatchLogsService(factory, config, resolver, null, null);
                metrics = new CloudWatchLogsMetricFilterService(logs, publisher, resolver);
            } else {
                metricStore = spy(new AccountAwareStorageBackend<MetricFilter>(new InMemoryStorage<>(), contexts, ACCOUNT));
                subscriptions = spy(new AccountAwareStorageBackend<SubscriptionFilter>(new InMemoryStorage<>(), contexts, ACCOUNT));
                logs = new CloudWatchLogsService(
                        new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, ACCOUNT),
                        new InMemoryStorage<>(), new InMemoryStorage<>(), subscriptions, metricStore, 10_000, resolver);
                metrics = new CloudWatchLogsMetricFilterService(logs, publisher, resolver);
            }
            logs.createLogGroup(GROUP, null, null, REGION);
        }

        private void put(boolean metric, String name, String pattern) {
            if (metric) {
                metrics.putMetricFilter(definition(name, pattern), REGION);
            } else {
                logs.putSubscriptionFilter(GROUP, name, pattern, "destination", null, REGION);
            }
        }
    }
}
