package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SchedulerScheduleCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final SchedulerService scheduler = mock(SchedulerService.class);
    private final SchedulerScheduleCfnProvisioner provisioner = new SchedulerScheduleCfnProvisioner(scheduler);

    @BeforeEach
    void returnCreatedSchedules() {
        Map<String, Schedule> created = new HashMap<>();
        when(scheduler.getSchedule(any(), any(), eq(REGION))).thenAnswer(inv ->
                created.get(inv.getArgument(1) + "/" + inv.getArgument(0)));
        when(scheduler.createSchedule(any(), eq(REGION))).thenAnswer(inv -> {
            Schedule result = schedule(inv.getArgument(0));
            created.put(result.getGroupName() + "/" + result.getName(), result);
            return result;
        });
        when(scheduler.updateSchedule(any(), eq(REGION))).thenAnswer(inv -> schedule(inv.getArgument(0)));
    }

    @Test
    void mapsPropertiesAndKeepsNestedTargetValues() throws Exception {
        ObjectNode props = properties("schedule", "group");
        props.put("Description", "description");
        props.put("ScheduleExpressionTimezone", "Europe/Paris");
        props.put("StartDate", "2030-01-01T00:00:00Z");
        props.put("EndDate", "2031-01-01T00:00:00Z");
        props.put("KmsKeyArn", "arn:aws:kms:us-east-1:000000000000:key/test");
        props.put("State", "DISABLED");
        ObjectNode target = (ObjectNode) props.get("Target");
        target.putObject("RetryPolicy").put("MaximumRetryAttempts", 3).put("MaximumEventAgeInSeconds", 600);
        target.putObject("DeadLetterConfig").put("Arn", "arn:aws:sqs:us-east-1:000000000000:dlq");
        target.putObject("SqsParameters").put("MessageGroupId", "orders");
        target.putObject("EcsParameters").put("TaskDefinitionArn", "task")
                .putObject("NetworkConfiguration").putObject("AwsVpcConfiguration")
                .putArray("Subnets").add("subnet-a").add("subnet-b");
        StackResource resource = resource();

        provisioner.provision(resource, props, context(null));

        ArgumentCaptor<ScheduleRequest> request = ArgumentCaptor.forClass(ScheduleRequest.class);
        verify(scheduler).createSchedule(request.capture(), eq(REGION));
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), request.getValue().getStartDate());
        assertEquals(Instant.parse("2031-01-01T00:00:00Z"), request.getValue().getEndDate());
        assertEquals("description", request.getValue().getDescription());
        assertEquals("Europe/Paris", request.getValue().getScheduleExpressionTimezone());
        assertEquals(props.get("KmsKeyArn").asText(), request.getValue().getKmsKeyArn());
        assertEquals("DISABLED", request.getValue().getState());
        assertEquals(3, request.getValue().getTarget().getRetryPolicy().getMaximumRetryAttempts());
        assertEquals("orders", request.getValue().getTarget().getSqsParameters().getMessageGroupId());
        assertEquals(2, request.getValue().getTarget().getEcsParameters().getNetworkConfiguration()
                .getAwsvpcConfiguration().getSubnets().size());
        assertEquals("schedule", resource.getPhysicalId());
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule/group/schedule",
                resource.getAttributes().get("Arn"));
    }

    @Test
    void unnamedScheduleKeepsItsGeneratedNameOnUpdate() throws Exception {
        ObjectNode props = properties(null, null);
        StackResource resource = resource();
        provisioner.provision(resource, props, context(null));
        String generated = resource.getPhysicalId();
        assertTrue(generated.startsWith("stack-Schedule-"));
        assertTrue(generated.length() <= 64);
        when(scheduler.getSchedule(generated, "default", REGION))
                .thenReturn(schedule(request(generated, "default", "rate(5 minutes)")));

        props.put("ScheduleExpression", "rate(10 minutes)");
        provisioner.provision(resource, props, context(generated));
        provisioner.completeUpdate(resource);

        assertEquals(generated, resource.getPhysicalId());
        verify(scheduler, times(1)).createSchedule(any(), eq(REGION));
        verify(scheduler).updateSchedule(any(), eq(REGION));
        assertFalse(resource.getAttributes().containsKey("__FlociSchedulerUpdateSnapshot"));
    }

    @Test
    void removingExplicitNameGeneratesReplacementAndDefersOldDeletion() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("old", "default"), context(null));

        provisioner.provision(resource, properties(null, "default"), context("old"));

        String generated = resource.getPhysicalId();
        assertNotEquals("old", generated);
        assertTrue(generated.startsWith("stack-Schedule-"));
        assertTrue(generated.length() <= 64);
        assertTrue(resource.getAttributes().get("Arn").endsWith("/default/" + generated));
        verify(scheduler).createSchedule(argThat(r -> generated.equals(r.getName())), eq(REGION));
        verify(scheduler, never()).deleteSchedule("old", "default", REGION);
        assertEquals("default/old", provisioner.updateCleanupPhysicalId(resource));

        assertTrue(provisioner.completeUpdate(resource).complete());

        verify(scheduler).deleteSchedule("old", "default", REGION);
        verify(scheduler, never()).deleteSchedule(generated, "default", REGION);
        verify(scheduler, never()).updateSchedule(any(), eq(REGION));
    }

    @Test
    void nameReplacementWaitsForCommitAndSupportsRollback() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("old", "a"), context(null));
        provisioner.provision(resource, properties("new", "b"), context("old"));

        verify(scheduler, never()).deleteSchedule("old", "a", REGION);
        assertEquals("a/old", provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("old", resource.getPhysicalId());
        assertTrue(resource.getAttributes().get("Arn").endsWith("/a/old"));
        verify(scheduler).deleteSchedule("new", "b", REGION);
        verify(scheduler, never()).deleteSchedule("old", "a", REGION);
        assertEquals("a", resource.getAttributes().get("FlociSchedulerGroupName"));
        assertFalse(resource.getAttributes().containsKey("__FlociReplacementCleanup"));
    }

    @Test
    void inPlaceUpdateUsesRecreatedScheduleAtItsAddressAndRollsBackTheObservedConfiguration() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        Schedule recreated = schedule(request("same", "a", "rate(30 minutes)"));
        recreated.setState("DISABLED");
        when(scheduler.getSchedule("same", "a", REGION)).thenReturn(recreated);

        provisioner.provision(resource, properties("same", "a"), context("same"));

        verify(scheduler).updateSchedule(argThat(request -> "rate(5 minutes)".equals(request.getScheduleExpression())
                && "same".equals(request.getName()) && "a".equals(request.getGroupName())), eq(REGION));
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(scheduler).updateSchedule(argThat(request -> "rate(30 minutes)".equals(request.getScheduleExpression())
                && "DISABLED".equals(request.getState())), eq(REGION));
        assertEquals("same", resource.getPhysicalId());
        assertFalse(provisioner.retainsFailedUpdateState(resource));
    }

    @Test
    void nameReplacementCreatesDestinationWhenTheOldAddressIsAlreadyGone() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("old", "a"), context(null));
        doThrow(new AwsException("ResourceNotFoundException", "current schedule absent", 404))
                .when(scheduler).deleteSchedule("old", "a", REGION);

        provisioner.provision(resource, properties("new", "b"), context("old"));

        assertEquals("new", resource.getPhysicalId());
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(scheduler).createSchedule(argThat(request -> "new".equals(request.getName())), eq(REGION));
        verify(scheduler).deleteSchedule("old", "a", REGION);
        verify(scheduler, never()).getSchedule("old", "a", REGION);
    }

    @Test
    void committedRetainReplacementKeepsTheOldAddressAndClearsCleanupTracking() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("old", "a"), context(null));
        resource.setUpdateReplacePolicy("Retain");
        provisioner.provision(resource, properties("new", "b"), context("old"));
        JsonNode cleanup = mapper.readTree(resource.getAttributes().get("__FlociReplacementCleanup"));
        assertEquals("a/old", cleanup.path("priorPhysicalId").asText());
        assertTrue(cleanup.path("displaced").get(0).path("retainable").asBoolean());
        assertNull(provisioner.updateCleanupPhysicalId(resource));

        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);

        assertFalse(resource.getAttributes().containsKey("__FlociReplacementCleanup"));
        assertEquals("new", resource.getPhysicalId());
        assertEquals("b", resource.getAttributes().get("FlociSchedulerGroupName"));
        verify(scheduler, never()).deleteSchedule("old", "a", REGION);
    }

    @Test
    void failedReplacementMergeKeepsTheOrphansAddressForDeletionRetry() throws Exception {
        StackResource previous = resource();
        provisioner.provision(previous, properties("old", "a"), context(null));
        StackResource attempted = resource();
        attempted.setPhysicalId(previous.getPhysicalId());
        attempted.setAttributes(new HashMap<>(previous.getAttributes()));
        provisioner.provision(attempted, properties("new", "b"), context("old"));
        doThrow(new AwsException("InternalServerException", "replacement deletion unavailable", 500))
                .when(scheduler).deleteSchedule("new", "b", REGION);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(attempted));

        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        assertEquals("old", previous.getPhysicalId());
        assertEquals("b/new", provisioner.updateCleanupPhysicalId(previous));
        assertFalse(provisioner.completeDeleteCleanup(previous).complete());
        assertEquals("b/new", provisioner.updateCleanupPhysicalId(previous));
        doNothing().when(scheduler).deleteSchedule("new", "b", REGION);
        assertTrue(provisioner.completeDeleteCleanup(previous).complete());
        verify(scheduler, times(3)).deleteSchedule("new", "b", REGION);
        verify(scheduler, never()).deleteSchedule("old", "a", REGION);
    }

    @Test
    void recreatingAnOldOrphanStartsFreshCleanupAndCanMergeTheFailedAttempt() throws Exception {
        StackResource previous = resource();
        provisioner.provision(previous, properties("current", "default"), context(null));
        ReplacementCleanup.recordOrphan(previous, "default/reused", previous.getResourceType(), REGION);
        ObjectNode oldCleanup = (ObjectNode) mapper.readTree(previous.getAttributes().get("__FlociReplacementCleanup"));
        ((ObjectNode) oldCleanup.path("displaced").get(0)).put("cleanupAttempts", 3);
        previous.getAttributes().put("__FlociReplacementCleanup", oldCleanup.toString());
        StackResource attempted = resource();
        attempted.setPhysicalId(previous.getPhysicalId());
        attempted.setAttributes(new HashMap<>(previous.getAttributes()));
        provisioner.provision(attempted, properties("reused", "default"), context("current"));
        doThrow(new AwsException("InternalServerException", "replacement delete unavailable", 500))
                .when(scheduler).deleteSchedule("reused", "default", REGION);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(attempted));

        JsonNode newCleanup = mapper.readTree(attempted.getAttributes().get("__FlociReplacementCleanup"));
        assertEquals(0, newCleanup.path("displaced").get(0).path("cleanupAttempts").asInt());
        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);
        JsonNode merged = mapper.readTree(previous.getAttributes().get("__FlociReplacementCleanup"));
        assertEquals(1, merged.path("displaced").size());
        assertEquals("default/reused", merged.path("displaced").get(0).path("physicalId").asText());
        assertEquals(0, merged.path("displaced").get(0).path("cleanupAttempts").asInt());
        doNothing().when(scheduler).deleteSchedule("reused", "default", REGION);
        assertTrue(provisioner.completeDeleteCleanup(previous).complete());
        verify(scheduler, times(2)).deleteSchedule("reused", "default", REGION);
    }

    @Test
    void mergingCopiedCleanupDebtPreservesItsExhaustedAttemptCount() throws Exception {
        StackResource previous = resource();
        provisioner.provision(previous, properties("current", "default"), context(null));
        ReplacementCleanup.recordOrphan(previous, "default/unrelated", previous.getResourceType(), REGION);
        ObjectNode cleanup = (ObjectNode) mapper.readTree(previous.getAttributes().get("__FlociReplacementCleanup"));
        ((ObjectNode) cleanup.path("displaced").get(0)).put("cleanupAttempts", 3);
        previous.getAttributes().put("__FlociReplacementCleanup", cleanup.toString());
        StackResource attempted = resource();
        attempted.setPhysicalId(previous.getPhysicalId());
        attempted.setAttributes(new HashMap<>(previous.getAttributes()));

        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        JsonNode merged = mapper.readTree(previous.getAttributes().get("__FlociReplacementCleanup"));
        assertEquals(1, merged.path("displaced").size());
        assertEquals("default/unrelated", merged.path("displaced").get(0).path("physicalId").asText());
        assertEquals(3, merged.path("displaced").get(0).path("cleanupAttempts").asInt());
        verify(scheduler, never()).deleteSchedule(any(), any(), eq(REGION));
    }

    @Test
    void malformedCleanupRejectsReplacementBeforeCreatingADestination() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("current", "default"), context(null));
        resource.getAttributes().put("__FlociReplacementCleanup", "{\"displaced\":{}}");
        Map<String, String> before = Map.copyOf(resource.getAttributes());
        ObjectNode desired = properties("new", "default");

        assertThrows(IllegalStateException.class, () -> provisioner.provision(resource, desired, context("current")));

        assertEquals("current", resource.getPhysicalId());
        assertEquals(before, resource.getAttributes());
        verify(scheduler, never()).createSchedule(argThat(request -> "new".equals(request.getName())), eq(REGION));
        verify(scheduler, never()).updateSchedule(any(), eq(REGION));
        verify(scheduler, never()).deleteSchedule(any(), any(), eq(REGION));
    }

    @Test
    void malformedCleanupRejectsInPlaceUpdateBeforeWritingTheSchedule() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("current", "default"), context(null));
        resource.getAttributes().put("__FlociReplacementCleanup", "{\"displaced\":{}}");
        Map<String, String> before = Map.copyOf(resource.getAttributes());
        ObjectNode desired = properties("current", "default").put("ScheduleExpression", "rate(10 minutes)");

        assertThrows(IllegalStateException.class, () -> provisioner.provision(resource, desired, context("current")));

        assertEquals("current", resource.getPhysicalId());
        assertEquals(before, resource.getAttributes());
        verify(scheduler, times(1)).createSchedule(any(), eq(REGION));
        verify(scheduler, never()).updateSchedule(any(), eq(REGION));
        verify(scheduler, never()).deleteSchedule(any(), any(), eq(REGION));
    }

    @Test
    void malformedCleanupDoesNotPartiallyChangeThePreviousResourceOnMerge() throws Exception {
        StackResource previous = resource();
        provisioner.provision(previous, properties("current", "default"), context(null));
        ReplacementCleanup.recordOrphan(previous, "default/orphan", previous.getResourceType(), REGION);
        StackResource attempted = resource();
        attempted.setPhysicalId(previous.getPhysicalId());
        attempted.setAttributes(new HashMap<>(previous.getAttributes()));
        attempted.getAttributes().put("__FlociReplacementCleanup", "{\"displaced\":{}}");
        Map<String, String> before = Map.copyOf(previous.getAttributes());

        assertThrows(IllegalStateException.class, () ->
                provisioner.mergeFailedUpdateResourceTracking(previous, attempted));

        assertEquals(before, previous.getAttributes());
        verify(scheduler, never()).deleteSchedule(any(), any(), eq(REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "null", "\"invalid\"", "[]"})
    void malformedCleanupEntryRejectsCreateBeforeAnyServiceCall(String entry) throws Exception {
        StackResource resource = resource();
        appendMalformedCleanupEntry(resource, entry);

        assertMalformedCleanupRejectedWithoutMutation(resource, properties("new", "default"), context(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "null", "\"invalid\"", "[]"})
    void malformedCleanupEntryRejectsReplacementBeforeAnyServiceCall(String entry) throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("current", "default"), context(null));
        appendMalformedCleanupEntry(resource, entry);

        assertMalformedCleanupRejectedWithoutMutation(resource, properties("new", "default"), context("current"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "null", "\"invalid\"", "[]"})
    void malformedCleanupEntryRejectsInPlaceUpdateBeforeAnyServiceCall(String entry) throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("current", "default"), context(null));
        appendMalformedCleanupEntry(resource, entry);

        assertMalformedCleanupRejectedWithoutMutation(resource,
                properties("current", "default").put("ScheduleExpression", "rate(10 minutes)"), context("current"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "null", "\"invalid\"", "[]"})
    void malformedCleanupEntryPreservesPendingRollbackBeforeAnotherUpdate(String entry) throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("current", "default"), context(null));
        provisioner.provision(resource,
                properties("current", "default").put("ScheduleExpression", "rate(10 minutes)"), context("current"));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        appendMalformedCleanupEntry(resource, entry);

        assertMalformedCleanupRejectedWithoutMutation(resource,
                properties("current", "default").put("ScheduleExpression", "rate(15 minutes)"), context("current"));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "null", "\"invalid\"", "[]"})
    void malformedCleanupEntryRejectsMergeWithoutChangingExistingDebt(String entry) throws Exception {
        StackResource previous = resource();
        provisioner.provision(previous, properties("current", "default"), context(null));
        ReplacementCleanup.recordOrphan(previous, "default/orphan", previous.getResourceType(), REGION);
        StackResource attempted = resource();
        attempted.setPhysicalId(previous.getPhysicalId());
        attempted.setAttributes(new HashMap<>(previous.getAttributes()));
        appendMalformedCleanupEntry(attempted, entry);
        Map<String, String> previousBefore = Map.copyOf(previous.getAttributes());
        Map<String, String> attemptedBefore = Map.copyOf(attempted.getAttributes());
        clearInvocations(scheduler);

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> provisioner.mergeFailedUpdateResourceTracking(previous, attempted)),
                () -> assertEquals(previousBefore, previous.getAttributes()),
                () -> assertEquals(attemptedBefore, attempted.getAttributes()),
                () -> verifyNoInteractions(scheduler));
    }

    private void appendMalformedCleanupEntry(StackResource resource, String entry) throws Exception {
        ReplacementCleanup.recordOrphan(resource, "default/orphan", resource.getResourceType(), REGION);
        ObjectNode cleanup = (ObjectNode) mapper.readTree(resource.getAttributes().get("__FlociReplacementCleanup"));
        ((ObjectNode) cleanup.path("displaced").get(0)).put("cleanupAttempts", 3)
                .put("cleanupFailureReason", "historical deletion failed");
        cleanup.withArray("displaced").add(mapper.readTree(entry));
        resource.getAttributes().put("__FlociReplacementCleanup", cleanup.toString());
        resource.getAttributes().put("ExistingAttribute", "unchanged");
    }

    private void assertMalformedCleanupRejectedWithoutMutation(StackResource resource, ObjectNode desired,
                                                              ProvisionContext ctx) {
        Map<String, String> before = Map.copyOf(resource.getAttributes());
        String physicalIdBefore = resource.getPhysicalId();
        clearInvocations(scheduler);

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> provisioner.provision(resource, desired, ctx)),
                () -> assertEquals(physicalIdBefore, resource.getPhysicalId()),
                () -> assertEquals(before, resource.getAttributes()),
                () -> verifyNoInteractions(scheduler));
    }

    @Test
    void groupMoveKeepsRefAndDoesNotHonorReplacementRetain() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        resource.setUpdateReplacePolicy("Retain");
        Schedule previous = schedule(request("same", "a", "rate(5 minutes)"));
        previous.setState("ENABLED");
        when(scheduler.getSchedule("same", "a", REGION)).thenReturn(previous);
        provisioner.provision(resource, properties("same", "b"), context("same"));

        InOrder order = inOrder(scheduler);
        order.verify(scheduler).updateSchedule(argThat(r -> "a".equals(r.getGroupName())
                && "DISABLED".equals(r.getState())), eq(REGION));
        order.verify(scheduler).createSchedule(argThat(r -> "b".equals(r.getGroupName())), eq(REGION));

        assertEquals("same", resource.getPhysicalId());
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);

        verify(scheduler).deleteSchedule("same", "a", REGION);
        verify(scheduler, never()).deleteSchedule("same", "b", REGION);
    }

    @Test
    void failedGroupDestinationCreationRestoresOriginalEnabledState() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        Schedule previous = schedule(request("same", "a", "rate(5 minutes)"));
        previous.setState("ENABLED");
        when(scheduler.getSchedule("same", "a", REGION)).thenReturn(previous);
        when(scheduler.createSchedule(argThat(r -> "b".equals(r.getGroupName())), eq(REGION)))
                .thenThrow(new AwsException("ConflictException", "destination owned elsewhere", 409));

        assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties("same", "b"), context("same")));

        ArgumentCaptor<ScheduleRequest> requests = ArgumentCaptor.forClass(ScheduleRequest.class);
        verify(scheduler, times(2)).updateSchedule(requests.capture(), eq(REGION));
        assertEquals("DISABLED", requests.getAllValues().getFirst().getState());
        assertEquals("ENABLED", requests.getAllValues().getLast().getState());
        verify(scheduler, never()).deleteSchedule("same", "b", REGION);
        assertEquals("same", resource.getPhysicalId());
    }

    @Test
    void failedGroupRollbackDeleteRestoresOriginalAndKeepsDisabledOrphanForCleanup() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        Schedule original = schedule(request("same", "a", "rate(5 minutes)"));
        original.setState("ENABLED");
        when(scheduler.getSchedule("same", "a", REGION)).thenReturn(original);
        provisioner.provision(resource, properties("same", "b"), context("same"));
        Schedule destination = schedule(request("same", "b", "rate(5 minutes)"));
        destination.setState("ENABLED");
        when(scheduler.getSchedule("same", "b", REGION)).thenReturn(destination);
        doThrow(new AwsException("InternalServerException", "delete unavailable", 500)).doNothing()
                .when(scheduler).deleteSchedule("same", "b", REGION);

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));

        assertEquals("same", resource.getPhysicalId());
        assertTrue(resource.getAttributes().get("Arn").endsWith("/a/same"));
        InOrder order = inOrder(scheduler);
        order.verify(scheduler).updateSchedule(argThat(r -> "b".equals(r.getGroupName())
                && "DISABLED".equals(r.getState())), eq(REGION));
        order.verify(scheduler).deleteSchedule("same", "b", REGION);
        order.verify(scheduler).updateSchedule(argThat(r -> "a".equals(r.getGroupName())
                && "ENABLED".equals(r.getState())), eq(REGION));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(scheduler, times(2)).deleteSchedule("same", "b", REGION);
        verify(scheduler, never()).deleteSchedule("same", "a", REGION);
    }

    @Test
    void failedGroupMoveWithPriorOrphanNeverTouchesAnExternalDestination() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        Schedule original = schedule(request("same", "a", "rate(5 minutes)"));
        original.setState("ENABLED");
        when(scheduler.getSchedule("same", "a", REGION)).thenReturn(original);
        provisioner.provision(resource, properties("same", "b"), context("same"));
        Schedule orphan = schedule(request("same", "b", "rate(5 minutes)"));
        orphan.setState("ENABLED");
        when(scheduler.getSchedule("same", "b", REGION)).thenReturn(orphan);
        doThrow(new AwsException("InternalServerException", "delete unavailable", 500)).doNothing()
                .when(scheduler).deleteSchedule("same", "b", REGION);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals("b/same", provisioner.updateCleanupPhysicalId(resource));

        Schedule external = schedule(request("same", "c", "rate(1 minute)"));
        external.setState("ENABLED");
        when(scheduler.getSchedule("same", "c", REGION)).thenReturn(external);
        when(scheduler.createSchedule(argThat(r -> "c".equals(r.getGroupName())), eq(REGION)))
                .thenThrow(new AwsException("ConflictException", "destination owned elsewhere", 409));

        assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties("same", "c"), context("same")));

        verify(scheduler, never()).getSchedule("same", "c", REGION);
        verify(scheduler, never()).updateSchedule(argThat(r -> "c".equals(r.getGroupName())), eq(REGION));
        verify(scheduler, never()).deleteSchedule("same", "c", REGION);
        ArgumentCaptor<ScheduleRequest> requests = ArgumentCaptor.forClass(ScheduleRequest.class);
        verify(scheduler, times(4)).updateSchedule(argThat(r -> "a".equals(r.getGroupName())), eq(REGION));
        verify(scheduler, times(5)).updateSchedule(requests.capture(), eq(REGION));
        assertEquals("a", requests.getAllValues().getLast().getGroupName());
        assertEquals("ENABLED", requests.getAllValues().getLast().getState());
        assertEquals("same", resource.getPhysicalId());
        assertEquals("a", resource.getAttributes().get("FlociSchedulerGroupName"));
        assertEquals("b/same", provisioner.updateCleanupPhysicalId(resource));

        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(scheduler, times(2)).deleteSchedule("same", "b", REGION);
        verify(scheduler, never()).deleteSchedule("same", "a", REGION);
    }

    @Test
    void aFailedEagerRestoreKeepsTheSnapshotForRollbackRetry() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        Schedule original = schedule(request("same", "a", "rate(5 minutes)"));
        original.setState("ENABLED");
        when(scheduler.getSchedule("same", "a", REGION)).thenReturn(original);
        when(scheduler.createSchedule(argThat(r -> "b".equals(r.getGroupName())), eq(REGION)))
                .thenThrow(new AwsException("ConflictException", "destination exists", 409));
        when(scheduler.updateSchedule(argThat(r -> "a".equals(r.getGroupName())
                && "ENABLED".equals(r.getState())), eq(REGION)))
                .thenThrow(new AwsException("InternalServerException", "restore failed", 500))
                .thenAnswer(inv -> schedule(inv.getArgument(0)));

        assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties("same", "b"), context("same")));

        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertTrue(resource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        verify(scheduler, times(2)).updateSchedule(argThat(r -> "a".equals(r.getGroupName())
                && "ENABLED".equals(r.getState())), eq(REGION));
    }

    @Test
    void aNewUpdateRecoversPendingRollbackBeforeTakingAnotherSnapshot() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        AtomicReference<Schedule> current = trackScheduleUpdates("same", "a");
        provisioner.provision(resource, properties("same", "a").put("ScheduleExpression", "rate(10 minutes)"),
                context("same"));
        when(scheduler.updateSchedule(argThat(r -> "rate(5 minutes)".equals(r.getScheduleExpression())), eq(REGION)))
                .thenThrow(new AwsException("InternalServerException", "restore failed", 500))
                .thenAnswer(inv -> {
                    Schedule restored = schedule(inv.getArgument(0));
                    current.set(restored);
                    return restored;
                });

        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertEquals("rate(10 minutes)", current.get().getScheduleExpression());

        provisioner.provision(resource, properties("same", "a").put("ScheduleExpression", "rate(15 minutes)"),
                context("same"));
        assertEquals("rate(15 minutes)", current.get().getScheduleExpression());
        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("rate(5 minutes)", current.get().getScheduleExpression());
        assertFalse(provisioner.retainsFailedUpdateState(resource));
    }

    @Test
    void anUnrecoverableSnapshotBlocksANewUpdateWithoutLosingTheOriginalState() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        AtomicReference<Schedule> current = trackScheduleUpdates("same", "a");
        provisioner.provision(resource, properties("same", "a").put("ScheduleExpression", "rate(10 minutes)"),
                context("same"));
        when(scheduler.updateSchedule(argThat(r -> "rate(5 minutes)".equals(r.getScheduleExpression())), eq(REGION)))
                .thenThrow(new AwsException("InternalServerException", "restore failed", 500))
                .thenThrow(new AwsException("InternalServerException", "restore still unavailable", 500))
                .thenAnswer(inv -> {
                    Schedule restored = schedule(inv.getArgument(0));
                    current.set(restored);
                    return restored;
                });
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        String snapshot = resource.getAttributes().get("__FlociSchedulerUpdateSnapshot");

        assertThrows(IllegalStateException.class,
                () -> provisioner.provision(resource,
                        properties("same", "a").put("ScheduleExpression", "rate(15 minutes)"), context("same")));

        assertEquals(snapshot, resource.getAttributes().get("__FlociSchedulerUpdateSnapshot"));
        assertEquals("rate(10 minutes)", current.get().getScheduleExpression());
        verify(scheduler, never()).updateSchedule(argThat(r -> "rate(15 minutes)".equals(r.getScheduleExpression())),
                eq(REGION));
        provisioner.provision(resource, properties("same", "a").put("ScheduleExpression", "rate(15 minutes)"),
                context("same"));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals("rate(5 minutes)", current.get().getScheduleExpression());
    }

    @Test
    void cleanupOfAnotherResourceCannotDiscardAFailedScheduleRollback() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        trackScheduleUpdates("same", "a");
        provisioner.provision(resource, properties("same", "a").put("ScheduleExpression", "rate(10 minutes)"),
                context("same"));
        when(scheduler.updateSchedule(argThat(r -> "rate(5 minutes)".equals(r.getScheduleExpression())), eq(REGION)))
                .thenThrow(new AwsException("InternalServerException", "restore failed", 500));
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        resource.setStatus("UPDATE_FAILED");
        String snapshot = resource.getAttributes().get("__FlociSchedulerUpdateSnapshot");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> provisioner.completeUpdate(resource));

        assertTrue(failure.getMessage().contains("Schedule rollback is still pending"));
        assertEquals(snapshot, resource.getAttributes().get("__FlociSchedulerUpdateSnapshot"));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
    }

    @Test
    void deleteCleanupPreservesPendingSnapshotUntilTheScheduleIsActuallyDeleted() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "a"), context(null));
        trackScheduleUpdates("same", "a");
        provisioner.provision(resource, properties("same", "a").put("ScheduleExpression", "rate(10 minutes)"),
                context("same"));
        when(scheduler.updateSchedule(argThat(r -> "rate(5 minutes)".equals(r.getScheduleExpression())), eq(REGION)))
                .thenThrow(new AwsException("InternalServerException", "restore failed", 500));
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        resource.setStatus("UPDATE_FAILED");
        String snapshot = resource.getAttributes().get("__FlociSchedulerUpdateSnapshot");

        provisioner.completeDeleteCleanup(resource);
        provisioner.clearDeleteCleanup(resource);
        assertEquals(snapshot, resource.getAttributes().get("__FlociSchedulerUpdateSnapshot"));
        doThrow(new AwsException("InternalServerException", "delete failed", 500)).doNothing()
                .when(scheduler).deleteSchedule("same", "a", REGION);
        assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION));
        assertEquals(snapshot, resource.getAttributes().get("__FlociSchedulerUpdateSnapshot"));

        provisioner.delete(resource, REGION);

        assertFalse(provisioner.retainsFailedUpdateState(resource));
        verify(scheduler, times(1)).updateSchedule(
                argThat(r -> "rate(5 minutes)".equals(r.getScheduleExpression())), eq(REGION));
    }

    @Test
    void deleteCleanupStillReportsDisplacedScheduleFailureAndHonorsRetain() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("old", "a"), context(null));
        provisioner.provision(resource, properties("new", "b"), context("old"));
        doThrow(new AwsException("InternalServerException", "delete unavailable", 500)).doNothing()
                .when(scheduler).deleteSchedule("old", "a", REGION);

        UpdateCleanupResult failed = provisioner.completeDeleteCleanup(resource);

        assertFalse(failed.complete());
        assertEquals(1, failed.attempts());
        assertEquals("delete unavailable", failed.failureReason());
        assertEquals("a/old", provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeDeleteCleanup(resource).complete());
        verify(scheduler, times(2)).deleteSchedule("old", "a", REGION);
        verify(scheduler, never()).deleteSchedule("new", "b", REGION);

        StackResource retained = resource();
        retained.setUpdateReplacePolicy("Retain");
        provisioner.provision(retained, properties("retained-old", "a"), context(null));
        provisioner.provision(retained, properties("retained-new", "b"), context("retained-old"));
        assertTrue(provisioner.completeDeleteCleanup(retained).complete());
        verify(scheduler, never()).deleteSchedule("retained-old", "a", REGION);
    }

    @Test
    void exhaustedDeleteCleanupKeepsEveryOwedAddressForTheNextStackDelete() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("current", "a"), context(null));
        ReplacementCleanup.recordOrphan(resource, "b/orphan-one", resource.getResourceType(), REGION);
        ReplacementCleanup.recordOrphan(resource, "c/orphan-two", resource.getResourceType(), REGION);
        doThrow(new AwsException("InternalServerException", "delete unavailable", 500))
                .when(scheduler).deleteSchedule("orphan-one", "b", REGION);
        doThrow(new AwsException("InternalServerException", "delete unavailable", 500))
                .when(scheduler).deleteSchedule("orphan-two", "c", REGION);

        for (int attempt = 1; attempt <= 3; attempt++) {
            UpdateCleanupResult failed = provisioner.completeDeleteCleanup(resource);
            assertFalse(failed.complete());
            assertEquals(attempt, failed.attempts());
        }
        provisioner.clearDeleteCleanup(resource);

        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals("b/orphan-one", provisioner.updateCleanupPhysicalId(resource));
        assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
        assertTrue(provisioner.hasReplacementUpdate(resource));
        doNothing().when(scheduler).deleteSchedule("orphan-one", "b", REGION);
        UpdateCleanupResult partlyRecovered = provisioner.completeDeleteCleanup(resource);
        assertFalse(partlyRecovered.complete());
        assertEquals("c/orphan-two", partlyRecovered.previousPhysicalId());
        assertEquals("c/orphan-two", provisioner.updateCleanupPhysicalId(resource));
        doNothing().when(scheduler).deleteSchedule("orphan-two", "c", REGION);
        assertTrue(provisioner.completeDeleteCleanup(resource).complete());
        provisioner.clearDeleteCleanup(resource);
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verify(scheduler, times(1)).deleteSchedule("current", "a", REGION);
    }

    @Test
    void deleteRetryRetainsTheOldNameButStillDeletesFailedUpdateOrphans() throws Exception {
        StackResource resource = resource();
        resource.setUpdateReplacePolicy("Retain");
        provisioner.provision(resource, properties("retained-old", "a"), context(null));
        provisioner.provision(resource, properties("current", "b"), context("retained-old"));
        ReplacementCleanup.recordOrphan(resource, "b/current", resource.getResourceType(), REGION);
        ReplacementCleanup.recordOrphan(resource, "c/orphan", resource.getResourceType(), REGION);
        doThrow(new AwsException("InternalServerException", "delete unavailable", 500))
                .when(scheduler).deleteSchedule("orphan", "c", REGION);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertFalse(provisioner.completeDeleteCleanup(resource).complete());
        }
        provisioner.clearDeleteCleanup(resource);
        assertEquals("true", resource.getAttributes().get("__FlociSchedulerNameReplacement"));
        assertEquals("c/orphan", provisioner.updateCleanupPhysicalId(resource));
        verify(scheduler, never()).deleteSchedule("retained-old", "a", REGION);
        verify(scheduler, never()).deleteSchedule("current", "b", REGION);

        doNothing().when(scheduler).deleteSchedule("orphan", "c", REGION);
        assertTrue(provisioner.completeDeleteCleanup(resource).complete());
        provisioner.clearDeleteCleanup(resource);
        assertFalse(resource.getAttributes().containsKey("__FlociSchedulerNameReplacement"));
        provisioner.delete(resource, REGION);
        verify(scheduler).deleteSchedule("current", "b", REGION);
        verify(scheduler, never()).deleteSchedule("retained-old", "a", REGION);
    }

    @Test
    void nameReplacementHonorsRetain() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("old", "a"), context(null));
        resource.setUpdateReplacePolicy("Retain");
        provisioner.provision(resource, properties("new", "b"), context("old"));

        assertTrue(provisioner.completeUpdate(resource).complete());

        verify(scheduler, never()).deleteSchedule("old", "a", REGION);
    }

    @Test
    void inPlaceRollbackRestoresPriorDatesStateAndTarget() throws Exception {
        StackResource resource = resource();
        ObjectNode oldProps = properties("same", "default");
        provisioner.provision(resource, oldProps, context(null));
        Schedule old = schedule(request("same", "default", "rate(5 minutes)"));
        old.setStartDate(Instant.parse("2030-01-01T00:00:00Z"));
        old.setState("DISABLED");
        when(scheduler.getSchedule("same", "default", REGION)).thenReturn(old);
        ObjectNode newProps = properties("same", "default").put("ScheduleExpression", "rate(10 minutes)");
        provisioner.provision(resource, newProps, context("same"));

        assertTrue(provisioner.rollbackUpdate(resource));

        ArgumentCaptor<ScheduleRequest> requests = ArgumentCaptor.forClass(ScheduleRequest.class);
        verify(scheduler, times(2)).updateSchedule(requests.capture(), eq(REGION));
        ScheduleRequest restored = requests.getAllValues().get(1);
        assertEquals("rate(5 minutes)", restored.getScheduleExpression());
        assertEquals("DISABLED", restored.getState());
        assertEquals(old.getStartDate(), restored.getStartDate());
    }

    @Test
    void missingRequiredPropertiesFailBeforeCreating() throws Exception {
        for (String property : new String[] {"FlexibleTimeWindow", "Target", "ScheduleExpression"}) {
            ObjectNode props = properties("same", "default");
            props.remove(property);
            AwsException failure = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource(), props, context(null)));
            assertEquals("ValidationError", failure.getErrorCode());
        }
        verify(scheduler, never()).createSchedule(any(), eq(REGION));
    }

    @Test
    void requiredObjectNullAndInvalidShapesFailBeforeCreating() throws Exception {
        for (String property : new String[] {"FlexibleTimeWindow", "Target"}) {
            for (JsonNode invalid : new JsonNode[] {mapper.nullNode(), mapper.createArrayNode(),
                    mapper.valueToTree("not-an-object"), mapper.valueToTree(false)}) {
                ObjectNode props = properties("same", "default");
                props.set(property, invalid);
                assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context(null)),
                        property + ": " + invalid);
            }
        }
        verify(scheduler, never()).createSchedule(any(), eq(REGION));
    }

    @Test
    void unsupportedTargetParametersAreRejectedInsteadOfDiscarded() throws Exception {
        ObjectNode props = properties("same", "default");
        ((ObjectNode) props.get("Target")).putObject("KinesisParameters").put("PartitionKey", "key");
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context(null)));
        verify(scheduler, never()).createSchedule(any(), eq(REGION));
    }

    @Test
    void blankExplicitNameOrGroupIsRejectedInsteadOfUsingADefault() throws Exception {
        for (String property : new String[] {"Name", "GroupName"}) {
            ObjectNode props = properties("same", "default").put(property, " ");
            assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context(null)));
        }
        verify(scheduler, never()).createSchedule(any(), eq(REGION));
    }

    @Test
    void invalidDateOrStateFailsBeforeMutatingTheExistingSchedule() throws Exception {
        for (Map.Entry<String, String> bad : Map.of("StartDate", "tomorrow", "State", "ACTIVE").entrySet()) {
            ObjectNode props = properties("same", "default").put(bad.getKey(), bad.getValue());
            assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context("same")));
        }
        verify(scheduler, never()).updateSchedule(any(), eq(REGION));
    }

    @Test
    void deleteUsesTheStoredGroupAndToleratesOnlyNotFound() throws Exception {
        StackResource resource = resource();
        provisioner.provision(resource, properties("same", "custom"), context(null));
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(scheduler).deleteSchedule("same", "custom", REGION);
        provisioner.delete(resource, REGION);
        doThrow(new AwsException("InternalServerException", "failed", 500))
                .when(scheduler).deleteSchedule("same", "custom", REGION);
        assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION));
    }

    private ObjectNode properties(String name, String group) throws Exception {
        ObjectNode props = (ObjectNode) mapper.readTree("""
                {"ScheduleExpression":"rate(5 minutes)","FlexibleTimeWindow":{"Mode":"OFF"},
                 "Target":{"Arn":"arn:aws:sqs:us-east-1:000000000000:queue",
                  "RoleArn":"arn:aws:iam::000000000000:role/scheduler","Input":"payload"}}
                """);
        if (name != null) {
            props.put("Name", name);
        }
        if (group != null) {
            props.put("GroupName", group);
        }
        return props;
    }

    private AtomicReference<Schedule> trackScheduleUpdates(String name, String group) {
        AtomicReference<Schedule> current = new AtomicReference<>(schedule(request(name, group, "rate(5 minutes)")));
        when(scheduler.getSchedule(name, group, REGION)).thenAnswer(inv -> current.get());
        when(scheduler.updateSchedule(any(), eq(REGION))).thenAnswer(inv -> {
            Schedule updated = schedule(inv.getArgument(0));
            current.set(updated);
            return updated;
        });
        return current;
    }

    private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, REGION, "000000000000", "stack", prior);
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Schedule");
        resource.setResourceType("AWS::Scheduler::Schedule");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private static ScheduleRequest request(String name, String group, String expression) {
        ScheduleRequest request = new ScheduleRequest();
        request.setName(name);
        request.setGroupName(group);
        request.setScheduleExpression(expression);
        return request;
    }

    private static Schedule schedule(ScheduleRequest request) {
        Schedule schedule = new Schedule();
        schedule.setName(request.getName());
        schedule.setGroupName(request.getGroupName());
        schedule.setArn("arn:aws:scheduler:us-east-1:000000000000:schedule/"
                + request.getGroupName() + "/" + request.getName());
        schedule.setScheduleExpression(request.getScheduleExpression());
        schedule.setTarget(request.getTarget());
        schedule.setFlexibleTimeWindow(request.getFlexibleTimeWindow());
        return schedule;
    }
}
