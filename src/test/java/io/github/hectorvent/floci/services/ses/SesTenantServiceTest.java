package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the tenant domain: the Phase 1 CRUD (create/get/list/delete, id/ARN generation, name
 * validation) and the Phase 2 resource associations (ARN parsing, duplicate/idempotency semantics,
 * sorting, cascade on tenant delete). Constructed with just its own stores; resource existence is the
 * facade's concern and is covered by the integration tests.
 */
class SesTenantServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private SesTenantService service;

    @BeforeEach
    void setUp() {
        service = new SesTenantService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                Clock.systemUTC(), new SecureRandom());
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
        // Unlike CreateTenant, both an absent and an empty name collapse to the service-level
        // message here (probe-confirmed 2026-08-30) — the Smithy wordings are CreateTenant-only.
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class, () -> service.getTenant("", REGION)).getMessage());
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class, () -> service.deleteTenant("   ", REGION)).getMessage());
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class, () -> service.getTenant(null, REGION)).getMessage());
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class, () -> service.deleteTenant(null, REGION)).getMessage());
    }

    @Test
    void findByTenantId_matchesIdOnly_perRegion() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        assertEquals("acme", service.findByTenantId(tenant.tenantId(), REGION).orElseThrow().tenantName());
        assertTrue(service.findByTenantId("tn-000000000000000000000000000000", REGION).isEmpty());
        assertTrue(service.findByTenantId(tenant.tenantId(), "eu-west-1").isEmpty());
    }

    @Test
    void tenantForTagArn_resolvesByIdAlone_notFoundMessagesMatchAws() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        // The name segment is never matched; only the id resolves.
        assertEquals("acme",
                service.tenantForTagArn("wrong-name/" + tenant.tenantId(), REGION).tenantName());
        // The missing space before "with" is AWS's own wording.
        assertEquals("No Tenant present with name: acmewith tenantId: tn-0000",
                assertThrows(AwsException.class,
                        () -> service.tenantForTagArn("acme/tn-0000", REGION)).getMessage());
        // A remainder without a slash parses as a null name with the segment as the id.
        assertEquals("No Tenant present with name: nullwith tenantId: acme",
                assertThrows(AwsException.class,
                        () -> service.tenantForTagArn("acme", REGION)).getMessage());
    }

    @Test
    void mutateTags_appliesToCurrentRecordUnderLock() {
        Tenant tenant = service.createTenant("acme", List.of(new Tag("team", "floci")), ACCOUNT, REGION);
        service.mutateTags("acme/" + tenant.tenantId(), REGION, tags -> List.of(new Tag("env", "dev")));
        List<Tag> tags = service.getTenant("acme", REGION).tags();
        assertEquals(1, tags.size());
        assertEquals("env", tags.get(0).key());

        // A stale TenantId (delete/recreate happened since the caller's lookup) must not resurrect
        // the old record: the mutation re-resolves by id under the lock and 404s.
        service.deleteTenant("acme", REGION);
        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.mutateTags(
                "acme/" + tenant.tenantId(), REGION, current -> current));
        assertEquals("No Tenant present with name: acmewith tenantId: " + tenant.tenantId(),
                e.getMessage());
        assertEquals(0, service.getTenant("acme", REGION).tags().size());
    }

    @Test
    void mutateTags_shieldsTheStoredRecordFromCallbackMutation() {
        Tenant tenant = service.createTenant("acme",
                new ArrayList<>(List.of(new Tag("team", "floci"))), ACCOUNT, REGION);
        // The callback gets an immutable copy: an in-place mutation fails fast instead of
        // silently aliasing the stored record's list.
        assertThrows(UnsupportedOperationException.class, () -> service.mutateTags(
                "acme/" + tenant.tenantId(), REGION, tags -> {
                    tags.add(new Tag("env", "dev"));
                    return tags;
                }));
        List<Tag> tags = service.getTenant("acme", REGION).tags();
        assertEquals(1, tags.size());
        assertEquals("team", tags.get(0).key());
    }

    // ──────────────────────── Resource associations (Phase 2) ────────────────────────

    private static SesTenantService.AssociationResource identityRef(String name) {
        return SesTenantService.parseResourceArn(
                "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":identity/" + name, ACCOUNT, REGION);
    }

    @Test
    void parseResourceArn_acceptsThreeTypes_andExtractsName() {
        SesTenantService.AssociationResource ref = SesTenantService.parseResourceArn(
                "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":configuration-set/my-cs", ACCOUNT, REGION);
        assertEquals("configuration-set", ref.type());
        assertEquals("my-cs", ref.name());
    }

    @Test
    void parseResourceArn_errorChainMatchesAws() {
        assertEquals("Provided resource identifier is not an SES resource",
                assertThrows(AwsException.class,
                        () -> SesTenantService.parseResourceArn("not-an-arn", ACCOUNT, REGION)).getMessage());
        assertEquals("Provided ARN is not in SES resource ARN format",
                assertThrows(AwsException.class, () -> SesTenantService.parseResourceArn(
                        "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":q", ACCOUNT, REGION)).getMessage());
        assertEquals("Unsupported resource type: contact-list",
                assertThrows(AwsException.class, () -> SesTenantService.parseResourceArn(
                        "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":contact-list/x", ACCOUNT, REGION)).getMessage());
        assertTrue(assertThrows(AwsException.class, () -> SesTenantService.parseResourceArn(
                "arn:aws:ses:eu-west-1:" + ACCOUNT + ":identity/a", ACCOUNT, REGION))
                .getMessage().endsWith("must be in the same region"));
        assertTrue(assertThrows(AwsException.class, () -> SesTenantService.parseResourceArn(
                "arn:aws:ses:" + REGION + ":111111111111:identity/a", ACCOUNT, REGION))
                .getMessage().endsWith("must be in the same account"));
        assertTrue(assertThrows(AwsException.class,
                () -> SesTenantService.parseResourceArn(null, ACCOUNT, REGION))
                .getMessage().contains("'resourceArn'"));
    }

    @Test
    void parseResourceArn_rejectsMissingOrEmptyName() {
        // A supported type without a name segment must be rejected as malformed, not resolved to an
        // empty-name resource that would 404 with a misleading message.
        assertEquals("Provided resource identifier is not an SES resource",
                assertThrows(AwsException.class, () -> SesTenantService.parseResourceArn(
                        "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":identity", ACCOUNT, REGION)).getMessage());
        assertEquals("Provided resource identifier is not an SES resource",
                assertThrows(AwsException.class, () -> SesTenantService.parseResourceArn(
                        "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":identity/", ACCOUNT, REGION)).getMessage());
    }

    @Test
    void resourceLookups_dontAliasNamesContainingDelimiter() {
        // Floci barely restricts resource names, so "x" and "template::x" can both exist; the
        // lookups must not let a key-suffix match blur them together.
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        SesTenantService.AssociationResource plain = SesTenantService.parseResourceArn(
                "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":template/x", ACCOUNT, REGION);
        SesTenantService.AssociationResource tricky = SesTenantService.parseResourceArn(
                "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":template/template::x", ACCOUNT, REGION);
        service.associate(tenant, tricky, REGION, () -> {});

        assertEquals(0, service.listResourceTenants(plain, REGION).size());
        assertTrue(service.findAssociationForResource("template", "x", REGION).isEmpty());
        assertEquals(1, service.listResourceTenants(tricky, REGION).size());
        assertTrue(service.findAssociationForResource("template", "template::x", REGION).isPresent());
    }

    @Test
    void associate_duplicateThrows_awsGrammarPreserved() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        SesTenantService.AssociationResource ref = identityRef("example.com");
        service.associate(tenant, ref, REGION, () -> {});
        AwsException e = assertThrows(AwsException.class,
                () -> service.associate(tenant, ref, REGION, () -> {}));
        assertEquals("AlreadyExistsException", e.getErrorCode());
        assertEquals("Resources " + ref.arn() + " has already been associated with tenant acme",
                e.getMessage());
    }

    @Test
    void disassociate_isIdempotent() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.disassociate(tenant, identityRef("example.com"), REGION);
        service.associate(tenant, identityRef("example.com"), REGION, () -> {});
        service.disassociate(tenant, identityRef("example.com"), REGION);
        assertEquals(0, service.listTenantResources(tenant, null, REGION).size());
    }

    @Test
    void listTenantResources_sortedByArn_andFilterable() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.associate(tenant, identityRef("example.com"), REGION, () -> {});
        service.associate(tenant, SesTenantService.parseResourceArn(
                "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":template/tpl", ACCOUNT, REGION),
                REGION, () -> {});
        service.associate(tenant, SesTenantService.parseResourceArn(
                "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":configuration-set/cs", ACCOUNT, REGION),
                REGION, () -> {});
        var all = service.listTenantResources(tenant, null, REGION);
        assertEquals(List.of("configuration-set", "identity", "template"),
                all.stream().map(a -> a.resourceType()).toList());
        var filtered = service.listTenantResources(tenant, "identity", REGION);
        assertEquals(1, filtered.size());
        assertEquals("identity", filtered.get(0).resourceType());
    }

    @Test
    void listResourceTenants_findsAllTenants_ofOneResource() {
        Tenant a = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        Tenant b = service.createTenant("beta", List.of(), ACCOUNT, REGION);
        service.associate(a, identityRef("example.com"), REGION, () -> {});
        service.associate(b, identityRef("example.com"), REGION, () -> {});
        var tenants = service.listResourceTenants(identityRef("example.com"), REGION);
        assertEquals(2, tenants.size());
        assertEquals("acme", tenants.get(0).tenantName());
        assertEquals(a.tenantId(), tenants.get(0).tenantId());
    }

    @Test
    void deleteTenant_cascadesAssociations_recreatedTenantSeesNone() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.associate(tenant, identityRef("example.com"), REGION, () -> {});
        service.deleteTenant("acme", REGION);
        assertEquals(0, service.listResourceTenants(identityRef("example.com"), REGION).size());
        Tenant recreated = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        assertEquals(0, service.listTenantResources(recreated, null, REGION).size());
    }

    @Test
    void associate_staleTenant_throwsNotFound() {
        // A tenant resolved before a concurrent DeleteTenant (same or recreated name) must not be
        // able to insert an orphan association: associate revalidates the TenantId under the lock.
        Tenant stale = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.deleteTenant("acme", REGION);
        AwsException gone = assertThrows(AwsException.class,
                () -> service.associate(stale, identityRef("example.com"), REGION, () -> {}));
        assertEquals("NotFoundException", gone.getErrorCode());

        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        AwsException recreated = assertThrows(AwsException.class,
                () -> service.associate(stale, identityRef("example.com"), REGION, () -> {}));
        assertEquals("NotFoundException", recreated.getErrorCode());
    }

    @Test
    void deleteBackingResource_blocksWhileAssociated_runsActionOtherwise() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.associate(tenant, identityRef("example.com"), REGION, () -> {});
        AwsException blocked = assertThrows(AwsException.class, () -> service.deleteBackingResource(
                "identity", "example.com", REGION, () -> {}));
        assertEquals("Cannot delete <" + identityRef("example.com").arn() + "> because it has tenant "
                + "associations. Remove all tenant associations and try again.", blocked.getMessage());

        boolean[] ran = {false};
        service.deleteBackingResource("identity", "other.com", REGION, () -> ran[0] = true);
        assertTrue(ran[0]);
    }

    @Test
    void findAssociationForResource_backsDeleteGuards() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.associate(tenant, identityRef("example.com"), REGION, () -> {});
        assertTrue(service.findAssociationForResource("identity", "example.com", REGION).isPresent());
        assertTrue(service.findAssociationForResource("identity", "other.com", REGION).isEmpty());
        assertTrue(service.findAssociationForResource("identity", "example.com", "eu-west-1").isEmpty());
    }

    @Test
    void tenantForAssociation_hasServiceLevelEmptyMessage() {
        AwsException empty = assertThrows(AwsException.class,
                () -> service.tenantForAssociation("", REGION));
        assertEquals("TenantName cannot be empty", empty.getMessage());
        // An absent TenantName gets the same message (probe-confirmed) — the Smithy not-null
        // variant exists only on CreateTenant.
        AwsException absent = assertThrows(AwsException.class,
                () -> service.tenantForAssociation(null, REGION));
        assertEquals("TenantName cannot be empty", absent.getMessage());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.tenantForAssociation("ghost", REGION));
        assertEquals("NotFoundException", missing.getErrorCode());
    }

    // ──────────────────────── Suppression attributes (Phase 3) ────────────────────────

    @Test
    void putSuppressionAttributes_setsAndClears() {
        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.putSuppressionAttributes("acme", List.of("BOUNCE", "COMPLAINT"), "TENANT", REGION);
        var attrs = service.getTenant("acme", REGION).suppressionAttributes();
        assertEquals(List.of("BOUNCE", "COMPLAINT"), attrs.suppressedReasons());
        assertEquals("TENANT", attrs.suppressionScope());

        // A put with neither member is AWS's clear mechanism.
        service.putSuppressionAttributes("acme", null, null, REGION);
        assertEquals(null, service.getTenant("acme", REGION).suppressionAttributes());
    }

    @Test
    void putSuppressionAttributes_emptyReasonListWithScope_isValidState() {
        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        service.putSuppressionAttributes("acme", List.of(), "TENANT", REGION);
        var attrs = service.getTenant("acme", REGION).suppressionAttributes();
        assertEquals(List.of(), attrs.suppressedReasons());
        assertEquals("TENANT", attrs.suppressionScope());
    }

    @Test
    void putSuppressionAttributes_pairRules_threeMessages() {
        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        assertEquals("SuppressedReasons cannot be specified without SuppressionScope.",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        "acme", List.of("BOUNCE"), null, REGION)).getMessage());
        assertEquals("SuppressionScope cannot be specified without SuppressedReasons.",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        "acme", null, "ACCOUNT", REGION)).getMessage());
        // An empty list without a scope gets AWS's third wording.
        assertEquals("SuppressionScope is required when SuppressedReasons are provided. "
                        + "Valid values are: TENANT, ACCOUNT",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        "acme", List.of(), null, REGION)).getMessage());
    }

    @Test
    void putSuppressionAttributes_enumAndDuplicateMessages() {
        service.createTenant("acme", List.of(), ACCOUNT, REGION);
        assertEquals("1 validation error detected: Value at 'suppressedReasons' failed to satisfy "
                        + "constraint: Member must satisfy constraint: [Member must satisfy enum value "
                        + "set: [BOUNCE, COMPLAINT]]",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        "acme", List.of("NOPE"), null, REGION)).getMessage());
        assertEquals("1 validation error detected: Value at 'suppressionScope' failed to satisfy "
                        + "constraint: Member must satisfy enum value set: [TENANT, ACCOUNT]",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        "acme", null, "NOPE", REGION)).getMessage());
        assertEquals("Each suppressed reason can only be specified at most once",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        "acme", List.of("BOUNCE", "BOUNCE"), "ACCOUNT", REGION)).getMessage());
    }

    @Test
    void putSuppressionAttributes_precedenceMatchesAws() {
        // Empty TenantName wins over the enum check; request validation wins over existence. An
        // absent TenantName gets the same message — no Smithy not-null variant on this operation.
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        "", List.of("NOPE"), "TENANT", REGION)).getMessage());
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        null, List.of("BOUNCE"), "TENANT", REGION)).getMessage());
        assertEquals("SuppressedReasons cannot be specified without SuppressionScope.",
                assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                        "ghost", List.of("BOUNCE"), null, REGION)).getMessage());
        AwsException ghost = assertThrows(AwsException.class, () -> service.putSuppressionAttributes(
                "ghost", List.of("BOUNCE"), "TENANT", REGION));
        assertEquals("NotFoundException", ghost.getErrorCode());
        assertEquals("The requested tenant <ghost> does not exist.", ghost.getMessage());
    }

    @Test
    void createTenant_withSuppressionAttributes_validatedAndStored() {
        Tenant tenant = service.createTenant("acme", List.of(), List.of("BOUNCE"), "TENANT",
                ACCOUNT, REGION);
        assertEquals("TENANT", tenant.suppressionAttributes().suppressionScope());
        assertThrows(AwsException.class, () -> service.createTenant("beta", List.of(),
                List.of("BOUNCE"), null, ACCOUNT, REGION));
    }

    @Test
    void runWithTenant_resolvesUnderLock_orThrows() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        String[] seenTenantId = new String[1];
        service.runWithTenant("acme", REGION, t -> seenTenantId[0] = t.tenantId());
        assertEquals(tenant.tenantId(), seenTenantId[0]);
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class,
                        () -> service.runWithTenant(" ", REGION, t -> null)).getMessage());
        // An absent TenantName collapses to the same service-level message (probe-confirmed) —
        // the Smithy not-null variant exists only on CreateTenant.
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class,
                        () -> service.runWithTenant(null, REGION, t -> null)).getMessage());
        assertEquals("NotFoundException",
                assertThrows(AwsException.class,
                        () -> service.runWithTenant("ghost", REGION, t -> null)).getErrorCode());
    }

    @Test
    void deleteTenant_runsDependentCascadeInsideLock_beforeRecordRemoval() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        boolean[] sawTenantStillPresent = new boolean[1];
        service.deleteTenant("acme", REGION, t -> {
            assertEquals(tenant.tenantId(), t.tenantId());
            sawTenantStillPresent[0] = service.find("acme", REGION).isPresent();
        });
        assertTrue(sawTenantStillPresent[0]);
        assertTrue(service.find("acme", REGION).isEmpty());
    }

    // ──────────────────────── Tenant-scoped sending (Phase 4) ────────────────────────

    @Test
    void tenantForSending_hasSendFlavoredNotFoundWording() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.tenantForSending("ghost", REGION, ACCOUNT));
        assertEquals("NotFoundException", e.getErrorCode());
        // No angle brackets, and the account id is included — unlike the management operations.
        assertEquals("Tenant ghost for AwsAccountId " + ACCOUNT + " not found.", e.getMessage());
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class,
                        () -> service.tenantForSending(" ", REGION, ACCOUNT)).getMessage());
        assertEquals("TenantName cannot be empty",
                assertThrows(AwsException.class,
                        () -> service.tenantForSending(null, REGION, ACCOUNT)).getMessage());
    }

    @Test
    void requireResourcesAssociated_collectsEveryMissingArn() {
        Tenant tenant = service.createTenant("acme", List.of(), ACCOUNT, REGION);
        SesTenantService.AssociationResource identity = identityRef("example.com");
        SesTenantService.AssociationResource cs = SesTenantService.parseResourceArn(
                "arn:aws:ses:" + REGION + ":" + ACCOUNT + ":configuration-set/cs", ACCOUNT, REGION);
        service.associate(tenant, identity, REGION, () -> {});

        service.requireResourcesAssociated(tenant, List.of(identity), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.requireResourcesAssociated(tenant, List.of(identity, cs), REGION));
        assertEquals("AccessDeniedException", e.getErrorCode());
        assertEquals(403, e.getHttpStatus());
        assertEquals("Tenant not associated with resources [" + cs.arn() + "].", e.getMessage());

        service.disassociate(tenant, identity, REGION);
        AwsException both = assertThrows(AwsException.class,
                () -> service.requireResourcesAssociated(tenant, List.of(identity, cs), REGION));
        assertEquals("Tenant not associated with resources [" + identity.arn() + ", " + cs.arn() + "].",
                both.getMessage());
    }

    @Test
    void validateFilterAndPaging_matchAwsMessages() {
        assertEquals("Invalid resource type NOPE specified.",
                assertThrows(AwsException.class,
                        () -> SesTenantService.validateResourceTypeFilter("NOPE")).getMessage());
        assertEquals("Invalid resource type EMAIL_IDENTITY specified.",
                assertThrows(AwsException.class,
                        () -> SesTenantService.validateResourceTypeFilter("EMAIL_IDENTITY")).getMessage());
        assertTrue(assertThrows(AwsException.class,
                () -> SesTenantService.validateListPaging(0, null))
                .getMessage().contains("greater than or equal to 1"));
        assertEquals("Invalid Next Token",
                assertThrows(AwsException.class,
                        () -> SesTenantService.validateListPaging(null, "garbage")).getMessage());
    }
}
