package io.floci.aws.services.dynamodb;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.kinesis.KinesisClient;

import java.util.UUID;

import static io.floci.aws.testing.DynamoDbTableFixtures.createKeyedTable;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbKinesisUpdateIamIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void permissionIsForTheUpdateActionAndRequestedTable() {
        String account = "000000000000";
        String allowed = "kinesis-update-iam-" + UUID.randomUUID();
        String denied = allowed + "-denied";
        DynamoDbClient root = AwsTestClients.create(DynamoDbClient.builder(), account, Region.US_EAST_1);
        cleanup.register(root::close);
        KinesisClient kinesis = AwsTestClients.create(KinesisClient.builder(), account, Region.US_EAST_1);
        cleanup.register(kinesis::close);
        kinesis.createStream(b -> b.streamName(allowed).shardCount(1));
        cleanup.register(() -> kinesis.deleteStream(b -> b.streamName(allowed)));
        String streamArn = kinesis.describeStreamSummary(b -> b.streamName(allowed))
                .streamDescriptionSummary().streamARN();
        String allowedArn = createKeyedTable(root, allowed);
        cleanup.register(() -> root.deleteTable(b -> b.tableName(allowed)));
        String deniedArn = createKeyedTable(root, denied);
        cleanup.register(() -> root.deleteTable(b -> b.tableName(denied)));
        root.enableKinesisStreamingDestination(b -> b.tableName(allowed).streamArn(streamArn));
        root.enableKinesisStreamingDestination(b -> b.tableName(denied).streamArn(streamArn));
        String name = "kinesis-updater-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(account, "iam"), name);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), name, "update-one", """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "dynamodb:UpdateKinesisStreamingDestination",
                      "Resource": "%s"
                    }
                  ]
                }
                """.formatted(allowedArn));
        try (DynamoDbClient user = AwsTestClients.create(DynamoDbClient.builder(),
                credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
            assertEquals("MICROSECOND", user.updateKinesisStreamingDestination(
                    b -> b.tableName(allowedArn).streamArn(streamArn)
                            .updateKinesisStreamingConfiguration(c -> c
                                    .approximateCreationDateTimePrecision("MICROSECOND")))
                    .updateKinesisStreamingConfiguration().approximateCreationDateTimePrecisionAsString());
            DynamoDbException error = assertThrows(DynamoDbException.class,
                    () -> user.updateKinesisStreamingDestination(b -> b.tableName(deniedArn).streamArn(streamArn)));
            assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
            DynamoDbException wrongAction = assertThrows(DynamoDbException.class,
                    () -> user.disableKinesisStreamingDestination(b -> b.tableName(allowed).streamArn(streamArn)));
            assertEquals("AccessDeniedException", wrongAction.awsErrorDetails().errorCode());
        }
        assertEquals("MILLISECOND", root.describeKinesisStreamingDestination(b -> b.tableName(denied))
                .kinesisDataStreamDestinations().getFirst().approximateCreationDateTimePrecisionAsString());
        assertEquals("ACTIVE", root.describeKinesisStreamingDestination(b -> b.tableName(allowed))
                .kinesisDataStreamDestinations().getFirst().destinationStatusAsString());
    }
}
