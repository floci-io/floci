package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted suppression domain. New facet: this closes the
 * shared-helper deferral from the account step — account suppression and the suppression list both
 * live here and share the reason validation, exercised by both paths below. Phase 3 adds the
 * tenant-scoped suppression list as a third store: fully separate from the account list, keyed by
 * TenantId, cascaded by DeleteTenant through {@code deleteAllForTenant}.
 */
class SesSuppressionServiceTest {

    private static final String REGION = "us-east-1";
    private SesSuppressionService service;

    @BeforeEach
    void setUp() {
        service = new SesSuppressionService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    @Test
    void accountSuppression_defaultsToBounceAndComplaint() {
        assertEquals(List.of("BOUNCE", "COMPLAINT"),
                service.getAccountSuppressionAttributes(REGION).getSuppressedReasons());
    }

    @Test
    void putAccountSuppression_rejectsInvalidReason() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.putAccountSuppressionAttributes(REGION, List.of("NONSENSE")));
        assertEquals("BadRequestException", e.getErrorCode());
    }

    @Test
    void suppressedDestination_putGetDelete_roundTrips() {
        service.putSuppressedDestination(REGION, "a@example.com", "BOUNCE");
        SuppressedDestination got = service.getSuppressedDestination(REGION, "a@example.com");
        assertEquals("BOUNCE", got.getReason());

        service.deleteSuppressedDestination(REGION, "a@example.com");
        assertTrue(service.findSuppressedDestination(REGION, "a@example.com").isEmpty());
    }

    @Test
    void putSuppressedDestination_rejectsInvalidReason() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.putSuppressedDestination(REGION, "a@example.com", "NONSENSE"));
        assertEquals("BadRequestException", e.getErrorCode());
    }

    @Test
    void findSuppressedDestination_normalizesDomainCase() {
        service.putSuppressedDestination(REGION, "User@Example.COM", "COMPLAINT");
        // Domain is canonicalized to lower case, so a differently-cased domain resolves the entry.
        assertTrue(service.findSuppressedDestination(REGION, "User@example.com").isPresent());
    }

    // ──────────────────── Tenant-scoped suppression list (Phase 3) ────────────────────

    private static final String TENANT_ID = "tn-000000000000000000000000000001";

    @Test
    void tenantList_isSeparateFromAccountList_bothWays() {
        service.putTenantSuppressedDestination(REGION, TENANT_ID, "acme", "t@example.com", "BOUNCE");
        service.putSuppressedDestination(REGION, "a@example.com", "BOUNCE");

        SuppressedDestination tenantEntry =
                service.getTenantSuppressedDestination(REGION, TENANT_ID, "t@example.com");
        assertEquals("acme", tenantEntry.getTenantName());

        AwsException accountMiss = assertThrows(AwsException.class,
                () -> service.getSuppressedDestination(REGION, "t@example.com"));
        assertEquals("Email address t@example.com does not exist on your suppression list.",
                accountMiss.getMessage());
        AwsException tenantMiss = assertThrows(AwsException.class,
                () -> service.getTenantSuppressedDestination(REGION, TENANT_ID, "a@example.com"));
        assertEquals("Email address a@example.com does not exist on your tenant suppression list.",
                tenantMiss.getMessage());
    }

    @Test
    void tenantDelete_isNotIdempotent() {
        service.putTenantSuppressedDestination(REGION, TENANT_ID, "acme", "t@example.com", "BOUNCE");
        service.deleteTenantSuppressedDestination(REGION, TENANT_ID, "t@example.com");
        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteTenantSuppressedDestination(REGION, TENANT_ID, "t@example.com"));
        assertEquals("NotFoundException", e.getErrorCode());
    }

    @Test
    void tenantList_filtersByReason_andIsPerTenant() {
        service.putTenantSuppressedDestination(REGION, TENANT_ID, "acme", "a@example.com", "BOUNCE");
        service.putTenantSuppressedDestination(REGION, TENANT_ID, "acme", "b@example.com", "COMPLAINT");
        service.putTenantSuppressedDestination(REGION, "tn-other", "beta", "c@example.com", "BOUNCE");

        assertEquals(2, service.listTenantSuppressedDestinations(REGION, TENANT_ID, null).size());
        List<SuppressedDestination> bounces =
                service.listTenantSuppressedDestinations(REGION, TENANT_ID, List.of("BOUNCE"));
        assertEquals(1, bounces.size());
        assertEquals("a@example.com", bounces.get(0).getEmailAddress());
    }

    @Test
    void deleteAllForTenant_removesOnlyThatTenant() {
        service.putTenantSuppressedDestination(REGION, TENANT_ID, "acme", "a@example.com", "BOUNCE");
        service.putTenantSuppressedDestination(REGION, "tn-other", "beta", "b@example.com", "BOUNCE");
        service.deleteAllForTenant(REGION, TENANT_ID);
        assertEquals(0, service.listTenantSuppressedDestinations(REGION, TENANT_ID, null).size());
        assertEquals(1, service.listTenantSuppressedDestinations(REGION, "tn-other", null).size());
    }

    @Test
    void tenantPut_sharesNormalizationAndReasonValidation() {
        service.putTenantSuppressedDestination(REGION, TENANT_ID, "acme", "User@Example.COM", "BOUNCE");
        assertEquals("User@example.com",
                service.getTenantSuppressedDestination(REGION, TENANT_ID, "User@example.com")
                        .getEmailAddress());
        assertThrows(AwsException.class, () -> service.putTenantSuppressedDestination(
                REGION, TENANT_ID, "acme", "x@example.com", "NONSENSE"));
    }
}
