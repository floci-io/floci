package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Batch CFN provisioner in isolation, with only {@link BatchService} mocked. */
class BatchCfnProvisionerTest {

    private static final String CE_ARN =
            "arn:aws:batch:us-east-1:000000000000:compute-environment/my-stack-Compute-ab12cd";
    private static final String JQ_ARN =
            "arn:aws:batch:us-east-1:000000000000:job-queue/my-stack-Queue-ab12cd";
    private static final String JD_ARN =
            "arn:aws:batch:us-east-1:000000000000:job-definition/my-stack-Definition-ab12cd:1";

    private final BatchService batch = mock(BatchService.class);
    private final BatchCfnProvisioner provisioner = new BatchCfnProvisioner(batch);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // The engine is a collaborator; these cases use scalar properties and flat objects, so a
        // pass-through stub keeps this a true isolated unit test.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setResourceType(type);
        r.setLogicalId(logicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode describeWith(String key, String arn) {
        ObjectNode out = mapper.createObjectNode();
        out.putArray(key).addObject().put("arn", arn);
        return out;
    }

    private ObjectNode describeEmpty(String key) {
        ObjectNode out = mapper.createObjectNode();
        out.putArray(key);
        return out;
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void computeEnvironmentSetsPhysicalIdAndTheSchemaAttribute() {
        when(batch.createComputeEnvironment(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("computeEnvironmentArn", CE_ARN));
        ObjectNode props = mapper.createObjectNode();
        props.put("ComputeEnvironmentName", "envy");
        props.put("Type", "MANAGED");

        StackResource r = resource("AWS::Batch::ComputeEnvironment", "Compute");
        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> req = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).createComputeEnvironment(req.capture(), anyString());
        assertEquals("envy", req.getValue().path("computeEnvironmentName").asText());
        assertEquals("MANAGED", req.getValue().path("type").asText());
        // Ref resolves to the physical id, and ComputeEnvironmentArn is the type's only
        // top-level read-only property in the registry schema.
        assertEquals(CE_ARN, r.getPhysicalId());
        assertEquals(CE_ARN, r.getAttributes().get("ComputeEnvironmentArn"));
        assertEquals("envy", r.getAttributes().get("ComputeEnvironmentName"));
    }

    @Test
    void jobQueueCarriesPriorityAndComputeEnvironmentOrder() {
        when(batch.createJobQueue(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("jobQueueArn", JQ_ARN));
        ObjectNode props = mapper.createObjectNode();
        props.put("JobQueueName", "queue");
        props.put("Priority", "7");
        props.putArray("ComputeEnvironmentOrder").addObject()
                .put("Order", 1).put("ComputeEnvironment", CE_ARN);

        StackResource r = resource("AWS::Batch::JobQueue", "Queue");
        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> req = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).createJobQueue(req.capture(), anyString());
        assertEquals(7, req.getValue().path("priority").asInt());
        assertEquals(CE_ARN,
                req.getValue().path("computeEnvironmentOrder").get(0).path("computeEnvironment").asText());
        assertEquals(JQ_ARN, r.getAttributes().get("JobQueueArn"));
    }

    @Test
    void jobDefinitionDefaultsTypeToContainer() {
        when(batch.registerJobDefinition(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("jobDefinitionArn", JD_ARN).put("revision", 1));
        ObjectNode props = mapper.createObjectNode();
        props.put("JobDefinitionName", "def");
        props.put("Type", "container");
        props.putObject("ContainerProperties").put("Image", "busybox");

        StackResource r = resource("AWS::Batch::JobDefinition", "Definition");
        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> req = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).registerJobDefinition(req.capture(), anyString());
        assertEquals("container", req.getValue().path("type").asText());
        assertEquals("busybox", req.getValue().path("containerProperties").path("image").asText());
        assertEquals(JD_ARN, r.getAttributes().get("JobDefinitionArn"));
    }

    // ── update ───────────────────────────────────────────────────────────────

    private ProvisionContext updateCtx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    @Test
    void updatingAComputeEnvironmentUpdatesInPlaceInsteadOfCreatingAgain() {
        // createComputeEnvironment rejects a duplicate name, so the second UpdateStack used to
        // fail the whole stack rather than update the environment.
        StackResource r = resource("AWS::Batch::ComputeEnvironment", "Compute");
        r.getAttributes().put("ComputeEnvironmentName", "envy");
        ObjectNode props = mapper.createObjectNode();
        props.put("ComputeEnvironmentName", "envy");
        props.put("Type", "MANAGED");
        props.put("State", "DISABLED");

        provisioner.provision(r, props, updateCtx(CE_ARN));

        ArgumentCaptor<JsonNode> update = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).updateComputeEnvironment(update.capture());
        verify(batch, never()).createComputeEnvironment(any(), anyString());
        assertEquals(CE_ARN, update.getValue().path("computeEnvironment").asText());
        assertEquals("DISABLED", update.getValue().path("state").asText());
        assertEquals(CE_ARN, r.getPhysicalId(), "an in-place update keeps the physical id");
    }

    @Test
    void updatingAJobQueueUpdatesInPlace() {
        StackResource r = resource("AWS::Batch::JobQueue", "Queue");
        r.getAttributes().put("JobQueueName", "queue");
        ObjectNode props = mapper.createObjectNode();
        props.put("JobQueueName", "queue");
        props.put("Priority", "9");

        provisioner.provision(r, props, updateCtx(JQ_ARN));

        ArgumentCaptor<JsonNode> update = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).updateJobQueue(update.capture());
        verify(batch, never()).createJobQueue(any(), anyString());
        assertEquals(9, update.getValue().path("priority").asInt());
        assertEquals(JQ_ARN, r.getPhysicalId());
    }

