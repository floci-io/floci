package io.floci.aws.services.elasticache;

import io.floci.aws.services.elasticache.container.ElastiCacheContainerHandle;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.DestinationType;
import software.amazon.awssdk.services.elasticache.model.ElastiCacheException;
import software.amazon.awssdk.services.elasticache.model.LogDeliveryConfiguration;
import software.amazon.awssdk.services.elasticache.model.LogDeliveryConfigurationRequest;
import software.amazon.awssdk.services.elasticache.model.LogDeliveryConfigurationStatus;
import software.amazon.awssdk.services.elasticache.model.LogFormat;
import software.amazon.awssdk.services.elasticache.model.LogType;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroupNotFoundException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** A replication group reports the log delivery configurations it was created with. */
@QuarkusTest
class ElastiCacheLogDeliveryIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @InjectMock
    ElastiCacheContainerManager containerManager;

    @BeforeEach
    void stubBackend() {
        when(containerManager.tryStart(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                new ElastiCacheContainerHandle("stub-" + invocation.getArgument(0), invocation.getArgument(0),
                        "127.0.0.1", 16379));
    }

    @Test
    void createAndDescribeReportTheRequestedLogDeliveryConfigurations() {
        String groupId = "log-delivery-" + UUID.randomUUID().toString().substring(0, 8);
        try (ElastiCacheClient client = client()) {
            List<LogDeliveryConfiguration> created = client.createReplicationGroup(r -> r
                    .replicationGroupId(groupId).replicationGroupDescription("log delivery").engine("redis")
                    .logDeliveryConfigurations(
                            c -> c.logType(LogType.SLOW_LOG).destinationType(DestinationType.CLOUDWATCH_LOGS)
                                    .destinationDetails(d -> d.cloudWatchLogsDetails(l -> l.logGroup("/aws/ec/slow")))
                                    .logFormat(LogFormat.JSON).enabled(true),
                            c -> c.logType(LogType.ENGINE_LOG).destinationType(DestinationType.KINESIS_FIREHOSE)
                                    .destinationDetails(d -> d.kinesisFirehoseDetails(k -> k.deliveryStream("engine")))
                                    .logFormat(LogFormat.TEXT).enabled(true)))
                    .replicationGroup().logDeliveryConfigurations();

            List<LogDeliveryConfiguration> expected = List.of(
                    LogDeliveryConfiguration.builder().logType(LogType.SLOW_LOG)
                            .destinationType(DestinationType.CLOUDWATCH_LOGS)
                            .destinationDetails(d -> d.cloudWatchLogsDetails(l -> l.logGroup("/aws/ec/slow")))
                            .logFormat(LogFormat.JSON).status(LogDeliveryConfigurationStatus.ACTIVE).build(),
                    LogDeliveryConfiguration.builder().logType(LogType.ENGINE_LOG)
                            .destinationType(DestinationType.KINESIS_FIREHOSE)
                            .destinationDetails(d -> d.kinesisFirehoseDetails(k -> k.deliveryStream("engine")))
                            .logFormat(LogFormat.TEXT).status(LogDeliveryConfigurationStatus.ACTIVE).build());
            assertEquals(expected, created);
            assertEquals(expected, client.describeReplicationGroups(r -> r.replicationGroupId(groupId))
                    .replicationGroups().getFirst().logDeliveryConfigurations());
            client.deleteReplicationGroup(r -> r.replicationGroupId(groupId));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"logType=audit-log", "destinationType=s3", "logFormat=xml", "logType="})
    void aLogDeliveryConfigurationWithAnUnknownOrMissingValueIsRefused(String override) {
        String groupId = "log-refused-" + UUID.randomUUID().toString().substring(0, 8);
        String[] field = override.split("=", -1);
        String value = field[1].isEmpty() ? null : field[1];
        LogDeliveryConfigurationRequest.Builder configuration = LogDeliveryConfigurationRequest.builder()
                .logType(LogType.SLOW_LOG).destinationType(DestinationType.CLOUDWATCH_LOGS)
                .destinationDetails(d -> d.cloudWatchLogsDetails(l -> l.logGroup("/aws/ec/slow")))
                .logFormat(LogFormat.JSON).enabled(true);
        switch (field[0]) {
            case "logType" -> configuration.logType(value);
            case "destinationType" -> configuration.destinationType(value);
            default -> configuration.logFormat(value);
        }
        try (ElastiCacheClient client = client()) {
            ElastiCacheException refused = assertThrows(ElastiCacheException.class, () -> client
                    .createReplicationGroup(r -> r.replicationGroupId(groupId).replicationGroupDescription("refused")
                            .engine("redis").logDeliveryConfigurations(configuration.build())));

            assertEquals("InvalidParameterValue", refused.awsErrorDetails().errorCode());
            assertThrows(ReplicationGroupNotFoundException.class,
                    () -> client.describeReplicationGroups(r -> r.replicationGroupId(groupId)));
        }
    }

    private ElastiCacheClient client() {
        return ElastiCacheClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
