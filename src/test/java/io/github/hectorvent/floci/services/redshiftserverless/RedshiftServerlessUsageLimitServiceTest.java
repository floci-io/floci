package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.UsageLimit;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftServerlessUsageLimitServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String WORKGROUP_ARN =
            "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/wg-1";

    private RedshiftServerlessService serverless;
    private RedshiftServerlessUsageLimitService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-usagelimits.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend)
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.buildArn(eq("redshift-serverless"), any(String.class), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:"
                        + invocation.getArgument(1, String.class) + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        serverless = mock(RedshiftServerlessService.class);
        liveWorkgroup(WORKGROUP_ARN);
        service = new RedshiftServerlessUsageLimitService(storageFactory, regionResolver, serverless);
    }

    private void liveWorkgroup(String arn) {
        Workgroup workgroup = new Workgroup();
        workgroup.setWorkgroupArn(arn);
        when(serverless.findWorkgroupByArn(arn, REGION)).thenReturn(Optional.of(workgroup));
    }

    private UsageLimit create(long amount) {
        return service.createUsageLimit(WORKGROUP_ARN, "serverless-compute", amount, null, null, REGION);
    }

    private static String errorCode(Runnable call) {
        return assertThrows(AwsException.class, call::run).getErrorCode();
    }

    @Test
    void createAppliesAwsDefaultsAndBuildsTheArn() {
        UsageLimit limit = create(100);

        assertEquals("monthly", limit.getPeriod());
        assertEquals("log", limit.getBreachAction());
        assertEquals(100, limit.getAmount());
        assertEquals(WORKGROUP_ARN, limit.getResourceArn());
        assertEquals(limit.getUsageLimitId(), UUID.fromString(limit.getUsageLimitId()).toString());
        assertEquals("arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":usagelimit/" + limit.getUsageLimitId(),
                limit.getUsageLimitArn());
    }

    @Test
    void createKeepsAnExplicitPeriodAndBreachAction() {
        UsageLimit limit = service.createUsageLimit(WORKGROUP_ARN, "cross-region-datasharing", 5L,
                "weekly", "deactivate", REGION);

        assertEquals("weekly", limit.getPeriod());
        assertEquals("deactivate", limit.getBreachAction());
        assertEquals("cross-region-datasharing", limit.getUsageType());
    }

    @Test
    void createRejectsInvalidInput() {
        assertEquals("ValidationException", errorCode(() -> create(0)));
        assertEquals("ValidationException", errorCode(() -> create(-1)));
        assertEquals("ValidationException", errorCode(() ->
                service.createUsageLimit(WORKGROUP_ARN, "serverless-compute", null, null, null, REGION)));
        assertEquals("ValidationException", errorCode(() ->
                service.createUsageLimit(WORKGROUP_ARN, "other", 1L, null, null, REGION)));
        assertEquals("ValidationException", errorCode(() ->
                service.createUsageLimit(WORKGROUP_ARN, null, 1L, null, null, REGION)));
        assertEquals("ValidationException", errorCode(() ->
                service.createUsageLimit(WORKGROUP_ARN, "serverless-compute", 1L, "hourly", null, REGION)));
        assertEquals("ValidationException", errorCode(() ->
                service.createUsageLimit(WORKGROUP_ARN, "serverless-compute", 1L, null, "block", REGION)));
        assertEquals("ValidationException", errorCode(() ->
                service.createUsageLimit(" ", "serverless-compute", 1L, null, null, REGION)));
    }

    @Test
    void createRejectsAnUnknownWorkgroup() {
        String missing = "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/absent";
        when(serverless.findWorkgroupByArn(missing, REGION)).thenReturn(Optional.empty());

        assertEquals("ResourceNotFoundException", errorCode(() ->
                service.createUsageLimit(missing, "serverless-compute", 1L, null, null, REGION)));
    }

    @Test
    void createRejectsASecondLimitOfTheSameTypeOnTheSameWorkgroup() {
        create(1);

        assertEquals("ConflictException", errorCode(() -> create(2)));
        // A different usage type on the same workgroup is a separate limit.
        service.createUsageLimit(WORKGROUP_ARN, "cross-region-datasharing", 2L, null, null, REGION);
    }

    @Test
    void getAndDeleteRejectAnUnknownId() {
        assertEquals("ResourceNotFoundException", errorCode(() -> service.getUsageLimit("absent", REGION)));
        assertEquals("ResourceNotFoundException", errorCode(() -> service.deleteUsageLimit("absent", REGION)));
        assertEquals("ValidationException", errorCode(() -> service.getUsageLimit(null, REGION)));
    }

    @Test
    void deleteRemovesTheLimitAndReturnsIt() {
        UsageLimit limit = create(1);

        UsageLimit deleted = service.deleteUsageLimit(limit.getUsageLimitId(), REGION);

        assertEquals(limit.getUsageLimitId(), deleted.getUsageLimitId());
        assertEquals("ResourceNotFoundException",
                errorCode(() -> service.getUsageLimit(limit.getUsageLimitId(), REGION)));
        // Deleting frees the (workgroup, usage type) slot.
        create(3);
    }

    @Test
    void updateChangesOnlyTheSuppliedFields() {
        UsageLimit limit = service.createUsageLimit(WORKGROUP_ARN, "serverless-compute", 10L,
                "daily", "emit-metric", REGION);

        UsageLimit amountOnly = service.updateUsageLimit(limit.getUsageLimitId(), 20L, null, REGION);
        assertEquals(20, amountOnly.getAmount());
        assertEquals("emit-metric", amountOnly.getBreachAction());
        assertEquals("daily", amountOnly.getPeriod());

        UsageLimit actionOnly = service.updateUsageLimit(limit.getUsageLimitId(), null, "deactivate", REGION);
        assertEquals(20, actionOnly.getAmount());
        assertEquals("deactivate", actionOnly.getBreachAction());
        assertEquals("deactivate", service.getUsageLimit(limit.getUsageLimitId(), REGION).getBreachAction());
    }

    @Test
    void updateRejectsInvalidInputAndUnknownId() {
        UsageLimit limit = create(10);

        assertEquals("ValidationException", errorCode(() ->
                service.updateUsageLimit(limit.getUsageLimitId(), 0L, null, REGION)));
        assertEquals("ValidationException", errorCode(() ->
                service.updateUsageLimit(limit.getUsageLimitId(), null, "block", REGION)));
        assertEquals("ResourceNotFoundException", errorCode(() ->
                service.updateUsageLimit("absent", 1L, null, REGION)));
        assertEquals(10, service.getUsageLimit(limit.getUsageLimitId(), REGION).getAmount());
    }

    @Test
    void listFiltersByResourceAndUsageType() {
        String otherArn = "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/wg-2";
        liveWorkgroup(otherArn);
        create(1);
        service.createUsageLimit(WORKGROUP_ARN, "cross-region-datasharing", 2L, null, null, REGION);
        service.createUsageLimit(otherArn, "serverless-compute", 3L, null, null, REGION);

        assertEquals(3, service.listUsageLimits(null, null, REGION, null, null).items().size());
        assertEquals(2, service.listUsageLimits(WORKGROUP_ARN, null, REGION, null, null).items().size());
        assertEquals(2, service.listUsageLimits(null, "serverless-compute", REGION, null, null).items().size());
        assertEquals(1, service.listUsageLimits(otherArn, "serverless-compute", REGION, null, null).items().size());
        assertEquals("ValidationException", errorCode(() ->
                service.listUsageLimits(null, "other", REGION, null, null)));
    }

    @Test
    void listPaginates() {
        String second = "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/wg-2";
        liveWorkgroup(second);
        create(1);
        service.createUsageLimit(second, "serverless-compute", 2L, null, null, REGION);

        PaginatedResult<UsageLimit> first = service.listUsageLimits(null, null, REGION, 1, null);
        assertEquals(1, first.items().size());
        assertTrue(first.nextToken() != null);

        PaginatedResult<UsageLimit> rest = service.listUsageLimits(null, null, REGION, 1, first.nextToken());
        assertEquals(1, rest.items().size());
        assertTrue(rest.nextToken() == null);
        assertEquals("InvalidPaginationException", errorCode(() ->
                service.listUsageLimits(null, null, REGION, 101, null)));
    }

    @Test
    void aLimitDisappearsWithItsWorkgroup() {
        UsageLimit limit = create(1);
        when(serverless.findWorkgroupByArn(WORKGROUP_ARN, REGION)).thenReturn(Optional.empty());

        assertEquals("ResourceNotFoundException",
                errorCode(() -> service.getUsageLimit(limit.getUsageLimitId(), REGION)));
        assertTrue(service.listUsageLimits(null, null, REGION, null, null).items().isEmpty());
    }

    @Test
    void clearRemovesEveryLimit() {
        create(1);

        service.clear();

        assertTrue(service.listUsageLimits(null, null, REGION, null, null).items().isEmpty());
    }
}
