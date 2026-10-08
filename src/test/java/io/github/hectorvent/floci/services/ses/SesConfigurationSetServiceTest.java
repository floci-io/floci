package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.MessageSecurityOptions;
import io.github.hectorvent.floci.services.ses.model.SigningScheme;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.TrackingOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void list_isPerRegion_sortedByName() {
        service.create(cs("b-cs"), REGION);
        service.create(cs("a-cs"), REGION);
        service.create(cs("other"), "eu-west-1");
        // Creation time no longer decides the order, so pin b-cs as the older one and expect the
        // name order regardless.
        stampCreated("b-cs", Instant.parse("2026-01-01T00:00:00Z"));
        stampCreated("a-cs", Instant.parse("2026-01-02T00:00:00Z"));
        List<ConfigurationSet> list = service.list(REGION);
        assertEquals(List.of("a-cs", "b-cs"), list.stream().map(ConfigurationSet::getName).toList());
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

    @Test
    void tagOps_lifecycle_notFoundMessage() {
        service.create(cs("my-cs"), REGION);
        service.tag("my-cs", REGION, List.of(new Tag("team", "floci"), new Tag("env", "dev")));
        assertEquals(2, service.listTags("my-cs", REGION).size());

        // Re-tagging an existing key replaces its value; untagging a missing key is a silent success.
        service.tag("my-cs", REGION, List.of(new Tag("env", "prod")));
        service.untag("my-cs", REGION, List.of("team", "ghost-key"));
        List<Tag> tags = service.listTags("my-cs", REGION);
        assertEquals(1, tags.size());
        assertEquals("env", tags.get(0).key());
        assertEquals("prod", tags.get(0).value());

        AwsException e = assertThrows(AwsException.class, () -> service.listTags("ghost", REGION));
        assertEquals("No ConfigurationSet present with name: ghost", e.getMessage());
    }

    @Test
    void validateTrackingOptions_orderAndPredicate() {
        TrackingOptions blankDomain = new TrackingOptions();
        blankDomain.setCustomRedirectDomain(" ");
        assertEquals("CustomRedirectDomain must be specified.",
                assertThrows(AwsException.class, () -> service.validateTrackingOptions(
                        blankDomain, domain -> true)).getMessage());

        TrackingOptions unverified = new TrackingOptions();
        unverified.setCustomRedirectDomain("example.com");
        assertEquals("Domain <example.com> is not verified under this account.",
                assertThrows(AwsException.class, () -> service.validateTrackingOptions(
                        unverified, domain -> false)).getMessage());

        // The verified-domain probe is injected; a passing predicate reaches the enum check.
        TrackingOptions badPolicy = new TrackingOptions();
        badPolicy.setCustomRedirectDomain("example.com");
        badPolicy.setHttpsPolicy("BOGUS");
        assertTrue(assertThrows(AwsException.class, () -> service.validateTrackingOptions(
                badPolicy, domain -> true)).getMessage().contains("httpsPolicy"));

        badPolicy.setHttpsPolicy("REQUIRE");
        service.validateTrackingOptions(badPolicy, domain -> true);
        service.validateTrackingOptions(null, domain -> { throw new AssertionError("not called"); });
    }

    @Test
    void validateDeliveryOptions_orderAndPredicate() {
        DeliveryOptions badTls = new DeliveryOptions();
        badTls.setTlsPolicy("BOGUS");
        assertTrue(assertThrows(AwsException.class, () -> service.validateDeliveryOptions(
                badTls, pool -> true)).getMessage().contains("tlsPolicy"));

        DeliveryOptions missingPool = new DeliveryOptions();
        missingPool.setSendingPoolName("ghost-pool");
        assertEquals("SendingPool <ghost-pool> doesn't exist",
                assertThrows(AwsException.class, () -> service.validateDeliveryOptions(
                        missingPool, pool -> false)).getMessage());
        service.validateDeliveryOptions(missingPool, pool -> true);

        DeliveryOptions tooFast = new DeliveryOptions();
        tooFast.setMaxDeliverySeconds(299L);
        assertTrue(assertThrows(AwsException.class, () -> service.validateDeliveryOptions(
                tooFast, pool -> true)).getMessage().contains("greater than or equal to 300"));
    }

    @Test
    void requireVerifiedRedirectDomain_nullBlankUnverified() {
        assertTrue(assertThrows(AwsException.class, () -> service.requireVerifiedRedirectDomain(
                null, domain -> true)).getMessage().contains("must not be null"));
        assertEquals("At least one field of TrackingOptions must contain a value.",
                assertThrows(AwsException.class, () -> service.requireVerifiedRedirectDomain(
                        " ", domain -> true)).getMessage());
        assertEquals("Domain <example.com> is not verified under this account.",
                assertThrows(AwsException.class, () -> service.requireVerifiedRedirectDomain(
                        "example.com", domain -> false)).getMessage());
        service.requireVerifiedRedirectDomain("example.com", domain -> true);
    }

    @Test
    void updateMessageSecurityOptions_nullKeepsTheStoredValue() {
        service.create(cs("my-cs"), REGION);
        service.updateMessageSecurityOptions("my-cs",
                new MessageSecurityOptions(SigningScheme.smime("DETACHED")), REGION);

        service.updateMessageSecurityOptions("my-cs", null, REGION);

        assertEquals("DETACHED", service.get("my-cs", REGION).getMessageSecurityOptions()
                .getSigningScheme().getSmimeScheme().getSignatureFormat());
    }

    @Test
    void messageSecurityOptions_surviveAPersistentStorageReload(@TempDir Path dir) {
        Path file = dir.resolve("ses-config-sets.json");
        SesConfigurationSetService writer = new SesConfigurationSetService(
                new PersistentStorage<>(file, new TypeReference<Map<String, ConfigurationSet>>() {}));
        writer.create(cs("smime-cs"), REGION);
        writer.create(cs("default-cs"), REGION);
        writer.updateMessageSecurityOptions("smime-cs",
                new MessageSecurityOptions(SigningScheme.smime("DETACHED")), REGION);
        writer.updateMessageSecurityOptions("default-cs",
                new MessageSecurityOptions(SigningScheme.defaultScheme()), REGION);

        PersistentStorage<String, ConfigurationSet> reloaded =
                new PersistentStorage<>(file, new TypeReference<Map<String, ConfigurationSet>>() {});
        reloaded.load();
        SesConfigurationSetService reader = new SesConfigurationSetService(reloaded);

        SigningScheme smime = reader.get("smime-cs", REGION).getMessageSecurityOptions().getSigningScheme();
        assertEquals("DETACHED", smime.getSmimeScheme().getSignatureFormat());
        assertNull(smime.getDefaultScheme());
        SigningScheme defaultScheme = reader.get("default-cs", REGION).getMessageSecurityOptions().getSigningScheme();
        assertEquals(Map.of(), defaultScheme.getDefaultScheme());
        assertNull(defaultScheme.getSmimeScheme());
    }

    @Test
    void updateMessageSecurityOptions_missingSetThrowsEvenWithoutOptions() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.updateMessageSecurityOptions("ghost", null, REGION));
        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
    }
}
