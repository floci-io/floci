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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerScheduleCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final SchedulerService scheduler = mock(SchedulerService.class);
    private final SchedulerScheduleCfnProvisioner provisioner = new SchedulerScheduleCfnProvisioner(scheduler);

    @BeforeEach
    void returnCreatedSchedules() {
        when(scheduler.createSchedule(any(), eq(REGION))).thenAnswer(inv -> schedule(inv.getArgument(0)));
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

    private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
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
