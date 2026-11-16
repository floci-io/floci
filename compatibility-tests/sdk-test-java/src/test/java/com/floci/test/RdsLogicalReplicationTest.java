package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.ApplyMethod;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RDS PostgreSQL logical replication")
class RdsLogicalReplicationTest {

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "secret123";

    @Test
    @DisplayName("rds.logical_replication=1 in the DB parameter group starts PostgreSQL with wal_level=logical")
    void logicalReplicationParameterSetsLogicalWalLevel() throws Exception {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Disposable emulator database fixtures");
        String group = TestFixtures.uniqueName("rds-logical-pg");
        String id = TestFixtures.uniqueName("rds-logical");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            rds.createDBParameterGroup(r -> r.dbParameterGroupName(group)
                    .dbParameterGroupFamily("postgres16").description("logical replication"));
            try {
                rds.modifyDBParameterGroup(r -> r.dbParameterGroupName(group).parameters(p -> p
                        .parameterName("rds.logical_replication").parameterValue("1")
                        .applyMethod(ApplyMethod.PENDING_REBOOT)));
                int port = rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres")
                        .dbInstanceClass("db.t3.micro").allocatedStorage(20).dbParameterGroupName(group)
                        .masterUsername(USERNAME).masterUserPassword(PASSWORD))
                        .dbInstance().endpoint().port();

                assertThat(walLevel(port)).isEqualTo("logical");
            } finally {
                rds.deleteDBInstance(r -> r.dbInstanceIdentifier(id).skipFinalSnapshot(true));
                rds.deleteDBParameterGroup(r -> r.dbParameterGroupName(group));
            }
        }
    }

    @Test
    @DisplayName("rds.logical_replication=1 in the DB cluster parameter group applies to the Aurora PostgreSQL writer on reboot")
    void clusterLogicalReplicationParameterAppliesOnWriterReboot() throws Exception {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Disposable emulator database fixtures");
        String group = TestFixtures.uniqueName("rds-logical-cpg");
        String clusterId = TestFixtures.uniqueName("rds-logical-cluster");
        String writerId = clusterId + "-writer";
        try (RdsClient rds = TestFixtures.rdsClient()) {
            rds.createDBClusterParameterGroup(r -> r.dbClusterParameterGroupName(group)
                    .dbParameterGroupFamily("aurora-postgresql16").description("logical replication"));
            try {
                int port = rds.createDBCluster(r -> r.dbClusterIdentifier(clusterId).engine("aurora-postgresql")
                        .dbClusterParameterGroupName(group)
                        .masterUsername(USERNAME).masterUserPassword(PASSWORD))
                        .dbCluster().port();
                rds.createDBInstance(r -> r.dbInstanceIdentifier(writerId).dbClusterIdentifier(clusterId)
                        .engine("aurora-postgresql").dbInstanceClass("db.r6g.large"));
                assertThat(walLevel(port)).isEqualTo("replica");

                rds.modifyDBClusterParameterGroup(r -> r.dbClusterParameterGroupName(group).parameters(p -> p
                        .parameterName("rds.logical_replication").parameterValue("1")
                        .applyMethod(ApplyMethod.PENDING_REBOOT)));
                assertThat(walLevel(port)).isEqualTo("replica");

                rds.rebootDBInstance(r -> r.dbInstanceIdentifier(writerId));
                assertThat(walLevel(port)).isEqualTo("logical");
            } finally {
                try {
                    rds.deleteDBInstance(r -> r.dbInstanceIdentifier(writerId).skipFinalSnapshot(true));
                } finally {
                    rds.deleteDBCluster(r -> r.dbClusterIdentifier(clusterId).skipFinalSnapshot(true));
                    rds.deleteDBClusterParameterGroup(r -> r.dbClusterParameterGroupName(group));
                }
            }
        }
    }

    private static String walLevel(int port) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", USERNAME);
        properties.setProperty("password", PASSWORD);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "5");
        try (Connection connection = TestFixtures.awaitJdbcConnection(
                     "jdbc:postgresql://" + TestFixtures.proxyHost() + ":" + port + "/postgres", properties);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SHOW wal_level")) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
