package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LogsLogStreamCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private CloudWatchLogsService logs;
    private CloudFormationTemplateEngine engine;
    private LogsLogStreamCfnProvisioner provisioner;

    @BeforeEach
    void setUp() {
        logs = mock(CloudWatchLogsService.class);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        provisioner = new LogsLogStreamCfnProvisioner(logs);
    }

    @Test
    void createsARealStreamAndRefIsItsName() {
        StackResource resource = create("/app/logs", "events");

        assertEquals(Set.of("AWS::Logs::LogStream"), provisioner.resourceTypes());
        assertEquals("events", resource.getPhysicalId());
        assertFalse(resource.getAttributes().containsKey("Arn"));
        verify(logs).createLogStream("/app/logs", "events", REGION);
    }

    @Test
    void resolvesPropertyIntrinsicsBeforeCallingLogs() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.putObject("LogGroupName").put("Ref", "Group");
        properties.putObject("LogStreamName").put("Ref", "Name");
        when(engine.resolveNode(properties)).thenReturn(props("/resolved", "resolved-name"));
        StackResource resource = resource();

        provisioner.provision(resource, properties, context(null));

        assertEquals("resolved-name", resource.getPhysicalId());
        verify(logs).createLogStream("/resolved", "resolved-name", REGION);
    }

    @Test
    void omittedNameIsGeneratedAndBounded() {
        StackResource resource = resource();
        provisioner.provision(resource, props("group", null),
                new ProvisionContext(engine, REGION, ACCOUNT, "stack".repeat(150)));

        String name = resource.getPhysicalId();
        assertEquals(512, name.length());
        assertTrue(name.matches(".*-[0-9a-f]{12}"));
        verify(logs).createLogStream("group", name, REGION);
    }

    @Test
    void rejectsInvalidPropertiesBeforeCreatingAnything() {
        List<ObjectNode> invalid = List.of(
                MAPPER.createObjectNode(),
                props("", "events"),
                props("invalid:group", "events"),
                props("g".repeat(513), "events"),
                props("group", ""),
                props("group", "events:one"),
                props("group", "events*"),
                props("group", "s".repeat(513)),
                MAPPER.createObjectNode().put("LogGroupName", "group").put("LogStreamName", 5));

        for (ObjectNode properties : invalid) {
            AwsException error = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource(), properties, context(null)));
            assertEquals("ValidationError", error.getErrorCode());
        }
        verifyNoInteractions(logs);
    }

    @Test
    void unchangedExplicitNameDoesNotRecreateOrDeleteTheStream() {
        StackResource resource = create("group", "events");
        clearInvocations(logs);

        update(resource, "group", "events");
        commit(resource);

        assertEquals("events", resource.getPhysicalId());
        verifyNoInteractions(logs);
    }

    @Test
    void unchangedGeneratedNameIsStable() {
        StackResource resource = create("group", null);
        String name = resource.getPhysicalId();
        clearInvocations(logs);

        update(resource, "group", null);
        commit(resource);

        assertEquals(name, resource.getPhysicalId());
        verifyNoInteractions(logs);
    }

    @Test
    void removingAnExplicitNameCreatesAReplacementBeforeCleanup() {
        StackResource resource = create("group", "chosen");
        clearInvocations(logs);

        update(resource, "group", null);

        String replacement = resource.getPhysicalId();
        assertNotEquals("chosen", replacement);
        verify(logs).createLogStream("group", replacement, REGION);
        verify(logs, never()).deleteLogStream(anyString(), anyString(), anyString());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals("chosen", provisioner.updateCleanupPhysicalId(resource));

        commit(resource);
        verify(logs).deleteLogStream("group", "chosen", REGION);
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void changingGroupWithTheSameStreamNameStillRequiresReplacement() {
        StackResource resource = create("old-group", "events");
        clearInvocations(logs);

        update(resource, "new-group", "events");

        assertEquals("events", resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        verify(logs).createLogStream("new-group", "events", REGION);
        verify(logs, never()).deleteLogStream(anyString(), anyString(), anyString());

        commit(resource);
        verify(logs).deleteLogStream("old-group", "events", REGION);
        verify(logs, never()).deleteLogStream("new-group", "events", REGION);
    }

    @Test
    void failedStackUpdateRestoresThePriorGroupWithoutDeletingItsEvents() {
        StackResource resource = create("old-group", "events");
        update(resource, "new-group", "events");
        clearInvocations(logs);

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("events", resource.getPhysicalId());
        verify(logs).deleteLogStream("new-group", "events", REGION);
        verify(logs, never()).deleteLogStream("old-group", "events", REGION);
        assertTrue(provisioner.hasPendingRollbackCleanup(resource));
        clearInvocations(logs);
        provisioner.delete(resource, REGION);
        verify(logs).deleteLogStream("old-group", "events", REGION);
    }

    @Test
    void noOpUpdateCanRollbackWithoutTouchingLogEvents() {
        StackResource resource = create("group", "events");
        clearInvocations(logs);
        update(resource, "group", "events");

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("events", resource.getPhysicalId());
        verifyNoInteractions(logs);
    }

    @Test
    void rollbackRestoresGeneratedNameModeWhenExplicitNameEqualsGeneratedName() {
        StackResource resource = create("group", null);
        String generated = resource.getPhysicalId();
        clearInvocations(logs);
        update(resource, "group", generated);

        assertTrue(provisioner.rollbackUpdate(resource));
        update(resource, "group", null);

        assertEquals(generated, resource.getPhysicalId());
        verifyNoInteractions(logs);
    }

    @Test
    void committedExplicitNameModeMakesLaterOmissionReplaceTheStream() {
        StackResource resource = create("group", null);
        String generated = resource.getPhysicalId();
        update(resource, "group", generated);
        commit(resource);
        clearInvocations(logs);

        update(resource, "group", null);

        assertNotEquals(generated, resource.getPhysicalId());
        verify(logs).createLogStream("group", resource.getPhysicalId(), REGION);
    }

    @Test
    void duplicateCreateDoesNotClaimOrDeleteAnExistingStream() {
        AwsException duplicate = new AwsException("ResourceAlreadyExistsException", "exists", 400);
        doThrow(duplicate).when(logs).createLogStream("group", "taken", REGION);
        StackResource resource = resource();

        assertSame(duplicate, assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props("group", "taken"), context(null))));
        assertNull(resource.getPhysicalId());
        provisioner.delete(resource, REGION);

        verify(logs, never()).deleteLogStream(anyString(), anyString(), anyString());
        assertFalse(provisioner.hasPendingRollbackCleanup(resource));
    }

    @Test
    void duplicateReplacementLeavesPriorIdentityAndExternalStreamAlone() {
        StackResource resource = create("group", "old");
        doThrow(new AwsException("ResourceAlreadyExistsException", "exists", 400))
                .when(logs).createLogStream("group", "taken", REGION);
        clearInvocations(logs);

        assertThrows(AwsException.class, () -> update(resource, "group", "taken"));

        assertEquals("old", resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verify(logs, never()).deleteLogStream(anyString(), anyString(), anyString());
        provisioner.delete(resource, REGION);
        verify(logs).deleteLogStream("group", "old", REGION);
        verify(logs, never()).deleteLogStream("group", "taken", REGION);
    }

    @Test
    void updateReplacePolicyRetainKeepsOnlyTheDisplacedStream() {
        StackResource resource = create("old-group", "events");
        update(resource, "new-group", "events");
        resource.setUpdateReplacePolicy("Retain");
        clearInvocations(logs);

        assertNull(provisioner.updateCleanupPhysicalId(resource));
        commit(resource);
        provisioner.delete(resource, REGION);

        verify(logs).deleteLogStream("new-group", "events", REGION);
        verify(logs, never()).deleteLogStream("old-group", "events", REGION);
    }

    @Test
    void updateReplacePolicyRetainDoesNotKeepAFailedReplacement() {
        StackResource resource = create("old-group", "events");
        update(resource, "new-group", "events");
        resource.setUpdateReplacePolicy("Retain");
        clearInvocations(logs);

        assertTrue(provisioner.rollbackUpdate(resource));

        verify(logs).deleteLogStream("new-group", "events", REGION);
        verify(logs, never()).deleteLogStream("old-group", "events", REGION);
    }

    @Test
    void missingStreamIsToleratedDuringDelete() {
        StackResource resource = create("group", "events");
        doThrow(new AwsException("ResourceNotFoundException", "gone", 400))
                .when(logs).deleteLogStream("group", "events", REGION);

        assertDoesNotThrow(() -> provisioner.delete(resource, REGION));
    }

    @Test
    void deletePropagatesRealServiceFailures() {
        StackResource resource = create("group", "events");
        AwsException unavailable = new AwsException("ServiceUnavailableException", "retry", 500);
        doThrow(unavailable).when(logs).deleteLogStream("group", "events", REGION);

        assertSame(unavailable, assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION)));
    }

    @Test
    void cleanupRetriesAndKeepsTheCurrentStreamSafe() {
        StackResource resource = create("old-group", "events");
        update(resource, "new-group", "events");
        doThrow(new AwsException("ServiceUnavailableException", "retry", 500)).doNothing()
                .when(logs).deleteLogStream("old-group", "events", REGION);

        UpdateCleanupResult first = provisioner.completeUpdate(resource);
        assertTrue(first.applicable());
        assertFalse(first.complete());
        assertEquals(1, first.attempts());
        assertEquals("retry", first.failureReason());
        assertEquals("events", first.previousPhysicalId());

        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
        verify(logs, times(2)).deleteLogStream("old-group", "events", REGION);
        verify(logs, never()).deleteLogStream("new-group", "events", REGION);
    }

    @Test
    void exhaustedCleanupRemainsAddressableByStackDeletion() {
        StackResource resource = create("old-group", "events");
        update(resource, "new-group", "events");
        doThrow(new AwsException("ServiceUnavailableException", "retry", 500))
                .when(logs).deleteLogStream("old-group", "events", REGION);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(attempt, provisioner.completeUpdate(resource).attempts());
        }
        provisioner.clearUpdate(resource);
        doNothing().when(logs).deleteLogStream("old-group", "events", REGION);
        clearInvocations(logs);

        provisioner.delete(resource, REGION);

        verify(logs).deleteLogStream("old-group", "events", REGION);
        verify(logs).deleteLogStream("new-group", "events", REGION);
    }

    @Test
    void failedRollbackDeletionPreservesBothAddressesForStackDeletion() {
        StackResource resource = create("old-group", "events");
        update(resource, "new-group", "events");
        doThrow(new AwsException("ServiceUnavailableException", "retry", 500))
                .when(logs).deleteLogStream("new-group", "events", REGION);

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals("events", resource.getPhysicalId());
        assertTrue(provisioner.hasPendingRollbackCleanup(resource));
        doNothing().when(logs).deleteLogStream("new-group", "events", REGION);
        clearInvocations(logs);

        provisioner.delete(resource, REGION);

        verify(logs).deleteLogStream("new-group", "events", REGION);
        verify(logs).deleteLogStream("old-group", "events", REGION);
    }

    @Test
    void finishingFailedRollbackCleanupDoesNotForgetOwnershipOfThePriorStream() {
        StackResource resource = create("old-group", "events");
        update(resource, "new-group", "events");
        doThrow(new AwsException("ServiceUnavailableException", "retry", 500))
                .when(logs).deleteLogStream("new-group", "events", REGION);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        resource.setStatus("UPDATE_FAILED");
        doNothing().when(logs).deleteLogStream("new-group", "events", REGION);
        clearInvocations(logs);

        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);

        assertTrue(provisioner.hasPendingRollbackCleanup(resource));
        provisioner.delete(resource, REGION);
        assertFalse(provisioner.hasPendingRollbackCleanup(resource));
        verify(logs).deleteLogStream("new-group", "events", REGION);
        verify(logs).deleteLogStream("old-group", "events", REGION);
    }

    @Test
    void newUpdateCleansEarlierPendingReplacementBeforeRecordingAnother() {
        StackResource resource = create("first-group", "events");
        update(resource, "second-group", "events");
        doThrow(new AwsException("ServiceUnavailableException", "retry", 500))
                .when(logs).deleteLogStream("first-group", "events", REGION);
        provisioner.completeUpdate(resource);
        provisioner.clearUpdate(resource);
        doNothing().when(logs).deleteLogStream("first-group", "events", REGION);
        clearInvocations(logs);

        update(resource, "third-group", "events");
        commit(resource);

        verify(logs).deleteLogStream("first-group", "events", REGION);
        verify(logs).deleteLogStream("second-group", "events", REGION);
        verify(logs).createLogStream("third-group", "events", REGION);
        verify(logs, never()).deleteLogStream("third-group", "events", REGION);
    }

    @Test
    void persistedMetadataKeepsTheGroupWhenNamesAreEqual() {
        StackResource resource = create("old-group", "events");
        update(resource, "new-group", "events");
        StackResource restored = resource();
        restored.setPhysicalId(resource.getPhysicalId());
        restored.setAttributes(new HashMap<>(resource.getAttributes()));
        LogsLogStreamCfnProvisioner restarted = new LogsLogStreamCfnProvisioner(logs);
        clearInvocations(logs);

        assertTrue(restarted.rollbackUpdate(restored));
        restarted.delete(restored, REGION);

        verify(logs).deleteLogStream("new-group", "events", REGION);
        verify(logs).deleteLogStream("old-group", "events", REGION);
    }

    private StackResource create(String group, String name) {
        StackResource resource = resource();
        provisioner.provision(resource, props(group, name), context(null));
        return resource;
    }

    private void update(StackResource resource, String group, String name) {
        provisioner.provision(resource, props(group, name), context(resource.getPhysicalId()));
    }

    private void commit(StackResource resource) {
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
    }

    private ProvisionContext context(String prior) {
        return new ProvisionContext(engine, REGION, ACCOUNT, "my-stack", prior);
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("LogStream");
        resource.setResourceType("AWS::Logs::LogStream");
        return resource;
    }

    private static ObjectNode props(String group, String name) {
        ObjectNode properties = MAPPER.createObjectNode().put("LogGroupName", group);
        if (name != null) {
            properties.put("LogStreamName", name);
        }
        return properties;
    }
}
