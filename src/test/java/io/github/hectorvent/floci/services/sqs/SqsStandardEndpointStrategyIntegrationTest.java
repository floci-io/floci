package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code floci.services.sqs.endpoint-strategy=standard}: queue URLs take the AWS form. */
@QuarkusTest
@TestProfile(SqsStandardEndpointStrategyIntegrationTest.StandardEndpointStrategyProfile.class)
class SqsStandardEndpointStrategyIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void queryProtocolReturnsTheAwsQueueUrl() {
        String queueName = "standard-url-queue";
        String expectedUrl = "https://sqs.us-east-1.amazonaws.com/" + ACCOUNT_ID + "/" + queueName;

        String createdUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        try {
            assertEquals(expectedUrl, createdUrl);

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queueName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("GetQueueUrlResponse.GetQueueUrlResult.QueueUrl", equalTo(expectedUrl));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListQueues")
                .formParam("QueueNamePrefix", queueName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<QueueUrl>" + expectedUrl + "</QueueUrl>"));

            for (String input : List.of(expectedUrl, "http://localhost:4566/" + ACCOUNT_ID + "/" + queueName,
                    queueName)) {
                given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "GetQueueAttributes")
                    .formParam("QueueUrl", input)
                    .formParam("AttributeName.1", "QueueArn")
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .body(containsString("arn:aws:sqs:us-east-1:" + ACCOUNT_ID + ":" + queueName));
            }

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueAttributes")
                .formParam("AttributeName.1", "QueueArn")
            .when()
                .post("/" + ACCOUNT_ID + "/" + queueName)
            .then()
                .statusCode(200)
                .body(containsString("arn:aws:sqs:us-east-1:" + ACCOUNT_ID + ":" + queueName));
        } finally {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteQueue")
                .formParam("QueueUrl", expectedUrl)
            .when()
                .post("/");
        }
    }

    @Test
    void jsonProtocolReturnsTheAwsQueueUrlForTheSignedRegion() {
        String auth = PartitionMatrix.sigV4Auth("eu-west-2", "sqs");
        String queueName = "json-standard-url-queue";
        String expectedUrl = "https://sqs.eu-west-2.amazonaws.com/" + ACCOUNT_ID + "/" + queueName;

        given()
            .contentType(JSON_CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .header("Authorization", auth)
            .body("{\"QueueName\":\"" + queueName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueueUrl", equalTo(expectedUrl));

        try {
            given()
                .contentType(JSON_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.GetQueueUrl")
                .header("Authorization", auth)
                .body("{\"QueueName\":\"" + queueName + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("QueueUrl", equalTo(expectedUrl));

            given()
                .contentType(JSON_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ListQueues")
                .header("Authorization", auth)
                .body("{\"QueueNamePrefix\":\"" + queueName + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("QueueUrls", contains(expectedUrl));

            for (String input : List.of(expectedUrl, "http://localhost:4566/" + ACCOUNT_ID + "/" + queueName,
                    queueName)) {
                given()
                    .contentType(JSON_CONTENT_TYPE)
                    .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                    .header("Authorization", auth)
                    .body("{\"QueueUrl\":\"" + input + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .body("Attributes.QueueArn",
                            equalTo("arn:aws:sqs:eu-west-2:" + ACCOUNT_ID + ":" + queueName));
            }
        } finally {
            given()
                .contentType(JSON_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                .header("Authorization", auth)
                .body("{\"QueueUrl\":\"" + expectedUrl + "\"}")
            .when()
                .post("/");
        }
    }

    @Test
    void queueUrlHostCarriesThePartitionDnsSuffix() {
        String auth = PartitionMatrix.sigV4Auth("cn-north-1", "sqs");
        String queueName = "cn-standard-url-queue";
        String expectedUrl = "https://sqs.cn-north-1.amazonaws.com.cn/" + ACCOUNT_ID + "/" + queueName;

        given()
            .contentType(JSON_CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .header("Authorization", auth)
            .body("{\"QueueName\":\"" + queueName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueueUrl", equalTo(expectedUrl));

        given()
            .contentType(JSON_CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
            .header("Authorization", auth)
            .body("{\"QueueUrl\":\"" + expectedUrl + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    public static final class StandardEndpointStrategyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.sqs.endpoint-strategy", "standard");
        }
    }
}
