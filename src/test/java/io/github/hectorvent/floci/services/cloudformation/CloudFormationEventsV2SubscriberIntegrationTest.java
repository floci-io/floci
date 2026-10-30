package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.eventbridgev2.EventBridgeV2Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceNotFoundException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * A stack of an Amazon SQS queue, an {@code AWS::EventsV2::EventBus} and an {@code AWS::EventsV2::Subscriber} whose
 * {@code FilterConfiguration} delivers only large orders to the queue, as {@code eventsv2.CfnSubscriber} synthesizes
 * it. {@code Ref} of each is its ARN and every {@code Fn::GetAtt} attribute its schema declares resolves; an update
 * of the filter changes the subscriber in place; the stack delete removes the subscriber and the bus.
 */
@QuarkusTest
class CloudFormationEventsV2SubscriberIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String ROLE = "arn:aws:iam::000000000000:role/EventBusDelivery";
    private static final String LARGE = "{\"detail\":{\"total\":[{\"numeric\":[\">\",500]}]}}";
    private static final String SMALL = "{\"detail\":{\"total\":[{\"numeric\":[\"<\",100]}]}}";
    /** A target Floci does not deliver to, so creating a subscriber for it fails. */
    private static final String NOT_A_QUEUE = "arn:aws:sns:us-east-1:000000000000:orders";
    private final List<String> stacks = new ArrayList<>();

    @InjectSpy
    EventBridgeV2Service eventBridgeV2Service;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        for (String stack : List.copyOf(stacks)) {
            deleteStack(stack);
        }
    }

    @Test
    void aSubscriberFromATemplateDeliversTheEventsItsFilterMatches() throws Exception {
        String suffix = suffix();
        String stack = createStack(template("orders-" + suffix, LARGE));
        String body = awaitStatus(stack, "CREATE_COMPLETE");

        String busArn = output(body, "BusRef");
        assertTrue(busArn.matches("arn:aws:events:us-east-1:000000000000:event-busv2/orders-" + suffix
                + "/[a-z0-9]{25}"), busArn);
        assertEquals(busArn, output(body, "BusArn"));
        assertEquals("ACTIVE", output(body, "BusState"));
        Instant.parse(output(body, "BusCreationTime"));
        Instant.parse(output(body, "BusLastModifiedTime"));
        String subscriberArn = output(body, "SubscriberRef");
        assertTrue(subscriberArn.matches("arn:aws:events:us-east-1:000000000000:subscriber/large-orders/[a-z0-9]{25}"),
                subscriberArn);
        assertEquals(subscriberArn, output(body, "SubscriberArn"));
        assertEquals("orders-" + suffix, output(body, "SubscriberBusName"));
        Instant.parse(output(body, "SubscriberCreationTime"));
        Instant.parse(output(body, "SubscriberLastModifiedTime"));
        String queueUrl = output(body, "QueueUrl");

        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            publish(events, busArn, 750, 40);
            List<Message> messages = receive(sqs, queueUrl);
            assertEquals(1, messages.size(), messages.toString());
            assertEquals(750, MAPPER.readTree(messages.getFirst().body()).path("detail").path("total").asInt());

            // A new pattern changes the subscriber in place.
            updateStack(stack, template("orders-" + suffix, SMALL));
            body = awaitStatus(stack, "UPDATE_COMPLETE");
            assertEquals(subscriberArn, output(body, "SubscriberRef"));
            publish(events, busArn, 750, 40);
            messages = receive(sqs, queueUrl);
            assertEquals(1, messages.size(), messages.toString());
            assertEquals(40, MAPPER.readTree(messages.getFirst().body()).path("detail").path("total").asInt());

            deleteStack(stack);
            assertThrows(ResourceNotFoundException.class, () -> publish(events, busArn, 750));
        }
    }

    @Test
    void aSameNameReplacementThatFailsToCreateRollsBackToTheOriginalSubscriber() throws Exception {
        String suffix = suffix();
        String stack = createStack(template("orders-" + suffix, LARGE));
        String body = awaitStatus(stack, "CREATE_COMPLETE");
        String busArn = output(body, "BusRef");
        String subscriberArn = output(body, "SubscriberRef");
        String queueUrl = output(body, "QueueUrl");

        updateStack(stack, template("orders-" + suffix, LARGE, "FIFO", NOT_A_QUEUE));
        awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            assertEquals("UNORDERED", events.describeSubscriber(request -> request.subscriberArn(subscriberArn))
                    .typeAsString(), "the original subscriber is back under its own ARN");
            publish(events, busArn, 750);
            assertEquals(1, receive(sqs, queueUrl).size());
        }
    }

    @Test
    void aRollbackThatCannotRecreateTheSubscriberFailsAndContinueUpdateRollbackRetriesIt() throws Exception {
        String suffix = suffix();
        String stack = createStack(template("orders-" + suffix, LARGE));
        String body = awaitStatus(stack, "CREATE_COMPLETE");
        String busArn = output(body, "BusRef");
        String subscriberArn = output(body, "SubscriberRef");
        String queueUrl = output(body, "QueueUrl");
        AtomicBoolean restoresFail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (restoresFail.get()) {
                throw new AwsException("InternalException", "restore unavailable", 500);
            }
            return invocation.callRealMethod();
        }).when(eventBridgeV2Service).restoreSubscriber(any(), any());

        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            updateStack(stack, template("orders-" + suffix, LARGE, "FIFO", NOT_A_QUEUE));
            awaitStatus(stack, "UPDATE_ROLLBACK_FAILED");
            assertThrows(ResourceNotFoundException.class,
                    () -> events.describeSubscriber(request -> request.subscriberArn(subscriberArn)));

            restoresFail.set(false);
            cfn(stack, "ContinueUpdateRollback", null).then().statusCode(200);
            awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

            assertEquals("UNORDERED", events.describeSubscriber(request -> request.subscriberArn(subscriberArn))
                    .typeAsString());
            publish(events, busArn, 750);
            assertEquals(1, receive(sqs, queueUrl).size());
        }
    }

    @Test
    void anInPlaceUpdateRollsBackWhenALaterResourceFails() throws Exception {
        String suffix = suffix();
        String stack = createStack(template("orders-" + suffix, LARGE));
        String body = awaitStatus(stack, "CREATE_COMPLETE");
        String busArn = output(body, "BusRef");
        String subscriberArn = output(body, "SubscriberRef");
        String queueUrl = output(body, "QueueUrl");

        // The bus and the subscriber change in place, then a subscriber the update adds fails to create.
        ObjectNode failing = (ObjectNode) MAPPER.readTree(template("orders-" + suffix, SMALL));
        ((ObjectNode) failing.path("Resources").path("Bus").path("Properties")).put("Description", "changed");
        ObjectNode broken = MAPPER.valueToTree(Map.of("Type", "AWS::EventsV2::Subscriber",
                "DependsOn", List.of("Subscriber"), "Properties", Map.of("Name", "broken",
                        "EventBusArn", Map.of("Fn::GetAtt", List.of("Bus", "EventBusArn")),
                        "InvokeConfiguration", Map.of("TargetArn", NOT_A_QUEUE, "RoleArn", ROLE))));
        ((ObjectNode) failing.path("Resources")).set("Broken", broken);
        updateStack(stack, MAPPER.writeValueAsString(failing));
        awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            assertNull(events.describeEventBus(request -> request.eventBusArn(busArn)).description(),
                    "the bus is back to the settings it had");
            assertEquals(LARGE, events.describeSubscriber(request -> request.subscriberArn(subscriberArn))
                    .filterConfiguration().filters().getFirst().pattern(), "the subscriber is back to its filter");
            publish(events, busArn, 750, 40);
            List<Message> messages = receive(sqs, queueUrl);
            assertEquals(1, messages.size(), messages.toString());
            assertEquals(750, MAPPER.readTree(messages.getFirst().body()).path("detail").path("total").asInt());
        }
    }

    private static void publish(EventBridgeV2Client events, String busArn, int... totals) {
        events.putEvents(request -> request.eventBusArn(busArn).entries(Arrays.stream(totals)
                .mapToObj(total -> PutEventsRequestEntry.builder()
                        .source("com.shop").detailType("Order Placed").detail("{\"total\":" + total + "}").build())
                .toList()));
    }

    private static List<Message> receive(SqsClient sqs, String queueUrl) {
        List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl).maxNumberOfMessages(10)
                .waitTimeSeconds(1)).messages();
        for (Message message : messages) {
            sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        }
        return messages;
    }

    private static String template(String busName, String pattern) throws Exception {
        return template(busName, pattern, "UNORDERED", Map.of("Fn::GetAtt", List.of("Queue", "Arn")));
    }

    private static String template(String busName, String pattern, String type, Object targetArn) throws Exception {
        Map<String, Object> subscriber = new LinkedHashMap<>();
        subscriber.put("Name", "large-orders");
        subscriber.put("EventBusArn", Map.of("Fn::GetAtt", List.of("Bus", "EventBusArn")));
        subscriber.put("Type", type);
        subscriber.put("InvokeConfiguration", Map.of("TargetArn", targetArn, "RoleArn", ROLE));
        subscriber.put("FilterConfiguration", Map.of("Filters", List.of(Map.of("Scope", "DATA", "Pattern", pattern))));
        subscriber.put("RetryPolicy", Map.of("MaxRetryAttempts", "3"));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Queue", Map.of("Type", "AWS::SQS::Queue"));
        resources.put("Bus", Map.of("Type", "AWS::EventsV2::EventBus", "Properties", Map.of("Name", busName,
                "StorageConfiguration", Map.of("RetentionPeriodInDays", 7),
                "Tags", List.of(Map.of("Key", "team", "Value", "orders")))));
        resources.put("Subscriber", Map.of("Type", "AWS::EventsV2::Subscriber", "Properties", subscriber));

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("QueueUrl", value(Map.of("Ref", "Queue")));
        outputs.put("BusRef", value(Map.of("Ref", "Bus")));
        for (String attribute : List.of("EventBusArn", "State", "CreationTime", "LastModifiedTime")) {
            outputs.put("Bus" + attribute.replace("EventBus", ""), value(getAtt("Bus", attribute)));
        }
        outputs.put("SubscriberRef", value(Map.of("Ref", "Subscriber")));
        for (String attribute : List.of("SubscriberArn", "BusName", "CreationTime", "LastModifiedTime")) {
            outputs.put(attribute.startsWith("Subscriber") ? attribute : "Subscriber" + attribute,
                    value(getAtt("Subscriber", attribute)));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", resources, "Outputs", outputs));
    }

    private static Map<String, Object> value(Object value) {
        return Map.of("Value", value);
    }

    private static Map<String, Object> getAtt(String resource, String attribute) {
        return Map.of("Fn::GetAtt", List.of(resource, attribute));
    }

    private String createStack(String template) {
        String stack = "cfn-eventsv2-" + suffix();
        cfn(stack, "CreateStack", template).then().statusCode(200);
        stacks.add(stack);
        return stack;
    }

    private static void updateStack(String stack, String template) {
        cfn(stack, "UpdateStack", template).then().statusCode(200);
    }

    private void deleteStack(String stack) {
        cfn(stack, "DeleteStack", null).then().statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Response response = cfn(stack, "DescribeStacks", null);
            assertEquals(400, response.statusCode(), response.asString());
        });
        stacks.remove(stack);
    }

    private static String awaitStatus(String stack, String status) {
        return await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn(stack, "DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> status.equals(XmlParser.extractFirst(body, "StackStatus", null)));
    }

    private static String output(String body, String key) {
        String value = XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue").get(key);
        assertFalse(value == null || value.isBlank(), key + " in " + body);
        return value;
    }

    private static Response cfn(String stack, String action, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.post("/");
    }

    private static EventBridgeV2Client events() {
        return EventBridgeV2Client.builder().endpointOverride(endpoint())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static SqsClient sqs() {
        return SqsClient.builder().endpointOverride(endpoint())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static URI endpoint() {
        return URI.create("http://localhost:" + RestAssured.port);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