    @Test
    void renamingAComputeEnvironmentCreatesRatherThanUpdates() {
        // The name is createOnly, so a changed name is a replacing update: it still arrives with a
        // prior physical id, but must create a new entity rather than mutate one that has that name.
        when(batch.createComputeEnvironment(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("computeEnvironmentArn", CE_ARN + "-new"));
        StackResource r = resource("AWS::Batch::ComputeEnvironment", "Compute");
        r.getAttributes().put("ComputeEnvironmentName", "envy");
        ObjectNode props = mapper.createObjectNode();
        props.put("ComputeEnvironmentName", "renamed");
        props.put("Type", "MANAGED");

        provisioner.provision(r, props, updateCtx(CE_ARN));

        verify(batch).createComputeEnvironment(any(), anyString());
        verify(batch, never()).updateComputeEnvironment(any());
        assertEquals(CE_ARN + "-new", r.getPhysicalId());
    }

    @Test
    void anUnnamedComputeEnvironmentKeepsItsGeneratedNameAcrossUpdates() {
        // The defect this guards: generating unconditionally gave an unnamed resource a fresh
        // random name on every update, creating a second environment and orphaning the first.
        StackResource r = resource("AWS::Batch::ComputeEnvironment", "Compute");
        r.getAttributes().put("ComputeEnvironmentName", "my-stack-Compute-ab12cd");

        provisioner.provision(r, mapper.createObjectNode().put("Type", "MANAGED"), updateCtx(CE_ARN));

        verify(batch).updateComputeEnvironment(any());
        verify(batch, never()).createComputeEnvironment(any(), anyString());
    }

    @Test
    void anUnnamedResourceRecoversItsNameFromThePriorArn() {
        // Stacks provisioned before the name attribute was recorded still have to update in place
        // rather than create a duplicate.
        StackResource r = resource("AWS::Batch::JobQueue", "Queue");

        provisioner.provision(r, mapper.createObjectNode().put("Priority", "1"), updateCtx(JQ_ARN));

        verify(batch).updateJobQueue(any());
        verify(batch, never()).createJobQueue(any(), anyString());
    }

    @Test
    void updatingAJobDefinitionRegistersANewRevisionUnderTheSameName() {
        // AWS updates a job definition by recording a new revision, which is what
        // registerJobDefinition already does, so there is no separate update call.
        when(batch.registerJobDefinition(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("jobDefinitionArn", JD_ARN).put("revision", 2));
        StackResource r = resource("AWS::Batch::JobDefinition", "Definition");
        r.getAttributes().put("JobDefinitionName", "def");
        ObjectNode props = mapper.createObjectNode();
        props.put("Type", "container");
        props.putObject("ContainerProperties").put("Image", "busybox");

        provisioner.provision(r, props, updateCtx(JD_ARN));

        ArgumentCaptor<JsonNode> req = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).registerJobDefinition(req.capture(), anyString());
        assertEquals("def", req.getValue().path("jobDefinitionName").asText());
    }

    // ── required properties ──────────────────────────────────────────────────

    @Test
    void aJobQueueWithoutPriorityIsRejected() {
        // Priority is the schema's only required property for this type. The switch substituted 1,
        // so a template missing it applied cleanly here and failed on AWS.
        ObjectNode props = mapper.createObjectNode();
        props.put("JobQueueName", "queue");

        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::Batch::JobQueue", "Queue"), props, ctx()));

        assertEquals("ValidationError", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("Priority"));
        verify(batch, never()).createJobQueue(any(), anyString());
    }

    @Test
    void aComputeEnvironmentWithoutTypeIsRejected() {
        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::Batch::ComputeEnvironment", "Compute"),
                mapper.createObjectNode().put("ComputeEnvironmentName", "envy"), ctx()));

