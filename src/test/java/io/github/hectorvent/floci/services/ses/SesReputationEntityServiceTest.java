package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.SesReputationEntityService.ReputationEntity;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesReputationEntityServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private MutableClock clock;
    private SesTenantService tenants;
    private SesReputationEntityService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        tenants = new SesTenantService(new InMemoryStorage<>(), new InMemoryStorage<>(), clock, new SecureRandom());
        service = new SesReputationEntityService(tenants, clock);
    }

    @Test
    void get_defaultPolicyFollowsTheRegionsPartition() {
        Tenant cn = tenants.createTenant("acme", List.of(), ACCOUNT, "cn-north-1");

        ReputationEntity entity = service.get("RESOURCE", cn.tenantArn(), "cn-north-1");

        assertEquals("arn:aws-cn:ses:cn-north-1:aws:reputation-policy/standard", entity.policy());
        assertNull(entity.customerManagedStatus());
        assertEquals("ENABLED", entity.sendingStatusAggregate());
    }

    @Test
    void get_isRegionScoped() {
        Tenant tenant = tenants.createTenant("acme", List.of(), ACCOUNT, REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> service.get("RESOURCE", tenant.tenantArn(), "us-west-2"));

        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void updatePolicy_checksOnlyTheAccountAndTheResource() {
        Tenant tenant = tenants.createTenant("acme", List.of(), ACCOUNT, REGION);

        for (String policy : List.of("arn:aws-cn:ses:cn-north-1:aws:reputation-policy/none",
                "arn:aws-nope:ses:mars-1:aws:reputation-policy/strict",
                "arn:aws:ses::aws:reputation-policy/standard")) {
            service.updatePolicy("RESOURCE", tenant.tenantArn(), policy, REGION);
            assertEquals(policy, service.get("RESOURCE", tenant.tenantArn(), REGION).policy());
        }
        for (String policy : List.of("arn:aws:sqs:us-east-1:aws:reputation-policy/none",
                "arn:aws:ses:us-east-1:aws:reputation-policy/",
                "arn:aws:ses:us-east-1:aws:reputation-policy/strict/x",
                "arn:aws:ses:us-east-1:aws:policy/none")) {
            AwsException e = assertThrows(AwsException.class,
                    () -> service.updatePolicy("RESOURCE", tenant.tenantArn(), policy, REGION));
            assertEquals("Provided resource identifier is invalid", e.getMessage());
        }
    }

    @Test
    void updateStatus_stampsEveryCallAndKeepsTheOtherTenantState() {
        Tenant tenant = tenants.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.updatePolicy("RESOURCE", tenant.tenantArn(), "arn:aws:ses:us-east-1:aws:reputation-policy/strict",
                REGION);

        service.updateCustomerManagedStatus("RESOURCE", tenant.tenantArn(), "DISABLED", REGION);
        Instant first = service.get("RESOURCE", tenant.tenantArn(), REGION).customerManagedStatus()
                .lastUpdatedTimestamp();
        clock.advance(Duration.ofMinutes(5));
        service.updateCustomerManagedStatus("RESOURCE", tenant.tenantArn(), "DISABLED", REGION);

        ReputationEntity entity = service.get("RESOURCE", tenant.tenantArn(), REGION);
        assertTrue(entity.customerManagedStatus().lastUpdatedTimestamp().isAfter(first.plus(Duration.ofMinutes(5))));
        assertEquals("DISABLED", entity.sendingStatusAggregate());
        assertEquals("arn:aws:ses:us-east-1:aws:reputation-policy/strict", entity.policy());
        assertEquals("DISABLED", tenants.getTenant("acme", REGION).sendingStatus());
    }

    @Test
    void list_byReference_prefixIsCaseSensitive() {
        Tenant b = tenants.createTenant("beta", List.of(), ACCOUNT, REGION);
        Tenant a = tenants.createTenant("alpha", List.of(), ACCOUNT, REGION);

        assertEquals(List.of(a.tenantArn(), b.tenantArn()),
                service.list(Map.of(), null, null, REGION).items().stream().map(ReputationEntity::reference).toList());
        assertTrue(service.list(Map.of("ENTITY_REFERENCE_PREFIX", a.tenantArn().toUpperCase(Locale.ROOT)),
                null, null, REGION).items().isEmpty());
    }

    @Test
    void list_tokenFromAnotherRegion_isUnreadableNotBoundElsewhere() {
        tenants.createTenant("alpha", List.of(), ACCOUNT, "us-west-2");
        tenants.createTenant("beta", List.of(), ACCOUNT, "us-west-2");
        PaginatedResult<ReputationEntity> page = service.list(Map.of(), 1, null, "us-west-2");

        AwsException e = assertThrows(AwsException.class,
                () -> service.list(Map.of(), 1, page.nextToken(), REGION));

        assertEquals("Failed to deserialize token. ", e.getMessage());
    }
}
