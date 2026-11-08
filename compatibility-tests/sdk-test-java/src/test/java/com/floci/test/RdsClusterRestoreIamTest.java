package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cluster restore IAM authentication defaults")
class RdsClusterRestoreIamTest {
    private static final Logger LOG = Logger.getLogger(RdsClusterRestoreIamTest.class.getName());

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "point"})
    @DisplayName("Omitted IAM authentication is disabled and explicit choices are preserved")
    void restoredIamDefaultsToDisabled(String path) {
        String source = TestFixtures.uniqueName("restore-iam");
        String snapshot = source + "-snap";
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                rds.createDBCluster(b -> b.dbClusterIdentifier(source).engine("aurora-postgresql")
                        .masterUsername("admin").masterUserPassword("Password123!")
                        .enableIAMDatabaseAuthentication(true));
                rds.createDBClusterSnapshot(b -> b.dbClusterIdentifier(source).dbClusterSnapshotIdentifier(snapshot));
                for (Boolean flag : new Boolean[]{null, false, true}) {
                    String target = source + "-" + (flag == null ? "omitted" : flag.toString());
                    try {
                        DBCluster restored;
                        if ("snapshot".equals(path)) {
                            restored = rds.restoreDBClusterFromSnapshot(b -> {
                                b.dbClusterIdentifier(target).snapshotIdentifier(snapshot).engine("aurora-postgresql");
                                if (flag != null) {
                                    b.enableIAMDatabaseAuthentication(flag);
                                }
                            }).dbCluster();
                        } else {
                            restored = rds.restoreDBClusterToPointInTime(b -> {
                                b.dbClusterIdentifier(target).sourceDBClusterIdentifier(source)
                                        .useLatestRestorableTime(true);
                                if (flag != null) {
                                    b.enableIAMDatabaseAuthentication(flag);
                                }
                            }).dbCluster();
                        }
                        boolean expected = Boolean.TRUE.equals(flag);
                        assertThat(restored.iamDatabaseAuthenticationEnabled()).isEqualTo(expected);
                        assertThat(rds.describeDBClusters(b -> b.dbClusterIdentifier(target)).dbClusters()
                                .get(0).iamDatabaseAuthenticationEnabled()).isEqualTo(expected);
                        assertThat(rds.describeDBClusters(b -> b.dbClusterIdentifier(source)).dbClusters()
                                .get(0).iamDatabaseAuthenticationEnabled()).isTrue();
                    } finally {
                        deleteCluster(rds, target);
                    }
                }
            } finally {
                try {
                    rds.deleteDBClusterSnapshot(b -> b.dbClusterSnapshotIdentifier(snapshot));
                } catch (RdsException e) {
                    LOG.log(Level.WARNING, "Could not clean up snapshot " + snapshot, e);
                }
                deleteCluster(rds, source);
            }
        }
    }

    private static void deleteCluster(RdsClient rds, String identifier) {
        try {
            rds.deleteDBCluster(b -> b.dbClusterIdentifier(identifier).skipFinalSnapshot(true));
        } catch (RdsException e) {
            LOG.log(Level.WARNING, "Could not clean up cluster " + identifier, e);
        }
    }
}
