package io.floci.aws.services.guardduty;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class GuardDutyPublishingDestinationIntegrationTest {

    private static final String ACCOUNT = "818181818181";
    private static final String OTHER_ACCOUNT = "828282828282";
    private static final String BUCKET_ARN = "arn:aws:s3:::findings-bucket";
    private static final String KMS_ARN = "arn:aws:kms:us-east-1:" + ACCOUNT + ":key/first";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void publishingDestinationLifecycle() {
        String detectorId = createDetector(ACCOUNT);
        String path = "/detector/" + detectorId + "/publishingDestination";

        String destinationId = given().contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"destinationType\":\"S3\",\"destinationProperties\":"
                        + "{\"destinationArn\":\"" + BUCKET_ARN + "\",\"kmsKeyArn\":\"" + KMS_ARN + "\"}}")
                .post(path)
                .then().statusCode(200)
                .body("destinationId", notNullValue())
                .extract().path("destinationId");

        given().header("Authorization", auth(ACCOUNT))
                .get(path + "/" + destinationId)
                .then().statusCode(200)
                .body("destinationId", equalTo(destinationId))
                .body("destinationType", equalTo("S3"))
                .body("status", equalTo("PUBLISHING"))
                .body("publishingFailureStartTimestamp", equalTo(0))
                .body("destinationProperties.destinationArn", equalTo(BUCKET_ARN))
                .body("destinationProperties.kmsKeyArn", equalTo(KMS_ARN));

        given().header("Authorization", auth(ACCOUNT))
                .get(path)
                .then().statusCode(200)
                .body("destinations", hasSize(1))
                .body("destinations[0].destinationId", equalTo(destinationId))
                .body("destinations[0].destinationType", equalTo("S3"))
                .body("destinations[0].status", equalTo("PUBLISHING"));

        String newKms = "arn:aws:kms:us-east-1:" + ACCOUNT + ":key/second";
        given().contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"destinationProperties\":{\"destinationArn\":\"" + BUCKET_ARN
                        + "\",\"kmsKeyArn\":\"" + newKms + "\"}}")
                .post(path + "/" + destinationId)
                .then().statusCode(200);

        given().header("Authorization", auth(ACCOUNT))
                .get(path + "/" + destinationId)
                .then().statusCode(200)
                .body("destinationProperties.kmsKeyArn", equalTo(newKms));

        given().header("Authorization", auth(ACCOUNT))
                .delete(path + "/" + destinationId)
                .then().statusCode(200);

        given().header("Authorization", auth(ACCOUNT))
                .get(path + "/" + destinationId)
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(
                        "The request is rejected because the one or more input parameters have invalid values."));

        given().header("Authorization", auth(ACCOUNT))
                .get(path)
                .then().statusCode(200)
                .body("destinations", hasSize(0));
    }

    /**
     * Another account cannot reach the destination because it cannot reach the owning detector: the
     * detector lookup rejects first. Destination storage partitioning itself is pinned in
     * GuardDutyServiceTest#publishingDestinationsArePartitionedByAccountEvenForTheSameDetector.
     */
    @Test
    void anotherAccountCannotReachDestinationThroughAForeignDetector() {
        String detectorId = createDetector(OTHER_ACCOUNT);
        String path = "/detector/" + detectorId + "/publishingDestination";
        String destinationId = given().contentType("application/json")
                .header("Authorization", auth(OTHER_ACCOUNT))
                .body("{\"destinationType\":\"S3\",\"destinationProperties\":"
                        + "{\"destinationArn\":\"" + BUCKET_ARN + "\",\"kmsKeyArn\":\"" + KMS_ARN + "\"}}")
                .post(path)
                .then().statusCode(200)
                .extract().path("destinationId");

        given().header("Authorization", auth(ACCOUNT))
                .get(path + "/" + destinationId)
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(GuardDutyService.DETECTOR_NOT_FOUND_MESSAGE));
    }

    @Test
    void rejectsUnsupportedDestinationType() {
        String detectorId = createDetector("838383838383");

        given().contentType("application/json")
                .header("Authorization", auth("838383838383"))
                .body("{\"destinationType\":\"SQS\",\"destinationProperties\":"
                        + "{\"destinationArn\":\"" + BUCKET_ARN + "\"}}")
                .post("/detector/" + detectorId + "/publishingDestination")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    private static String createDetector(String accountId) {
        return given()
                .contentType("application/json")
                .header("Authorization", auth(accountId))
                .body("{\"enable\":true}")
                .post("/detector")
                .then().statusCode(200)
                .extract().path("detectorId");
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260904/us-east-1/guardduty/aws4_request";
    }
}
