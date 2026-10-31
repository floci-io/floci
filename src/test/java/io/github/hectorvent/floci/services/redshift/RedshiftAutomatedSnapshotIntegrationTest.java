package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.Snapshot;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class RedshiftAutomatedSnapshotIntegrationTest {
    private static final String ACCOUNT = "333333333333";

    @InjectMock
    RedshiftContainerManager manager;
    @InjectMock
    RedshiftProxyManager proxy;
    @Inject
    RedshiftSnapshotScheduler scheduler;
    @Inject
    StorageFactory factory;
    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void setup() {
        when(manager.start(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new RedshiftContainerHandle("mock", "warehouse", "localhost", 5432));
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(3, Path.class), "backup contents");
            return null;
        }).when(manager).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
    }

    @Test
    void sdkReportsAutomatedSnapshotsAndControlsTheirRetention() {
        String clusterId = "sdk-automated-warehouse";
        String scheduleId = "sdk-automated-hourly";
        try (RedshiftClient client = client()) {
            client.createSnapshotSchedule(request -> request.scheduleIdentifier(scheduleId).scheduleDefinitions("rate(1 hour)"));
            client.createCluster(request -> request.clusterIdentifier(clusterId).nodeType("dc2.large")
                    .masterUsername("admin").masterUserPassword("Secret123").automatedSnapshotRetentionPeriod(1));
            try {
                client.modifyClusterSnapshotSchedule(request -> request.clusterIdentifier(clusterId).scheduleIdentifier(scheduleId));
                AccountAwareStorageBackend<Cluster> clusters = factory.create("redshift", "redshift-clusters.json",
                        new TypeReference<Map<String, Cluster>>() { });
                Instant due = clusters.getForAccount(ACCOUNT, clusterId).orElseThrow().getAutomatedSnapshotAnchor().plusSeconds(3600);
                scheduler.tick(due);
                List<Snapshot> automated = client.describeClusterSnapshots(request ->
                        request.clusterIdentifier(clusterId).snapshotType("automated")).snapshots();
                assertEquals(1, automated.size());
                Snapshot snapshot = automated.getFirst();
                assertEquals("automated", snapshot.snapshotType());
                assertEquals(due, snapshot.snapshotCreateTime());
                assertTrue(snapshot.snapshotArn().contains(":" + ACCOUNT + ":snapshot:"));
                assertTrue(client.describeClusterSnapshots(request ->
                        request.clusterIdentifier(clusterId).snapshotType("manual")).snapshots().isEmpty());
                RedshiftException refused = assertThrows(RedshiftException.class, () ->
                        client.deleteClusterSnapshot(request -> request.snapshotIdentifier(snapshot.snapshotIdentifier())));
                assertEquals("InvalidClusterSnapshotState", refused.awsErrorDetails().errorCode());
                client.createClusterSnapshot(request -> request.clusterIdentifier(clusterId).snapshotIdentifier("sdk-manual-keep"));
                assertEquals(0, client.modifyCluster(request -> request.clusterIdentifier(clusterId)
                        .automatedSnapshotRetentionPeriod(0)).cluster().automatedSnapshotRetentionPeriod());
                scheduler.tick(due.plusSeconds(60));
                assertTrue(client.describeClusterSnapshots(request ->
                        request.clusterIdentifier(clusterId).snapshotType("automated")).snapshots().isEmpty());
                assertEquals("manual", client.describeClusterSnapshots(request -> request.snapshotIdentifier("sdk-manual-keep"))
                        .snapshots().getFirst().snapshotType());
                client.deleteClusterSnapshot(request -> request.snapshotIdentifier("sdk-manual-keep"));
            } finally {
                client.deleteCluster(request -> request.clusterIdentifier(clusterId).skipFinalClusterSnapshot(true));
                client.deleteSnapshotSchedule(request -> request.scheduleIdentifier(scheduleId));
                scheduler.tick(Instant.now().plusSeconds(7200));
            }
        }
    }

    @Test
    void sdkRejectsInvalidRetentionAndTooFrequentCron() {
        try (RedshiftClient client = client()) {
            RedshiftException retention = assertThrows(RedshiftException.class, () -> client.createCluster(request ->
                    request.clusterIdentifier("invalid-auto-retention").nodeType("ra3.xlplus")
                            .masterUsername("admin").masterUserPassword("Secret123").automatedSnapshotRetentionPeriod(0)));
            assertEquals("InvalidParameterValue", retention.awsErrorDetails().errorCode());
            RedshiftException cron = assertThrows(RedshiftException.class, () -> client.createSnapshotSchedule(request ->
                    request.scheduleIdentifier("invalid-auto-cron").scheduleDefinitions("cron(* * *)")));
            assertEquals("InvalidSchedule", cron.awsErrorDetails().errorCode());
            verify(manager, never()).start(anyString(), anyString(), anyString(), anyString(), any(), any());
        }
    }

    private RedshiftClient client() {
        return RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"))).build();
    }
}
