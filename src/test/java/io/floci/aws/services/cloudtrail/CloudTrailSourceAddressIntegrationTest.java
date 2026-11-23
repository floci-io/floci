package io.floci.aws.services.cloudtrail;

import io.floci.aws.testing.CloudTrailLogFiles;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static io.floci.aws.testing.CloudTrailRequests.invokeCloudTrail;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code sourceIPAddress} is "the IP address that the request was made from"
 * (https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-event-reference-record-contents.html):
 * the connection's, not an {@code X-Forwarded-For} any client can send.
 */
@QuarkusTest
class CloudTrailSourceAddressIntegrationTest {

    @Inject
    CloudTrailLogWriter writer;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aClientSentForwardedForIsNotTheSourceAddress() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "ct-src-" + suffix;
        String logs = "ct-src-logs-" + suffix;
        String trail = "ct-src-" + suffix;
        given().when().put("/" + bucket).then().statusCode(200);
        given().when().put("/" + logs).then().statusCode(200);
        cloudTrail("CreateTrail", "{\"Name\":\"" + trail + "\",\"S3BucketName\":\"" + logs + "\"}");
        cloudTrail("PutEventSelectors", "{\"TrailName\":\"" + trail + "\",\"EventSelectors\":[{\"ReadWriteType\":"
                + "\"All\",\"IncludeManagementEvents\":false,\"DataResources\":[{\"Type\":\"AWS::S3::Object\","
                + "\"Values\":[\"arn:aws:s3:::" + bucket + "/\"]}]}]}");
        cloudTrail("StartLogging", "{\"Name\":\"" + trail + "\"}");

        given().header("X-Forwarded-For", "203.0.113.7").body("x")
                .when().put("/" + bucket + "/forwarded.txt").then().statusCode(200);
        writer.flushNow();

        String recorded = CloudTrailLogFiles.records(logs).stream()
                .filter(r -> "forwarded.txt".equals(r.path("requestParameters").path("key").asText()))
                .map(r -> r.path("sourceIPAddress").asText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record of the forwarded write in " + logs));
        assertThat(recorded, equalTo("127.0.0.1"));
    }

    private static void cloudTrail(String action, String body) {
        invokeCloudTrail(action, body).then().statusCode(200);
    }
}
