package io.floci.aws.services.kinesis;

import io.floci.aws.services.kinesis.model.KinesisStream;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(KinesisStatusWindowIntegrationTest.WindowProfile.class)
class KinesisStatusWindowIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String STREAM_NAME = "kinesis-status-window-test";

    @Inject
    KinesisService service;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStreamReportsCreatingUntilItsConfiguredDeadline() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + STREAM_NAME + "\",\"ShardCount\":1}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        cleanup.register(() -> given()
            .header("X-Amz-Target", "Kinesis_20131202.DeleteStream")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + STREAM_NAME + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200));

        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + STREAM_NAME + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.StreamStatus", equalTo("CREATING"));

        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + STREAM_NAME + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescriptionSummary.StreamStatus", equalTo("CREATING"));

        given()
            .header("X-Amz-Target", "Kinesis_20131202.SplitShard")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + STREAM_NAME
                    + "\",\"ShardToSplit\":\"shardId-000000000000\","
                    + "\"NewStartingHashKey\":\"170141183460469231731687303715884105728\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    void updateStreamModeReportsUpdatingThenActiveOnTheWire() {
        String name = STREAM_NAME + "-update";
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + name + "\",\"ShardCount\":1}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        cleanup.register(() -> given()
            .header("X-Amz-Target", "Kinesis_20131202.DeleteStream")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200));

        KinesisStream stream = service.describeStream(name, "us-east-1");
        stream.setStatusTransitionDeadline(Instant.EPOCH);
        given()
            .header("X-Amz-Target", "Kinesis_20131202.UpdateStreamMode")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamARN\":\"" + stream.getStreamArn()
                    + "\",\"StreamModeDetails\":{\"StreamMode\":\"ON_DEMAND\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescriptionSummary.StreamStatus", equalTo("UPDATING"));

        stream.setStatusTransitionDeadline(Instant.EPOCH);
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(CONTENT_TYPE)
            .body("{\"StreamName\":\"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.StreamStatus", equalTo("ACTIVE"));
    }

    public static final class WindowProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.kinesis.creating-delay-millis", "60000",
                    "floci.services.kinesis.updating-delay-millis", "60000");
        }
    }
}
