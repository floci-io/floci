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

import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(KinesisStatusWindowIntegrationTest.WindowProfile.class)
class KinesisStatusWindowIntegrationTest {

    private static final String KINESIS_TARGET = "Kinesis_20131202";
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
        RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "CreateStream",
                "{\"StreamName\":\"" + STREAM_NAME + "\",\"ShardCount\":1}")
        .then()
            .statusCode(200);
        cleanup.register(() -> RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "DeleteStream",
                "{\"StreamName\":\"" + STREAM_NAME + "\"}")
                .then().statusCode(200));

        RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "DescribeStream",
                "{\"StreamName\":\"" + STREAM_NAME + "\"}")
        .then()
            .statusCode(200)
            .body("StreamDescription.StreamStatus", equalTo("CREATING"));

        RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "DescribeStreamSummary",
                "{\"StreamName\":\"" + STREAM_NAME + "\"}")
        .then()
            .statusCode(200)
            .body("StreamDescriptionSummary.StreamStatus", equalTo("CREATING"));

        RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "SplitShard",
                "{\"StreamName\":\"" + STREAM_NAME
                    + "\",\"ShardToSplit\":\"shardId-000000000000\","
                    + "\"NewStartingHashKey\":\"170141183460469231731687303715884105728\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    void updateStreamModeReportsUpdatingThenActiveOnTheWire() {
        String name = STREAM_NAME + "-update";
        RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "CreateStream",
                "{\"StreamName\":\"" + name + "\",\"ShardCount\":1}")
        .then()
            .statusCode(200);
        cleanup.register(() -> RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "DeleteStream",
                "{\"StreamName\":\"" + name + "\"}")
                .then().statusCode(200));

        KinesisStream stream = service.describeStream(name, "us-east-1");
        stream.setStatusTransitionDeadline(Instant.EPOCH);
        RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "UpdateStreamMode",
                "{\"StreamARN\":\"" + stream.getStreamArn()
                    + "\",\"StreamModeDetails\":{\"StreamMode\":\"ON_DEMAND\"}}")
        .then()
            .statusCode(200);

        RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "DescribeStreamSummary",
                "{\"StreamName\":\"" + name + "\"}")
        .then()
            .statusCode(200)
            .body("StreamDescriptionSummary.StreamStatus", equalTo("UPDATING"));

        stream.setStatusTransitionDeadline(Instant.EPOCH);
        RestAssuredJsonUtils.awsAction(KINESIS_TARGET, "DescribeStream",
                "{\"StreamName\":\"" + name + "\"}")
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
