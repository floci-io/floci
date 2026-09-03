package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted configuration-set domain: store ownership, key derivation and name
 * validation, CRUD semantics, and the find/save escape hatches the facade's cross-domain
 * orchestration relies on. The option setters and event destinations keep their coverage in the
 * existing facade-level unit and integration tests, which now exercise them through the delegation.
 */
class SesConfigurationSetServiceTest {

    private static final String REGION = "us-east-1";
    private SesConfigurationSetService service;

    private static ConfigurationSet cs(String name) {
        ConfigurationSet cs = new ConfigurationSet();
        cs.setName(name);
        return cs;
    }

    @BeforeEach
    void setUp() {
        service = new SesConfigurationSetService(new InMemoryStorage<>());
    }

    @Test
    void create_get_roundTrips_andStampsTimestamp() {
        service.create(cs("my-cs"), REGION);
        ConfigurationSet got = service.get("my-cs", REGION);
        assertEquals("my-cs", got.getName());
        assertTrue(got.getCreatedTimestamp() != null);
    }

    @Test
    void create_duplicateThrows() {
        service.create(cs("my-cs"), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.create(cs("my-cs"), REGION));
        assertEquals("ConfigurationSetAlreadyExists", e.getErrorCode());
    }

    @Test
    void get_missingThrows_withV1Code() {
        AwsException e = assertThrows(AwsException.class, () -> service.get("ghost", REGION));
        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
        assertEquals("Configuration set <ghost> does not exist.", e.getMessage());
    }

    @Test
    void list_isPerRegion_sortedByCreation() {
        service.create(cs("b-cs"), REGION);
        service.create(cs("a-cs"), REGION);
        service.create(cs("other"), "eu-west-1");
        // create() stamps Instant.now(), which can collide within one clock tick and fall back to
        // the name tie-break; pin distinct timestamps through the escape hatch so the
        // creation-order assertion stays deterministic.
        stampCreated("b-cs", Instant.parse("2026-01-01T00:00:00Z"));
        stampCreated("a-cs", Instant.parse("2026-01-02T00:00:00Z"));
        List<ConfigurationSet> list = service.list(REGION);
        assertEquals(2, list.size());
        assertEquals("b-cs", list.get(0).getName());
    }

    private void stampCreated(String name, Instant timestamp) {
        ConfigurationSet loaded = service.find(name, REGION).orElseThrow();
        loaded.setCreatedTimestamp(timestamp);
        service.save(loaded, REGION);
    }

    @Test
    void findAndSave_backTheFacadeOrchestration_removeDeletes() {
        assertTrue(service.find("my-cs", REGION).isEmpty());
        service.create(cs("my-cs"), REGION);
        ConfigurationSet loaded = service.find("my-cs", REGION).orElseThrow();
        loaded.setSendingEnabled(false);
        service.save(loaded, REGION);
        assertEquals(false, service.get("my-cs", REGION).getSendingEnabled());

        service.remove("my-cs", REGION);
        assertTrue(service.find("my-cs", REGION).isEmpty());
    }

    @Test
    void nameValidation_guardsKeysAndTenantGate() {
        AwsException e = assertThrows(AwsException.class, () -> service.get("bad name!", REGION));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertTrue(SesConfigurationSetService.isValidName("my-cs"));
        assertFalse(SesConfigurationSetService.isValidName("bad name!"));
        assertFalse(SesConfigurationSetService.isValidName(null));
    }
}