        assertEquals("ValidationError", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("Type"));
    }

    @Test
    void aJobDefinitionWithoutTypeIsRejected() {
        // The switch defaulted this to "container"; the schema makes it required.
        AwsException thrown = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::Batch::JobDefinition", "Definition"),
                mapper.createObjectNode().put("JobDefinitionName", "def"), ctx()));

        assertEquals("ValidationError", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("Type"));
    }

    @Test
    void aJobDefinitionDoesNotAdvertiseARevisionAttribute() {
        // Revision is not a property of AWS::Batch::JobDefinition in the registry schema and the
        // legacy spec gives the type no attributes either, so Fn::GetAtt on it fails on AWS.
        // Offering it here would let a template pass on floci and break on AWS.
        when(batch.registerJobDefinition(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("jobDefinitionArn", JD_ARN).put("revision", 1));
        ObjectNode props = mapper.createObjectNode();
        props.put("JobDefinitionName", "def");
        props.put("Type", "container");
        props.putObject("ContainerProperties").put("Image", "busybox");

        StackResource r = resource("AWS::Batch::JobDefinition", "Definition");
        provisioner.provision(r, props, ctx());

        assertFalse(r.getAttributes().containsKey("Revision"));
        assertEquals(JD_ARN, r.getAttributes().get("JobDefinitionArn"));
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void deletingAJobQueueDisablesItFirst() {
        // DeleteJobQueue refuses an ENABLED queue, as on AWS, so the disable has to come first
        // and in this order.
        when(batch.describeJobQueues(any())).thenReturn(describeWith("jobQueues", JQ_ARN));

        provisioner.delete("AWS::Batch::JobQueue", JQ_ARN, "us-east-1");

        InOrder order = inOrder(batch);
        ArgumentCaptor<JsonNode> disable = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(batch).updateJobQueue(disable.capture());
        order.verify(batch).deleteJobQueue(any());
        assertEquals("DISABLED", disable.getValue().path("state").asText());
        assertEquals(JQ_ARN, disable.getValue().path("jobQueue").asText());
    }

    @Test
    void deletingAComputeEnvironmentDisablesItFirst() {
        when(batch.describeComputeEnvironments(any()))
                .thenReturn(describeWith("computeEnvironments", CE_ARN));

        provisioner.delete("AWS::Batch::ComputeEnvironment", CE_ARN, "us-east-1");

        InOrder order = inOrder(batch);
        ArgumentCaptor<JsonNode> disable = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(batch).updateComputeEnvironment(disable.capture());
        order.verify(batch).deleteComputeEnvironment(any());
        assertEquals("DISABLED", disable.getValue().path("state").asText());
    }

    @Test
    void deletingAJobDefinitionDeregistersIt() {
        when(batch.describeJobDefinitions(any())).thenReturn(describeWith("jobDefinitions", JD_ARN));

        provisioner.delete("AWS::Batch::JobDefinition", JD_ARN, "us-east-1");

        ArgumentCaptor<JsonNode> req = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).deregisterJobDefinition(req.capture());
        assertEquals(JD_ARN, req.getValue().path("jobDefinition").asText());
    }

    @Test
    void deletingAnEntityThatIsAlreadyGoneDoesNothing() {
        // A repeated stack delete must be idempotent. It matters most for the job definition:
        // deregister throws when the definition is missing, so the existence check is the guard.
        when(batch.describeComputeEnvironments(any())).thenReturn(describeEmpty("computeEnvironments"));
        when(batch.describeJobQueues(any())).thenReturn(describeEmpty("jobQueues"));
        when(batch.describeJobDefinitions(any())).thenReturn(describeEmpty("jobDefinitions"));

        provisioner.delete("AWS::Batch::ComputeEnvironment", CE_ARN, "us-east-1");
        provisioner.delete("AWS::Batch::JobQueue", JQ_ARN, "us-east-1");
        provisioner.delete("AWS::Batch::JobDefinition", JD_ARN, "us-east-1");

        verify(batch, never()).updateComputeEnvironment(any());
        verify(batch, never()).deleteComputeEnvironment(any());
        verify(batch, never()).updateJobQueue(any());
        verify(batch, never()).deleteJobQueue(any());
        verify(batch, never()).deregisterJobDefinition(any());
    }

    @Test
    void aRefusedDeletePropagatesInsteadOfBeingSwallowed() {
        // The failure that must not be tolerated: a compute environment still attached to a queue.
        // Swallowing it would report a green stack delete over a resource that is still there.
        when(batch.describeComputeEnvironments(any()))
                .thenReturn(describeWith("computeEnvironments", CE_ARN));
        when(batch.deleteComputeEnvironment(any())).thenThrow(new AwsException("ClientException",
                "Cannot delete compute environment still associated with a job queue: envy", 400));

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::Batch::ComputeEnvironment", CE_ARN, "us-east-1"));
        assertTrue(thrown.getMessage().contains("still associated with a job queue"));
    }

    @Test
    void deleteWithoutAPhysicalIdTouchesNothing() {
        provisioner.delete("AWS::Batch::JobQueue", null, "us-east-1");
        provisioner.delete("AWS::Batch::JobQueue", "", "us-east-1");

        verify(batch, never()).describeJobQueues(any());
        verify(batch, never()).deleteJobQueue(any());
    }

    // ── replacement cleanup ──────────────────────────────────────────────────

    @Test
    void renamingAComputeEnvironmentRecordsTheDisplacedOneForCleanup() {
        // The leak this closes: the rename created the replacement, but the engine was never told
        // a replacement happened, so the old environment stayed live in Batch forever.
        when(batch.createComputeEnvironment(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("computeEnvironmentArn", CE_ARN + "-new"));
        StackResource r = resource("AWS::Batch::ComputeEnvironment", "Compute");
        r.getAttributes().put("ComputeEnvironmentName", "envy");
        ObjectNode props = mapper.createObjectNode();
        props.put("ComputeEnvironmentName", "renamed");
        props.put("Type", "MANAGED");

        provisioner.provision(r, props, updateCtx(CE_ARN));

        assertTrue(provisioner.hasReplacementUpdate(r), "the engine has to learn a replacement happened");
        assertEquals(CE_ARN, provisioner.updateCleanupPhysicalId(r), "the displaced environment is owed a delete");
    }

    @Test
    void completingTheUpdateDeletesTheDisplacedComputeEnvironment() {
        when(batch.createComputeEnvironment(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("computeEnvironmentArn", CE_ARN + "-new"));
        when(batch.describeComputeEnvironments(any()))
                .thenReturn(describeWith("computeEnvironments", CE_ARN));
        StackResource r = resource("AWS::Batch::ComputeEnvironment", "Compute");
        r.getAttributes().put("ComputeEnvironmentName", "envy");
        ObjectNode props = mapper.createObjectNode();
        props.put("ComputeEnvironmentName", "renamed");
        props.put("Type", "MANAGED");
        provisioner.provision(r, props, updateCtx(CE_ARN));

        UpdateCleanupResult result = provisioner.completeUpdate(r);

        assertTrue(result.applicable());
        assertTrue(result.complete(), "the displaced environment was deleted");
        ArgumentCaptor<JsonNode> deleted = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).deleteComputeEnvironment(deleted.capture());
        assertEquals(CE_ARN, deleted.getValue().path("computeEnvironment").asText());
    }

    @Test
    void anInPlaceUpdateOwesNoReplacementCleanup() {
        StackResource r = resource("AWS::Batch::JobQueue", "Queue");
        r.getAttributes().put("JobQueueName", "queue");
        ObjectNode props = mapper.createObjectNode();
        props.put("JobQueueName", "queue");
        props.put("Priority", "9");

        provisioner.provision(r, props, updateCtx(JQ_ARN));

        assertFalse(provisioner.hasReplacementUpdate(r), "nothing was displaced");
    }

    @Test
    void aJobDefinitionRevisionBumpIsNotAReplacement() {
        // The prior revision stays ACTIVE on AWS, and the schema's primaryIdentifier for this type
        // is JobDefinitionName rather than the ARN. Recording the bump as a replacement would have
        // the cleanup deregister a revision a running job may still name.
        when(batch.registerJobDefinition(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("jobDefinitionArn", JD_ARN + "-r2"));
        StackResource r = resource("AWS::Batch::JobDefinition", "Definition");
        r.getAttributes().put("JobDefinitionName", "my-stack-Definition-ab12cd");
        ObjectNode props = mapper.createObjectNode();
        props.put("JobDefinitionName", "my-stack-Definition-ab12cd");
        props.put("Type", "container");

        provisioner.provision(r, props, updateCtx(JD_ARN));

        assertFalse(provisioner.hasReplacementUpdate(r),
                "a new revision under the same name replaces nothing");
    }

    @Test
    void renamingAJobDefinitionIsAReplacement() {
        when(batch.registerJobDefinition(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("jobDefinitionArn", JD_ARN + "-renamed"));
        StackResource r = resource("AWS::Batch::JobDefinition", "Definition");
        r.getAttributes().put("JobDefinitionName", "my-stack-Definition-ab12cd");
        ObjectNode props = mapper.createObjectNode();
        props.put("JobDefinitionName", "renamed");
        props.put("Type", "container");

        provisioner.provision(r, props, updateCtx(JD_ARN));

        assertTrue(provisioner.hasReplacementUpdate(r), "a changed name replaces the definition");
        assertEquals(JD_ARN, provisioner.updateCleanupPhysicalId(r));
    }

    private ObjectNode detail(String key, String arn, java.util.Map<String, Object> fields) {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode item = out.putArray(key).addObject();
        item.put("arn", arn);
        fields.forEach((k, v) -> {
            if (v instanceof Integer i) {
                item.put(k, i);
            } else {
                item.put(k, String.valueOf(v));
            }
        });
        return out;
    }

    // ── update rollback ──────────────────────────────────────────────────────

    @Test
    void rollingBackAnInPlaceComputeEnvironmentUpdateRestoresWhatItFound() {
        // Without this the resource fell to "Rollback is not implemented", which drives the whole
        // stack to UPDATE_ROLLBACK_FAILED and leaves the environment on the failed update's values.
        when(batch.describeComputeEnvironments(any())).thenReturn(
                detail("computeEnvironments", CE_ARN,
                        java.util.Map.of("state", "ENABLED", "serviceRole", "role-before")));
        StackResource r = resource("AWS::Batch::ComputeEnvironment", "Compute");
        r.getAttributes().put("ComputeEnvironmentName", "envy");
        ObjectNode props = mapper.createObjectNode();
        props.put("ComputeEnvironmentName", "envy");
        props.put("Type", "MANAGED");
        props.put("State", "DISABLED");
        props.put("ServiceRole", "role-after");
        provisioner.provision(r, props, updateCtx(CE_ARN));

        assertTrue(provisioner.rollbackUpdate(r), "an in-place update is restorable from the snapshot");

        ArgumentCaptor<JsonNode> calls = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch, org.mockito.Mockito.times(2)).updateComputeEnvironment(calls.capture());
        JsonNode restore = calls.getAllValues().get(1);
        assertEquals(CE_ARN, restore.path("computeEnvironment").asText());
        assertEquals("ENABLED", restore.path("state").asText(), "the state it was found with");
        assertEquals("role-before", restore.path("serviceRole").asText());
    }

    @Test
    void rollingBackAnInPlaceJobQueueUpdateRestoresPriorityAndState() {
        when(batch.describeJobQueues(any())).thenReturn(
                detail("jobQueues", JQ_ARN, java.util.Map.of("state", "ENABLED", "priority", 3)));
        StackResource r = resource("AWS::Batch::JobQueue", "Queue");
        r.getAttributes().put("JobQueueName", "queue");
        ObjectNode props = mapper.createObjectNode();
        props.put("JobQueueName", "queue");
        props.put("Priority", "9");
        props.put("State", "DISABLED");
        provisioner.provision(r, props, updateCtx(JQ_ARN));

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<JsonNode> calls = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch, org.mockito.Mockito.times(2)).updateJobQueue(calls.capture());
        JsonNode restore = calls.getAllValues().get(1);
        assertEquals(3, restore.path("priority").asInt(), "the priority it was found with");
        assertEquals("ENABLED", restore.path("state").asText());
    }

    @Test
    void rollingBackAJobDefinitionRevisionDeregistersTheOneTheUpdateRegistered() {
        when(batch.registerJobDefinition(any(), anyString()))
                .thenReturn(mapper.createObjectNode().put("jobDefinitionArn", JD_ARN + "-r2"));
        when(batch.describeJobDefinitions(any()))
                .thenReturn(describeWith("jobDefinitions", JD_ARN + "-r2"));
        StackResource r = resource("AWS::Batch::JobDefinition", "Definition");
        r.getAttributes().put("JobDefinitionName", "my-stack-Definition-ab12cd");
        ObjectNode props = mapper.createObjectNode();
        props.put("JobDefinitionName", "my-stack-Definition-ab12cd");
        props.put("Type", "container");
        provisioner.provision(r, props, updateCtx(JD_ARN));

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<JsonNode> deregistered = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).deregisterJobDefinition(deregistered.capture());
        assertEquals(JD_ARN + "-r2", deregistered.getValue().path("jobDefinition").asText(),
                "the revision the failed update registered, not the one it started from");
        assertEquals(JD_ARN, r.getPhysicalId(), "the resource names the prior revision again");
    }

    @Test
    void aResourceThisUpdateNeverTouchedReportsItCannotBeRolledBack() {
        // Answering true here would claim a restore that never happened. False is the honest
        // answer and is what makes the engine report the resource accurately.
        StackResource r = resource("AWS::Batch::JobQueue", "Queue");

        assertFalse(provisioner.rollbackUpdate(r));
    }

    @Test
    void clearingTheUpdateDropsTheSnapshot() {
        when(batch.describeJobQueues(any())).thenReturn(
                detail("jobQueues", JQ_ARN, java.util.Map.of("state", "ENABLED", "priority", 3)));
        StackResource r = resource("AWS::Batch::JobQueue", "Queue");
        r.getAttributes().put("JobQueueName", "queue");
        ObjectNode props = mapper.createObjectNode();
        props.put("JobQueueName", "queue");
        props.put("Priority", "9");
        provisioner.provision(r, props, updateCtx(JQ_ARN));

        provisioner.clearUpdate(r);

        assertFalse(r.getAttributes().containsKey(CfnRollback.BATCH_UPDATE_SNAPSHOT_ATTR));
        assertFalse(provisioner.rollbackUpdate(r), "a spent snapshot cannot be replayed");
    }
}
