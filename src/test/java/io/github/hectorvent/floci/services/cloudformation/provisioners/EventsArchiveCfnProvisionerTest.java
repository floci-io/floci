package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.Archive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Events::Archive}: the name is the physical id and {@code Ref}, {@code Arn} is the
 * archive ARN, the name and source are create-only, an in-place update sends only the properties
 * the template declares, and a changed source or name replaces the archive.
 */
class EventsArchiveCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String STACK = "my-stack";
    private static final String TYPE = "AWS::Events::Archive";
    private static final String BUS_ARN = "arn:aws:events:us-east-1:000000000000:event-bus/orders";
    private static final String OTHER_BUS_ARN = "arn:aws:events:us-east-1:000000000000:event-bus/billing";
    private static final String PATTERN = "{\"source\":[\"app.orders\"]}";

    private EventBridgeService events;
    private CloudFormationTemplateEngine engine;
    private EventsArchiveCfnProvisioner provisioner;
    /** What the mocked service holds, so a describe answers what an earlier create stored. */
    private final Map<String, Archive> archives = new HashMap<>();

    @BeforeEach
    void setUp() {
        events = mock(EventBridgeService.class);
        engine = mock(CloudFormationTemplateEngine.class);
        // Like the real engine, AWS::NoValue resolves to an empty string.
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            return isNoValue(node) ? "" : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(engine.resolveJsonAttribute(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            if (isNoValue(node)) {
                return "";
            }
            return node.isTextual() ? node.asText() : node.toString();
        });
        when(events.createArchive(anyString(), anyString(), any(), any(), anyInt(), eq(REGION))).thenAnswer(i -> {
            Archive archive = archive(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3),
                    i.getArgument(4));
            archives.put(archive.getArchiveName(), archive);
            return archive;
        });
        when(events.describeArchive(anyString(), eq(REGION))).thenAnswer(i -> {
            Archive archive = archives.get(i.<String>getArgument(0));
            if (archive == null) {
                throw new AwsException("ResourceNotFoundException", "Archive not found: " + i.getArgument(0), 400);
            }
            return archive;
        });
        when(events.updateArchive(anyString(), any(), any(), any(), eq(REGION))).thenAnswer(i ->
                archives.get(i.<String>getArgument(0)));
        provisioner = new EventsArchiveCfnProvisioner(events);
    }

    @Test
    void servesOnlyTheArchiveType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void createWithAnExplicitNameSetsRefAndArn() {
        StackResource resource = create(props("orders-archive", BUS_ARN)
                .put("Description", "orders")
                .put("RetentionDays", 7)
                .set("EventPattern", pattern()));

        verify(events).createArchive("orders-archive", BUS_ARN, "orders", PATTERN, 7, REGION);
        assertEquals("orders-archive", resource.getPhysicalId());
        assertEquals(arn("orders-archive"), resource.getAttributes().get("Arn"));
    }

    @Test
    void createWithoutRetentionDaysStoresZero() {
        create(props("orders-archive", BUS_ARN));

        verify(events).createArchive("orders-archive", BUS_ARN, null, null, 0, REGION);
    }

    @Test
    void omittedNameIsGeneratedWithinTheArchiveNameLimit() {
        StackResource resource = resource();
        provisioner.provision(resource, props(null, BUS_ARN),
                new ProvisionContext(engine, REGION, ACCOUNT, "stack".repeat(20)));

        String name = resource.getPhysicalId();
        assertTrue(name.length() <= 48, name);
        assertTrue(name.matches("[.\\-_A-Za-z0-9]+-[0-9a-f]{12}"), name);
        verify(events).createArchive(name, BUS_ARN, null, null, 0, REGION);
        assertEquals(arn(name), resource.getAttributes().get("Arn"));
    }

    @Test
    void namesOutsideTheSchemaAreRejectedBeforeAnyCall() {
        for (String name : List.of("bad:name", "a".repeat(49), "", " ")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> create(props(name, BUS_ARN)), name);

            assertEquals("ValidationError", error.getErrorCode(), name);
            assertTrue(error.getMessage().contains("1 to 48 characters"), error.getMessage());
        }
        verifyNoInteractions(events);
    }

    @Test
    void nameUsingEveryAllowedCharacterClassIsAccepted() {
        StackResource resource = create(props("a.b-c_D9", BUS_ARN));

        assertEquals("a.b-c_D9", resource.getPhysicalId());
        verify(events).createArchive("a.b-c_D9", BUS_ARN, null, null, 0, REGION);
    }

    @Test
    void noValueNameIsGenerated() {
        ObjectNode properties = props(null, BUS_ARN);
        properties.set("ArchiveName", noValue());

        StackResource resource = create(properties);

        assertTrue(resource.getPhysicalId().startsWith(STACK + "-Archive-"), resource.getPhysicalId());
    }

    @Test
    void noValueDescriptionAndPatternAreAbsentOnCreate() {
        ObjectNode properties = props("orders-archive", BUS_ARN);
        properties.set("Description", noValue());
        properties.set("EventPattern", noValue());

        create(properties);

        verify(events).createArchive("orders-archive", BUS_ARN, null, null, 0, REGION);
    }

    @Test
    void noValueDescriptionAndPatternAreNotSentOnUpdate() {
        StackResource resource = create(props("orders-archive", BUS_ARN)
                .put("Description", "orders")
                .set("EventPattern", pattern()));
        clearInvocations(events);
        ObjectNode properties = props("orders-archive", BUS_ARN).put("RetentionDays", 30);
        properties.set("Description", noValue());
        properties.set("EventPattern", noValue());

        update(resource, properties);

        verify(events).updateArchive("orders-archive", null, null, 30, REGION);
    }

    @Test
    void literalEmptyNameOnUpdateIsRefusedWithoutAnyMutation() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        clearInvocations(events);

        AwsException error = assertThrows(AwsException.class, () -> update(resource, props("", BUS_ARN)));

        assertEquals("ValidationError", error.getErrorCode());
        verify(events, never()).createArchive(anyString(), anyString(), any(), any(), anyInt(), anyString());
        verify(events, never()).updateArchive(anyString(), any(), any(), any(), anyString());
        verify(events, never()).deleteArchive(anyString(), anyString());
        assertEquals("orders-archive", resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void literalEmptyDescriptionIsSentOnUpdateToClearIt() {
        StackResource resource = create(props("orders-archive", BUS_ARN).put("Description", "orders"));
        clearInvocations(events);

        update(resource, props("orders-archive", BUS_ARN).put("Description", ""));

        verify(events).updateArchive("orders-archive", "", null, null, REGION);
    }

    @Test
    void missingSourceArnIsRejectedBeforeAnyCall() {
        AwsException error = assertThrows(AwsException.class,
                () -> create(props("orders-archive", null)));

        assertEquals("ValidationError", error.getErrorCode());
        verifyNoInteractions(events);
    }

    @Test
    void inPlaceUpdateSendsOnlyThePropertiesTheTemplateDeclares() {
        StackResource resource = create(props("orders-archive", BUS_ARN)
                .put("Description", "orders")
                .put("RetentionDays", 7)
                .set("EventPattern", pattern()));
        clearInvocations(events);

        update(resource, props("orders-archive", BUS_ARN).put("RetentionDays", 30));

        verify(events).updateArchive("orders-archive", null, null, 30, REGION);
        verify(events, never()).createArchive(anyString(), anyString(), any(), any(), anyInt(), anyString());
        assertEquals("orders-archive", resource.getPhysicalId());
        assertEquals(arn("orders-archive"), resource.getAttributes().get("Arn"));
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void inPlaceUpdateKeepsAGeneratedName() {
        StackResource resource = create(props(null, BUS_ARN));
        String generated = resource.getPhysicalId();
        clearInvocations(events);

        update(resource, props(null, BUS_ARN).put("Description", "orders"));

        assertEquals(generated, resource.getPhysicalId());
        verify(events).updateArchive(generated, "orders", null, null, REGION);
        verify(events, never()).createArchive(anyString(), anyString(), any(), any(), anyInt(), anyString());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void rollbackUpdateRestoresTheSnapshotAndClearsWhatWasUnset() {
        StackResource resource = create(props("orders-archive", BUS_ARN)
                .put("Description", "orders")
                .put("RetentionDays", 5));
        update(resource, props("orders-archive", BUS_ARN)
                .put("Description", "changed")
                .set("EventPattern", pattern()));
        assertTrue(resource.getAttributes().containsKey(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR));
        clearInvocations(events);

        assertTrue(provisioner.rollbackUpdate(resource));

        verify(events).updateArchive("orders-archive", "orders", "", 5, REGION);
        assertFalse(resource.getAttributes().containsKey(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackUpdateWithoutASnapshotChangesNothing() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        clearInvocations(events);

        assertTrue(provisioner.rollbackUpdate(resource));

        verifyNoInteractions(events);
    }

    @Test
    void clearUpdateDropsTheSnapshot() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        update(resource, props("orders-archive", BUS_ARN).put("RetentionDays", 3));

        provisioner.clearUpdate(resource);

        assertFalse(resource.getAttributes().containsKey(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void aStaleSnapshotIsDroppedWhenTheNextProvisionStarts() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        update(resource, props("orders-archive", BUS_ARN).put("RetentionDays", 3));
        commit(resource);
        resource.getAttributes().put(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR, "{\"stale\":true}");

        update(resource, props("renamed-archive", BUS_ARN));

        assertFalse(resource.getAttributes().containsKey(CfnRollback.ARCHIVE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void changingTheSourceOfAGeneratedNameReplacesTheArchive() {
        StackResource resource = create(props(null, BUS_ARN));
        String original = resource.getPhysicalId();
        clearInvocations(events);

        update(resource, props(null, OTHER_BUS_ARN));

        String replacement = resource.getPhysicalId();
        assertNotEquals(original, replacement);
        verify(events).createArchive(replacement, OTHER_BUS_ARN, null, null, 0, REGION);
        verify(events, never()).updateArchive(anyString(), any(), any(), any(), anyString());
        verify(events, never()).deleteArchive(anyString(), anyString());
        assertEquals(arn(replacement), resource.getAttributes().get("Arn"));
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals(original, provisioner.updateCleanupPhysicalId(resource));

        commit(resource);
        verify(events).deleteArchive(original, REGION);
        verify(events, never()).deleteArchive(replacement, REGION);
    }

    @Test
    void changingTheSourceOfAnExplicitNameIsRefusedBeforeAnyMutation() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        clearInvocations(events);

        AwsException error = assertThrows(AwsException.class,
                () -> update(resource, props("orders-archive", OTHER_BUS_ARN)));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                + "Rename orders-archive and update the stack again.", error.getMessage());
        verify(events, never()).createArchive(anyString(), anyString(), any(), any(), anyInt(), anyString());
        verify(events, never()).updateArchive(anyString(), any(), any(), any(), anyString());
        verify(events, never()).deleteArchive(anyString(), anyString());
        assertEquals("orders-archive", resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void droppingTheExplicitNameReplacesTheArchive() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        clearInvocations(events);

        update(resource, props(null, BUS_ARN));

        String generated = resource.getPhysicalId();
        assertTrue(generated.startsWith(STACK + "-Archive-"), generated);
        verify(events).createArchive(generated, BUS_ARN, null, null, 0, REGION);
        assertEquals("orders-archive", provisioner.updateCleanupPhysicalId(resource));

        commit(resource);
        verify(events).deleteArchive("orders-archive", REGION);
    }

    @Test
    void renamingTheArchiveReplacesIt() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        clearInvocations(events);

        update(resource, props("orders-archive-v2", OTHER_BUS_ARN));

        assertEquals("orders-archive-v2", resource.getPhysicalId());
        verify(events).createArchive("orders-archive-v2", OTHER_BUS_ARN, null, null, 0, REGION);
        assertEquals("orders-archive", provisioner.updateCleanupPhysicalId(resource));
    }

    @Test
    void aFailedStackUpdateDeletesTheReplacementAndRestoresThePriorArchive() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        update(resource, props("orders-archive-v2", BUS_ARN));
        clearInvocations(events);

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("orders-archive", resource.getPhysicalId());
        assertEquals(arn("orders-archive"), resource.getAttributes().get("Arn"));
        verify(events).deleteArchive("orders-archive-v2", REGION);
        verify(events, never()).deleteArchive("orders-archive", REGION);
    }

    @Test
    void updatingAnArchiveThatIsGoneFails() {
        StackResource resource = create(props("orders-archive", BUS_ARN));
        archives.clear();
        clearInvocations(events);

        AwsException error = assertThrows(AwsException.class,
                () -> update(resource, props("orders-archive", BUS_ARN)));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        verify(events, never()).createArchive(anyString(), anyString(), any(), any(), anyInt(), anyString());
    }

    @Test
    void deleteToleratesAnArchiveThatIsAlreadyGone() {
        doThrow(new AwsException("ResourceNotFoundException", "gone", 400))
                .when(events).deleteArchive("orders-archive", REGION);

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "orders-archive", REGION));
    }

    @Test
    void deletePropagatesOtherFailures() {
        AwsException unavailable = new AwsException("InternalException", "retry", 500);
        doThrow(unavailable).when(events).deleteArchive("orders-archive", REGION);

        assertSame(unavailable, assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, "orders-archive", REGION)));
    }

    @Test
    void deleteSkipsABlankPhysicalId() {
        provisioner.delete(TYPE, " ", REGION);
        provisioner.delete(TYPE, null, REGION);

        verifyNoInteractions(events);
    }

    private StackResource create(ObjectNode properties) {
        StackResource resource = resource();
        provisioner.provision(resource, properties, context(null));
        return resource;
    }

    private void update(StackResource resource, ObjectNode properties) {
        provisioner.provision(resource, properties, context(resource.getPhysicalId()));
    }

    private void commit(StackResource resource) {
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
    }

    private ProvisionContext context(String prior) {
        return new ProvisionContext(engine, REGION, ACCOUNT, STACK, prior);
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Archive");
        resource.setResourceType(TYPE);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private static ObjectNode props(String name, String sourceArn) {
        ObjectNode properties = MAPPER.createObjectNode();
        if (name != null) {
            properties.put("ArchiveName", name);
        }
        if (sourceArn != null) {
            properties.put("SourceArn", sourceArn);
        }
        return properties;
    }

    private static JsonNode pattern() {
        try {
            return MAPPER.readTree(PATTERN);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JsonNode noValue() {
        return MAPPER.createObjectNode().put("Ref", "AWS::NoValue");
    }

    private static boolean isNoValue(JsonNode node) {
        return node.isObject() && "AWS::NoValue".equals(node.path("Ref").asText());
    }

    private static Archive archive(String name, String sourceArn, String description, String eventPattern,
                                   int retentionDays) {
        Archive archive = new Archive();
        archive.setArchiveName(name);
        archive.setArchiveArn(arn(name));
        archive.setEventSourceArn(sourceArn);
        archive.setDescription(description);
        archive.setEventPattern(eventPattern);
        archive.setRetentionDays(retentionDays);
        return archive;
    }

    private static String arn(String name) {
        return "arn:aws:events:us-east-1:000000000000:archive/" + name;
    }
}
