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
        props.putObject("ContainerProperties").put("Image", "busybox");

        StackResource r = resource("AWS::Batch::JobDefinition", "Definition");
        provisioner.provision(r, props, ctx());

        ArgumentCaptor<JsonNode> req = ArgumentCaptor.forClass(JsonNode.class);
        verify(batch).registerJobDefinition(req.capture(), anyString());
        assertEquals("container", req.getValue().path("type").asText());
        assertEquals("busybox", req.getValue().path("containerProperties").path("image").asText());
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
}
