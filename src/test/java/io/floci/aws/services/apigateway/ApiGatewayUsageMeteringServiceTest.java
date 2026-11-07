package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.GatewayResponseType;
import io.floci.aws.services.apigateway.model.UsagePlan;
import io.floci.aws.services.apigatewayv2.ApiGatewayV2Service;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ManualClock;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class ApiGatewayUsageMeteringServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    // A Friday.
    private final ManualClock clock = new ManualClock(Instant.parse("2026-11-06T10:00:00Z"));
    private final RegionResolver regionResolver = spy(new RegionResolver(REGION, ACCOUNT));
    private ApiGatewayService service = service(new InMemoryStorageFactory(ACCOUNT));

    private ApiGatewayService service(StorageFactory storageFactory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        return new ApiGatewayService(storageFactory, config, mock(TlsCertificateManager.class), regionResolver,
                new ApiGatewayV2Service(storageFactory, config, regionResolver), clock);
    }

    private String plan(Map<String, Object> limits) {
        Map<String, Object> request = new HashMap<>(limits);
        request.put("name", "metered");
        return service.createUsagePlan(REGION, request).getId();
    }

    private String keyOn(String planId) {
        String keyId = service.createApiKey(REGION, Map.of("name", "metered-key", "enabled", true)).getId();
        service.createUsagePlanKey(REGION, planId, Map.of("keyId", keyId, "keyType", "API_KEY"));
        return keyId;
    }

    private List<List<Long>> usage(String planId, String keyId, String from, String to) {
        return service.getUsage(REGION, planId, from, to, keyId, null, null).items().get(keyId).stream()
                .map(pair -> List.of(pair[0], pair[1]))
                .toList();
    }

    @Test
    void dailyQuotaAdmitsTheLimitRefusesTheRestAndResetsAtUtcMidnight() {
        String planId = plan(Map.of("quota", Map.of("limit", 2, "period", "DAY")));
        String keyId = keyOn(planId);

        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        assertEquals(List.of(List.of(1L, 1L)), usage(planId, keyId, "2026-11-06", "2026-11-06"));
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        assertEquals(GatewayResponseType.QUOTA_EXCEEDED, service.meterApiKeyCall(REGION, planId, keyId));
        // Measured: a refused call is not counted.
        assertEquals(List.of(List.of(2L, 0L)), usage(planId, keyId, "2026-11-06", "2026-11-06"));

        clock.advance(Duration.ofHours(14));
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        assertEquals(List.of(List.of(2L, 0L), List.of(1L, 1L)), usage(planId, keyId, "2026-11-06", "2026-11-07"));
    }

    // Measured: remaining is the limit minus the use from the start of the period through that day,
    // and a day of the previous period reports the full limit.
    @Test
    void weeklyRemainingIsCumulativeOverThePeriod() {
        String planId = plan(Map.of("quota", Map.of("limit", 5, "period", "WEEK")));
        String keyId = keyOn(planId);

        clock.advance(Duration.ofDays(-1));
        service.meterApiKeyCall(REGION, planId, keyId);
        service.meterApiKeyCall(REGION, planId, keyId);
        clock.advance(Duration.ofDays(1));
        service.meterApiKeyCall(REGION, planId, keyId);

        assertEquals(List.of(List.of(0L, 5L), List.of(0L, 5L), List.of(0L, 5L), List.of(0L, 5L),
                        List.of(0L, 5L), List.of(2L, 3L), List.of(1L, 2L)),
                usage(planId, keyId, "2026-10-31", "2026-11-06"));
        assertEquals(List.of(List.of(1L, 2L)), usage(planId, keyId, "2026-11-06", "2026-11-06"));
    }

    // Measured: a plan without a quota reports [0, 0] for a key it admitted.
    @Test
    void planWithoutQuotaNeverRefusesAndReportsZero() {
        String planId = plan(Map.of());
        String keyId = keyOn(planId);

        for (int call = 0; call < 3; call++) {
            assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        }
        assertEquals(List.of(List.of(0L, 0L)), usage(planId, keyId, "2026-11-06", "2026-11-06"));
    }

    @Test
    void throttleIsATokenBucketOfBurstSizeRefilledAtTheRate() {
        String planId = plan(Map.of("throttle", Map.of("burstLimit", 2, "rateLimit", 1.0)));
        String keyId = keyOn(planId);

        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        assertEquals(GatewayResponseType.THROTTLED, service.meterApiKeyCall(REGION, planId, keyId));

        clock.advance(Duration.ofSeconds(1));
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        assertEquals(GatewayResponseType.THROTTLED, service.meterApiKeyCall(REGION, planId, keyId));
    }

    @Test
    void throttledCallIsNotCountedAgainstTheQuota() {
        String planId = plan(Map.of("throttle", Map.of("burstLimit", 1, "rateLimit", 0.0),
                "quota", Map.of("limit", 5, "period", "DAY")));
        String keyId = keyOn(planId);

        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        assertEquals(GatewayResponseType.THROTTLED, service.meterApiKeyCall(REGION, planId, keyId));
        assertEquals(List.of(List.of(1L, 4L)), usage(planId, keyId, "2026-11-06", "2026-11-06"));
    }

    @Test
    void throttleBucketsAreSeparatePerAccount() {
        String planId = plan(Map.of("throttle", Map.of("burstLimit", 1, "rateLimit", 0.0)));
        String keyId = keyOn(planId);

        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        doReturn("111111111111").when(regionResolver).getAccountId();
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
    }

    // An emulator reset wipes the stores and then calls clear(), which takes the in-memory buckets with them.
    @Test
    void resetRefillsTheThrottleBuckets() {
        String planId = plan(Map.of("throttle", Map.of("burstLimit", 1, "rateLimit", 0.0)));
        String keyId = keyOn(planId);
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        assertEquals(GatewayResponseType.THROTTLED, service.meterApiKeyCall(REGION, planId, keyId));

        service.clear();

        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void quotaUseSurvivesARestart(String mode, @TempDir Path directory) {
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        String planId;
        String keyId;
        try {
            service = service(first);
            planId = plan(Map.of("quota", Map.of("limit", 1, "period", "DAY")));
            keyId = keyOn(planId);
            assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            service = service(second);
            assertEquals(List.of(List.of(1L, 0L)), usage(planId, keyId, "2026-11-06", "2026-11-06"));
            assertEquals(GatewayResponseType.QUOTA_EXCEEDED, service.meterApiKeyCall(REGION, planId, keyId));
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void throttleRefillKeepsTimeBelowAMillisecond() {
        String planId = plan(Map.of("throttle", Map.of("burstLimit", 1, "rateLimit", 1.0)));
        String keyId = keyOn(planId);

        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
        int admitted = 0;
        for (int attempt = 0; attempt < 2_100; attempt++) {
            clock.advance(Duration.ofNanos(500_000));
            if (service.meterApiKeyCall(REGION, planId, keyId) == null) {
                admitted++;
            }
        }
        assertEquals(1, admitted);
    }

    @Test
    void planRecreatedWithTheIdOfADeletedOneStartsWithNoUse() {
        Map<String, Object> request = Map.of("name", "pinned", "quota", Map.of("limit", 1, "period", "DAY"),
                "tags", Map.of("floci:override-id", "pinned-plan"));
        String planId = service.createUsagePlan(REGION, request).getId();
        String keyId = keyOn(planId);
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));

        service.deleteUsagePlan(REGION, planId);
        service.createUsagePlan(REGION, request);

        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
    }

    @Test
    void callResolvedBeforeItsKeyWasDeletedLeavesNoUseBehind() {
        String planId = plan(Map.of("quota", Map.of("limit", 1, "period", "DAY")));
        Map<String, Object> keyRequest = Map.of("name", "fixed", "enabled", true,
                "value", "fixed-value-0123456789", "generateDistinctId", false);
        String keyId = service.createApiKey(REGION, keyRequest).getId();
        service.createUsagePlanKey(REGION, planId, Map.of("keyId", keyId, "keyType", "API_KEY"));

        service.deleteApiKey(REGION, keyId);
        service.meterApiKeyCall(REGION, planId, keyId);
        service.createApiKey(REGION, keyRequest);
        service.createUsagePlanKey(REGION, planId, Map.of("keyId", keyId, "keyType", "API_KEY"));

        assertEquals(List.of(List.of(0L, 1L)), usage(planId, keyId, "2026-11-06", "2026-11-06"));
    }

    @Test
    void keyRecreatedWithTheIdOfADeletedOneStartsWithNoUse() {
        String planId = plan(Map.of("quota", Map.of("limit", 1, "period", "DAY")));
        Map<String, Object> keyRequest = Map.of("name", "fixed", "enabled", true,
                "value", "fixed-value-0123456789", "generateDistinctId", false);
        String keyId = service.createApiKey(REGION, keyRequest).getId();
        service.createUsagePlanKey(REGION, planId, Map.of("keyId", keyId, "keyType", "API_KEY"));
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));

        service.deleteApiKey(REGION, keyId);
        service.createApiKey(REGION, keyRequest);
        service.createUsagePlanKey(REGION, planId, Map.of("keyId", keyId, "keyType", "API_KEY"));

        assertEquals(List.of(List.of(0L, 1L)), usage(planId, keyId, "2026-11-06", "2026-11-06"));
        assertNull(service.meterApiKeyCall(REGION, planId, keyId));
    }

    @Test
    void quotaPeriodStartsOnTheDaySundayOrFirstShiftedByTheOffset() {
        LocalDate friday = LocalDate.parse("2026-11-06");
        assertEquals(friday, ApiGatewayService.quotaPeriodStart(friday, new UsagePlan.Quota(1, 0, "DAY")));
        assertEquals(LocalDate.parse("2026-11-01"),
                ApiGatewayService.quotaPeriodStart(friday, new UsagePlan.Quota(1, 0, "WEEK")));
        assertEquals(friday, ApiGatewayService.quotaPeriodStart(friday, new UsagePlan.Quota(1, 5, "WEEK")));
        assertEquals(LocalDate.parse("2026-10-31"),
                ApiGatewayService.quotaPeriodStart(friday, new UsagePlan.Quota(1, 6, "WEEK")));
        assertEquals(LocalDate.parse("2026-11-01"),
                ApiGatewayService.quotaPeriodStart(friday, new UsagePlan.Quota(1, 0, "MONTH")));
        assertEquals(LocalDate.parse("2026-10-11"),
                ApiGatewayService.quotaPeriodStart(friday, new UsagePlan.Quota(1, 10, "MONTH")));
        assertEquals(LocalDate.parse("2026-11-11"), ApiGatewayService.quotaPeriodStart(
                LocalDate.parse("2026-11-11"), new UsagePlan.Quota(1, 10, "MONTH")));
    }
}
