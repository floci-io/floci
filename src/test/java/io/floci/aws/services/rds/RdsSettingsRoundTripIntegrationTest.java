package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsSettingsRoundTripIntegrationTest {
    @TestHTTPResource
    URI endpoint;
    private RdsClient client;
    private String cluster;
    private String member;

    @BeforeEach
    void setup() {
        client = RdsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        cluster = "settings-" + UUID.randomUUID();
        member = cluster + "-member";
    }

    @AfterEach
    void cleanup() {
        try {
            client.deleteDBInstance(r -> r.dbInstanceIdentifier(member).skipFinalSnapshot(true));
        } catch (RdsException ignored) {
            // Some cases intentionally reject creation before a resource exists.
        }
        try {
            client.deleteDBCluster(r -> r.dbClusterIdentifier(cluster).skipFinalSnapshot(true));
        } catch (RdsException ignored) {
            // A create rejected before mutation leaves no cluster to delete.
        }
        client.close();
    }

    private DBCluster createCluster(boolean configured) {
        return client.createDBCluster(r -> {
            r.dbClusterIdentifier(cluster).engine("aurora-postgresql").engineVersion("16.3")
                    .masterUsername("admin").masterUserPassword(UUID.randomUUID().toString());
            if (configured) {
                r.copyTagsToSnapshot(true).enableCloudwatchLogsExports("postgresql");
            }
        }).dbCluster();
    }

    private DBCluster describeCluster() {
        return client.describeDBClusters(r -> r.dbClusterIdentifier(cluster)).dbClusters().getFirst();
    }

    @Test
    void clusterSnapshotAndLogSettingsRoundTripThroughCreateAndDescribe() {
        DBCluster created = createCluster(true);
        DBCluster described = describeCluster();
        assertAll(() -> assertEquals(true, created.copyTagsToSnapshot()),
                () -> assertEquals(List.of("postgresql"), created.enabledCloudwatchLogsExports()),
                () -> assertEquals(true, described.copyTagsToSnapshot()),
                () -> assertEquals(List.of("postgresql"), described.enabledCloudwatchLogsExports()));
    }

    @Test
    void clusterModifyPreservesOmittedSettingsAndAppliesFalseAndLogDeltas() {
        createCluster(true);
        DBCluster unchanged = client.modifyDBCluster(r -> r.dbClusterIdentifier(cluster).applyImmediately(true)).dbCluster();
        assertEquals(true, unchanged.copyTagsToSnapshot());
        assertEquals(List.of("postgresql"), unchanged.enabledCloudwatchLogsExports());
        DBCluster changed = client.modifyDBCluster(r -> r.dbClusterIdentifier(cluster).copyTagsToSnapshot(false)
                .cloudwatchLogsExportConfiguration(c -> c.enableLogTypes("iam-db-auth-error")
                        .disableLogTypes("postgresql")).applyImmediately(true)).dbCluster();
        assertEquals(false, changed.copyTagsToSnapshot());
        assertEquals(List.of("iam-db-auth-error"), changed.enabledCloudwatchLogsExports());
        client.modifyDBCluster(r -> r.dbClusterIdentifier(cluster)
                .cloudwatchLogsExportConfiguration(c -> c.disableLogTypes("iam-db-auth-error")));
        assertFalse(describeCluster().copyTagsToSnapshot());
        assertTrue(describeCluster().enabledCloudwatchLogsExports().isEmpty());
    }

    @Test
    void clusterDefaultsAndEnableOnlyChangesPreserveExistingExports() {
        DBCluster created = createCluster(false);
        assertFalse(created.copyTagsToSnapshot());
        assertTrue(created.enabledCloudwatchLogsExports().isEmpty());
        client.modifyDBCluster(r -> r.dbClusterIdentifier(cluster).copyTagsToSnapshot(true)
                .cloudwatchLogsExportConfiguration(c -> c.enableLogTypes("postgresql")));
        DBCluster changed = client.modifyDBCluster(r -> r.dbClusterIdentifier(cluster)
                .cloudwatchLogsExportConfiguration(c -> c.enableLogTypes("iam-db-auth-error"))).dbCluster();
        assertTrue(changed.copyTagsToSnapshot());
        assertEquals(List.of("postgresql", "iam-db-auth-error"), changed.enabledCloudwatchLogsExports());
        assertEquals(changed.enabledCloudwatchLogsExports(), describeCluster().enabledCloudwatchLogsExports());
    }

    @Test
    void memberPromotionTierRoundTripsAndTracksExplicitZero() {
        createCluster(false);
        DBInstance created = client.createDBInstance(r -> r.dbInstanceIdentifier(member).dbClusterIdentifier(cluster)
                .engine("aurora-postgresql").dbInstanceClass("db.r6g.large").promotionTier(7)).dbInstance();
        assertEquals(7, created.promotionTier());
        assertEquals(7, describeCluster().dbClusterMembers().getFirst().promotionTier());
        assertEquals(7, client.modifyDBInstance(r -> r.dbInstanceIdentifier(member)
                .autoMinorVersionUpgrade(false)).dbInstance().promotionTier());
        assertEquals(0, client.modifyDBInstance(r -> r.dbInstanceIdentifier(member).promotionTier(0))
                .dbInstance().promotionTier());
        assertEquals(0, client.describeDBInstances(r -> r.dbInstanceIdentifier(member))
                .dbInstances().getFirst().promotionTier());
        assertEquals(0, describeCluster().dbClusterMembers().getFirst().promotionTier());
        assertEquals(15, client.modifyDBInstance(r -> r.dbInstanceIdentifier(member).promotionTier(15))
                .dbInstance().promotionTier());
        assertEquals(15, describeCluster().dbClusterMembers().getFirst().promotionTier());
    }

    @Test
    void promotionTierDefaultsToOneAndInvalidChangesAreAtomic() {
        createCluster(false);
        for (int tier : List.of(-1, 16)) {
            RdsException error = assertThrows(RdsException.class, () -> client.createDBInstance(r ->
                    r.dbInstanceIdentifier(member).dbClusterIdentifier(cluster).engine("aurora-postgresql")
                            .dbInstanceClass("db.r6g.large").promotionTier(tier)));
            assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
            assertTrue(describeCluster().dbClusterMembers().isEmpty());
        }
        assertEquals(1, client.createDBInstance(r -> r.dbInstanceIdentifier(member).dbClusterIdentifier(cluster)
                .engine("aurora-postgresql").dbInstanceClass("db.r6g.large")).dbInstance().promotionTier());
        assertThrows(RdsException.class, () -> client.modifyDBInstance(r -> r.dbInstanceIdentifier(member)
                .promotionTier(16).autoMinorVersionUpgrade(false)));
        DBInstance unchanged = client.describeDBInstances(r -> r.dbInstanceIdentifier(member)).dbInstances().getFirst();
        assertEquals(1, unchanged.promotionTier());
        assertTrue(unchanged.autoMinorVersionUpgrade());
    }
}
