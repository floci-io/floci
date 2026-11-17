package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.AccountWithRestoreAccess;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.Snapshot;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sharing a manual Redshift cluster snapshot with another account, through the AWS SDK for Java v2. */
@DisplayName("Redshift snapshot sharing")
class RedshiftSnapshotSharingTest {

    private static final String OWNER = "111111111111";
    private static final String CALLER = "222222222222";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String CLUSTER = "share-c-" + SUFFIX;
    private static final String SNAPSHOT = "share-s-" + SUFFIX;
    private static final String RESTORED = "share-r-" + SUFFIX;

    private static RedshiftClient owner;
    private static RedshiftClient caller;

    @BeforeAll
    static void setUp() {
        String endpoint = System.getenv("FLOCI_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:4566";
        }
        if (!endpoint.startsWith("http")) {
            endpoint = "http://" + endpoint;
        }
        owner = client(endpoint, OWNER);
        caller = client(endpoint, CALLER);
    }

    @AfterAll
    static void cleanup() {
        for (Runnable step : new Runnable[] {
                () -> caller.deleteCluster(r -> r.clusterIdentifier(RESTORED).skipFinalClusterSnapshot(true)),
                () -> owner.deleteClusterSnapshot(r -> r.snapshotIdentifier(SNAPSHOT)),
                () -> owner.deleteCluster(r -> r.clusterIdentifier(CLUSTER).skipFinalClusterSnapshot(true))}) {
            try {
                step.run();
            } catch (Exception ignored) {
                // Best effort: the test may have failed before creating the resource.
            }
        }
        owner.close();
        caller.close();
    }

    private static RedshiftClient client(String endpoint, String account) {
        return RedshiftClient.builder().endpointOverride(URI.create(endpoint)).region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    @Test
    @DisplayName("an authorized account restores the snapshot and a revoked one cannot")
    void authorizedAccountRestoresUntilRevoked() {
        owner.createCluster(r -> r.clusterIdentifier(CLUSTER).nodeType("dc2.large")
                .masterUsername("admin").masterUserPassword("Secret123"));
        Snapshot created = owner.createClusterSnapshot(r -> r.snapshotIdentifier(SNAPSHOT)
                .clusterIdentifier(CLUSTER)).snapshot();
        assertThat(created.ownerAccount()).isEqualTo(OWNER);

        assertThatThrownBy(() -> caller.restoreFromClusterSnapshot(r -> r.clusterIdentifier(RESTORED)
                .snapshotIdentifier(SNAPSHOT).ownerAccount(OWNER)))
                .isInstanceOfSatisfying(RedshiftException.class,
                        e -> assertThat(e.awsErrorDetails().errorCode()).isEqualTo("AccessToSnapshotDenied"));

        Snapshot authorized = owner.authorizeSnapshotAccess(r -> r.snapshotIdentifier(SNAPSHOT)
                .accountWithRestoreAccess(CALLER)).snapshot();
        assertThat(authorized.accountsWithRestoreAccess()).extracting(AccountWithRestoreAccess::accountId)
                .containsExactly(CALLER);
        assertThat(caller.describeClusterSnapshots(r -> r.ownerAccount(OWNER)).snapshots())
                .extracting(Snapshot::snapshotIdentifier).containsExactly(SNAPSHOT);

        caller.restoreFromClusterSnapshot(r -> r.clusterIdentifier(RESTORED)
                .snapshotIdentifier(SNAPSHOT).ownerAccount(OWNER));
        assertThat(caller.describeClusters(r -> r.clusterIdentifier(RESTORED)).clusters()).hasSize(1);

        owner.revokeSnapshotAccess(r -> r.snapshotIdentifier(SNAPSHOT).accountWithRestoreAccess(CALLER));
        assertThat(caller.describeClusterSnapshots(r -> r.ownerAccount(OWNER)).snapshots()).isEmpty();
    }
}
