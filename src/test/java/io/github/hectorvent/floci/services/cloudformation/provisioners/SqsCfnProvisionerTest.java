package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The SQS CFN provisioner in isolation — the payoff of item 15's decomposition: one mocked
 * service instead of the ~30 the monolithic provisioner needed.
 */
class SqsCfnProvisionerTest {

    private final SqsService sqs = mock(SqsService.class);
    private final SqsCfnProvisioner provisioner = new SqsCfnProvisioner(sqs);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // The engine is a collaborator; for scalar properties it just returns the node's text,
        // which is all these SQS cases exercise. Mocking keeps this a true isolated unit test.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        // Mirrors the engine's resolveNode contract for the flat objects these tests use:
        // Fn::GetAtt collapses to a text ARN, everything else passes through untouched.
        when(engine.resolveNode(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node == null || !node.isObject()) {
                return node;
            }
            ObjectNode resolved = mapper.createObjectNode();
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isObject() && e.getValue().has("Fn::GetAtt")) {
                    resolved.put(e.getKey(), "arn:aws:sqs:us-east-1:000000000000:my-stack-Dlq.fifo");
                } else {
                    resolved.set(e.getKey(), e.getValue());
                }
            });
            return resolved;
        });
        when(engine.resolveJsonAttribute(any())).thenAnswer(inv -> {
            JsonNode resolved = engine.resolveNode(inv.getArgument(0));
            return resolved != null && resolved.isTextual() ? resolved.asText() : resolved.toString();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    @Test
    void queueSetsPhysicalIdAndGetAttAttributes() {
        when(sqs.createQueue(eq("the-queue"), any(), eq("us-east-1")))
                .thenReturn(new Queue("the-queue", "http://localhost:4566/000000000000/the-queue"));
        StackResource r = resource("AWS::SQS::Queue", "MyQueue");
        ObjectNode props = mapper.createObjectNode().put("QueueName", "the-queue");

        provisioner.provision(r, props, ctx());

        assertEquals("http://localhost:4566/000000000000/the-queue", r.getPhysicalId());
        assertEquals("arn:aws:sqs:us-east-1:000000000000:the-queue", r.getAttributes().get("Arn"));
        assertEquals("the-queue", r.getAttributes().get("QueueName"));
        assertEquals("http://localhost:4566/000000000000/the-queue", r.getAttributes().get("QueueUrl"));
    }

    @Test
    void queueWithoutNameGeneratesPhysicalName() {
        when(sqs.createQueue(anyString(), any(), eq("us-east-1")))
                .thenAnswer(inv -> new Queue(inv.getArgument(0), "http://q/" + inv.getArgument(0)));
        StackResource r = resource("AWS::SQS::Queue", "MyQueue");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        // <stack>-<logicalId>-<suffix>, capped at 80
        assertEquals("my-stack-MyQueue", r.getAttributes().get("QueueName").replaceAll("-[0-9a-f]{12}$", ""));
    }

    @Test
    void queuePassesFifoScalarAttributesToCreateQueue() {
        // Reproduces #1907: DeduplicationScope and FifoThroughputLimit from the template must
        // reach SqsService.createQueue — the engine already persists them (issue Control 2).
        when(sqs.createQueue(eq("orders.fifo"), any(), eq("us-east-1")))
                .thenReturn(new Queue("orders.fifo", "http://localhost:4566/000000000000/orders.fifo"));
        StackResource r = resource("AWS::SQS::Queue", "MyQueue");
        ObjectNode props = mapper.createObjectNode()
                .put("QueueName", "orders.fifo")
                .put("FifoQueue", true)
                .put("ContentBasedDeduplication", false)
                .put("DeduplicationScope", "messageGroup")
                .put("FifoThroughputLimit", "perMessageGroupId")
                .put("VisibilityTimeout", 30);

        provisioner.provision(r, props, ctx());

        Map<String, String> attrs = capturedCreateQueueAttributes("orders.fifo");
        assertEquals("messageGroup", attrs.get("DeduplicationScope"));
        assertEquals("perMessageGroupId", attrs.get("FifoThroughputLimit"));
        // The two attributes that already worked keep working.
        assertEquals("false", attrs.get("ContentBasedDeduplication"));
        assertEquals("30", attrs.get("VisibilityTimeout"));
    }

    @Test
    void queueSerializesRedrivePolicyWithResolvedDlqArn() {
        // Reproduces #1907: RedrivePolicy is a JSON object in the template (with an intrinsic
        // for the DLQ ARN) and must reach createQueue as the JSON string SqsService parses.
        when(sqs.createQueue(eq("orders.fifo"), any(), eq("us-east-1")))
                .thenReturn(new Queue("orders.fifo", "http://localhost:4566/000000000000/orders.fifo"));
        StackResource r = resource("AWS::SQS::Queue", "MyQueue");
        ObjectNode getAtt = mapper.createObjectNode();
        getAtt.set("Fn::GetAtt", mapper.createArrayNode().add("Dlq").add("Arn"));
        ObjectNode redrive = mapper.createObjectNode();
        redrive.set("deadLetterTargetArn", getAtt);
        redrive.put("maxReceiveCount", 5);
        ObjectNode props = mapper.createObjectNode().put("QueueName", "orders.fifo");
        props.set("RedrivePolicy", redrive);

        provisioner.provision(r, props, ctx());

        Map<String, String> attrs = capturedCreateQueueAttributes("orders.fifo");
        assertEquals(
                "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:my-stack-Dlq.fifo\",\"maxReceiveCount\":5}",
                attrs.get("RedrivePolicy"));
    }

    @Test
    void queuePassesThroughAlreadySerializedRedrivePolicyString() {
        // Reported on #1939: CDK commonly emits RedrivePolicy via Fn::Join, which resolveNode
        // collapses to an already-JSON-encoded TextNode. TextNode#toString() re-encodes it (quotes
        // and escapes the string), double-serializing the policy so SqsService can't parse it.
        when(sqs.createQueue(eq("orders.fifo"), any(), eq("us-east-1")))
                .thenReturn(new Queue("orders.fifo", "http://localhost:4566/000000000000/orders.fifo"));
        StackResource r = resource("AWS::SQS::Queue", "MyQueue");
        String serializedRedrive =
                "{\"maxReceiveCount\":5,\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:my-stack-Dlq.fifo\"}";
        ObjectNode props = mapper.createObjectNode().put("QueueName", "orders.fifo");
        props.put("RedrivePolicy", serializedRedrive);

        provisioner.provision(r, props, ctx());

        Map<String, String> attrs = capturedCreateQueueAttributes("orders.fifo");
        assertEquals(serializedRedrive, attrs.get("RedrivePolicy"));
    }

    @Test
    void fifoQueueWithoutNameGetsGeneratedFifoNameAndFifoAttribute() {
        // Like real CloudFormation, a FifoQueue: true resource without a QueueName must get a
        // generated physical name ending in .fifo — SqsService rejects FifoQueue=true otherwise.
        when(sqs.createQueue(anyString(), any(), eq("us-east-1")))
                .thenAnswer(inv -> new Queue(inv.getArgument(0), "http://q/" + inv.getArgument(0)));
        StackResource r = resource("AWS::SQS::Queue", "MyDlq");
        ObjectNode props = mapper.createObjectNode().put("FifoQueue", true);

        provisioner.provision(r, props, ctx());

        String queueName = r.getAttributes().get("QueueName");
        assertTrue(queueName.endsWith(".fifo"),
                "generated FIFO queue name should end with .fifo but was: " + queueName);
        assertTrue(queueName.length() <= 80, "queue name must stay within the 80-char limit");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(sqs).createQueue(eq(queueName), captor.capture(), eq("us-east-1"));
        assertEquals("true", captor.getValue().get("FifoQueue"));
    }

    private Map<String, String> capturedCreateQueueAttributes(String queueName) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(sqs).createQueue(eq(queueName), captor.capture(), eq("us-east-1"));
        return captor.getValue();
    }

    @Test
    void queuePolicyGetsAPhysicalId() {
        StackResource r = resource("AWS::SQS::QueuePolicy", "MyPolicy");
        provisioner.provision(r, mapper.createObjectNode(), ctx());
        assertNotNull(r.getPhysicalId());
        assertTrue(r.getPhysicalId().startsWith("queue-policy-"));
        verifyNoInteractions(sqs);
    }

    @Test
    void deleteQueueDelegatesToService() {
        provisioner.delete("AWS::SQS::Queue", "http://q/url", "us-east-1");
        verify(sqs).deleteQueue("http://q/url", "us-east-1");
    }

    @Test
    void deleteQueuePolicyIsNoOp() {
        provisioner.delete("AWS::SQS::QueuePolicy", "queue-policy-abc", "us-east-1");
        verifyNoInteractions(sqs);
    }
}
