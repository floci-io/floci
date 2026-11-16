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
                    assertThat(rows.getString(1)).isEqualTo("logical");
                }
            } finally {
                rds.deleteDBInstance(r -> r.dbInstanceIdentifier(id).skipFinalSnapshot(true));
                rds.deleteDBParameterGroup(r -> r.dbParameterGroupName(group));
            }
        }
    }
}
