package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SqsReceiveCountIntegrationTest {
    private static final String ACCOUNT = "741682000001";
    private static final String REGION = "eu-west-2";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261028/" + REGION + "/sqs/aws4_request";
    private SqsClient client;
    private String queueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createQueue() {
        client = AwsTestClients.create(SqsClient.builder(), ACCOUNT, Region.of(REGION));
        String created = client.createQueue(request -> request.queueName("receive-count-" + UUID.randomUUID())).queueUrl();
        queueUrl = "http://localhost:" + RestAssured.port + URI.create(created).getRawPath();
    }

    @AfterEach
    void cleanUp() {
        try {
            if (queueUrl != null) {
                client.deleteQueue(request -> request.queueUrl(queueUrl));
            }
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 11, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void sdkRejectsInvalidCountsWithoutReceivingAMessage(int count) {
        send("kept");
        SqsException error = assertThrows(SqsException.class, () -> client.receiveMessage(request -> request
                .queueUrl(queueUrl).maxNumberOfMessages(count).waitTimeSeconds(0)));
        assertEquals(400, error.statusCode());
        assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
        assertTrue(error.awsErrorDetails().errorMessage().contains("MaxNumberOfMessages"));
        assertUnreceivedMessageRemains();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void sdkAcceptsBothRangeBoundaries(int count) {
        for (int i = 0; i < 12; i++) {
            send("message-" + i);
        }
        assertEquals(count, client.receiveMessage(request -> request.queueUrl(queueUrl)
                .maxNumberOfMessages(count).waitTimeSeconds(0)).messages().size());
    }

    @Test
    void omittedAndNullCountsKeepTheDefaultOfOne() {
        send("first");
        send("second");
        assertEquals(1, client.receiveMessage(request -> request.queueUrl(queueUrl)
                .visibilityTimeout(0).waitTimeSeconds(0)).messages().size());
        given().contentType("application/x-amz-json-1.0").header("Authorization", AUTH)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":null}")
                .post("/").then().statusCode(200).body("Messages.size()", equalTo(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "11", "2147483648", "4294967297", "1.5", "invalid", ""})
    void queryProtocolRejectsInvalidCountsAtBothEndpoints(String count) {
        send("kept");
        for (String path : List.of("/", URI.create(queueUrl).getRawPath())) {
            given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                    .formParam("Action", "ReceiveMessage").formParam("QueueUrl", queueUrl)
                    .formParam("MaxNumberOfMessages", count).formParam("WaitTimeSeconds", 0)
                    .post(path).then().statusCode(400)
                    .body(containsString("<Code>InvalidParameterValue</Code>"));
        }
        assertUnreceivedMessageRemains();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "4294967297", "9223372036854775808", "1.5", "\"1\"", "true", "{}", "[]"})
    void jsonProtocolRejectsCoercionAndOverflowAtBothEndpoints(String count) {
        send("kept");
        for (String path : List.of("/", URI.create(queueUrl).getRawPath())) {
            given().contentType("application/x-amz-json-1.0").header("Authorization", AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":" + count + "}")
                    .post(path).then().statusCode(400).body("__type", equalTo("InvalidParameterValue"));
        }
        assertUnreceivedMessageRemains();
    }

    @Test
    void anEmptyQueueStillRejectsAnInvalidCount() {
        SqsException error = assertThrows(SqsException.class, () -> client.receiveMessage(request -> request
                .queueUrl(queueUrl).maxNumberOfMessages(0).waitTimeSeconds(0)));
        assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
    }

    private void send(String body) {
        client.sendMessage(request -> request.queueUrl(queueUrl).messageBody(body));
    }

    private void assertUnreceivedMessageRemains() {
        List<Message> messages = client.receiveMessage(request -> request.queueUrl(queueUrl).maxNumberOfMessages(1)
                .waitTimeSeconds(0).messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
        assertEquals(1, messages.size());
        assertEquals("kept", messages.getFirst().body());
        assertEquals("1", messages.getFirst().attributesAsStrings().get("ApproximateReceiveCount"));
    }
}
