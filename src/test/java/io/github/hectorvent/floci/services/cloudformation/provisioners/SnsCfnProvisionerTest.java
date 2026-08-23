package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The SNS CFN provisioner in isolation: {@code AWS::SNS::TopicPolicy} (issue #17 aws-bench gap
 * batch). {@code AWS::SNS::Topic} and {@code AWS::SNS::Subscription} are not covered here — they
 * remain on the legacy provisioner switch.
 */
class SnsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String TOPIC_ARN_1 = "arn:aws:sns:us-east-1:000000000000:topic-one";
    private static final String TOPIC_ARN_2 = "arn:aws:sns:us-east-1:000000000000:topic-two";

    private final SnsService snsService = mock(SnsService.class);
    private final SnsCfnProvisioner provisioner = new SnsCfnProvisioner(snsService);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        org.mockito.Mockito.when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        org.mockito.Mockito.when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("MyTopicPolicy");
        r.setResourceType("AWS::SNS::TopicPolicy");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode props(List<String> topics) {
        ObjectNode props = mapper.createObjectNode();
        ArrayNode topicsNode = props.putArray("Topics");
        topics.forEach(topicsNode::add);
        ObjectNode policy = props.putObject("PolicyDocument");
        policy.put("Version", "2012-10-17");
        policy.putArray("Statement");
        return props;
    }

    @Test
    void appliesPolicyToEveryDeclaredTopicAndGetsASyntheticPhysicalId() {
        StackResource r = resource();

        provisioner.provision(r, props(List.of(TOPIC_ARN_1, TOPIC_ARN_2)), ctx());

        assertNotNull(r.getPhysicalId());
        assertTrue(r.getPhysicalId().startsWith("topic-policy-"));
        assertEquals(r.getPhysicalId(), r.getAttributes().get("Id"));
        verify(snsService).setTopicAttributes(eq(TOPIC_ARN_1), eq("Policy"), contains("2012-10-17"), eq(REGION));
        verify(snsService).setTopicAttributes(eq(TOPIC_ARN_2), eq("Policy"), contains("2012-10-17"), eq(REGION));
    }

    @Test
    void updateReappliesPolicyAndKeepsThePhysicalIdStable() {
        StackResource r = resource();
        provisioner.provision(r, props(List.of(TOPIC_ARN_1)), ctx());
        String physicalId = r.getPhysicalId();

        provisioner.provision(r, props(List.of(TOPIC_ARN_1)), ctx());

        assertEquals(physicalId, r.getPhysicalId());
        verify(snsService, org.mockito.Mockito.times(2))
                .setTopicAttributes(eq(TOPIC_ARN_1), eq("Policy"), anyString(), eq(REGION));
    }

    @Test
    void missingTopicsIsRejected() {
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode();
        props.putObject("PolicyDocument");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        verifyNoInteractions(snsService);
    }

    @Test
    void deleteHasNoBackingResourceAndIsANoOp() {
        provisioner.delete("AWS::SNS::TopicPolicy", "topic-policy-abc", REGION);
        verifyNoInteractions(snsService);
    }
}
