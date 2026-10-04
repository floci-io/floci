package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@QuarkusTest
class S3NotificationDestinationValidationIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String OTHER_ACCOUNT = "000000000002";

    @Inject SqsService sqsService;
    @InjectSpy SnsService snsService;
    @Inject RegionResolver regionResolver;
    @Inject ObjectMapper objectMapper;

    private String bucket;
    private final List<String> queueUrls = new ArrayList<>();
    private final List<String> topicArns = new ArrayList<>();

    @BeforeEach
    void createBucket() {
        bucket = "notification-validation-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
    }

    @AfterEach
    void removeDestinations() {
        given().when().delete("/" + bucket).then().statusCode(204);
        for (String topicArn : topicArns) {
            snsService.deleteTopic(topicArn, REGION);
        }
        for (String queueUrl : queueUrls) {
            sqsService.deleteQueue(queueUrl, REGION);
        }
    }

    @Test
    void malformedArnIsRejectedEvenWhenDestinationValidationIsSkipped() {
        String xml = notificationConfiguration(queueConfiguration("not-an-arn"));

        given().contentType("application/xml").body(xml)
                .when().put("/" + bucket + "?notification")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"))
                .body(containsString("<Message>The ARN could not be parsed</Message>"))
                .body(containsString("<ArgumentName>Queue</ArgumentName>"))
                .body(containsString("<ArgumentValue>not-an-arn</ArgumentValue>"));

        given().header("x-amz-skip-destination-validation", "true")
                .contentType("application/xml").body(xml)
                .when().put("/" + bucket + "?notification")
                .then().statusCode(400)
                .body(containsString("<ArgumentName>Queue</ArgumentName>"));

        given().when().get("/" + bucket + "?notification")
                .then().statusCode(200).body(not(containsString("QueueConfiguration")));
    }

    @Test
    void wrongDestinationServiceIsRejectedBeforeSkipOrExistenceChecks() {
        String queueFieldWithTopicArn = regionResolver.buildArn("sns", REGION, "wrong-service");
        putNotification(notificationConfiguration(queueConfiguration(queueFieldWithTopicArn)), true)
                .statusCode(400)
                .body(containsString("<Message>The ARN could not be parsed</Message>"))
                .body(containsString("<ArgumentName>Queue</ArgumentName>"));

        String topicFieldWithQueueArn = regionResolver.buildArn("sqs", REGION, "wrong-service");
        putNotification(notificationConfiguration(topicConfiguration(topicFieldWithQueueArn)), true)
                .statusCode(400)
                .body(containsString("<ArgumentName>Topic</ArgumentName>"));

        putNotification(notificationConfiguration(lambdaConfiguration("not-a-lambda-arn")), true)
                .statusCode(400)
                .body(containsString("<ArgumentName>CloudFunction</ArgumentName>"));
    }

    @Test
    void missingDestinationsAreReportedTogetherWithoutReplacingPreviousConfiguration() {
        String existingQueueArn = createQueue("existing", Map.of());
        putNotification(notificationConfiguration(queueConfiguration(existingQueueArn)), false)
                .statusCode(200);
        Message initialTestEvent = receiveQueueMessage(queueUrls.getFirst()).getFirst();
        sqsService.deleteMessage(queueUrls.getFirst(), initialTestEvent.getReceiptHandle(), REGION);

        String missingQueueOne = regionResolver.buildArn("sqs", REGION, "missing-one");
        String missingQueueTwo = regionResolver.buildArn("sqs", REGION, "missing-two");
        String missingTopic = regionResolver.buildArn("sns", REGION, "missing-topic");
        String missingFunction = regionResolver.buildArn("lambda", REGION, "function:missing-function");
        putNotification(notificationConfiguration(
                queueConfiguration(missingQueueOne) + queueConfiguration(missingQueueTwo)
                        + topicConfiguration(missingTopic) + lambdaConfiguration(missingFunction)), false)
                .statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"))
                .body(containsString("Unable to validate the following destination configurations"))
                .body(containsString("<ArgumentName1>" + missingQueueOne + "</ArgumentName1>"))
                .body(containsString("<ArgumentValue1>The destination queue does not exist</ArgumentValue1>"))
                .body(containsString("<ArgumentName2>" + missingQueueTwo + "</ArgumentName2>"))
                .body(containsString("<ArgumentName3>" + missingTopic + "</ArgumentName3>"))
                .body(containsString("<ArgumentName4>" + missingFunction + ", null</ArgumentName4>"))
                .body(containsString("<ArgumentValue4>Not authorized to invoke function ["
                        + missingFunction + "]</ArgumentValue4>"));

        given().when().get("/" + bucket + "?notification")
                .then().statusCode(200)
                .body(containsString(existingQueueArn))
                .body(not(containsString(missingQueueOne)))
                .body(not(containsString(missingTopic)));
        assertTrue(receiveQueueMessage(queueUrls.getFirst()).isEmpty());
    }

    @Test
    void skipDestinationValidationStoresUnknownDestinationsWithoutSendingTestEvent() {
        String existingQueueArn = createQueue("skip-existing", Map.of());
        String missingQueueArn = regionResolver.buildArn("sqs", REGION, "skip-missing");
        String missingTopicArn = regionResolver.buildArn("sns", REGION, "skip-topic");
        String missingFunctionArn = regionResolver.buildArn("lambda", REGION, "function:skip-function");

        putNotification(notificationConfiguration(queueConfiguration(existingQueueArn)
                + queueConfiguration(missingQueueArn)
                + topicConfiguration(missingTopicArn)
                + lambdaConfiguration(missingFunctionArn)), true).statusCode(200);

        given().when().get("/" + bucket + "?notification")
                .then().statusCode(200)
                .body(containsString(existingQueueArn))
                .body(containsString(missingQueueArn))
                .body(containsString(missingTopicArn))
                .body(containsString(missingFunctionArn));
        assertTrue(receiveQueueMessage(queueUrls.getFirst()).isEmpty());
    }

    @Test
    void validQueueAndTopicReceiveTestEventWithoutRecords() throws Exception {
        String directQueueArn = createQueue("direct", Map.of());
        String subscribedQueueArn = createQueue("subscribed", Map.of());
        String topicArn = createTopic("test-topic");
        snsService.subscribe(topicArn, "sqs", subscribedQueueArn, REGION,
                Map.of("RawMessageDelivery", "true"));

        putNotification(notificationConfiguration(queueConfiguration(directQueueArn)
                + topicConfiguration(topicArn)), false).statusCode(200);

        assertTestEvent(receiveQueueMessage(queueUrls.get(0)).getFirst().getBody());
        assertTestEvent(receiveQueueMessage(queueUrls.get(1)).getFirst().getBody());
    }

    @Test
    void crossRegionTopicReceivesTestEventAndLaterObjectEvent() throws Exception {
        String topicRegion = "ap-southeast-2";
        String queueName = "notification-topic-cross-region-" + UUID.randomUUID().toString().substring(0, 8);
        String queueUrl = sqsService.createQueue(queueName, Map.of(), topicRegion).getQueueUrl();
        String queueArn = sqsService.getQueueAttributes(queueUrl, List.of("QueueArn"), topicRegion)
                .get("QueueArn");
        String topicArn = snsService.createTopic(queueName, Map.of(), Map.of(), topicRegion).getTopicArn();
        try {
            snsService.subscribe(topicArn, "sqs", queueArn, topicRegion,
                    Map.of("RawMessageDelivery", "true"));
            putNotification(notificationConfiguration(topicConfiguration(topicArn)), false).statusCode(200);

            Message testEvent = sqsService.receiveMessage(queueUrl, 1, 0, 0, topicRegion).getFirst();
            assertTestEvent(testEvent.getBody());
            sqsService.deleteMessage(queueUrl, testEvent.getReceiptHandle(), topicRegion);

            given().contentType("text/plain").body("payload")
                    .when().put("/" + bucket + "/cross-region-object")
                    .then().statusCode(200);
            JsonNode objectEvent = objectMapper.readTree(
                    sqsService.receiveMessage(queueUrl, 1, 0, 0, topicRegion).getFirst().getBody());
            assertEquals("aws:s3", objectEvent.path("Records").get(0).path("eventSource").asText());
            assertEquals("cross-region-object", objectEvent.path("Records").get(0)
                    .path("s3").path("object").path("key").asText());
        } finally {
            given().when().delete("/" + bucket + "/cross-region-object").then().statusCode(204);
            snsService.deleteTopic(topicArn, topicRegion);
            sqsService.deleteQueue(queueUrl, topicRegion);
        }
    }

    @Test
    void failedTestMessageLeavesPreviousConfigurationUntouched() {
        String validQueueArn = createQueue("before-failure", Map.of());
        putNotification(notificationConfiguration(queueConfiguration(validQueueArn)), false)
                .statusCode(200);

        String fifoQueueArn = createQueue("cannot-send.fifo", Map.of("FifoQueue", "true"));
        putNotification(notificationConfiguration(queueConfiguration(fifoQueueArn)), false)
                .statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"))
                .body(containsString("<ArgumentName1>" + fifoQueueArn + "</ArgumentName1>"));

        given().when().get("/" + bucket + "?notification")
                .then().statusCode(200)
                .body(containsString(validQueueArn))
                .body(not(containsString(fifoQueueArn)));
    }

    @Test
    void crossAccountQueueReceivesTestEventWithoutIamEnforcement() throws Exception {
        String queueName = "notification-cross-account-" + UUID.randomUUID().toString().substring(0, 8);
        String auth = "AWS4-HMAC-SHA256 Credential=" + OTHER_ACCOUNT
                + "/20261001/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";
        String queueUrl = given().header("Authorization", auth)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue").formParam("QueueName", queueName)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        String queueArn = "arn:aws:sqs:" + REGION + ":" + OTHER_ACCOUNT + ":" + queueName;
        try {
            putNotification(notificationConfiguration(queueConfiguration(queueArn)), false).statusCode(200);
            Message testEvent = sqsService.receiveMessage(queueUrl, 1, 0, 0, REGION).getFirst();
            assertTestEvent(testEvent.getBody());
            sqsService.deleteMessage(queueUrl, testEvent.getReceiptHandle(), REGION);

            given().contentType("text/plain").body("payload")
                    .when().put("/" + bucket + "/cross-account-object").then().statusCode(200);
            assertTrue(sqsService.receiveMessage(queueUrl, 1, 0, 0, REGION).getFirst()
                    .getBody().contains("cross-account-object"));
            given().when().delete("/" + bucket + "/cross-account-object").then().statusCode(204);
        } finally {
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteQueue").formParam("QueueUrl", queueUrl)
                    .when().post("/").then().statusCode(200);
        }
    }

    @Test
    void crossAccountTopicIsValidatedWithoutTestPublishByDefault() {
        String topicName = "notification-cross-account-" + UUID.randomUUID().toString().substring(0, 8);
        String authorization = "AWS4-HMAC-SHA256 Credential=" + OTHER_ACCOUNT
                + "/20261001/us-east-1/sns/aws4_request, SignedHeaders=host, Signature=abc";
        String topicArn = given().header("Authorization", authorization)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateTopic").formParam("Name", topicName)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        try {
            assertTrue(snsService.topicExists(topicArn, REGION));
            putNotification(notificationConfiguration(topicConfiguration(topicArn)), false).statusCode(200);
            verify(snsService, never()).publish(eq(topicArn), isNull(), anyString(),
                    eq("Amazon S3 Notification"), eq(REGION));
            given().when().get("/" + bucket + "?notification")
                    .then().statusCode(200).body(containsString(topicArn));

            String missingArn = "arn:aws:sns:" + REGION + ":" + OTHER_ACCOUNT + ":notification-missing-"
                    + UUID.randomUUID();
            putNotification(notificationConfiguration(topicConfiguration(missingArn)), false)
                    .statusCode(400).body(containsString("The destination topic does not exist"));
            given().when().get("/" + bucket + "?notification")
                    .then().statusCode(200).body(containsString(topicArn)).body(not(containsString(missingArn)));

            String ownTopicArn = createTopic("same-account");
            putNotification(notificationConfiguration(topicConfiguration(ownTopicArn)), false).statusCode(200);
            verify(snsService).publish(eq(ownTopicArn), isNull(), anyString(),
                    eq("Amazon S3 Notification"), eq(REGION));
        } finally {
            given().header("Authorization", authorization).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteTopic").formParam("TopicArn", topicArn)
                    .when().post("/").then().statusCode(200);
        }
    }

    private String createQueue(String suffix, Map<String, String> attributes) {
        String queueName = "notification-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        if (queueName.contains(".fifo-")) {
            queueName = queueName.replace(".fifo-", "-") + ".fifo";
        }
        String queueUrl = sqsService.createQueue(queueName, attributes, REGION).getQueueUrl();
        queueUrls.add(queueUrl);
        return sqsService.getQueueAttributes(queueUrl, List.of("QueueArn"), REGION).get("QueueArn");
    }

    private String createTopic(String suffix) {
        String topicArn = snsService.createTopic(
                "notification-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8),
                Map.of(), Map.of(), REGION).getTopicArn();
        topicArns.add(topicArn);
        return topicArn;
    }

    private List<Message> receiveQueueMessage(String queueUrl) {
        return sqsService.receiveMessage(queueUrl, 1, 0, 0, REGION);
    }

    private void assertTestEvent(String json) throws Exception {
        JsonNode event = objectMapper.readTree(json);
        assertEquals("Amazon S3", event.path("Service").asText());
        assertEquals("s3:TestEvent", event.path("Event").asText());
        assertEquals(bucket, event.path("Bucket").asText());
        assertNotNull(Instant.parse(event.path("Time").asText()));
        assertFalse(event.path("RequestId").asText().isBlank());
        assertFalse(event.path("HostId").asText().isBlank());
        assertFalse(event.has("Records"));
    }

    private ValidatableResponse putNotification(String xml, boolean skip) {
        return given().header("x-amz-skip-destination-validation", Boolean.toString(skip))
                .contentType("application/xml").body(xml)
                .when().put("/" + bucket + "?notification")
                .then();
    }

    private static String notificationConfiguration(String contents) {
        return "<NotificationConfiguration>" + contents + "</NotificationConfiguration>";
    }

    private static String queueConfiguration(String arn) {
        return "<QueueConfiguration><Queue>" + arn
                + "</Queue><Event>s3:ObjectCreated:*</Event></QueueConfiguration>";
    }

    private static String topicConfiguration(String arn) {
        return "<TopicConfiguration><Topic>" + arn
                + "</Topic><Event>s3:ObjectCreated:*</Event></TopicConfiguration>";
    }

    private static String lambdaConfiguration(String arn) {
        return "<CloudFunctionConfiguration><CloudFunction>" + arn
                + "</CloudFunction><Event>s3:ObjectCreated:*</Event></CloudFunctionConfiguration>";
    }
}
