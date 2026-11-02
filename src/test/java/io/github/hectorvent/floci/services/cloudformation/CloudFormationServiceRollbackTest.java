package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackUpdateSnapshot;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CodePipelineCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.EcsCapacityCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.LambdaAddressingCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.S3CfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.SecretTargetAttachmentCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.UpdateCleanupResult;
import io.github.hectorvent.floci.services.cloudformation.provisioners.WafV2CfnProvisioner;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers rollback cleanup when another actor removes a resource after its create succeeded, which
 * resources a stack delete walks after a failed operation left them in a failed status, what a
 * failed update rollback keeps so ContinueUpdateRollback can retry it, that a stack which
 * finished deleting is no longer updatable, and that a stack being deleted is neither deleted a
 * second time nor updated by a change set.
 */
class CloudFormationServiceRollbackTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String FORCED_ROLLBACK_REASON = "One or more resources were skipped during UpdateRollback."
            + " These resources were not rolled back and may have drifted from the template."
            + " Run drift detection (DetectStackDrift) to review their current state before your next update.";

    private CfnResourceDispatcher provisioner;
    private CloudFormationService service;

    @BeforeEach
    void setUp() {
        provisioner = mock(CfnResourceDispatcher.class);
        // What the registry and the real provisioners say of a type: an unregistered Custom:: type
        // goes to the generic custom-resource handler, Custom::DynamoDBReplica has its own, and the
        // provisioners of the types whose ids relate to a parent's say how.
        CfnResourceDispatcher routing = new CfnResourceDispatcher(new ObjectMapper(),
                new CloudFormationResourceRegistry(List.of(
                        serving("AWS::CloudFormation::CustomResource"), serving("Custom::DynamoDBReplica"),
                        real(S3CfnProvisioner.class), real(SecretTargetAttachmentCfnProvisioner.class),
                        real(LambdaAddressingCfnProvisioner.class), real(EcsCapacityCfnProvisioner.class),
                        real(WafV2CfnProvisioner.class), real(CodePipelineCfnProvisioner.class))),
                null, null);
        when(provisioner.isCustomResourceType(any()))
                .thenAnswer(invocation -> routing.isCustomResourceType(invocation.getArgument(0)));
        when(provisioner.takesParentPhysicalId(any()))
                .thenAnswer(invocation -> routing.takesParentPhysicalId(invocation.getArgument(0)));
        when(provisioner.idPrefixCanNameParent(any()))
                .thenAnswer(invocation -> routing.idPrefixCanNameParent(invocation.getArgument(0)));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);

        service = new CloudFormationService(
                provisioner,
                mock(S3Service.class),
                mock(SsmService.class),
                mock(GetStackOutputRoleAuthorizer.class),
                mock(CfnDynamicReferences.class),
                new ObjectMapper(),
                config,
                mock(RegionResolver.class),
                Clock.systemUTC(),
                new InMemoryStorageFactory());
    }

    @Test
    void createRollback_withAlreadyDeletedResource_reachesRollbackComplete() {
        Stack stack = new Stack();
        stack.setStackName("rollback-missing-resource");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);

        StackResource created = resource("RestApi", "api-id", "AWS::ApiGateway::RestApi", "CREATE_COMPLETE");
        StackResource failed = resource("FailingResource", null, "AWS::Test::Failure", "CREATE_FAILED");
        failed.setStatusReason("simulated create failure");
        stack.getResources().put(created.getLogicalId(), created);
        stack.getResources().put(failed.getLogicalId(), failed);

        doThrow(new AwsException("NotFoundException", "Invalid API id specified", 404))
                .when(provisioner).delete(eq(created), eq(REGION));

        service.rollbackFailedExecution(stack, REGION, true, failed, null, Set.of());

        assertEquals("ROLLBACK_COMPLETE", stack.getStatus());
        assertEquals("DELETE_COMPLETE", created.getStatus());
        assertNull(created.getStatusReason());
        verify(provisioner).delete(created, REGION);
    }

    /**
     * With rollback enabled AWS moves the stack from CREATE_IN_PROGRESS straight to
     * ROLLBACK_IN_PROGRESS; CREATE_FAILED is the failed resource's status, not the stack's. A
     * stack status of CREATE_FAILED, even for a moment, is read as final by a DescribeStacks poll.
     * Each status is published after its reason and event, so a poll that sees it sees them too.
     */
    @Test
    void createRollback_neverReportsTheStackAsCreateFailed() {
        StatusRecordingStack stack = new StatusRecordingStack();
        stack.setStackName("rollback-stack-events");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        StackResource failed = resource("FailingResource", null, "AWS::Test::Failure", "CREATE_FAILED");
        failed.setStatusReason("simulated create failure");
        stack.getResources().put(failed.getLogicalId(), failed);

        service.rollbackFailedExecution(stack, REGION, true, failed, null, Set.of());

        assertEquals(List.of("ROLLBACK_IN_PROGRESS", "ROLLBACK_COMPLETE"), stack.statuses());
        assertEquals(List.of("ROLLBACK_IN_PROGRESS", "ROLLBACK_COMPLETE"), stackEventStatuses(stack));
        stack.assertEachStatusFollowsItsEvent();
        assertEquals("The following resource(s) failed to create: [FailingResource]. Rollback requested by user.",
                stack.writes().getFirst().reason());
    }

    /**
     * The update counterpart: UPDATE_IN_PROGRESS goes straight to UPDATE_ROLLBACK_IN_PROGRESS, with
     * no stack-level UPDATE_FAILED in between.
     */
    @Test
    void updateRollback_neverReportsTheStackAsUpdateFailed() {
        StatusRecordingStack stack = new StatusRecordingStack();
        stack.setStackName("update-rollback-stack-events");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_IN_PROGRESS");
        stack.writes().clear();
        StackResource added = resource("Dup", null, "AWS::Logs::LogGroup", "CREATE_FAILED");
        added.setStatusReason("already exists");
        stack.getResources().put(added.getLogicalId(), added);
        StackUpdateSnapshot previousState = new StackUpdateSnapshot(
                "previous-template", "previous-original", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of());

        service.rollbackFailedExecution(stack, REGION, false, added, previousState,
                new LinkedHashSet<>(List.of("Dup")));

        assertEquals(List.of("UPDATE_ROLLBACK_IN_PROGRESS", "UPDATE_ROLLBACK_COMPLETE"), stack.statuses());
        assertEquals(List.of("UPDATE_ROLLBACK_IN_PROGRESS", "UPDATE_ROLLBACK_COMPLETE"), stackEventStatuses(stack));
        stack.assertEachStatusFollowsItsEvent();
        assertEquals("The following resource(s) failed to create: [Dup].", stack.writes().getFirst().reason());
    }

    /**
     * The stack's rollback event names the resource that failed, as AWS does; the resource's own
     * message stays on the resource's event. An update rollback carries no "Rollback requested by
     * user." suffix.
     */
    @Test
    void updateRollback_reasonSummarizesTheResourceThatFailedToUpdate() {
        Stack stack = new Stack();
        stack.setStackName("update-rollback-reason");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_IN_PROGRESS");
        StackResource failed = resource("MyDB", "db-1", "AWS::RDS::DBInstance", "UPDATE_FAILED");
        failed.setStatusReason("Size cannot be less than current size; requested: 5; current: 10");
        stack.getResources().put(failed.getLogicalId(), failed);
        StackUpdateSnapshot previousState = new StackUpdateSnapshot(
                "previous-template", "previous-original", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of());

        service.rollbackFailedExecution(stack, REGION, false, failed, previousState,
                new LinkedHashSet<>(List.of("MyDB")));

        StackEvent rollbackEvent = stack.getEvents().stream()
                .filter(event -> "UPDATE_ROLLBACK_IN_PROGRESS".equals(event.getResourceStatus()))
                .findFirst()
                .orElseThrow();
        assertEquals("The following resource(s) failed to update: [MyDB].", rollbackEvent.getResourceStatusReason());
        assertEquals("Size cannot be less than current size; requested: 5; current: 10", failed.getStatusReason());
    }

    /**
     * The stack's own reason is the summary; the cause a stack set instance reports is the failed
     * resource's message, from the operation that rolled back.
     */
    @Test
    void failureCause_afterACreateRollback_isTheFailedResourcesMessage() {
        Stack stack = new Stack();
        stack.setStackName("failure-cause");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.getEvents().add(stackEvent(stack, "CREATE_IN_PROGRESS"));
        stack.getEvents().add(resourceEvent(stack, "FailingResource", "CREATE_FAILED", "simulated create failure"));
        StackResource failed = resource("FailingResource", null, "AWS::Test::Failure", "CREATE_FAILED");
        failed.setStatusReason("simulated create failure");
        stack.getResources().put(failed.getLogicalId(), failed);

        service.rollbackFailedExecution(stack, REGION, true, failed, null, Set.of());

        assertEquals("ROLLBACK_COMPLETE", stack.getStatus());
        assertEquals("simulated create failure", CloudFormationService.failureCause(stack));
    }

    /** A resource may share its stack's name; only the stack's ID tells the stack's own events apart. */
    @Test
    void failureCause_withAResourceNamedLikeItsStack_isThatResourcesMessage() {
        Stack stack = new Stack();
        stack.setStackName("Queue");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.getEvents().add(stackEvent(stack, "CREATE_IN_PROGRESS"));
        stack.getEvents().add(resourceEvent(stack, "Queue", "CREATE_FAILED", "simulated create failure"));
        StackResource failed = resource("Queue", null, "AWS::SQS::Queue", "CREATE_FAILED");
        failed.setStatusReason("simulated create failure");
        stack.getResources().put(failed.getLogicalId(), failed);

        service.rollbackFailedExecution(stack, REGION, true, failed, null, Set.of());

        assertEquals("The following resource(s) failed to create: [Queue]. Rollback requested by user.",
                stack.getStatusReason());
        assertEquals("simulated create failure", CloudFormationService.failureCause(stack));
    }

    /** A reason that is not the rollback summary, such as a failed rollback's, is the cause as is. */
    @Test
    void failureCause_afterAFailedRollback_isTheStacksReason() {
        Stack stack = new Stack();
        stack.setStackName("failure-cause-rollback-failed");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        StackResource created = resource("Queue", "queue-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource failed = resource("FailingResource", null, "AWS::Test::Failure", "CREATE_FAILED");
        failed.setStatusReason("simulated create failure");
        stack.getResources().put(created.getLogicalId(), created);
        stack.getResources().put(failed.getLogicalId(), failed);
        stack.getEvents().add(resourceEvent(stack, "FailingResource", "CREATE_FAILED", "simulated create failure"));
        doThrow(new IllegalStateException("cannot delete")).when(provisioner).delete(eq(created), eq(REGION));

        service.rollbackFailedExecution(stack, REGION, true, failed, null, Set.of());

        assertEquals("ROLLBACK_FAILED", stack.getStatus());
        assertEquals("The following resource(s) failed to roll back: [Queue].", CloudFormationService.failureCause(stack));
    }

    @Test
    void createRollback_withDependencyNotFoundMessage_reachesRollbackFailed() {
        Stack stack = new Stack();
        stack.setStackName("rollback-delete-failure");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);

        StackResource created = resource("ListenerRule", "rule-id", "AWS::ElasticLoadBalancingV2::ListenerRule",
                "CREATE_COMPLETE");
        StackResource failed = resource("FailingResource", null, "AWS::Test::Failure", "CREATE_FAILED");
        failed.setStatusReason("simulated create failure");
        stack.getResources().put(created.getLogicalId(), created);
        stack.getResources().put(failed.getLogicalId(), failed);

        doThrow(new IllegalStateException("Cannot delete listener rule: target group floci-tg-1 not found"))
                .when(provisioner).delete(eq(created), eq(REGION));

        service.rollbackFailedExecution(stack, REGION, true, failed, null, Set.of());

        assertEquals("ROLLBACK_FAILED", stack.getStatus());
        assertEquals("DELETE_FAILED", created.getStatus());
        assertEquals(
                "Cannot delete listener rule: target group floci-tg-1 not found",
                created.getStatusReason());
        verify(provisioner).delete(created, REGION);
    }

    @Test
    void deleteStack_afterFailedUpdateRollback_deletesUpdateFailedResources() {
        Stack stack = new Stack();
        stack.setStackName("delete-after-update-rollback-failed");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_FAILED");

        StackResource role = resource("Role", "leak-probe-role", "AWS::IAM::Role", "UPDATE_FAILED");
        role.setStatusReason("Rollback is not implemented for AWS::IAM::Role");
        StackResource logGroup = resource("LogGroup", "/leak-probe/lg", "AWS::Logs::LogGroup", "UPDATE_FAILED");
        StackResource alreadyDeleted = resource("Gone", "gone-id", "AWS::SQS::Queue", "DELETE_COMPLETE");
        StackResource adopted = resource("Adopted", "/outside/existing", "AWS::Logs::LogGroup", "CREATE_FAILED");
        StackResource owned = resource("Owned", "owned-id", "AWS::IAM::User", "CREATE_FAILED");
        owned.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        for (StackResource resource : new StackResource[] {role, logGroup, alreadyDeleted, adopted, owned}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        assertEquals("DELETE_COMPLETE", stack.getStatus());
        verify(provisioner).completeDeleteCleanup(role);
        verify(provisioner, never()).completeUpdate(any());
        verify(provisioner).delete(role, REGION);
        verify(provisioner).delete(logGroup, REGION);
        verify(provisioner).delete(owned, REGION);
        verify(provisioner, never()).delete(eq(alreadyDeleted), anyString());
        verify(provisioner, never()).delete(eq(adopted), anyString());
        assertEquals("DELETE_COMPLETE", role.getStatus());
        assertNull(role.getStatusReason());
        assertEquals("DELETE_COMPLETE", logGroup.getStatus());
        assertEquals("CREATE_FAILED", adopted.getStatus());
    }

    @Test
    void deleteStack_withUndeletableUpdateFailedResource_reachesDeleteFailed() {
        Stack stack = new Stack();
        stack.setStackName("delete-update-failed-bucket");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_FAILED");

        StackResource bucket = resource("Bucket", "leak-probe-bucket", "AWS::S3::Bucket", "UPDATE_FAILED");
        stack.getResources().put(bucket.getLogicalId(), bucket);
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());
        doThrow(new AwsException("BucketNotEmpty", "The bucket you tried to delete is not empty", 409))
                .when(provisioner).delete(eq(bucket), eq(REGION));

        assertThrows(IllegalStateException.class, () -> service.deleteStackResources(stack, REGION, ACCOUNT));

        assertEquals("DELETE_FAILED", stack.getStatus());
        assertEquals("The following resource(s) failed to delete: [Bucket].", stack.getStatusReason());
        assertEquals("DELETE_FAILED", bucket.getStatus());
    }

    @Test
    void updateChangeSet_onStackThatFinishedDeleting_refusesItsIdAndMissesItsName() {
        String template = "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}";
        service.createChangeSet("finished-deleting", "create", "CREATE", template, null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        Stack stack = service.describeStacks("finished-deleting", REGION, ACCOUNT).getFirst();
        // A delete marks the stack DELETE_COMPLETE before it leaves the live map.
        stack.setStatus("DELETE_COMPLETE");

        for (String nameOrId : List.of(stack.getStackName(), stack.getStackId())) {
            AwsException error = assertThrows(AwsException.class, () -> service.createChangeSet(
                    nameOrId, "update", "UPDATE", template, null, Map.of(), List.of(), Map.of(),
                    REGION, ACCOUNT));
            assertEquals("ValidationError", error.getErrorCode());
            assertEquals(nameOrId.equals(stack.getStackId())
                    ? "Stack:" + nameOrId + " is in DELETE_COMPLETE state and can not be updated."
                    : "Stack with id " + nameOrId + " does not exist", error.getMessage());
        }
        assertEquals(Set.of("create"), stack.getChangeSets().keySet());
    }

    @Test
    void deleteStack_retriedWhileDeleting_deletesEachResourceOnce() throws Exception {
        Stack stack = liveStack("retried-delete");
        StackResource queue = resource("Queue", "queue-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        stack.getResources().put(queue.getLogicalId(), queue);
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            deleting.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(provisioner).delete(eq(queue), eq(REGION));

        Future<?> first = service.deleteStack("retried-delete", REGION, ACCOUNT);
        assertTrue(deleting.await(10, TimeUnit.SECONDS));
        // The retry starts no deletion of its own, so it never waits on the held resource delete.
        service.deleteStack("retried-delete", REGION, ACCOUNT).get(5, TimeUnit.SECONDS);
        release.countDown();
        first.get(10, TimeUnit.SECONDS);

        verify(provisioner, times(1)).delete(queue, REGION);
        assertEquals("DELETE_COMPLETE", stack.getStatus());
    }

    @Test
    void deleteStack_retainingResourcesOfADeleteFailedStack_skipsOnlyThose() throws Exception {
        Stack stack = deleteFailedStack("retained-delete");
        StackResource data = stack.getResources().get("Data");
        StackResource spare = stack.getResources().get("Spare");

        service.deleteStack("retained-delete", List.of("Data"), REGION, ACCOUNT).get(10, TimeUnit.SECONDS);

        assertEquals("DELETE_COMPLETE", stack.getStatus());
        verify(provisioner, never()).delete(eq(data), anyString());
        verify(provisioner).delete(spare, REGION);
        assertEquals("DELETE_SKIPPED", data.getStatus());
        assertNull(data.getStatusReason());
        assertTrue(stack.getEvents().stream().anyMatch(event -> "Data".equals(event.getLogicalResourceId())
                && "DELETE_SKIPPED".equals(event.getResourceStatus())));
    }

    @Test
    void deleteStack_retainingResourcesTheDeleteWouldNotTouch_skipsThemToo() throws Exception {
        Stack stack = deleteFailedStack("retained-failed-create");
        StackResource borrowed = resource("Borrowed", "existing-param", "AWS::SSM::Parameter", "CREATE_FAILED");
        StackResource unnamed = resource("Unnamed", null, "AWS::SSM::Parameter", "CREATE_FAILED");
        for (StackResource failed : List.of(borrowed, unnamed)) {
            failed.setStatusReason("Error occurred during operation 'PutParameter'.");
            stack.getResources().put(failed.getLogicalId(), failed);
        }

        service.deleteStack("retained-failed-create", List.of("Data", "Borrowed", "Unnamed"), REGION, ACCOUNT)
                .get(10, TimeUnit.SECONDS);

        assertEquals("DELETE_COMPLETE", stack.getStatus());
        for (StackResource failed : List.of(borrowed, unnamed)) {
            verify(provisioner, never()).delete(eq(failed), anyString());
            assertEquals("DELETE_SKIPPED", failed.getStatus());
            assertNull(failed.getStatusReason());
            assertTrue(stack.getEvents().stream().anyMatch(event -> failed.getLogicalId()
                    .equals(event.getLogicalResourceId()) && "DELETE_SKIPPED".equals(event.getResourceStatus())));
        }
    }

    @Test
    void deleteStack_retriedWhileDeleting_refusesADifferentRetainList() throws Exception {
        Stack stack = deleteFailedStack("retain-retried");
        StackResource spare = stack.getResources().get("Spare");
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            deleting.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(provisioner).delete(eq(spare), eq(REGION));

        Future<?> first = service.deleteStack("retain-retried", List.of("Data"), REGION, ACCOUNT);
        assertTrue(deleting.await(10, TimeUnit.SECONDS));
        service.deleteStack("retain-retried", List.of("Data"), REGION, ACCOUNT).get(5, TimeUnit.SECONDS);
        for (List<String> other : List.of(List.<String>of(), List.of("Data", "Spare"))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.deleteStack("retain-retried", other, REGION, ACCOUNT));
            assertEquals("ValidationError", error.getErrorCode());
            assertEquals("A delete stack operation is already in progress for stack [" + stack.getStackId()
                    + "]. Do not submit another delete stack request specifying different resources to retain"
                    + " or resources to retain in a different order.", error.getMessage());
        }
        release.countDown();
        first.get(10, TimeUnit.SECONDS);

        verify(provisioner, times(1)).delete(spare, REGION);
        assertEquals("DELETE_COMPLETE", stack.getStatus());
    }

    @Test
    void deleteStack_retriedOnAChildItsParentIsDeleting_isANoOp() throws Exception {
        Stack child = deleteFailedStack("retain-child");
        child.setRetainResources(List.of("Data"));
        Stack parent = liveStack("retain-parent");
        parent.getResources().clear();
        parent.getResources().put("Child",
                resource("Child", child.getStackId(), "AWS::CloudFormation::Stack", "CREATE_COMPLETE"));
        StackResource spare = child.getResources().get("Spare");
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            deleting.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(provisioner).delete(eq(spare), eq(REGION));

        Future<?> parentDelete = service.deleteStack("retain-parent", REGION, ACCOUNT);
        assertTrue(deleting.await(10, TimeUnit.SECONDS));
        service.deleteStack("retain-child", List.of(), REGION, ACCOUNT).get(5, TimeUnit.SECONDS);
        release.countDown();
        parentDelete.get(10, TimeUnit.SECONDS);

        assertEquals("DELETE_COMPLETE", child.getStatus());
    }

    @Test
    void executeChangeSet_onStackBeingDeleted_isRefused() {
        Stack stack = liveStack("executed-while-deleting");
        service.createChangeSet("executed-while-deleting", "update", "UPDATE",
                "{\"Resources\":{\"Topic\":{\"Type\":\"AWS::SNS::Topic\"}}}", null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        stack.setStatus("DELETE_IN_PROGRESS");

        AwsException error = assertThrows(AwsException.class,
                () -> service.executeChangeSet("executed-while-deleting", "update", REGION, ACCOUNT));

        assertEquals("InvalidChangeSetStatus", error.getErrorCode());
        assertEquals("DELETE_IN_PROGRESS", stack.getStatus());
    }

    @Test
    void deleteStack_savedWithCircularTemplate_stillDeletesDependentsFirst() {
        // A stack saved before circular templates were rejected: the queues depend on each other,
        // and the target group was added by a later update, so it sits after the listener using it.
        Stack stack = new Stack();
        stack.setStackName("delete-saved-circular-stack");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_COMPLETE");
        stack.setTemplateBody("""
                {"Resources": {
                  "FirstQueue": {"Type": "AWS::SQS::Queue", "DependsOn": "SecondQueue"},
                  "SecondQueue": {"Type": "AWS::SQS::Queue", "DependsOn": "FirstQueue"},
                  "TargetGroup": {"Type": "AWS::ElasticLoadBalancingV2::TargetGroup"},
                  "Listener": {"Type": "AWS::ElasticLoadBalancingV2::Listener",
                               "Properties": {"DefaultActions": [{"Type": "forward",
                                   "TargetGroupArn": {"Ref": "TargetGroup"}}]}}
                }}""");
        StackResource listener = resource("Listener", "listener-arn", "AWS::ElasticLoadBalancingV2::Listener",
                "CREATE_COMPLETE");
        StackResource firstQueue = resource("FirstQueue", "first-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource secondQueue = resource("SecondQueue", "second-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource targetGroup = resource("TargetGroup", "target-group-arn",
                "AWS::ElasticLoadBalancingV2::TargetGroup", "CREATE_COMPLETE");
        for (StackResource resource : new StackResource[] {listener, firstQueue, secondQueue, targetGroup}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(listener, REGION);
        deletes.verify(provisioner).delete(targetGroup, REGION);
        assertEquals("DELETE_COMPLETE", stack.getStatus());
    }

    @Test
    void deleteStack_savedWithCircularTemplate_deletesResourcesDependingOnTheCycleInDependencyOrder() {
        // The target group depends on a queue in the cycle, so it cannot be ordered either; the
        // listener using it comes first in the template, yet must still be deleted first.
        Stack stack = new Stack();
        stack.setStackName("delete-saved-circular-chain");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_COMPLETE");
        stack.setTemplateBody("""
                {"Resources": {
                  "Listener": {"Type": "AWS::ElasticLoadBalancingV2::Listener",
                               "Properties": {"DefaultActions": [{"Type": "forward",
                                   "TargetGroupArn": {"Ref": "TargetGroup"}}]}},
                  "FirstQueue": {"Type": "AWS::SQS::Queue", "DependsOn": "SecondQueue"},
                  "SecondQueue": {"Type": "AWS::SQS::Queue", "DependsOn": "FirstQueue"},
                  "TargetGroup": {"Type": "AWS::ElasticLoadBalancingV2::TargetGroup", "DependsOn": "FirstQueue"}
                }}""");
        StackResource listener = resource("Listener", "listener-arn", "AWS::ElasticLoadBalancingV2::Listener",
                "CREATE_COMPLETE");
        StackResource firstQueue = resource("FirstQueue", "first-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource secondQueue = resource("SecondQueue", "second-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource targetGroup = resource("TargetGroup", "target-group-arn",
                "AWS::ElasticLoadBalancingV2::TargetGroup", "CREATE_COMPLETE");
        for (StackResource resource : new StackResource[] {listener, firstQueue, secondQueue, targetGroup}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(listener, REGION);
        deletes.verify(provisioner).delete(targetGroup, REGION);
        deletes.verify(provisioner).delete(firstQueue, REGION);
        assertEquals("DELETE_COMPLETE", stack.getStatus());
    }

    @Test
    void deleteStack_savedWithLinkedCircularTemplates_deletesTheDependentCycleFirst() {
        // Two cycles, the consumer's cycle also depending on the queues' cycle: the consumer must
        // be deleted while the queues it uses still exist.
        Stack stack = new Stack();
        stack.setStackName("delete-saved-linked-cycles");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_COMPLETE");
        stack.setTemplateBody("""
                {"Resources": {
                  "Consumer": {"Type": "AWS::SQS::Queue", "DependsOn": ["Partner", "FirstQueue"]},
                  "Partner": {"Type": "AWS::SQS::Queue", "DependsOn": "Consumer"},
                  "FirstQueue": {"Type": "AWS::SQS::Queue", "DependsOn": "SecondQueue"},
                  "SecondQueue": {"Type": "AWS::SQS::Queue", "DependsOn": "FirstQueue"}
                }}""");
        StackResource consumer = resource("Consumer", "consumer-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource partner = resource("Partner", "partner-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource firstQueue = resource("FirstQueue", "first-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource secondQueue = resource("SecondQueue", "second-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        for (StackResource resource : new StackResource[] {consumer, partner, firstQueue, secondQueue}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(consumer, REGION);
        deletes.verify(provisioner).delete(firstQueue, REGION);
        assertEquals("DELETE_COMPLETE", stack.getStatus());
    }

    @Test
    void deleteStack_savedWithLongChainIntoCycle_deletesWithoutExhaustingTheThreadStack() throws InterruptedException {
        // Each resource depends on the next and the last one on a cycle, so finding the cycle has
        // to follow the whole chain; a small thread stack makes a recursive search overflow.
        int chainLength = 5_000;
        StringBuilder resources = new StringBuilder();
        Stack stack = new Stack();
        stack.setStackName("delete-saved-long-chain");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_COMPLETE");
        StackResource[] chain = new StackResource[chainLength];
        for (int i = 0; i < chainLength; i++) {
            String next = i + 1 < chainLength ? "Link" + (i + 1) : "FirstQueue";
            resources.append("\"Link").append(i).append("\": {\"Type\": \"AWS::SQS::Queue\", \"DependsOn\": \"")
                    .append(next).append("\"},");
            chain[i] = resource("Link" + i, "link-" + i, "AWS::SQS::Queue", "CREATE_COMPLETE");
            stack.getResources().put(chain[i].getLogicalId(), chain[i]);
        }
        stack.setTemplateBody("{\"Resources\": {" + resources
                + "\"FirstQueue\": {\"Type\": \"AWS::SQS::Queue\", \"DependsOn\": \"SecondQueue\"},"
                + "\"SecondQueue\": {\"Type\": \"AWS::SQS::Queue\", \"DependsOn\": \"FirstQueue\"}}}");
        StackResource firstQueue = resource("FirstQueue", "first-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource secondQueue = resource("SecondQueue", "second-url", "AWS::SQS::Queue", "CREATE_COMPLETE");
        stack.getResources().put(firstQueue.getLogicalId(), firstQueue);
        stack.getResources().put(secondQueue.getLogicalId(), secondQueue);
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread deleter = new Thread(null, () -> {
            try {
                service.deleteStackResources(stack, REGION, ACCOUNT);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "small-stack-delete", 256 * 1024);
        deleter.start();
        deleter.join();

        assertNull(failure.get());
        assertEquals("DELETE_COMPLETE", stack.getStatus());
        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(chain[0], REGION);
        deletes.verify(provisioner).delete(chain[chainLength - 1], REGION);
        deletes.verify(provisioner).delete(firstQueue, REGION);
    }

    @Test
    void failedStackDeletePreservesRollbackMetadataUntilTheResourceDeleteSucceeds() {
        Stack stack = new Stack();
        stack.setStackName("delete-keeps-rollback-state");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_FAILED");
        StackResource resource = resource("Stateful", "current-id", "AWS::Test::Stateful", "UPDATE_FAILED");
        resource.getAttributes().put("rollbackSnapshot", "original-configuration");
        resource.getAttributes().put("cleanupPending", "true");
        stack.getResources().put(resource.getLogicalId(), resource);

        CfnResourceDispatcher cleanupDispatcher = mock(CfnResourceDispatcher.class, invocation ->
                switch (invocation.getMethod().getName()) {
                    case "updateCleanupPhysicalId" -> resource.getAttributes().containsKey("cleanupPending")
                            ? "displaced-id" : null;
                    case "completeUpdate" -> {
                        resource.getAttributes().remove("rollbackSnapshot");
                        yield new UpdateCleanupResult(true, true, "displaced-id", 0, null);
                    }
                    case "completeDeleteCleanup" -> resource.getAttributes().containsKey("cleanupPending")
                            ? new UpdateCleanupResult(true, true, "displaced-id", 0, null)
                            : UpdateCleanupResult.notApplicable();
                    case "clearUpdate" -> {
                        resource.getAttributes().remove("rollbackSnapshot");
                        resource.getAttributes().remove("cleanupPending");
                        yield null;
                    }
                    case "clearDeleteCleanup" -> {
                        resource.getAttributes().remove("cleanupPending");
                        yield null;
                    }
                    default -> RETURNS_DEFAULTS.answer(invocation);
                });
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new AwsException("ServiceUnavailable", "temporary current-resource deletion failure", 503);
            }
            assertEquals("original-configuration", resource.getAttributes().get("rollbackSnapshot"));
            resource.getAttributes().remove("rollbackSnapshot");
            return null;
        }).when(cleanupDispatcher).delete(resource, REGION);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        CloudFormationService deleteService = new CloudFormationService(cleanupDispatcher, mock(S3Service.class),
                mock(SsmService.class), mock(GetStackOutputRoleAuthorizer.class), mock(CfnDynamicReferences.class), new ObjectMapper(),
                config, mock(RegionResolver.class), Clock.systemUTC(), new InMemoryStorageFactory());

        assertThrows(IllegalStateException.class, () -> deleteService.deleteStackResources(stack, REGION, ACCOUNT));
        assertEquals("DELETE_FAILED", stack.getStatus());
        assertEquals("DELETE_FAILED", resource.getStatus());
        assertEquals("original-configuration", resource.getAttributes().get("rollbackSnapshot"));
        assertNull(resource.getAttributes().get("cleanupPending"));
        assertTrue(stack.getEvents().stream().anyMatch(event -> "Stateful".equals(event.getLogicalResourceId())
                && "displaced-id".equals(event.getPhysicalResourceId())
                && "DELETE_COMPLETE".equals(event.getResourceStatus())));

        deleteService.deleteStackResources(stack, REGION, ACCOUNT);

        assertEquals(2, attempts.get());
        assertEquals("DELETE_COMPLETE", stack.getStatus());
        assertEquals("DELETE_COMPLETE", resource.getStatus());
        assertNull(resource.getAttributes().get("rollbackSnapshot"));
    }

    @Test
    void refusesUpdate_afterUpdateRollbackFailed_refusesButStillAllowsAfterUpdateRollbackComplete() {
        assertTrue(CloudFormationService.refusesUpdate("UPDATE_ROLLBACK_FAILED"));
        assertFalse(CloudFormationService.refusesUpdate("UPDATE_ROLLBACK_COMPLETE"));
    }

    /**
     * The walker's inputs live only for the update that failed. What it needs to run again is kept
     * on the stack, and must survive the stack's persisted JSON: a ContinueUpdateRollback after a
     * restart works from the stored copy alone.
     */
    @Test
    void updateRollbackFailed_persistsWhatContinueUpdateRollbackNeeds() throws Exception {
        Stack stack = new Stack();
        stack.setStackName("update-rollback-failed-snapshot");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_IN_PROGRESS");
        stack.setTemplateBody("failed-template");
        stack.setOriginalTemplateBody("failed-original");

        StackResource logGroup = resource("LogGroup", "/lg", "AWS::Logs::LogGroup", "UPDATE_COMPLETE");
        StackResource added = resource("Dup", null, "AWS::Logs::LogGroup", "CREATE_FAILED");
        added.setStatusReason("already exists");
        stack.getResources().put(logGroup.getLogicalId(), logGroup);
        stack.getResources().put(added.getLogicalId(), added);
        StackUpdateSnapshot previousState = new StackUpdateSnapshot(
                "previous-template", "previous-original", Map.of("P", "1"), Map.of("P", "1"),
                Map.of("Out", "v"), Map.of(), Map.of(),
                Map.of("LogGroup", resource("LogGroup", "/lg", "AWS::Logs::LogGroup", "CREATE_COMPLETE")));

        service.rollbackFailedExecution(stack, REGION, false, added, previousState,
                new LinkedHashSet<>(List.of("LogGroup", "Dup")));

        assertEquals("UPDATE_ROLLBACK_FAILED", stack.getStatus());
        assertEquals("failed-template", stack.getTemplateBody());

        Stack restored = persistedCopy(stack);
        assertEquals(List.of("LogGroup"), restored.getUpdateRollbackResourceIds());
        assertEquals("previous-template", restored.getUpdateRollbackSnapshot().templateBody());
        assertEquals("CREATE_COMPLETE",
                restored.getUpdateRollbackSnapshot().resources().get("LogGroup").getStatus());

        when(provisioner.rollbackUpdate(any(StackResource.class), any())).thenReturn(true);
        restored.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
        service.resumeUpdateRollback(restored, List.of(), false, REGION);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", restored.getStatus());
        assertEquals("previous-template", restored.getTemplateBody());
        assertEquals("previous-original", restored.getOriginalTemplateBody());
        assertEquals("v", restored.getOutputs().get("Out"));
        assertEquals("CREATE_COMPLETE", restored.getResources().get("LogGroup").getStatus());
        assertNull(restored.getUpdateRollbackSnapshot());
        assertNull(restored.getUpdateRollbackResourceIds());
    }

    /**
     * A stack stored before the snapshot was kept still has a way out: every UPDATE_FAILED
     * resource is owed a rollback, and the template it already holds is kept.
     */
    @Test
    void resumeUpdateRollback_withoutAStoredSnapshot_owesEveryUpdateFailedResourceAndKeepsTheTemplate() {
        Stack stack = new Stack();
        stack.setStackName("legacy-update-rollback-failed");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
        stack.setTemplateBody("current-template");

        StackResource logGroup = resource("LogGroup", "/lg", "AWS::Logs::LogGroup", "UPDATE_FAILED");
        StackResource stable = resource("Stable", "/stable", "AWS::Logs::LogGroup", "UPDATE_COMPLETE");
        stack.getResources().put(logGroup.getLogicalId(), logGroup);
        stack.getResources().put(stable.getLogicalId(), stable);
        when(provisioner.rollbackUpdate(any(StackResource.class), any())).thenReturn(true);

        service.resumeUpdateRollback(stack, List.of(), false, REGION);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack.getStatus());
        assertEquals("current-template", stack.getTemplateBody());
        assertEquals("UPDATE_COMPLETE", logGroup.getStatus());
        assertEquals("UPDATE_COMPLETE", stable.getStatus());
        verify(provisioner).rollbackUpdate(eq(logGroup), any());
        verify(provisioner, never()).rollbackUpdate(eq(stable), any());
    }

    /**
     * A skip naming a resource of a nested stack Floci holds is one AWS would act on and Floci does
     * not support: the rollback fails again with Floci's reason, touching no resource and keeping
     * what a later retry needs.
     */
    @Test
    void resumeUpdateRollback_skippingANestedStackResource_failsAgainWithoutTouchingAResource() {
        Stack stack = new Stack();
        stack.setStackName("nested-skip");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
        StackResource nested = resource("Nested", "nested-stack-id", "AWS::CloudFormation::Stack", "UPDATE_FAILED");
        stack.getResources().put(nested.getLogicalId(), nested);
        StackUpdateSnapshot previousState = new StackUpdateSnapshot(
                "previous-template", "previous-original", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("Nested", resource("Nested", "nested-stack-id", "AWS::CloudFormation::Stack",
                        "CREATE_COMPLETE")));
        stack.setUpdateRollbackSnapshot(previousState);
        stack.setUpdateRollbackResourceIds(List.of("Nested"));

        service.resumeUpdateRollback(stack, List.of("Nested.Child"), false, REGION);

        assertEquals("UPDATE_ROLLBACK_FAILED", stack.getStatus());
        assertEquals("Skipping a resource of a nested stack is not supported by Floci: Nested.Child",
                stack.getStatusReason());
        assertEquals("UPDATE_FAILED", nested.getStatus());
        assertEquals(previousState, stack.getUpdateRollbackSnapshot());
        assertEquals(List.of("Nested"), stack.getUpdateRollbackResourceIds());
        verify(provisioner, never()).rollbackUpdate(any(StackResource.class), any());
    }

    /**
     * A resume runs the restore of a resource still owed one again, through the same provisioner
     * hook the first rollback used: here one whose first restore threw.
     */
    @Test
    void resumeUpdateRollback_afterARestoreThatThrew_runsTheRestoreAgain() {
        Stack stack = new Stack();
        stack.setStackName("retry-thrown-restore");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_IN_PROGRESS");
        StackResource dashboard = resource("Dashboard", "ops", "AWS::CloudWatch::Dashboard", "UPDATE_COMPLETE");
        StackResource added = resource("ZFail", null, "AWS::CloudFormation::Stack", "CREATE_FAILED");
        stack.getResources().put(dashboard.getLogicalId(), dashboard);
        stack.getResources().put(added.getLogicalId(), added);
        StackUpdateSnapshot previousState = new StackUpdateSnapshot(
                "previous-template", "previous-original", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("Dashboard", resource("Dashboard", "ops", "AWS::CloudWatch::Dashboard", "CREATE_COMPLETE")));
        when(provisioner.rollbackUpdate(eq(dashboard), any()))
                .thenThrow(new IllegalStateException("simulated restore failure"))
                .thenReturn(true);

        service.rollbackFailedExecution(stack, REGION, false, added, previousState,
                new LinkedHashSet<>(List.of("Dashboard", "ZFail")));

        assertEquals("UPDATE_ROLLBACK_FAILED", stack.getStatus());
        assertEquals("UPDATE_FAILED", dashboard.getStatus());

        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
        service.resumeUpdateRollback(stack, List.of(), false, REGION);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack.getStatus());
        assertEquals("CREATE_COMPLETE", dashboard.getStatus());
        verify(provisioner, times(2)).rollbackUpdate(eq(dashboard), any());
    }

    /**
     * A retry walks the failed update's template with the parameters the first pass already
     * restored, so a condition the update turned on can read false again. A resource the stack
     * holds is still ordered by its dependencies, so the load balancer the update created on the
     * subnet it added is deleted before that subnet, not after it. The load balancer is inserted
     * first, so falling back to the map's order would delete the subnet first too.
     */
    @Test
    void resumeUpdateRollback_ordersAHeldResourceByItsDependenciesWhateverItsConditionReadsNow() {
        Stack stack = new Stack();
        stack.setStackName("retry-flipped-condition");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setTemplateBody("""
                {
                  "Parameters": {"Env": {"Type": "String"}},
                  "Conditions": {"IsProd": {"Fn::Equals": [{"Ref": "Env"}, "prod"]}},
                  "Resources": {
                    "SubnetC": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": "vpc-1", "CidrBlock": "10.0.3.0/24"}},
                    "ProdAlb": {
                      "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                      "Condition": "IsProd",
                      "Properties": {"Subnets": [{"Ref": "SubnetC"}]}
                    }
                  }
                }
                """);
        stack.replaceParameters(Map.of("Env", "dev"));
        stack.replaceResolvedParameters(Map.of("Env", "dev"));
        StackResource subnet = resource("SubnetC", "subnet-c", "AWS::EC2::Subnet", "CREATE_COMPLETE");
        StackResource loadBalancer = resource("ProdAlb", "arn:aws:elasticloadbalancing:us-east-1:000000000000:"
                + "loadbalancer/app/prod/1", "AWS::ElasticLoadBalancingV2::LoadBalancer", "CREATE_COMPLETE");
        stack.getResources().put(loadBalancer.getLogicalId(), loadBalancer);
        stack.getResources().put(subnet.getLogicalId(), subnet);
        stack.setUpdateRollbackSnapshot(new StackUpdateSnapshot("previous-template", "previous-original",
                Map.of("Env", "dev"), Map.of("Env", "dev"), Map.of(), Map.of(), Map.of(), Map.of()));
        stack.setUpdateRollbackResourceIds(List.of("SubnetC", "ProdAlb"));
        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");

        service.resumeUpdateRollback(stack, List.of(), false, REGION);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack.getStatus());
        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(eq(loadBalancer), anyString());
        deletes.verify(provisioner).delete(eq(subnet), anyString());
    }

    /**
     * A held resource can depend on a condition-false one the stack no longer holds, such as a role
     * an earlier cleanup deleted while the load balancer on it failed to delete. Ordering must not
     * then give up on the whole template: the load balancer still goes before the subnet it uses.
     */
    @Test
    void deleteStack_withAHeldResourceOnAGoneConditionFalseOne_stillDeletesDependentsFirst() {
        Stack stack = new Stack();
        stack.setStackName("delete-held-on-gone-conditional");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_COMPLETE");
        stack.setTemplateBody("""
                {
                  "Parameters": {"Env": {"Type": "String"}},
                  "Conditions": {"IsProd": {"Fn::Equals": [{"Ref": "Env"}, "prod"]}},
                  "Resources": {
                    "ProdRole": {"Type": "AWS::IAM::Role", "Condition": "IsProd"},
                    "SubnetC": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": "vpc-1", "CidrBlock": "10.0.3.0/24"}},
                    "ProdAlb": {
                      "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                      "Condition": "IsProd",
                      "DependsOn": "ProdRole",
                      "Properties": {"Subnets": [{"Ref": "SubnetC"}]}
                    }
                  }
                }
                """);
        stack.replaceParameters(Map.of("Env", "dev"));
        stack.replaceResolvedParameters(Map.of("Env", "dev"));
        StackResource loadBalancer = resource("ProdAlb", "arn:aws:elasticloadbalancing:us-east-1:000000000000:"
                + "loadbalancer/app/prod/1", "AWS::ElasticLoadBalancingV2::LoadBalancer", "DELETE_FAILED");
        StackResource subnet = resource("SubnetC", "subnet-c", "AWS::EC2::Subnet", "CREATE_COMPLETE");
        stack.getResources().put(loadBalancer.getLogicalId(), loadBalancer);
        stack.getResources().put(subnet.getLogicalId(), subnet);
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(loadBalancer, REGION);
        deletes.verify(provisioner).delete(subnet, REGION);
    }

    /**
     * Counting every Fn::If branch can close a cycle no choice of branches has: here A uses B when
     * Flip is true, and B uses A when it is false. The walk then orders by the branches the
     * conditions choose now, so A, which uses B, still goes first.
     */
    @Test
    void deleteStack_whenEveryBranchWouldCloseACycle_ordersByTheBranchesChosenNow() {
        Stack stack = new Stack();
        stack.setStackName("delete-branch-cycle");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("CREATE_COMPLETE");
        stack.setTemplateBody("""
                {
                  "Parameters": {"Flip": {"Type": "String"}},
                  "Conditions": {
                    "Flipped": {"Fn::Equals": [{"Ref": "Flip"}, "yes"]},
                    "NotFlipped": {"Fn::Not": [{"Condition": "Flipped"}]}
                  },
                  "Resources": {
                    "B": {"Type": "AWS::SQS::Queue",
                          "Properties": {"Tags": {"Fn::If": ["NotFlipped", [{"Key": "a", "Value": {"Ref": "A"}}],
                                                             {"Ref": "AWS::NoValue"}]}}},
                    "A": {"Type": "AWS::SQS::Queue",
                          "Properties": {"Tags": {"Fn::If": ["Flipped", [{"Key": "b", "Value": {"Ref": "B"}}],
                                                             {"Ref": "AWS::NoValue"}]}}}
                  }
                }
                """);
        stack.replaceParameters(Map.of("Flip", "yes"));
        StackResource b = resource("B", "queue-b", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource a = resource("A", "queue-a", "AWS::SQS::Queue", "CREATE_COMPLETE");
        stack.getResources().put(b.getLogicalId(), b);
        stack.getResources().put(a.getLogicalId(), a);
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(a, REGION);
        deletes.verify(provisioner).delete(b, REGION);
    }

    /**
     * A cycle that only counting every branch closes, between X and Y, must not cost the rest of
     * the stack its other-branch dependencies: the load balancer still on the subnet the failed
     * update chose goes before that subnet, though the restored parameter now picks the other.
     * SubnetC is created two steps after SubnetA, so only that dependency puts the load balancer
     * after it.
     */
    @Test
    void deleteStack_withAnUnrelatedBranchCycle_stillDeletesAResourceBeforeItsOtherBranchDependency() {
        Stack stack = new Stack();
        stack.setStackName("delete-branch-cycle-and-held-branch");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_FAILED");
        stack.setTemplateBody("""
                {
                  "Parameters": {"Placement": {"Type": "String"}},
                  "Conditions": {
                    "Second": {"Fn::Equals": [{"Ref": "Placement"}, "second"]},
                    "First": {"Fn::Not": [{"Condition": "Second"}]}
                  },
                  "Resources": {
                    "Alb": {"Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                            "Properties": {"Subnets": {"Fn::If": ["Second", [{"Ref": "SubnetC"}], [{"Ref": "SubnetA"}]]}}},
                    "SubnetA": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": "vpc-1", "CidrBlock": "10.0.1.0/24"}},
                    "SubnetB": {"Type": "AWS::EC2::Subnet", "DependsOn": "SubnetA",
                                "Properties": {"VpcId": "vpc-1", "CidrBlock": "10.0.2.0/24"}},
                    "SubnetC": {"Type": "AWS::EC2::Subnet", "DependsOn": "SubnetB",
                                "Properties": {"VpcId": "vpc-1", "CidrBlock": "10.0.3.0/24"}},
                    "Y": {"Type": "AWS::SQS::Queue",
                          "Properties": {"Tags": {"Fn::If": ["First", [{"Key": "x", "Value": {"Ref": "X"}}],
                                                             {"Ref": "AWS::NoValue"}]}}},
                    "X": {"Type": "AWS::SQS::Queue",
                          "Properties": {"Tags": {"Fn::If": ["Second", [{"Key": "y", "Value": {"Ref": "Y"}}],
                                                             {"Ref": "AWS::NoValue"}]}}}
                  }
                }
                """);
        stack.replaceParameters(Map.of("Placement", "first"));
        StackResource loadBalancer = resource("Alb", "arn:aws:elasticloadbalancing:us-east-1:000000000000:"
                + "loadbalancer/app/web/1", "AWS::ElasticLoadBalancingV2::LoadBalancer", "UPDATE_FAILED");
        StackResource subnetA = resource("SubnetA", "subnet-a", "AWS::EC2::Subnet", "CREATE_COMPLETE");
        StackResource subnetB = resource("SubnetB", "subnet-b", "AWS::EC2::Subnet", "CREATE_COMPLETE");
        StackResource subnetC = resource("SubnetC", "subnet-c", "AWS::EC2::Subnet", "CREATE_COMPLETE");
        StackResource y = resource("Y", "queue-y", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource x = resource("X", "queue-x", "AWS::SQS::Queue", "CREATE_COMPLETE");
        for (StackResource resource : new StackResource[] {loadBalancer, subnetA, subnetB, subnetC, y, x}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder subnets = inOrder(provisioner);
        subnets.verify(provisioner).delete(loadBalancer, REGION);
        subnets.verify(provisioner).delete(subnetC, REGION);
        InOrder queues = inOrder(provisioner);
        queues.verify(provisioner).delete(y, REGION);
        queues.verify(provisioner).delete(x, REGION);
    }

    /**
     * A resource the template no longer names, such as a subnet ContinueUpdateRollback skipped, has
     * no template entry left to order it by. It still goes as late as possible, after the load
     * balancer that may still use it, but before the VPC its attributes name, or that VPC delete
     * fails while the subnet exists.
     */
    @Test
    void deleteStack_deletesALeftoverResourceBeforeTheResourceItsAttributesName() {
        Stack stack = new Stack();
        stack.setStackName("delete-leftover-subnet");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_COMPLETE");
        stack.setTemplateBody("""
                {"Resources": {
                  "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.0.0.0/16"}},
                  "SubnetA": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.0.1.0/24"}},
                  "Alb": {"Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                          "Properties": {"Subnets": [{"Ref": "SubnetA"}]}}
                }}""");
        StackResource leftover = resource("SubnetC", "subnet-c", "AWS::EC2::Subnet", "UPDATE_COMPLETE");
        leftover.getAttributes().put("VpcId", "vpc-1");
        StackResource vpc = resource("Vpc", "vpc-1", "AWS::EC2::VPC", "CREATE_COMPLETE");
        StackResource subnet = resource("SubnetA", "subnet-a", "AWS::EC2::Subnet", "CREATE_COMPLETE");
        subnet.getAttributes().put("VpcId", "vpc-1");
        StackResource loadBalancer = resource("Alb", "arn:aws:elasticloadbalancing:us-east-1:000000000000:"
                + "loadbalancer/app/web/1", "AWS::ElasticLoadBalancingV2::LoadBalancer", "UPDATE_COMPLETE");
        for (StackResource resource : new StackResource[] {vpc, subnet, loadBalancer, leftover}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(loadBalancer, REGION);
        deletes.verify(provisioner).delete(leftover, REGION);
        deletes.verify(provisioner).delete(vpc, REGION);
    }

    /**
     * A custom resource's attributes are its handler's response Data, which can name any resource,
     * such as a bucket created after it and depending on it. Leftover ordering infers nothing from
     * them, so two leftovers keep their reverse creation order: the bucket goes before the custom
     * resource it depends on.
     */
    @Test
    void deleteStack_infersNoLeftoverOrderFromACustomResourcesData() {
        Stack stack = new Stack();
        stack.setStackName("delete-leftover-custom-data");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_COMPLETE");
        stack.setTemplateBody("""
                {"Resources": {"Queue": {"Type": "AWS::SQS::Queue"}}}""");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource namer = resource("Namer", "namer-1", "Custom::BucketNamer", "UPDATE_COMPLETE");
        namer.getAttributes().put("BucketName", "bucket-1");
        StackResource bucket = resource("Bucket", "bucket-1", "AWS::S3::Bucket", "UPDATE_COMPLETE");
        for (StackResource resource : new StackResource[] {queue, namer, bucket}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(bucket, REGION);
        deletes.verify(provisioner).delete(namer, REGION);
    }

    /**
     * A leftover custom resource goes before the bucket its definition named, which its handler may
     * need to empty first: provisioning kept that bucket's id for it.
     */
    @Test
    void deleteStack_deletesALeftoverCustomResourceBeforeTheBucketItsDefinitionNamed() {
        String token = "arn:aws:sns:us-east-1:000000000000:emptier";
        Stack stack = leftoverStack("delete-leftover-custom-uses", "{\"Bucket\": {\"Type\": \"AWS::S3::Bucket\"}}");
        StackResource emptier = resource("Emptier", "emptier-1", "Custom::EmptyBucket", "UPDATE_COMPLETE");
        emptier.getAttributes().put("__FlociServiceToken", token);
        emptier.getAttributes().put(CfnRollback.USES_ATTR_PREFIX + "Resource.Bucket", "bucket-1");
        StackResource bucket = resource("Bucket", "bucket-1", "AWS::S3::Bucket", "CREATE_COMPLETE");
        hold(stack, emptier, bucket);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(emptier, REGION);
        deletes.verify(provisioner).delete(bucket, REGION);
    }

    /**
     * A literal a custom resource was given names nothing, even when another resource has that
     * name: a leftover certificate resource given a domain's name still goes after the leftover
     * custom domain that uses the certificate.
     */
    @Test
    void deleteStack_infersNoLeftoverOrderFromACustomResourcesLiteralProperties() {
        String token = "arn:aws:lambda:us-east-1:000000000000:function:cert-requestor";
        String certArn = "arn:aws:acm:us-east-1:000000000000:certificate/abc";
        Stack stack = leftoverStack("delete-leftover-custom-literal", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource cert = resource("Cert", certArn, "Custom::DNSValidatedCertificate", "UPDATE_COMPLETE");
        cert.getAttributes().put("__FlociServiceToken", token);
        cert.getAttributes().put("__FlociResourceProperties",
                "{\"ServiceToken\": \"" + token + "\", \"DomainName\": \"auth.example.com\"}");
        cert.getAttributes().put("Arn", certArn);
        StackResource domain = resource("Domain", "auth.example.com", "AWS::Cognito::UserPoolDomain",
                "UPDATE_COMPLETE");
        hold(stack, queue, cert, domain);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(domain, REGION);
        deletes.verify(provisioner).delete(cert, REGION);
    }

    /**
     * {@code Custom::DynamoDBReplica} has its own provisioner, and its {@code TableName} names the
     * table it replicates, so a leftover replica still goes before that table.
     */
    @Test
    void deleteStack_deletesALeftoverDynamoDbReplicaBeforeItsTable() {
        Stack stack = leftoverStack("delete-leftover-replica", "{\"Table\": {\"Type\": \"AWS::DynamoDB::Table\"}}");
        StackResource replica = resource("Replica", "table-1-replica", "Custom::DynamoDBReplica", "UPDATE_COMPLETE");
        replica.getAttributes().put("TableName", "table-1");
        StackResource table = resource("Table", "table-1", "AWS::DynamoDB::Table", "CREATE_COMPLETE");
        hold(stack, replica, table);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(replica, REGION);
        deletes.verify(provisioner).delete(table, REGION);
    }

    /**
     * Floci's own attributes on a custom resource are not handler Data: a leftover custom resource
     * still goes before the topic its {@code ServiceToken} names, so its Delete reaches the handler.
     */
    @Test
    void deleteStack_deletesALeftoverCustomResourceBeforeItsServiceToken() {
        String topicArn = "arn:aws:sns:us-east-1:000000000000:handler";
        Stack stack = leftoverStack("delete-leftover-custom-token", "{\"Topic\": {\"Type\": \"AWS::SNS::Topic\"}}");
        StackResource custom = resource("Notifier", "notifier-1", "Custom::Notifier", "UPDATE_COMPLETE");
        custom.getAttributes().put("__FlociServiceToken", topicArn);
        StackResource topic = resource("Topic", topicArn, "AWS::SNS::Topic", "CREATE_COMPLETE");
        hold(stack, custom, topic);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(custom, REGION);
        deletes.verify(provisioner).delete(topic, REGION);
    }

    /**
     * A Lambda {@code ServiceToken} is the function's ARN while the function's physical id is its
     * name: a leftover custom resource still goes before its handler function.
     */
    @Test
    void deleteStack_deletesALeftoverLambdaBackedCustomResourceBeforeItsHandler() {
        String handlerArn = "arn:aws:lambda:us-east-1:000000000000:function:handler";
        Stack stack = leftoverStack("delete-leftover-custom-lambda",
                "{\"Handler\": {\"Type\": \"AWS::Lambda::Function\"}}");
        StackResource custom = resource("Seeder", "seeder-1", "Custom::Seeder", "UPDATE_COMPLETE");
        custom.getAttributes().put("__FlociServiceToken", handlerArn);
        StackResource handler = resource("Handler", "handler", "AWS::Lambda::Function", "CREATE_COMPLETE");
        handler.getAttributes().put("Arn", handlerArn);
        hold(stack, custom, handler);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(custom, REGION);
        deletes.verify(provisioner).delete(handler, REGION);
    }

    /**
     * An SSM parameter's {@code Value} is what it was given, such as a bucket's name, so a leftover
     * parameter goes before that bucket, as a stack delete orders {@code Value: !Ref Bucket}.
     */
    @Test
    void deleteStack_deletesALeftoverParameterBeforeTheBucketItsValueNames() {
        Stack stack = leftoverStack("delete-leftover-parameter", "{\"Bucket\": {\"Type\": \"AWS::S3::Bucket\"}}");
        StackResource parameter = resource("Parameter", "bucket-name", "AWS::SSM::Parameter", "UPDATE_COMPLETE");
        parameter.getAttributes().put("Value", "bucket-1");
        StackResource bucket = resource("Bucket", "bucket-1", "AWS::S3::Bucket", "CREATE_COMPLETE");
        hold(stack, parameter, bucket);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(parameter, REGION);
        deletes.verify(provisioner).delete(bucket, REGION);
    }

    /**
     * A leftover nested stack goes before the VPC its definition passed its child, where the child
     * created subnets: provisioning kept that VPC's id for it.
     */
    @Test
    void deleteStack_deletesALeftoverNestedStackBeforeTheVpcItPassedItsChild() {
        String childId = "arn:aws:cloudformation:us-east-1:000000000000:stack/parent-Network/1";
        Stack stack = leftoverStack("delete-leftover-nested-uses", "{\"Vpc\": {\"Type\": \"AWS::EC2::VPC\"}}");
        StackResource nested = resource("Network", childId, "AWS::CloudFormation::Stack", "UPDATE_COMPLETE");
        nested.getAttributes().put("Arn", childId);
        nested.getAttributes().put(CfnRollback.USES_ATTR_PREFIX + "Resource.Vpc", "vpc-1");
        StackResource vpc = resource("Vpc", "vpc-1", "AWS::EC2::VPC", "CREATE_COMPLETE");
        hold(stack, nested, vpc);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        assertEquals(List.of("Network", "Vpc"), deletesInProgress(stack));
    }

    /**
     * A resource a nested stack only {@code DependsOn} can be replaced by a later update that leaves
     * the nested stack alone, so the id kept for it is stale: the leftover still goes before what the
     * stack now holds under that logical id.
     */
    @Test
    void deleteStack_deletesALeftoverNestedStackBeforeAReplacedResourceItDependsOn() {
        String childId = "arn:aws:cloudformation:us-east-1:000000000000:stack/parent-Network/1";
        Stack stack = leftoverStack("delete-leftover-nested-stale", "{\"Policy\": {\"Type\": \"AWS::IAM::Policy\"}}");
        StackResource nested = resource("Network", childId, "AWS::CloudFormation::Stack", "UPDATE_COMPLETE");
        nested.getAttributes().put("Arn", childId);
        nested.getAttributes().put(CfnRollback.USES_ATTR_PREFIX + "Resource.Policy", "old-policy");
        StackResource policy = resource("Policy", "new-policy", "AWS::IAM::Policy", "UPDATE_COMPLETE");
        hold(stack, nested, policy);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        assertEquals(List.of("Network", "Policy"), deletesInProgress(stack));
    }

    /**
     * A nested stack's {@code Outputs.*} are its child's outputs: a leftover bucket named after
     * one, created after the nested stack, still goes first. (The nested stack keeps what its
     * definition named, here a resource the stack no longer holds.)
     */
    @Test
    void deleteStack_infersNoLeftoverOrderFromANestedStacksOutputs() {
        String childId = "arn:aws:cloudformation:us-east-1:000000000000:stack/parent-Names/1";
        Stack stack = leftoverStack("delete-leftover-nested-outputs", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource nested = resource("Names", childId, "AWS::CloudFormation::Stack", "UPDATE_COMPLETE");
        nested.getAttributes().put("Arn", childId);
        nested.getAttributes().put(CfnRollback.USES_ATTR_PREFIX + "Resource.Source", "source-1");
        nested.getAttributes().put("Outputs.BucketName", "bucket-1");
        StackResource bucket = resource("Bucket", "bucket-1", "AWS::S3::Bucket", "UPDATE_COMPLETE");
        hold(stack, queue, nested, bucket);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        assertEquals(List.of("Queue", "Bucket", "Names"), deletesInProgress(stack));
    }

    /**
     * A nested stack saved before Floci kept what its definition named still orders by its outputs,
     * as it did then: a leftover one echoing the VPC it was passed goes before that VPC.
     */
    @Test
    void deleteStack_ordersALeftoverNestedStackSavedWithoutKeptUsesByItsOutputs() {
        String childId = "arn:aws:cloudformation:us-east-1:000000000000:stack/parent-Network/1";
        Stack stack = leftoverStack("delete-leftover-nested-legacy", "{\"Vpc\": {\"Type\": \"AWS::EC2::VPC\"}}");
        StackResource nested = resource("Network", childId, "AWS::CloudFormation::Stack", "UPDATE_COMPLETE");
        nested.getAttributes().put("Arn", childId);
        nested.getAttributes().put("Outputs.VpcId", "vpc-1");
        StackResource vpc = resource("Vpc", "vpc-1", "AWS::EC2::VPC", "CREATE_COMPLETE");
        hold(stack, nested, vpc);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        assertEquals(List.of("Network", "Vpc"), deletesInProgress(stack));
    }

    /**
     * CDK's S3AutoDeleteObjects resource takes its bucket's name as its id, depends on the bucket
     * and its policy, and empties the bucket on delete. Left over with both, it goes first: what its
     * definition named outweighs the bucket's own name, and the policy does not count it as the
     * holder of its id it depends on.
     */
    @Test
    void deleteStack_deletesALeftoverAutoDeleteObjectsResourceBeforeItsPolicyAndBucket() {
        Stack stack = leftoverStack("delete-leftover-auto-delete", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource bucket = resource("Bucket", "bucket-1", "AWS::S3::Bucket", "UPDATE_COMPLETE");
        bucket.getAttributes().put("BucketName", "bucket-1");
        bucket.getAttributes().put("Arn", "arn:aws:s3:::bucket-1");
        StackResource policy = resource("BucketPolicy", "bucket-1", "AWS::S3::BucketPolicy", "UPDATE_COMPLETE");
        StackResource autoDelete = resource("AutoDelete", "bucket-1", "Custom::S3AutoDeleteObjects",
                "UPDATE_COMPLETE");
        autoDelete.getAttributes().put("__FlociServiceToken",
                "arn:aws:lambda:us-east-1:000000000000:function:provider");
        autoDelete.getAttributes().put(CfnRollback.USES_ATTR_PREFIX + "Resource.Bucket", "bucket-1");
        autoDelete.getAttributes().put(CfnRollback.USES_ATTR_PREFIX + "Resource.BucketPolicy", "bucket-1");
        hold(stack, queue, bucket, policy, autoDelete);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(autoDelete, REGION);
        deletes.verify(provisioner).delete(policy, REGION);
        deletes.verify(provisioner).delete(bucket, REGION);
    }

    /**
     * A bucket policy takes its bucket's name as its own physical id, so the bucket's
     * {@code BucketName} names the policy as well as itself. That is not a reference: the policy,
     * created after the bucket, still goes first.
     */
    @Test
    void deleteStack_deletesALeftoverBucketPolicyBeforeItsBucket() {
        Stack stack = leftoverStack("delete-leftover-policy", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource bucket = resource("Bucket", "bucket-1", "AWS::S3::Bucket", "UPDATE_COMPLETE");
        bucket.getAttributes().put("BucketName", "bucket-1");
        bucket.getAttributes().put("Arn", "arn:aws:s3:::bucket-1");
        StackResource policy = resource("Policy", "bucket-1", "AWS::S3::BucketPolicy", "UPDATE_COMPLETE");
        hold(stack, queue, bucket, policy);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(policy, REGION);
        deletes.verify(provisioner).delete(bucket, REGION);
    }

    /** A secret target attachment takes its secret's ARN as its physical id and still goes first. */
    @Test
    void deleteStack_deletesALeftoverSecretAttachmentBeforeItsSecret() {
        String secretArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf";
        Stack stack = leftoverStack("delete-leftover-attachment", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource secret = resource("Secret", secretArn, "AWS::SecretsManager::Secret", "UPDATE_COMPLETE");
        secret.getAttributes().put("Arn", secretArn);
        StackResource attachment = resource("Attachment", secretArn,
                "AWS::SecretsManager::SecretTargetAttachment", "UPDATE_COMPLETE");
        attachment.getAttributes().put("Id", secretArn);
        hold(stack, queue, secret, attachment);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(attachment, REGION);
        deletes.verify(provisioner).delete(secret, REGION);
    }

    /**
     * A leftover secret target attachment whose secret the template still names goes before that
     * secret: it shares the secret's id, and its type alone says which of the two depends on the
     * other.
     */
    @Test
    void deleteStack_deletesALeftoverSecretAttachmentBeforeTheSecretTheTemplateStillNames() {
        String secretArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf";
        Stack stack = leftoverStack("delete-leftover-attachment-ranked",
                "{\"Secret\": {\"Type\": \"AWS::SecretsManager::Secret\"}}");
        StackResource attachment = resource("Attachment", secretArn,
                "AWS::SecretsManager::SecretTargetAttachment", "UPDATE_COMPLETE");
        StackResource secret = resource("Secret", secretArn, "AWS::SecretsManager::Secret", "CREATE_COMPLETE");
        secret.getAttributes().put("Arn", secretArn);
        hold(stack, attachment, secret);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(attachment, REGION);
        deletes.verify(provisioner).delete(secret, REGION);
    }

    /**
     * A value equal to a leftover's own id can still name another kind of resource that shares the
     * id: a leftover rule named like the event bus it is on goes before that bus, which cannot be
     * deleted while it has rules.
     */
    @Test
    void deleteStack_deletesALeftoverRuleBeforeTheSameNamedBusItIsOn() {
        Stack stack = leftoverStack("delete-leftover-rule", "{\"Bus\": {\"Type\": \"AWS::Events::EventBus\"}}");
        StackResource rule = resource("Rule", "orders", "AWS::Events::Rule", "UPDATE_COMPLETE");
        rule.getAttributes().put("RuleName", "orders");
        rule.getAttributes().put("EventBusName", "orders");
        StackResource bus = resource("Bus", "orders", "AWS::Events::EventBus", "CREATE_COMPLETE");
        bus.getAttributes().put("Name", "orders");
        hold(stack, rule, bus);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(rule, REGION);
        deletes.verify(provisioner).delete(bus, REGION);
    }

    /**
     * A function URL on an alias takes the alias's ARN as its id, so a leftover one goes before the
     * alias, and a leftover alias does not count its own ARN as using the URL.
     */
    @Test
    void deleteStack_deletesALeftoverAliasUrlBeforeItsAlias() {
        String aliasArn = "arn:aws:lambda:us-east-1:000000000000:function:fn:live";
        Stack stack = leftoverStack("delete-leftover-url", "{\"Fn\": {\"Type\": \"AWS::Lambda::Function\"}}");
        StackResource url = resource("Url", aliasArn, "AWS::Lambda::Url", "UPDATE_COMPLETE");
        url.getAttributes().put("FunctionArn", aliasArn);
        StackResource alias = resource("Live", aliasArn, "AWS::Lambda::Alias", "UPDATE_COMPLETE");
        alias.getAttributes().put("AliasArn", aliasArn);
        StackResource fn = resource("Fn", "fn", "AWS::Lambda::Function", "CREATE_COMPLETE");
        hold(stack, url, alias, fn);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(url, REGION);
        deletes.verify(provisioner).delete(alias, REGION);
    }

    /** A leftover alias recorded before its leftover URL still does not count its ARN as using it. */
    @Test
    void deleteStack_aLeftoverAliasRecordedFirstStillGoesAfterItsUrl() {
        String aliasArn = "arn:aws:lambda:us-east-1:000000000000:function:fn:live";
        Stack stack = leftoverStack("delete-leftover-url-alias-first",
                "{\"Fn\": {\"Type\": \"AWS::Lambda::Function\"}}");
        StackResource alias = resource("Live", aliasArn, "AWS::Lambda::Alias", "UPDATE_COMPLETE");
        alias.getAttributes().put("AliasArn", aliasArn);
        StackResource url = resource("Url", aliasArn, "AWS::Lambda::Url", "UPDATE_COMPLETE");
        url.getAttributes().put("FunctionArn", aliasArn);
        StackResource fn = resource("Fn", "fn", "AWS::Lambda::Function", "CREATE_COMPLETE");
        hold(stack, alias, url, fn);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(url, REGION);
        deletes.verify(provisioner).delete(alias, REGION);
    }

    /** A cluster's capacity provider associations take the cluster's name as their id and go first. */
    @Test
    void deleteStack_deletesLeftoverCapacityProviderAssociationsBeforeTheirCluster() {
        Stack stack = leftoverStack("delete-leftover-associations", "{\"Cluster\": {\"Type\": \"AWS::ECS::Cluster\"}}");
        StackResource associations = resource("Associations", "cluster-1",
                "AWS::ECS::ClusterCapacityProviderAssociations", "UPDATE_COMPLETE");
        StackResource cluster = resource("Cluster", "cluster-1", "AWS::ECS::Cluster", "CREATE_COMPLETE");
        cluster.getAttributes().put("Arn", "arn:aws:ecs:us-east-1:000000000000:cluster/cluster-1");
        hold(stack, associations, cluster);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(associations, REGION);
        deletes.verify(provisioner).delete(cluster, REGION);
    }

    /** A leftover recorded without a type, as old state may be, costs the other leftovers nothing. */
    @Test
    void deleteStack_aLeftoverWithNoTypeKeepsTheOthersOrdered() {
        Stack stack = leftoverStack("delete-leftover-untyped", "{\"Vpc\": {\"Type\": \"AWS::EC2::VPC\"}}");
        StackResource subnet = resource("Subnet", "subnet-1", "AWS::EC2::Subnet", "UPDATE_COMPLETE");
        subnet.getAttributes().put("VpcId", "vpc-1");
        StackResource vpc = resource("Vpc", "vpc-1", "AWS::EC2::VPC", "CREATE_COMPLETE");
        StackResource untyped = resource("Legacy", "legacy-1", null, "UPDATE_COMPLETE");
        hold(stack, subnet, vpc, untyped);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(subnet, REGION);
        deletes.verify(provisioner).delete(vpc, REGION);
    }

    /** A leftover bucket policy recorded before its leftover bucket still goes first. */
    @Test
    void deleteStack_deletesALeftoverBucketPolicyRecordedFirstBeforeItsBucket() {
        Stack stack = leftoverStack("delete-leftover-policy-first", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource policy = resource("Policy", "bucket-1", "AWS::S3::BucketPolicy", "UPDATE_COMPLETE");
        StackResource bucket = resource("Bucket", "bucket-1", "AWS::S3::Bucket", "UPDATE_COMPLETE");
        bucket.getAttributes().put("BucketName", "bucket-1");
        hold(stack, queue, policy, bucket);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(policy, REGION);
        deletes.verify(provisioner).delete(bucket, REGION);
    }

    /**
     * A WAF web ACL's id is its own name, id and scope joined by {@code |}, so its first part names
     * no parent: a leftover ACL is not ordered after a leftover bucket of that name, and the two keep
     * their reverse creation order.
     */
    @Test
    void deleteStack_infersNoLeftoverOrderFromTheNameAWebAclsIdStartsWith() {
        Stack stack = leftoverStack("delete-leftover-web-acl", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource acl = resource("Acl", "shared|a1b2c3|REGIONAL", "AWS::WAFv2::WebACL", "UPDATE_COMPLETE");
        acl.getAttributes().put("Id", "a1b2c3");
        StackResource bucket = resource("Bucket", "shared", "AWS::S3::Bucket", "UPDATE_COMPLETE");
        hold(stack, queue, acl, bucket);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(bucket, REGION);
        deletes.verify(provisioner).delete(acl, REGION);
    }

    /**
     * A leftover whose physical id extends another's, such as {@code parent-1/child-1}, belongs to
     * that resource and goes before it, whatever order the two were recorded in.
     */
    @Test
    void deleteStack_deletesALeftoverWhoseIdExtendsAnothersBeforeIt() {
        Stack stack = leftoverStack("delete-leftover-derived", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource queue = resource("Queue", "queue-1", "AWS::SQS::Queue", "CREATE_COMPLETE");
        StackResource child = resource("Child", "parent-1/child-1", "AWS::Test::Child", "UPDATE_COMPLETE");
        StackResource parent = resource("Parent", "parent-1", "AWS::Test::Parent", "UPDATE_COMPLETE");
        hold(stack, queue, child, parent);

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(child, REGION);
        deletes.verify(provisioner).delete(parent, REGION);
    }

    /**
     * A long chain of leftovers, each naming the next and recorded first link first, is ordered
     * without recursion: on a thread with a small stack the walk still completes, first link first,
     * instead of overflowing.
     */
    @Test
    void deleteStack_ordersALongLeftoverChainWithoutOverflowingTheStack() throws Exception {
        int length = 10_000;
        Stack stack = leftoverStack("delete-leftover-chain-long", "{\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}");
        StackResource[] chain = new StackResource[length];
        for (int i = 0; i < length; i++) {
            chain[i] = resource("Link" + i, "link-" + i, "AWS::Test::Link", "UPDATE_COMPLETE");
            if (i + 1 < length) {
                chain[i].getAttributes().put("NextId", "link-" + (i + 1));
            }
            stack.getResources().put(chain[i].getLogicalId(), chain[i]);
        }
        hold(stack);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(null, () -> {
            try {
                service.deleteStackResources(stack, REGION, ACCOUNT);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "small-stack", 256 * 1024);
        worker.start();
        worker.join(60_000);

        assertFalse(worker.isAlive(), "the walk did not finish");

        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(chain[0], REGION);
        deletes.verify(provisioner).delete(chain[length - 1], REGION);
    }

    private static Stack leftoverStack(String name, String resources) {
        Stack stack = new Stack();
        stack.setStackName(name);
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_COMPLETE");
        stack.setTemplateBody("{\"Resources\": " + resources + "}");
        return stack;
    }

    private void hold(Stack stack, StackResource... resources) {
        for (StackResource resource : resources) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());
    }

    /** The resources a stack delete reached, in order, as its DELETE_IN_PROGRESS events name them. */
    private static List<String> deletesInProgress(Stack stack) {
        return stack.getEvents().stream()
                .filter(event -> "DELETE_IN_PROGRESS".equals(event.getResourceStatus()))
                .map(StackEvent::getLogicalResourceId)
                .toList();
    }

    /**
     * A leftover may name another leftover: a VPC an update added and a subnet in it, both left by
     * a failed rollback. The subnet goes before its VPC, which names only itself.
     */
    @Test
    void deleteStack_deletesALeftoverBeforeTheLeftoverItsAttributesName() {
        Stack stack = new Stack();
        stack.setStackName("delete-leftover-chain");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_FAILED");
        stack.setTemplateBody("""
                {"Resources": {
                  "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.0.0.0/16"}}
                }}""");
        StackResource vpc = resource("Vpc", "vpc-1", "AWS::EC2::VPC", "CREATE_COMPLETE");
        vpc.getAttributes().put("VpcId", "vpc-1");
        StackResource leftoverSubnet = resource("SubnetX", "subnet-x", "AWS::EC2::Subnet", "UPDATE_COMPLETE");
        leftoverSubnet.getAttributes().put("SubnetId", "subnet-x");
        leftoverSubnet.getAttributes().put("VpcId", "vpc-2");
        StackResource leftoverVpc = resource("Vpc2", "vpc-2", "AWS::EC2::VPC", "UPDATE_COMPLETE");
        leftoverVpc.getAttributes().put("VpcId", "vpc-2");
        for (StackResource resource : new StackResource[] {vpc, leftoverSubnet, leftoverVpc}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        InOrder deletes = inOrder(provisioner);
        deletes.verify(provisioner).delete(leftoverSubnet, REGION);
        deletes.verify(provisioner).delete(leftoverVpc, REGION);
    }

    /**
     * The resume runs on the operation executor, whose future nobody reads. An unexpected failure
     * must still settle the stack in UPDATE_ROLLBACK_FAILED, keeping what a retry needs, instead
     * of stranding it in UPDATE_ROLLBACK_IN_PROGRESS, where ContinueUpdateRollback refuses it.
     * A resource the failed pass already rolled back is no longer owed, so the retry does not roll it back again.
     */
    @Test
    void resumeUpdateRollback_whenTheBodyThrows_settlesInUpdateRollbackFailedAndCanBeContinued()
            throws Exception {
        FailingClock clock = new FailingClock();
        CloudFormationService resumeService = serviceReading(clock);
        resumeService.createChangeSet("resume-throws", "create", "CREATE",
                "{\"Resources\":{\"LogGroup\":{\"Type\":\"AWS::Logs::LogGroup\"}}}", null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        Stack stack = resumeService.describeStacks("resume-throws", REGION, ACCOUNT).getFirst();
        StackResource logGroup = resource("LogGroup", "/lg", "AWS::Logs::LogGroup", "UPDATE_FAILED");
        stack.getResources().put(logGroup.getLogicalId(), logGroup);
        StackUpdateSnapshot previousState = new StackUpdateSnapshot(
                "previous-template", "previous-original", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("LogGroup", resource("LogGroup", "/lg", "AWS::Logs::LogGroup", "CREATE_COMPLETE")));
        stack.setUpdateRollbackSnapshot(previousState);
        stack.setUpdateRollbackResourceIds(List.of("LogGroup"));
        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
        when(provisioner.rollbackUpdate(eq(logGroup), any())).thenReturn(true, false);
        clock.failNextRead("simulated clock failure");

        resumeService.resumeUpdateRollback(stack, List.of(), false, REGION);

        assertEquals("UPDATE_ROLLBACK_FAILED", stack.getStatus());
        assertEquals("simulated clock failure", stack.getStatusReason());
        StackEvent last = stack.getEvents().getLast();
        assertEquals("resume-throws", last.getLogicalResourceId());
        assertEquals("AWS::CloudFormation::Stack", last.getResourceType());
        assertEquals("UPDATE_ROLLBACK_FAILED", last.getResourceStatus());
        assertEquals("simulated clock failure", last.getResourceStatusReason());
        assertEquals(previousState, stack.getUpdateRollbackSnapshot());
        assertEquals(List.of(), stack.getUpdateRollbackResourceIds());

        resumeService.continueUpdateRollback("resume-throws", List.of(), false, REGION).get(10, TimeUnit.SECONDS);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack.getStatus());
        assertNull(stack.getUpdateRollbackSnapshot());
        verify(provisioner).rollbackUpdate(eq(logGroup), any());
    }

    /**
     * A skip already reported is no longer owed a rollback when an unexpected failure settles the
     * stack, or a later retry would roll back a resource the caller skipped.
     */
    @Test
    void resumeUpdateRollback_whenTheBodyThrowsAfterASkip_noLongerOwesTheSkippedResource() throws Exception {
        FailingClock clock = new FailingClock();
        CloudFormationService resumeService = serviceReading(clock);
        resumeService.createChangeSet("resume-throws-after-skip", "create", "CREATE",
                "{\"Resources\":{\"LogGroup\":{\"Type\":\"AWS::Logs::LogGroup\"}}}", null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        Stack stack = resumeService.describeStacks("resume-throws-after-skip", REGION, ACCOUNT).getFirst();
        StackResource skipped = resource("Skipped", "/skipped", "AWS::Logs::LogGroup", "UPDATE_FAILED");
        stack.getResources().put(skipped.getLogicalId(), skipped);
        StackUpdateSnapshot previousState = new StackUpdateSnapshot(
                "previous-template", "previous-original", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("Skipped", resource("Skipped", "/skipped", "AWS::Logs::LogGroup", "CREATE_COMPLETE")));
        stack.setUpdateRollbackSnapshot(previousState);
        stack.setUpdateRollbackResourceIds(List.of("Skipped"));
        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
        clock.failNextRead("simulated clock failure");

        resumeService.resumeUpdateRollback(stack, List.of("Skipped"), false, REGION);

        assertEquals("UPDATE_ROLLBACK_FAILED", stack.getStatus());
        assertEquals("simulated clock failure", stack.getStatusReason());
        assertEquals("UPDATE_COMPLETE", skipped.getStatus());
        assertEquals("Resource skipped during UpdateRollback", skipped.getStatusReason());
        assertEquals(previousState, stack.getUpdateRollbackSnapshot());
        assertEquals(List.of(), stack.getUpdateRollbackResourceIds());

        resumeService.continueUpdateRollback("resume-throws-after-skip", List.of(), false, REGION)
                .get(10, TimeUnit.SECONDS);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack.getStatus());
        assertEquals("UPDATE_COMPLETE", skipped.getStatus());
        verify(provisioner, never()).rollbackUpdate(any(StackResource.class), any());
    }

    /**
     * ForceRollback skips every UPDATE_FAILED resource, reporting each as a ResourcesToSkip skip
     * is, and a rollback it completes says in the stack's reason that resources were skipped.
     */
    @Test
    void resumeUpdateRollback_forced_skipsEveryUpdateFailedResourceAndExplainsTheCompletion() {
        Stack stack = new Stack();
        stack.setStackName("forced-rollback");
        stack.setStackId("stack-id");
        stack.setRegion(REGION);
        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
        stack.setTemplateBody("failed-template");
        StackResource role = resource("Role", "role", "AWS::IAM::Role", "UPDATE_FAILED");
        StackResource stable = resource("Stable", "/stable", "AWS::Logs::LogGroup", "CREATE_COMPLETE");
        StackResource logGroup = resource("LogGroup", "/lg", "AWS::Logs::LogGroup", "UPDATE_FAILED");
        for (StackResource resource : new StackResource[] {role, stable, logGroup}) {
            stack.getResources().put(resource.getLogicalId(), resource);
        }
        stack.setUpdateRollbackSnapshot(new StackUpdateSnapshot(
                "previous-template", "previous-original", Map.of(), Map.of(), Map.of("Out", "v"), Map.of(),
                Map.of(), Map.of(
                        "Role", resource("Role", "role", "AWS::IAM::Role", "CREATE_COMPLETE"),
                        "Stable", resource("Stable", "/stable", "AWS::Logs::LogGroup", "CREATE_COMPLETE"),
                        "LogGroup", resource("LogGroup", "/lg", "AWS::Logs::LogGroup", "CREATE_COMPLETE"))));
        stack.setUpdateRollbackResourceIds(List.of("Role", "LogGroup"));

        service.resumeUpdateRollback(stack, List.of(), true, REGION);

        for (StackResource skipped : List.of(role, logGroup)) {
            assertEquals("UPDATE_COMPLETE", skipped.getStatus(), skipped.getLogicalId());
            assertEquals("Resource skipped during UpdateRollback", skipped.getStatusReason(), skipped.getLogicalId());
        }
        verify(provisioner, never()).rollbackUpdate(any(StackResource.class), any());
        assertEquals(List.of(
                        "Role UPDATE_COMPLETE Resource skipped during UpdateRollback",
                        "LogGroup UPDATE_COMPLETE Resource skipped during UpdateRollback",
                        "forced-rollback UPDATE_ROLLBACK_COMPLETE " + FORCED_ROLLBACK_REASON),
                stack.getEvents().stream()
                        .map(event -> event.getLogicalResourceId() + " " + event.getResourceStatus() + " "
                                + event.getResourceStatusReason())
                        .toList());
        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack.getStatus());
        assertEquals(FORCED_ROLLBACK_REASON, stack.getStatusReason());
        assertEquals(FORCED_ROLLBACK_REASON, stack.getEvents().getLast().getResourceStatusReason());
        assertEquals("previous-template", stack.getTemplateBody());
        assertEquals("v", stack.getOutputs().get("Out"));
        assertNull(stack.getUpdateRollbackSnapshot());
        assertNull(stack.getUpdateRollbackResourceIds());
    }

    /** A stack whose create finished, created through the service so it is in the live map. */
    private Stack liveStack(String stackName) {
        service.createChangeSet(stackName, "create", "CREATE",
                "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}", null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        Stack stack = service.describeStacks(stackName, REGION, ACCOUNT).getFirst();
        stack.getChangeSets().clear();
        stack.setStatus("CREATE_COMPLETE");
        return stack;
    }

    /** A live stack whose delete failed on both of its buckets, Data and Spare. */
    private Stack deleteFailedStack(String stackName) {
        Stack stack = liveStack(stackName);
        stack.setStatus("DELETE_FAILED");
        stack.getResources().clear();
        for (String logicalId : List.of("Data", "Spare")) {
            StackResource bucket = resource(logicalId, logicalId.toLowerCase() + "-bucket", "AWS::S3::Bucket",
                    "DELETE_FAILED");
            bucket.setStatusReason("The bucket you tried to delete is not empty");
            stack.getResources().put(logicalId, bucket);
        }
        when(provisioner.completeDeleteCleanup(any())).thenReturn(UpdateCleanupResult.notApplicable());
        return stack;
    }

    private static List<String> stackEventStatuses(Stack stack) {
        return stack.getEvents().stream()
                .filter(event -> "AWS::CloudFormation::Stack".equals(event.getResourceType()))
                .map(StackEvent::getResourceStatus)
                .toList();
    }

    /** One status write, with the reason and the latest stack-level event it was published with. */
    private record StatusWrite(String status, String reason, String latestStackEvent) {
    }

    /**
     * Records every status write, so a test sees a status DescribeStacks could have served for a
     * moment, not only the one the stack settled in.
     */
    private static final class StatusRecordingStack extends Stack {
        private final List<StatusWrite> writes = new ArrayList<>();

        @Override
        public void setStatus(String status) {
            List<String> stackEvents = stackEventStatuses(this);
            writes.add(new StatusWrite(status, getStatusReason(),
                    stackEvents.isEmpty() ? null : stackEvents.getLast()));
            super.setStatus(status);
        }

        List<StatusWrite> writes() {
            return writes;
        }

        List<String> statuses() {
            return writes.stream().map(StatusWrite::status).toList();
        }

        void assertEachStatusFollowsItsEvent() {
            for (StatusWrite write : writes) {
                assertEquals(write.status(), write.latestStackEvent(), "event published after " + write.status());
            }
        }
    }

    private static StackEvent stackEvent(Stack stack, String status) {
        StackEvent event = resourceEvent(stack, stack.getStackName(), status, null);
        event.setPhysicalResourceId(stack.getStackId());
        event.setResourceType("AWS::CloudFormation::Stack");
        return event;
    }

    private static StackEvent resourceEvent(Stack stack, String logicalId, String status, String reason) {
        StackEvent event = new StackEvent();
        event.setStackName(stack.getStackName());
        event.setLogicalResourceId(logicalId);
        event.setResourceStatus(status);
        event.setResourceStatusReason(reason);
        return event;
    }

    private static StackResource resource(String logicalId, String physicalId, String resourceType, String status) {
        StackResource resource = new StackResource();
        resource.setLogicalId(logicalId);
        resource.setPhysicalId(physicalId);
        resource.setResourceType(resourceType);
        resource.setStatus(status);
        return resource;
    }

    /**
     * A provisioner of {@code type} built without its dependencies: only what it says of its types,
     * its resource types and the id hooks, is called on it.
     */
    private static <T extends CfnResourceProvisioner> T real(Class<T> type) {
        return mock(type, CALLS_REAL_METHODS);
    }

    /**
     * A provisioner that serves {@code type}, for routing through a real registry, and answers the
     * interface's own defaults as a provisioner that overrides none of them does.
     */
    private static CfnResourceProvisioner serving(String type) {
        CfnResourceProvisioner serving = mock(CfnResourceProvisioner.class, CALLS_REAL_METHODS);
        doReturn(Set.of(type)).when(serving).resourceTypes();
        return serving;
    }

    /** A service over the shared provisioner that reads the time from {@code clock}. */
    private CloudFormationService serviceReading(Clock clock) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        return new CloudFormationService(provisioner, mock(S3Service.class), mock(SsmService.class),
                mock(GetStackOutputRoleAuthorizer.class), mock(CfnDynamicReferences.class), new ObjectMapper(), config,
                regionResolver, clock, new InMemoryStorageFactory());
    }

    /** The stack as {@code PersistentStorage} writes it and reads it back. */
    private static Stack persistedCopy(Stack stack) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper.readValue(mapper.writeValueAsString(stack), Stack.class);
    }

    /** The system clock, except that the read after {@link #failNextRead} throws. */
    private static final class FailingClock extends Clock {
        private final AtomicReference<String> failure = new AtomicReference<>();

        void failNextRead(String message) {
            failure.set(message);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            String message = failure.getAndSet(null);
            if (message != null) {
                throw new IllegalStateException(message);
            }
            return Instant.now();
        }
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                         TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }
}
