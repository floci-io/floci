package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EventBridge")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeTest {

    private static EventBridgeClient eb;
    private static SqsClient sqs;

    private static String ruleName;
    private static String busName;
    private static String sinkQueueUrl;
    private static String sinkQueueArn;
    private static String transformerQueueUrl;
    private static String transformerQueueArn;

    @BeforeAll
    static void setup() {
        eb = TestFixtures.eventBridgeClient();
        sqs = TestFixtures.sqsClient();
        ruleName = TestFixtures.uniqueName("eb-rule");
        busName = TestFixtures.uniqueName("eb-bus");
    }

    @AfterAll
    static void cleanup() {
        try {
            eb.removeTargets(RemoveTargetsRequest.builder().rule(ruleName).ids("sqs-target", "transformer-target").build());
        } catch (Exception ignored) {}
        try {
            eb.deleteRule(DeleteRuleRequest.builder().name(ruleName).build());
        } catch (Exception ignored) {}
        try {
            eb.deleteEventBus(DeleteEventBusRequest.builder().name(busName).build());
        } catch (Exception ignored) {}
        try {
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(sinkQueueUrl).build());
        } catch (Exception ignored) {}
        try {
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(transformerQueueUrl).build());
        } catch (Exception ignored) {}
        eb.close();
        sqs.close();
    }

    // ──────────────────────────── Event Buses ────────────────────────────

    @Test
    @Order(1)
    void createEventBus() {
        CreateEventBusResponse response = eb.createEventBus(
                CreateEventBusRequest.builder().name(busName).build());
        assertThat(response.eventBusArn()).contains(busName);
    }

    @Test
    @Order(2)
    void describeEventBus() {
        DescribeEventBusResponse response = eb.describeEventBus(
                DescribeEventBusRequest.builder().name(busName).build());
        assertThat(response.name()).isEqualTo(busName);
        assertThat(response.arn()).contains(busName);
    }

    @Test
    @Order(3)
    void listEventBuses() {
        ListEventBusesResponse response = eb.listEventBuses(
                ListEventBusesRequest.builder().build());
        assertThat(response.eventBuses()).extracting(EventBus::name).contains("default", busName);
    }

    // ──────────────────────────── Rules ────────────────────────────

    @Test
    @Order(10)
    void putRule() {
        PutRuleResponse response = eb.putRule(PutRuleRequest.builder()
                .name(ruleName)
                .eventPattern("{\"source\":[\"com.myapp\"]}")
                .state(RuleState.ENABLED)
                .description("Test rule")
                .build());
        assertThat(response.ruleArn()).contains(ruleName);
    }

    @Test
    @Order(11)
    void describeRule() {
        DescribeRuleResponse response = eb.describeRule(
                DescribeRuleRequest.builder().name(ruleName).build());
        assertThat(response.name()).isEqualTo(ruleName);
        assertThat(response.state()).isEqualTo(RuleState.ENABLED);
        assertThat(response.eventPattern()).contains("com.myapp");
    }

    @Test
    @Order(12)
    void listRules() {
        ListRulesResponse response = eb.listRules(ListRulesRequest.builder().build());
        assertThat(response.rules()).extracting(Rule::name).contains(ruleName);
    }

    @Test
    @Order(13)
    void disableAndEnableRule() {
        eb.disableRule(DisableRuleRequest.builder().name(ruleName).build());
        assertThat(eb.describeRule(DescribeRuleRequest.builder().name(ruleName).build()).state())
                .isEqualTo(RuleState.DISABLED);

        eb.enableRule(EnableRuleRequest.builder().name(ruleName).build());
        assertThat(eb.describeRule(DescribeRuleRequest.builder().name(ruleName).build()).state())
                .isEqualTo(RuleState.ENABLED);
    }

    // ──────────────────────────── Targets + PutEvents ────────────────────────────

    @Test
    @Order(20)
    void createSinkQueue() {
        sinkQueueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(TestFixtures.uniqueName("eb-sink")).build()).queueUrl();
        sinkQueueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(sinkQueueUrl).attributeNamesWithStrings("QueueArn").build())
                .attributesAsStrings().get("QueueArn");
        assertThat(sinkQueueArn).contains("eb-sink");
    }

    @Test
    @Order(21)
    void putSqsTarget() {
        PutTargetsResponse response = eb.putTargets(PutTargetsRequest.builder()
                .rule(ruleName)
                .targets(Target.builder().id("sqs-target").arn(sinkQueueArn).build())
                .build());
        assertThat(response.failedEntryCount()).isZero();
    }

    @Test
    @Order(22)
    void listTargetsByRule() {
        ListTargetsByRuleResponse response = eb.listTargetsByRule(
                ListTargetsByRuleRequest.builder().rule(ruleName).build());
        assertThat(response.targets()).extracting(Target::id).contains("sqs-target");
    }

    @Test
    @Order(23)
    void putEventsDeliveredToSqsTarget() {
        eb.putEvents(PutEventsRequest.builder()
                .entries(PutEventsRequestEntry.builder()
                        .source("com.myapp")
                        .detailType("OrderPlaced")
                        .detail("{\"orderId\":\"123\"}")
                        .build())
                .build());

        ReceiveMessageResponse msg = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(sinkQueueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(2)
                .build());
        assertThat(msg.messages()).hasSize(1);
        assertThat(msg.messages().get(0).body()).contains("com.myapp").contains("OrderPlaced");
    }

    @Test
    @Order(24)
    void putEventsNoMatchingRuleNotDelivered() {
        eb.putEvents(PutEventsRequest.builder()
                .entries(PutEventsRequestEntry.builder()
                        .source("other.app")
                        .detailType("Ignored")
                        .detail("{}")
                        .build())
                .build());

        ReceiveMessageResponse msg = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(sinkQueueUrl)
                .maxNumberOfMessages(1)
                .build());
        assertThat(msg.messages()).isEmpty();
    }

    // ──────────────────────────── InputTransformer ────────────────────────────

    @Test
    @Order(30)
    void createTransformerQueue() {
        transformerQueueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(TestFixtures.uniqueName("eb-xform")).build()).queueUrl();
        transformerQueueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(transformerQueueUrl).attributeNamesWithStrings("QueueArn").build())
                .attributesAsStrings().get("QueueArn");
        assertThat(transformerQueueArn).contains("eb-xform");
    }

    @Test
    @Order(31)
    void putInputTransformerTarget() {
        PutTargetsResponse response = eb.putTargets(PutTargetsRequest.builder()
                .rule(ruleName)
                .targets(Target.builder()
                        .id("transformer-target")
                        .arn(transformerQueueArn)
                        .inputTransformer(InputTransformer.builder()
                                .inputPathsMap(Map.of("src", "$.source", "type", "$.detail-type"))
                                .inputTemplate("{\"source\":\"<src>\",\"type\":\"<type>\"}")
                                .build())
                        .build())
                .build());
        assertThat(response.failedEntryCount()).isZero();
    }

    @Test
    @Order(32)
    void inputTransformerTargetStoredCorrectly() {
        ListTargetsByRuleResponse response = eb.listTargetsByRule(
                ListTargetsByRuleRequest.builder().rule(ruleName).build());
        Target xformTarget = response.targets().stream()
                .filter(t -> "transformer-target".equals(t.id()))
                .findFirst().orElseThrow();
        assertThat(xformTarget.inputTransformer()).isNotNull();
        assertThat(xformTarget.inputTransformer().inputPathsMap()).containsKey("src");
        assertThat(xformTarget.inputTransformer().inputTemplate()).contains("<src>");
    }

    @Test
    @Order(33)
    void putEventsInputTransformerTransformsPayload() {
        // Drain any prior messages
        sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(transformerQueueUrl).maxNumberOfMessages(10).build());

        eb.putEvents(PutEventsRequest.builder()
                .entries(PutEventsRequestEntry.builder()
                        .source("com.myapp")
                        .detailType("OrderShipped")
                        .detail("{\"orderId\":\"456\"}")
                        .build())
                .build());

        ReceiveMessageResponse msg = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(transformerQueueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(2)
                .build());
        assertThat(msg.messages()).hasSize(1);
        String body = msg.messages().get(0).body();
        assertThat(body).contains("com.myapp").contains("OrderShipped");
        assertThat(body).doesNotContain("orderId");
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Test
    @Order(40)
    void listTagsForResource() {
        String ruleArn = eb.describeRule(DescribeRuleRequest.builder().name(ruleName).build()).arn();
        ListTagsForResourceResponse response = eb.listTagsForResource(
                ListTagsForResourceRequest.builder().resourceARN(ruleArn).build());
        assertThat(response.tags()).isNotNull();
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(90)
    void removeTargets() {
        RemoveTargetsResponse response = eb.removeTargets(RemoveTargetsRequest.builder()
                .rule(ruleName)
                .ids("sqs-target", "transformer-target")
                .build());
        assertThat(response.failedEntryCount()).isZero();
        assertThat(eb.listTargetsByRule(ListTargetsByRuleRequest.builder().rule(ruleName).build())
                .targets()).isEmpty();
    }

    @Test
    @Order(91)
    void deleteRule() {
        eb.deleteRule(DeleteRuleRequest.builder().name(ruleName).build());
        assertThatThrownBy(() ->
                eb.describeRule(DescribeRuleRequest.builder().name(ruleName).build()))
                .isInstanceOf(software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException.class);
    }

    @Test
    @Order(92)
    void deleteEventBus() {
        eb.deleteEventBus(DeleteEventBusRequest.builder().name(busName).build());
        ListEventBusesResponse response = eb.listEventBuses(ListEventBusesRequest.builder().build());
        assertThat(response.eventBuses()).extracting(EventBus::name).doesNotContain(busName);
    }
}
