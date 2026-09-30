package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3NotificationDestinationIamEnforcementIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String BUCKET_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "000000000002";

    @Inject SqsService sqsService;
    @Inject ObjectMapper objectMapper;

    private String bucket;

    @BeforeEach
    void createBucket() {
        bucket = "notification-enforced-" + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .when().put("/" + bucket).then().statusCode(200);
    }

    @AfterEach
    void removeBucket() {
        given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void crossAccountQueueIsValidatedWithoutReceivingTestMessage() throws Exception {
        String otherQueueName = "notification-other-" + UUID.randomUUID().toString().substring(0, 8);
        String ownQueueName = "notification-own-" + UUID.randomUUID().toString().substring(0, 8);
        String otherQueueUrl = createQueue(OTHER_ACCOUNT, otherQueueName);
        String ownQueueUrl = createQueue(BUCKET_ACCOUNT, ownQueueName);
        String otherQueueArn = queueArn(OTHER_ACCOUNT, otherQueueName);
        String ownQueueArn = queueArn(BUCKET_ACCOUNT, ownQueueName);
        try {
            putNotification(otherQueueArn).statusCode(200);
            assertTrue(sqsService.receiveMessage(otherQueueUrl, 1, 0, 0, REGION).isEmpty());
            getNotification().body(containsString(otherQueueArn));

            String missingQueueArn = queueArn(OTHER_ACCOUNT, "notification-missing-" + UUID.randomUUID());
            putNotification(missingQueueArn).statusCode(400)
                    .body(containsString("The destination queue does not exist"));
            getNotification().body(containsString(otherQueueArn))
                    .body(not(containsString(missingQueueArn)));
            assertTrue(sqsService.receiveMessage(otherQueueUrl, 1, 0, 0, REGION).isEmpty());

            putNotification(ownQueueArn).statusCode(200);
            List<Message> ownMessages = sqsService.receiveMessage(ownQueueUrl, 1, 0, 0, REGION);
            assertFalse(ownMessages.isEmpty());
            JsonNode testEvent = objectMapper.readTree(ownMessages.getFirst().getBody());
            assertEquals("s3:TestEvent", testEvent.path("Event").asText());
            assertFalse(testEvent.has("Records"));
            assertTrue(sqsService.receiveMessage(otherQueueUrl, 1, 0, 0, REGION).isEmpty());
        } finally {
            deleteQueue(BUCKET_ACCOUNT, ownQueueUrl);
            deleteQueue(OTHER_ACCOUNT, otherQueueUrl);
        }
    }

    private static String createQueue(String accountId, String queueName) {
        return given().header("Authorization", authorization(accountId, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue").formParam("QueueName", queueName)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static void deleteQueue(String accountId, String queueUrl) {
        given().header("Authorization", authorization(accountId, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteQueue").formParam("QueueUrl", queueUrl)
                .when().post("/").then().statusCode(200);
    }

    private ValidatableResponse putNotification(String queueArn) {
        String xml = "<NotificationConfiguration><QueueConfiguration><Queue>" + queueArn
                + "</Queue><Event>s3:ObjectCreated:*</Event></QueueConfiguration></NotificationConfiguration>";
        return given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .contentType("application/xml").body(xml)
                .when().put("/" + bucket + "?notification").then();
    }

    private ValidatableResponse getNotification() {
        return given().header("Authorization", authorization(BUCKET_ACCOUNT, "s3"))
                .when().get("/" + bucket + "?notification").then().statusCode(200);
    }

    private static String queueArn(String accountId, String queueName) {
        return "arn:aws:sqs:" + REGION + ":" + accountId + ":" + queueName;
    }

    private static String authorization(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20261001/" + REGION
                + "/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
