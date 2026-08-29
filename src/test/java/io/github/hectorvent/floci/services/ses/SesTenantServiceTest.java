package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the tenant domain (Phase 1 CRUD): create/get/list/delete, id/ARN generation, and the
 * probe-confirmed name validation and duplicate/not-found errors. Constructed with just its own store.
 */
class SesTenantServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private SesTenantService service;

    @BeforeEach
    void setUp() {
        service = new SesTenantService(new InMemoryStorage<>(), Clock.systemUTC(), new SecureRandom());
    }

    @Test
    void create_generatesIdAndArn_defaultsSendingEnabled() {
        Tenant t = service.createTenant("acme", List.of(new Tag("team", "floci")), ACCOUNT, REGION);
        assertEquals("acme", t.tenantName());
        assertTrue(t.tenantId().startsWith("tn-"));
        assertEquals("tn-".length() + 30, t.tenantId().length());
        assertEquals("arn:aws:ses:" + REGION + ":" + ACCOUNT + ":tenant/acme/" + t.tenantId(), t.tenantArn());
        assertEquals("ENABLED", t.sendingStatus());
        assertEquals(1, t.tags().size());
    }

    @Test
    void create_thenGet_roundTrips() {
        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        assertEquals("acme", service.getTenant("acme", REGION).tenantName());
    }

    @Test
    void create_duplicateThrows() {
        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createTenant("acme", List.of(), ACCOUNT, REGION));
        assertEquals("AlreadyExistsException", e.getErrorCode());
    }

    @Test
    void get_missingThrows() {
        AwsException e = assertThrows(AwsException.class, () -> service.getTenant("ghost", REGION));
        assertEquals("NotFoundException", e.getErrorCode());
    }

    @Test
    void delete_removesTenant_missingThrows() {
        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.deleteTenant("acme", REGION);
        assertThrows(AwsException.class, () -> service.getTenant("acme", REGION));
        assertThrows(AwsException.class, () -> service.deleteTenant("acme", REGION));
    }

    @Test
    void list_isPerRegion() {
        service.createTenant("a", List.of(), ACCOUNT, REGION);
        service.createTenant("b", List.of(), ACCOUNT, REGION);
        service.createTenant("other", List.of(), ACCOUNT, "eu-west-1");
        List<Tenant> list = service.listTenants(REGION);
        assertEquals(2, list.size());
    }

    @Test
    void validate_nullName_mustNotBeNull() {
        AwsException e = assertThrows(AwsException.class, () -> service.createTenant(null, List.of(), ACCOUNT, REGION));
        assertEquals("BadRequestException", e.getErrorCode());
        assertTrue(e.getMessage().contains("Member must not be null"));
    }

    @Test
    void validate_emptyName_smithyMinLength() {
        AwsException e = assertThrows(AwsException.class, () -> service.createTenant("", List.of(), ACCOUNT, REGION));
        assertEquals("BadRequestException", e.getErrorCode());
        assertTrue(e.getMessage().contains("Member must have length greater than or equal to 1"));
    }

    @Test
    void validate_blankName() {
        AwsException e = assertThrows(AwsException.class, () -> service.createTenant("   ", List.of(), ACCOUNT, REGION));
        assertEquals("TenantName cannot be empty", e.getMessage());
    }

    @Test
    void validate_tooLongName() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createTenant("x".repeat(65), List.of(), ACCOUNT, REGION));
        assertEquals("TenantName cannot exceed 64 characters.", e.getMessage());
    }

    @Test
    void validate_badChars() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createTenant("bad name!", List.of(), ACCOUNT, REGION));
        assertTrue(e.getMessage().startsWith("Invalid tenant name <bad name!>:"));
    }

    @Test
    void create_invalidTag_isRejected() {
        assertThrows(AwsException.class,
                () -> service.createTenant("acme", List.of(new Tag("", "v")), ACCOUNT, REGION));
    }

    // Per-account isolation is provided transparently by AccountAwareStorageBackend (which
    // StorageFactory wraps every store in), not by the tenant key, so it is covered by the core
    // storage tests rather than re-tested here — consistent with the other SES resources.

    @Test
    void getAndDelete_rejectMalformedName() {
        // A required, min-length-1 member: a blank name is a BadRequest, not a NotFound.
        AwsException g = assertThrows(AwsException.class, () -> service.getTenant("", REGION));
        assertEquals("BadRequestException", g.getErrorCode());
        AwsException d = assertThrows(AwsException.class, () -> service.deleteTenant("   ", REGION));
        assertEquals("BadRequestException", d.getErrorCode());
    }
}
