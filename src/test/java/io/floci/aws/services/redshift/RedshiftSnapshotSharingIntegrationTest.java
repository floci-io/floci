package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.container.RedshiftContainerHandle;
import io.floci.aws.services.redshift.container.RedshiftContainerManager;
import io.floci.aws.services.redshift.proxy.RedshiftProxyManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.AccountWithRestoreAccess;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.Snapshot;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftSnapshotSharingIntegrationTest {
    private static final String OWNER = "111111111111";
    private static final String CALLER = "222222222222";
    private static final String DUMP = "CREATE TABLE shared (id integer); INSERT INTO shared VALUES (7);";

    @InjectMock
    RedshiftContainerManager containerManager;

    @InjectMock
    RedshiftProxyManager proxyManager;

    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void prepareRuntime() {
        when(containerManager.start(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new RedshiftContainerHandle("mock-container", "cluster", "localhost", 5432));
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(3, Path.class), DUMP);
            return null;
        }).when(containerManager).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
    }

    @Test
    void sdkAuthorizedAccountRestoresAnotherAccountSnapshotUntilRevoked() {
        String source = "share-source";
        String snapshotName = "share-snap";
        String restored = "share-restored";
        String refused = "share-refused";
        AtomicReference<String> restoredDump = new AtomicReference<>();
        doAnswer(invocation -> {
            restoredDump.set(Files.readString(invocation.getArgument(3, Path.class)));
            return null;
        }).when(containerManager).restoreSnapshot(eq(CALLER), eq(restored), eq("admin"), any(Path.class));
        try (RedshiftClient owner = client(OWNER); RedshiftClient caller = client(CALLER)) {
            createCluster(owner, source);
            try {
                Snapshot created = owner.createClusterSnapshot(request -> request.snapshotIdentifier(snapshotName)
                        .clusterIdentifier(source)).snapshot();
                assertEquals(OWNER, created.ownerAccount());
                assertTrue(created.accountsWithRestoreAccess().isEmpty());

                // Nothing is shared yet: the caller cannot see the snapshot, and cannot restore it.
                assertTrue(caller.describeClusterSnapshots(request -> request.ownerAccount(OWNER)).snapshots().isEmpty());
                assertEquals("ClusterSnapshotNotFound", assertThrows(RedshiftException.class,
                        () -> caller.describeClusterSnapshots(request -> request.ownerAccount(OWNER)
                                .snapshotIdentifier(snapshotName))).awsErrorDetails().errorCode());
                assertEquals("AccessToSnapshotDenied", assertThrows(RedshiftException.class,
                        () -> caller.restoreFromClusterSnapshot(request -> request.clusterIdentifier(refused)
                                .snapshotIdentifier(snapshotName).ownerAccount(OWNER))).awsErrorDetails().errorCode());

                Snapshot authorized = owner.authorizeSnapshotAccess(request -> request.snapshotIdentifier(snapshotName)
                        .accountWithRestoreAccess(CALLER)).snapshot();
                assertEquals(List.of(CALLER), accounts(authorized));
                assertEquals("AuthorizationAlreadyExists", assertThrows(RedshiftException.class,
                        () -> owner.authorizeSnapshotAccess(request -> request.snapshotIdentifier(snapshotName)
                                .accountWithRestoreAccess(CALLER))).awsErrorDetails().errorCode());
                assertEquals(List.of(CALLER), accounts(owner.describeClusterSnapshots(request -> request
                        .snapshotIdentifier(snapshotName)).snapshots().getFirst()));

                List<Snapshot> shared = caller.describeClusterSnapshots(request -> request.ownerAccount(OWNER)).snapshots();
                assertEquals(1, shared.size());
                assertEquals(OWNER, shared.getFirst().ownerAccount());
                assertEquals(snapshotName, shared.getFirst().snapshotIdentifier());

                caller.restoreFromClusterSnapshot(request -> request.clusterIdentifier(restored)
                        .snapshotIdentifier(snapshotName).ownerAccount(OWNER));
                assertEquals(DUMP, restoredDump.get());
                assertEquals(restored, caller.describeClusters(request -> request.clusterIdentifier(restored))
                        .clusters().getFirst().clusterIdentifier());

                owner.revokeSnapshotAccess(request -> request.snapshotIdentifier(snapshotName)
                        .accountWithRestoreAccess(CALLER));
                assertEquals("AuthorizationNotFound", assertThrows(RedshiftException.class,
                        () -> owner.revokeSnapshotAccess(request -> request.snapshotIdentifier(snapshotName)
                                .accountWithRestoreAccess(CALLER))).awsErrorDetails().errorCode());
                assertEquals("AccessToSnapshotDenied", assertThrows(RedshiftException.class,
                        () -> caller.restoreFromClusterSnapshot(request -> request.clusterIdentifier(refused)
                                .snapshotIdentifier(snapshotName).ownerAccount(OWNER))).awsErrorDetails().errorCode());

                // A snapshot deleted and taken again under the same name does not inherit the grant.
                owner.authorizeSnapshotAccess(request -> request.snapshotIdentifier(snapshotName)
                        .accountWithRestoreAccess(CALLER));
                owner.deleteClusterSnapshot(request -> request.snapshotIdentifier(snapshotName));
                owner.createClusterSnapshot(request -> request.snapshotIdentifier(snapshotName).clusterIdentifier(source));
                assertTrue(caller.describeClusterSnapshots(request -> request.ownerAccount(OWNER)).snapshots().isEmpty());
            } finally {
                deleteClusterIfPresent(owner, source);
                deleteClusterIfPresent(caller, restored);
                deleteSnapshotIfPresent(owner, snapshotName);
            }
        }
    }

    @Test
    void sdkSharedSnapshotIsRestorableByItsArnAndOnlyTheOwnerGrantsAccess() {
        String source = "share-arn-source";
        String snapshotName = "share-arn-snap";
        String restored = "share-arn-restored";
        try (RedshiftClient owner = client(OWNER); RedshiftClient caller = client(CALLER)) {
            createCluster(owner, source);
            try {
                String arn = owner.createClusterSnapshot(request -> request.snapshotIdentifier(snapshotName)
                        .clusterIdentifier(source)).snapshot().snapshotArn();
                owner.authorizeSnapshotAccess(request -> request.snapshotArn(arn).accountWithRestoreAccess(CALLER));

                caller.restoreFromClusterSnapshot(request -> request.clusterIdentifier(restored).snapshotArn(arn));
                assertEquals(restored, caller.describeClusters(request -> request.clusterIdentifier(restored))
                        .clusters().getFirst().clusterIdentifier());

                // A caller cannot grant itself access to a snapshot it does not own.
                assertEquals("ClusterSnapshotNotFound", assertThrows(RedshiftException.class,
                        () -> caller.authorizeSnapshotAccess(request -> request.snapshotArn(arn)
                                .accountWithRestoreAccess(CALLER))).awsErrorDetails().errorCode());
                assertEquals("InvalidParameterValue", assertThrows(RedshiftException.class,
                        () -> owner.authorizeSnapshotAccess(request -> request.snapshotIdentifier(snapshotName)
                                .accountWithRestoreAccess("not-an-account"))).awsErrorDetails().errorCode());
            } finally {
                deleteClusterIfPresent(owner, source);
                deleteClusterIfPresent(caller, restored);
                deleteSnapshotIfPresent(owner, snapshotName);
            }
        }
    }

    private static List<String> accounts(Snapshot snapshot) {
        return snapshot.accountsWithRestoreAccess().stream().map(AccountWithRestoreAccess::accountId).toList();
    }

    private RedshiftClient client(String account) {
        return RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }

    private static void createCluster(RedshiftClient client, String name) {
        client.createCluster(request -> request.clusterIdentifier(name).nodeType("dc2.large")
                .masterUsername("admin").masterUserPassword("Secret123"));
    }

    private static void deleteClusterIfPresent(RedshiftClient client, String name) {
        try {
            client.deleteCluster(request -> request.clusterIdentifier(name).skipFinalClusterSnapshot(true));
        } catch (RedshiftException expected) {
            if (!"ClusterNotFound".equals(expected.awsErrorDetails().errorCode())) {
                throw expected;
            }
        }
    }

    private static void deleteSnapshotIfPresent(RedshiftClient client, String name) {
        try {
            client.deleteClusterSnapshot(request -> request.snapshotIdentifier(name));
        } catch (RedshiftException expected) {
            if (!"ClusterSnapshotNotFound".equals(expected.awsErrorDetails().errorCode())) {
                throw expected;
            }
        }
    }
}
