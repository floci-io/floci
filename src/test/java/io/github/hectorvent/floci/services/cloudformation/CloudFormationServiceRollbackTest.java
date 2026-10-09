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
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackUpdateSnapshot;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudformation.provisioners.UpdateCleanupResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
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
 * failed update rollback keeps so ContinueUpdateRollback can retry it, and that a stack which
 * finished deleting is no longer updatable.
 */
class CloudFormationServiceRollbackTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private CfnResourceDispatcher provisioner;
    private CloudFormationService service;

    @BeforeEach
    void setUp() {
        provisioner = mock(CfnResourceDispatcher.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);

        service = new CloudFormationService(
                provisioner,
                mock(S3Service.class),
                mock(SsmService.class),
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
        when(provisioner.completeUpdate(any())).thenReturn(UpdateCleanupResult.notApplicable());

        service.deleteStackResources(stack, REGION, ACCOUNT);

        assertEquals("DELETE_COMPLETE", stack.getStatus());
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
        when(provisioner.completeUpdate(any())).thenReturn(UpdateCleanupResult.notApplicable());
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
        when(provisioner.completeUpdate(any())).thenReturn(UpdateCleanupResult.notApplicable());

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
        when(provisioner.completeUpdate(any())).thenReturn(UpdateCleanupResult.notApplicable());

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
        when(provisioner.completeUpdate(any())).thenReturn(UpdateCleanupResult.notApplicable());

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
        when(provisioner.completeUpdate(any())).thenReturn(UpdateCleanupResult.notApplicable());

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
        service.resumeUpdateRollback(restored, List.of(), REGION);

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

        service.resumeUpdateRollback(stack, List.of(), REGION);

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

        service.resumeUpdateRollback(stack, List.of("Nested.Child"), REGION);

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
        service.resumeUpdateRollback(stack, List.of(), REGION);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack.getStatus());
        assertEquals("CREATE_COMPLETE", dashboard.getStatus());
        verify(provisioner, times(2)).rollbackUpdate(eq(dashboard), any());
    }

    private static StackResource resource(String logicalId, String physicalId, String resourceType, String status) {
        StackResource resource = new StackResource();
        resource.setLogicalId(logicalId);
        resource.setPhysicalId(physicalId);
        resource.setResourceType(resourceType);
        resource.setStatus(status);
        return resource;
    }

    /** The stack as {@code PersistentStorage} writes it and reads it back. */
    private static Stack persistedCopy(Stack stack) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper.readValue(mapper.writeValueAsString(stack), Stack.class);
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
