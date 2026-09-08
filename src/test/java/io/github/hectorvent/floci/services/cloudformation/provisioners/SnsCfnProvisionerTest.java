package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** {@code AWS::SNS::Topic}, {@code AWS::SNS::Subscription} and {@code AWS::SNS::TopicPolicy}. */
class SnsCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:events";

    private SnsService sns;
    private SnsCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;
    private ProvisionContext ctx;

    @BeforeEach
    void setUp() {
        sns = mock(SnsService.class);
        provisioner = new SnsCfnProvisioner(sns);
        engine = mock(CloudFormationTemplateEngine.class);
        // The two resolvers are stubbed with the semantics that distinguish them, so a body that
        // reaches for the wrong one is visible: resolve() is scalar (an object node flattens to
        // ""), resolveJsonAttribute() serializes the document.
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveJsonAttribute(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null ? null : MAPPER.writeValueAsString(node);
        });
        when(engine.resolveStringList(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            List<String> values = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(element -> values.add(element.asText()));
            }
            return values;
        });
        ctx = new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private StackResource provision(String type, String json) {
        StackResource r = new StackResource();
        r.setLogicalId(type.endsWith("Topic") ? "Topic" : "Sub");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        provisioner.provision(r, props(json), ctx);
        return r;
    }

    private static Topic topic(String arn) {
        Topic t = new Topic();
        t.setTopicArn(arn);
        return t;
    }

    private static Subscription subscription(String arn) {
        Subscription s = new Subscription();
        s.setSubscriptionArn(arn);
        return s;
    }

    @Test
    void topicRefIsTheArnNotTheName() {
        when(sns.createTopic(eq("events"), anyMap(), anyMap(), eq(REGION))).thenReturn(topic(TOPIC_ARN));

        StackResource r = provision("AWS::SNS::Topic", """
                {"TopicName": "events"}
                """);

        // Ref on an SNS topic returns the ARN, unlike most types where it is the name.
        assertEquals(TOPIC_ARN, r.getPhysicalId());
        // TopicArn is the attribute aws-sns-topic.json declares; Arn is kept for templates written
        // against earlier releases.
        assertEquals(Map.of("TopicArn", TOPIC_ARN, "Arn", TOPIC_ARN, "TopicName", "events"),
                r.getAttributes());
    }

    @Test
    void anUnnamedTopicGetsAGeneratedStackScopedName() {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        when(sns.createTopic(name.capture(), anyMap(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));

        provision("AWS::SNS::Topic", "{}");

        assertTrue(name.getValue().startsWith("my-stack-Topic-"), name.getValue());
    }

    /**
     * The topic's physical id is its ARN, so the prior name is read back from the TopicName
     * attribute. Without that an unnamed topic would be recreated under a new name on every update,
     * orphaning the old topic and every subscription attached to it.
     */
    @Test
    void anUnnamedTopicKeepsItsNameAcrossUpdates() {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        when(sns.createTopic(name.capture(), anyMap(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));

        StackResource r = new StackResource();
        r.setLogicalId("Topic");
        r.setResourceType("AWS::SNS::Topic");
        r.setAttributes(new HashMap<>(Map.of("TopicName", "my-stack-Topic-abc123def456")));
        r.setPhysicalId(TOPIC_ARN);
        provisioner.provision(r, props("{}"),
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", TOPIC_ARN));

        assertEquals("my-stack-Topic-abc123def456", name.getValue(),
                "the prior name must come from the attribute, not from the ARN physical id");
    }

    @Test
    void contentBasedDeduplicationIsForwardedOnlyWhenSet() {
        ArgumentCaptor<Map<String, String>> attrs = ArgumentCaptor.forClass(Map.class);
        when(sns.createTopic(anyString(), attrs.capture(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));

        provision("AWS::SNS::Topic", """
                {"TopicName": "t", "ContentBasedDeduplication": "true"}
                """);
        assertEquals("true", attrs.getValue().get("ContentBasedDeduplication"));

        setUp();
        ArgumentCaptor<Map<String, String>> plain = ArgumentCaptor.forClass(Map.class);
        when(sns.createTopic(anyString(), plain.capture(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));
        provision("AWS::SNS::Topic", """
                {"TopicName": "t"}
                """);
        assertFalse(plain.getValue().containsKey("ContentBasedDeduplication"),
                "an absent flag must not be sent as an empty attribute");
    }

    @Test
    void fifoThroughputScopeIsForwardedOnlyWhenSet() {
        ArgumentCaptor<Map<String, String>> attrs = ArgumentCaptor.forClass(Map.class);
        when(sns.createTopic(anyString(), attrs.capture(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));

        provision("AWS::SNS::Topic", """
                {"TopicName": "events.fifo", "FifoTopic": true, "FifoThroughputScope": "MessageGroup"}
                """);
        assertEquals("MessageGroup", attrs.getValue().get("FifoThroughputScope"));
        verify(sns, never()).setTopicAttributes(anyString(), anyString(), anyString(), anyString());

        setUp();
        ArgumentCaptor<Map<String, String>> plain = ArgumentCaptor.forClass(Map.class);
        when(sns.createTopic(anyString(), plain.capture(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));
        provision("AWS::SNS::Topic", """
                {"TopicName": "events.fifo", "FifoTopic": true}
                """);
        assertFalse(plain.getValue().containsKey("FifoThroughputScope"),
                "an absent scope must not be sent as an empty attribute");
    }

    @Test
    void anUnnamedFifoTopicGetsAGeneratedNameEndingInFifo() {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        when(sns.createTopic(name.capture(), anyMap(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));

        provision("AWS::SNS::Topic", """
                {"FifoTopic": true, "FifoThroughputScope": "MessageGroup"}
                """);
        assertTrue(name.getValue().startsWith("my-stack-Topic-"));
        assertTrue(name.getValue().endsWith(".fifo"),
                "SnsService reads FifoTopic off the name, so a generated one must carry the suffix");

        setUp();
        ArgumentCaptor<String> standard = ArgumentCaptor.forClass(String.class);
        when(sns.createTopic(standard.capture(), anyMap(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));
        provision("AWS::SNS::Topic", "{}");
        assertFalse(standard.getValue().endsWith(".fifo"),
                "a standard topic must not be renamed into a FIFO one");
    }

    /**
     * Replacing would displace a topic nothing cleans up, subscriptions included, so it is refused.
     */
    @Test
    void aTopicTurningFifoIsRejectedRatherThanReplaced() {
        StackResource r = new StackResource();
        r.setLogicalId("Topic");
        r.setResourceType("AWS::SNS::Topic");
        r.setAttributes(new HashMap<>(Map.of("TopicName", "my-stack-Topic-abc123def456")));
        r.setPhysicalId(TOPIC_ARN);
        ProvisionContext update =
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", TOPIC_ARN);
        JsonNode turningFifo = props("""
                {"FifoTopic": true}
                """);

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, turningFifo, update));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("requires resource replacement"), e.getMessage());
        verify(sns, never()).createTopic(anyString(), anyMap(), anyMap(), anyString());
    }

    /** The suffix carries FifoTopic on its own, so this is the same topic, not a mode change. */
    @Test
    void anExplicitlyNamedFifoTopicUpdatesWithoutTheFlag() {
        when(sns.createTopic(eq("events.fifo"), anyMap(), anyMap(), eq(REGION))).thenReturn(topic(TOPIC_ARN));

        provisionUpdate("""
                {"TopicName": "events.fifo"}
                """);

        verify(sns).createTopic(eq("events.fifo"), anyMap(), anyMap(), eq(REGION));
    }

    @Test
    void anUpdateThatDropsAPropertyResetsItToItsDefault() {
        when(sns.createTopic(eq("events.fifo"), anyMap(), anyMap(), eq(REGION))).thenReturn(topic(TOPIC_ARN));

        // The template dropped both properties, so both go back to their create-time values.
        provisionUpdate("""
                {"TopicName": "events.fifo", "FifoTopic": true}
                """);

        verify(sns).setTopicAttributes(TOPIC_ARN, "FifoThroughputScope", "Topic", REGION);
        verify(sns).setTopicAttributes(TOPIC_ARN, "ContentBasedDeduplication", "false", REGION);
    }

    /** Real SNS rejects both attributes on a standard topic, so an update must not invent them. */
    @Test
    void aStandardTopicUpdateWritesNoFifoDefaults() {
        when(sns.createTopic(eq("events"), anyMap(), anyMap(), eq(REGION))).thenReturn(topic(TOPIC_ARN));

        provisionUpdate("""
                {"TopicName": "events"}
                """);

        verify(sns, never()).setTopicAttributes(anyString(), anyString(), anyString(), anyString());
    }

    /** Provisions an existing topic again, the way UpdateStack re-invokes the provisioner. */
    private void provisionUpdate(String json) {
        StackResource r = new StackResource();
        r.setLogicalId("Topic");
        r.setResourceType("AWS::SNS::Topic");
        JsonNode resolved = props(json);
        String priorName = resolved.path("TopicName").asText(null);
        r.setAttributes(new HashMap<>(Map.of("TopicName", priorName)));
        r.setPhysicalId(TOPIC_ARN);
        provisioner.provision(r, resolved,
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", TOPIC_ARN));
    }

    @Test
    void mutableTopicAttributesAreWrittenOnUpdate() {
        when(sns.createTopic(eq("events.fifo"), anyMap(), anyMap(), eq(REGION))).thenReturn(topic(TOPIC_ARN));

        StackResource r = new StackResource();
        r.setLogicalId("Topic");
        r.setResourceType("AWS::SNS::Topic");
        r.setAttributes(new HashMap<>(Map.of("TopicName", "events.fifo")));
        r.setPhysicalId(TOPIC_ARN);
        provisioner.provision(r, props("""
                {"TopicName": "events.fifo", "FifoTopic": true, "FifoThroughputScope": "MessageGroup",
                 "ContentBasedDeduplication": "true"}
                """), new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", TOPIC_ARN));

        // createTopic returns the existing topic, so without this a changed scope would be lost.
        verify(sns).setTopicAttributes(TOPIC_ARN, "FifoThroughputScope", "MessageGroup", REGION);
        verify(sns).setTopicAttributes(TOPIC_ARN, "ContentBasedDeduplication", "true", REGION);
    }

    @Test
    void subscriptionRefIsTheSubscriptionArn() {
        when(sns.subscribe(eq(TOPIC_ARN), eq("sqs"), eq("arn:queue"), eq(REGION), anyMap()))
                .thenReturn(subscription("arn:sub"));

        StackResource r = provision("AWS::SNS::Subscription", """
                {"TopicArn": "%s", "Protocol": "sqs", "Endpoint": "arn:queue"}
                """.formatted(TOPIC_ARN));

        assertEquals("arn:sub", r.getPhysicalId());
        assertEquals(Map.of("Arn", "arn:sub"), r.getAttributes());
    }

    /**
     * FilterPolicy is a JSON document, and SNS expects it as a JSON string. It must be serialized
     * once: handing over an already-encoded string would give SNS a quoted blob and silently break
     * message filtering.
     */
    @Test
    void filterPolicyIsSerializedAsJsonNotAsAQuotedString() {
        ArgumentCaptor<Map<String, String>> attrs = ArgumentCaptor.forClass(Map.class);
        when(sns.subscribe(anyString(), anyString(), anyString(), anyString(), attrs.capture()))
                .thenReturn(subscription("arn:sub"));

        provision("AWS::SNS::Subscription", """
                {"TopicArn": "t", "Protocol": "sqs", "Endpoint": "e",
                 "FilterPolicy": {"eventType": ["created", "updated"]}}
                """);

        String filterPolicy = attrs.getValue().get("FilterPolicy");
        assertEquals("{\"eventType\":[\"created\",\"updated\"]}", filterPolicy);
        assertFalse(filterPolicy.startsWith("\"\\{") || filterPolicy.startsWith("\"{"),
                "double-encoded FilterPolicy: " + filterPolicy);
    }

    @Test
    void redrivePolicyIsSerializedTheSameWay() {
        ArgumentCaptor<Map<String, String>> attrs = ArgumentCaptor.forClass(Map.class);
        when(sns.subscribe(anyString(), anyString(), anyString(), anyString(), attrs.capture()))
                .thenReturn(subscription("arn:sub"));

        provision("AWS::SNS::Subscription", """
                {"TopicArn": "t", "Protocol": "sqs", "Endpoint": "e",
                 "RedrivePolicy": {"deadLetterTargetArn": "arn:dlq"}}
                """);

        assertEquals("{\"deadLetterTargetArn\":\"arn:dlq\"}", attrs.getValue().get("RedrivePolicy"));
    }

    @Test
    void scalarSubscriptionAttributesStayScalar() {
        ArgumentCaptor<Map<String, String>> attrs = ArgumentCaptor.forClass(Map.class);
        when(sns.subscribe(anyString(), anyString(), anyString(), anyString(), attrs.capture()))
                .thenReturn(subscription("arn:sub"));

        provision("AWS::SNS::Subscription", """
                {"TopicArn": "t", "Protocol": "sqs", "Endpoint": "e",
                 "RawMessageDelivery": "true", "FilterPolicyScope": "MessageBody"}
                """);

        assertEquals("true", attrs.getValue().get("RawMessageDelivery"));
        assertEquals("MessageBody", attrs.getValue().get("FilterPolicyScope"));
    }

    @Test
    void absentPolicyAttributesAreOmittedEntirely() {
        ArgumentCaptor<Map<String, String>> attrs = ArgumentCaptor.forClass(Map.class);
        when(sns.subscribe(anyString(), anyString(), anyString(), anyString(), attrs.capture()))
                .thenReturn(subscription("arn:sub"));

        provision("AWS::SNS::Subscription", """
                {"TopicArn": "t", "Protocol": "sqs", "Endpoint": "e"}
                """);

        assertEquals(Map.of(), attrs.getValue(),
                "unset optional attributes must not be sent as empty strings");
    }

    @Test
    void topicPolicyWritesTheDocumentToEveryListedTopic() {
        StackResource r = provision("AWS::SNS::TopicPolicy", """
                {"Topics": ["arn:aws:sns:us-east-1:000000000000:a", "arn:aws:sns:us-east-1:000000000000:b"],
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": [
                   {"Sid": "AllowPublish", "Effect": "Allow", "Principal": "*", "Action": "sns:Publish", "Resource": "*"}]}}
                """);

        // The document reaches SNS as serialized JSON, one SetTopicAttributes per topic.
        verify(sns).setTopicAttributes(eq("arn:aws:sns:us-east-1:000000000000:a"), eq("Policy"),
                contains("\"Sid\":\"AllowPublish\""), eq(REGION));
        verify(sns).setTopicAttributes(eq("arn:aws:sns:us-east-1:000000000000:b"), eq("Policy"),
                contains("\"Sid\":\"AllowPublish\""), eq(REGION));
        assertTrue(r.getPhysicalId().startsWith("topic-policy-"), r.getPhysicalId());
        assertEquals(Map.of("Id", r.getPhysicalId()), r.getAttributes());
    }

    @Test
    void topicPolicyKeepsItsPhysicalIdAcrossUpdates() {
        StackResource r = new StackResource();
        r.setLogicalId("Policy");
        r.setResourceType("AWS::SNS::TopicPolicy");
        r.setPhysicalId("topic-policy-abc12345");
        r.setAttributes(new HashMap<>(Map.of("Id", "topic-policy-abc12345")));
        ProvisionContext update = new ProvisionContext(engine, REGION, "000000000000", "my-stack", "topic-policy-abc12345");

        provisioner.provision(r, props("""
                {"Topics": ["arn:aws:sns:us-east-1:000000000000:a"], "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                """), update);

        verify(sns).setTopicAttributes(eq("arn:aws:sns:us-east-1:000000000000:a"), eq("Policy"), anyString(), eq(REGION));
        assertEquals("topic-policy-abc12345", r.getPhysicalId());
        assertEquals("topic-policy-abc12345", r.getAttributes().get("Id"));
    }

    @Test
    void topicPolicyWithoutTopicsIsRejected() {
        AwsException e = assertThrows(AwsException.class, () -> provision("AWS::SNS::TopicPolicy", """
                {"Topics": [], "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                """));

        assertEquals("ValidationError", e.getErrorCode());
        verify(sns, never()).setTopicAttributes(any(), any(), any(), any());
    }

    @Test
    void topicPolicyWithoutADocumentIsRejected() {
        AwsException e = assertThrows(AwsException.class, () -> provision("AWS::SNS::TopicPolicy", """
                {"Topics": ["arn:aws:sns:us-east-1:000000000000:a"]}
                """));

        assertEquals("ValidationError", e.getErrorCode());
        verify(sns, never()).setTopicAttributes(any(), any(), any(), any());
    }

    @Test
    void deleteRoutesEachTypeToItsOwnCall() {
        provisioner.delete("AWS::SNS::Topic", TOPIC_ARN, REGION);
        verify(sns).deleteTopic(TOPIC_ARN, REGION);

        provisioner.delete("AWS::SNS::Subscription", "arn:sub", REGION);
        verify(sns).unsubscribe("arn:sub", REGION);

        // A topic policy has no entity of its own, so its delete touches nothing.
        provisioner.delete("AWS::SNS::TopicPolicy", "topic-policy-abc12345", REGION);
        verifyNoMoreInteractions(sns);
    }
}
