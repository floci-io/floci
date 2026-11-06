package io.floci.aws.services.sqs;

import io.floci.aws.testing.PartitionMatrix;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** {@code floci.services.sqs.endpoint-strategy=aws}: queue URLs take the AWS form. */
@QuarkusTest
@TestProfile(SqsAwsEndpointStrategyIntegrationTest.AwsEndpointStrategyProfile.class)
class SqsAwsEndpointStrategyIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void queryProtocolReturnsTheAwsQueueUrl() {
        SqsQueueUrlRoundTrip.query("aws-url-queue",
                "https://sqs.us-east-1.amazonaws.com/" + ACCOUNT_ID + "/aws-url-queue",
                "http://localhost:4566/" + ACCOUNT_ID + "/aws-url-queue");
    }

    @Test
    void jsonProtocolReturnsTheAwsQueueUrlForTheSignedRegion() {
        SqsQueueUrlRoundTrip.json(PartitionMatrix.sigV4Auth("eu-west-2", "sqs"), "eu-west-2",
                "json-aws-url-queue",
                "https://sqs.eu-west-2.amazonaws.com/" + ACCOUNT_ID + "/json-aws-url-queue",
                "http://localhost:4566/" + ACCOUNT_ID + "/json-aws-url-queue");
    }

    @Test
    void queueUrlHostCarriesThePartitionDnsSuffix() {
        String auth = PartitionMatrix.sigV4Auth("cn-north-1", "sqs");
        String queueName = "cn-aws-url-queue";
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

    public static final class AwsEndpointStrategyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.sqs.endpoint-strategy", "aws");
        }
    }
}
