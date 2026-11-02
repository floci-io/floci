package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshiftserverless.model.Snapshot;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftServerlessSnapshotCopyIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    /** The copy needs no PostgreSQL container: the snapshots here are metadata only. */
    @InjectMock
    RedshiftServerlessRuntime runtime;

    @Inject
    RedshiftServerlessSnapshotCopyScheduler scheduler;

    @BeforeEach
    void stubRuntime() {
        when(runtime.generatePassword()).thenReturn("Generated123");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
    }

    private RedshiftServerlessClient client(Region region) {
        return RedshiftServerlessClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The application is shared with other tests, so what a test made is removed, not left for later sweeps. */
    private static void cleanUp(RedshiftServerlessClient east, RedshiftServerlessClient west, String namespace,
                                String snapshotName, String restoreNamespace, String restoreWorkgroup) {
        removeIfPresent(() -> west.deleteWorkgroup(request -> request.workgroupName(restoreWorkgroup)));
        removeIfPresent(() -> west.deleteNamespace(request -> request.namespaceName(restoreNamespace)));
        removeIfPresent(() -> west.deleteSnapshot(request -> request.snapshotName(snapshotName)));
        removeIfPresent(() -> east.deleteSnapshot(request -> request.snapshotName(snapshotName)));
        removeIfPresent(() -> east.listSnapshotCopyConfigurations(request -> request.namespaceName(namespace))
                .snapshotCopyConfigurations().forEach(configuration -> east.deleteSnapshotCopyConfiguration(
                        request -> request.snapshotCopyConfigurationId(configuration.snapshotCopyConfigurationId()))));
        removeIfPresent(() -> east.deleteNamespace(request -> request.namespaceName(namespace)));
    }

    private static void removeIfPresent(Runnable removal) {
        try {
            removal.run();
        } catch (ResourceNotFoundException alreadyGone) {
            // The test itself deleted it, or never got far enough to create it.
        }
    }

    @Test
    void copiedSnapshotIsVisibleAndRestorableInTheDestinationRegion() {
        String namespace = unique("copy-src");
        String snapshotName = unique("copy-snap");
        String restoreNamespace = unique("copy-dst");
        String restoreWorkgroup = unique("copy-wg");
        try (RedshiftServerlessClient east = client(Region.US_EAST_1);
             RedshiftServerlessClient west = client(Region.US_WEST_2)) {
            try {
                east.createNamespace(request -> request.namespaceName(namespace).adminUsername("admin")
                        .adminUserPassword("Password123!"));
                east.createSnapshotCopyConfiguration(request -> request.namespaceName(namespace)
                        .destinationRegion("us-west-2").snapshotRetentionPeriod(14));
                Snapshot source = east.createSnapshot(request -> request.namespaceName(namespace)
                        .snapshotName(snapshotName)).snapshot();

                scheduler.tick(Instant.now());

                Snapshot copy = west.getSnapshot(request -> request.snapshotName(snapshotName)).snapshot();
                assertEquals("AVAILABLE", copy.statusAsString());
                assertTrue(copy.snapshotArn().contains(":us-west-2:"), copy.snapshotArn());
                assertEquals(source.ownerAccount(), copy.ownerAccount());
                assertEquals(14, copy.snapshotRetentionPeriod());
                assertTrue(west.listSnapshots(request -> request.namespaceName(namespace)).snapshots().stream()
                        .anyMatch(snapshot -> snapshotName.equals(snapshot.snapshotName())));
                assertThrows(ResourceNotFoundException.class,
                        () -> east.getSnapshot(request -> request.snapshotName(snapshotName + "-missing")));

                west.createNamespace(request -> request.namespaceName(restoreNamespace).adminUsername("admin")
                        .adminUserPassword("Password123!"));
                west.createWorkgroup(request -> request.workgroupName(restoreWorkgroup)
                        .namespaceName(restoreNamespace));
                assertEquals("AVAILABLE", west.restoreFromSnapshot(request -> request
                        .namespaceName(restoreNamespace).workgroupName(restoreWorkgroup)
                        .snapshotName(snapshotName)).namespace().statusAsString());
            } finally {
                cleanUp(east, west, namespace, snapshotName, restoreNamespace, restoreWorkgroup);
            }
        }
    }

    @Test
    void deletingTheCopyKeepsTheSourceAndIsNotUndoneBySweeps() {
        String namespace = unique("copy-del");
        String snapshotName = unique("copy-snap");
        try (RedshiftServerlessClient east = client(Region.US_EAST_1);
             RedshiftServerlessClient west = client(Region.US_WEST_2)) {
            try {
                east.createNamespace(request -> request.namespaceName(namespace).adminUsername("admin")
                        .adminUserPassword("Password123!"));
                east.createSnapshotCopyConfiguration(request -> request.namespaceName(namespace)
                        .destinationRegion("us-west-2"));
                east.createSnapshot(request -> request.namespaceName(namespace).snapshotName(snapshotName));
                scheduler.tick(Instant.now());
                west.deleteSnapshot(request -> request.snapshotName(snapshotName));

                scheduler.tick(Instant.now().plusSeconds(60));

                assertThrows(ResourceNotFoundException.class,
                        () -> west.getSnapshot(request -> request.snapshotName(snapshotName)));
                assertEquals("AVAILABLE", east.getSnapshot(request -> request.snapshotName(snapshotName))
                        .snapshot().statusAsString());
            } finally {
                cleanUp(east, west, namespace, snapshotName, unique("none"), unique("none"));
            }
        }
    }

    @Test
    void deletingTheConfigurationStopsFurtherCopies() {
        String namespace = unique("copy-off");
        String snapshotName = unique("copy-snap");
        try (RedshiftServerlessClient east = client(Region.US_EAST_1);
             RedshiftServerlessClient west = client(Region.US_WEST_2)) {
            try {
                east.createNamespace(request -> request.namespaceName(namespace).adminUsername("admin")
                        .adminUserPassword("Password123!"));
                String configurationId = east.createSnapshotCopyConfiguration(request -> request
                        .namespaceName(namespace).destinationRegion("us-west-2"))
                        .snapshotCopyConfiguration().snapshotCopyConfigurationId();
                east.deleteSnapshotCopyConfiguration(request -> request
                        .snapshotCopyConfigurationId(configurationId));
                east.createSnapshot(request -> request.namespaceName(namespace).snapshotName(snapshotName));

                scheduler.tick(Instant.now());

                assertThrows(ResourceNotFoundException.class,
                        () -> west.getSnapshot(request -> request.snapshotName(snapshotName)));
            } finally {
                cleanUp(east, west, namespace, snapshotName, unique("none"), unique("none"));
            }
        }
    }
}
