package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * A SigV4 scope region that no partition publishes or admits by its region pattern is refused,
 * as moto and LocalStack refuse it by default; a label of a published shape that the vendored
 * data does not list yet is served, which is the AWS SDKs' own rule. The escape hatch serves any
 * label with its own namespace.
 */
@QuarkusTest
class PartitionUnknownRegionIntegrationTest {

    static final String UNKNOWN = "polygondwanaland-west-1";
    static final String UNPUBLISHED_BUT_SHAPED = "eu-south-9";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aQueryRequestSignedForAnUnknownRegionIsRefusedWithTheSignatureError() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(UNKNOWN, "sqs"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListQueues")
        .when().post("/").then().statusCode(400)
            .contentType(containsString("xml"))
            .body("ErrorResponse.Error.Code", equalTo("InvalidSignatureException"))
            .body("ErrorResponse.Error.Type", equalTo("Sender"))
            .body("ErrorResponse.Error.Message", containsString(UNKNOWN));
    }

    @Test
    void aJsonRequestSignedForAnUnknownRegionGetsTheJsonSignatureError() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(UNKNOWN, "sqs"))
            .header("X-Amz-Target", "AmazonSQS.ListQueues")
            .contentType("application/x-amz-json-1.0")
            .body("{}")
        .when().post("/").then().statusCode(400)
            .header("X-Amzn-Errortype", "InvalidSignatureException")
            .body("__type", equalTo("InvalidSignatureException"))
            .body("message", containsString(UNKNOWN));
    }

    /** A presigned POST's credential sits in the form body, out of the ingress filter's sight. */
    @Test
    void aPresignedPostSignedForAnUnknownRegionGetsS3sMalformedHeaderError() {
        given()
            .multiPart("key", "unknown-region.txt")
            .multiPart("x-amz-credential", "AKID/20260215/" + UNKNOWN + "/s3/aws4_request")
            .multiPart("file", "unknown-region.txt", "never stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when().post("/unknown-region-post-bucket").then().statusCode(400)
            .body("Error.Code", equalTo("AuthorizationHeaderMalformed"))
            .body("Error.Message", containsString(UNKNOWN));
    }

    @Test
    void anS3RequestSignedForAnUnknownRegionGetsS3sMalformedHeaderError() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(UNKNOWN, "s3"))
        .when().get("/").then().statusCode(400)
            .body(containsString("<Code>AuthorizationHeaderMalformed</Code>"))
            .body(containsString("the region &apos;" + UNKNOWN + "&apos; is wrong"));
    }

    @Test
    void aPatternAdmittedRegionTheDataDoesNotListIsServed() {
        String name = "shaped-" + Long.toString(System.nanoTime(), 36);
        cleanup.register(() -> deleteQueue(UNPUBLISHED_BUT_SHAPED, name));
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(UNPUBLISHED_BUT_SHAPED, "sqs"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", name)
        .when().post("/").then().statusCode(200)
            .body(containsString(name));
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(UNPUBLISHED_BUT_SHAPED, "s3"))
        .when().get("/").then().statusCode(200)
            .body(not(containsString("AuthorizationHeaderMalformed")));
    }

    static void deleteQueue(String region, String name) {
        // Spell the envelope out: AwsQueryResponse.envelope wraps the result as
        // <GetQueueUrlResponse><GetQueueUrlResult><QueueUrl>. XmlPath reads "**" as a parameter
        // reference rather than a deep scan, so it threw before the delete ever ran and
        // PartitionCleanup swallowed it, leaving the queue behind in the shared emulator.
        String url = given()
                .header("Authorization", PartitionMatrix.sigV4Auth(region, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", name)
            .when().post("/").xmlPath()
                .getString("GetQueueUrlResponse.GetQueueUrlResult.QueueUrl");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("GetQueueUrl returned no QueueUrl for " + name
                    + " in " + region + "; the queue would leak into the shared emulator");
        }
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "sqs"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", url)
        .when().post("/");
    }
}
