package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::SNS::Topic} and {@code AWS::SNS::Subscription}. */
class SnsCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:events";

    private SnsService sns;
    private SnsCfnProvisioner provisioner;
    private ProvisionContext ctx;

    @BeforeEach
    void setUp() {
        sns = mock(SnsService.class);
        provisioner = new SnsCfnProvisioner(sns);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
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
        assertEquals(Map.of("Arn", TOPIC_ARN, "TopicName", "events"), r.getAttributes());
    }

    @Test
    void anUnnamedTopicGetsAGeneratedStackScopedName() {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        when(sns.createTopic(name.capture(), anyMap(), anyMap(), anyString())).thenReturn(topic(TOPIC_ARN));

        provision("AWS::SNS::Topic", "{}");

        assertTrue(name.getValue().startsWith("my-stack-Topic-"), name.getValue());
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
    void deleteRoutesEachTypeToItsOwnCall() {
        provisioner.delete("AWS::SNS::Topic", TOPIC_ARN, REGION);
        verify(sns).deleteTopic(TOPIC_ARN, REGION);

        provisioner.delete("AWS::SNS::Subscription", "arn:sub", REGION);
        verify(sns).unsubscribe("arn:sub", REGION);
    }
}
