package io.floci.aws.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static io.floci.aws.testing.RdsRequests.deleteInstance;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * A parameter group's overrides are the engine's configuration: an Aurora MySQL cluster runs with
 * its DB cluster parameter group with its writer's DB parameter group on top, a standalone MySQL
 * instance with its DB parameter group, and a value changed in the group, or a group switched by
 * ModifyDBCluster or ModifyDBInstance, takes effect when the writer or the instance is rebooted. A
 * value the server refuses leaves the instance incompatible-parameters. A PostgreSQL instance or an
 * Aurora PostgreSQL writer whose groups set {@code rds.logical_replication} to 1 runs with
 * {@code wal_level=logical}, from the same reboot. Skipped without Docker.
 */
@QuarkusTest
@Tag("docker")
class RdsParameterGroupEngineDockerNightlyIntegrationTest {

    private static final String MASTER_USER = "admin";
    private static final String MASTER_PASSWORD = "Passw0rd12345";

    @BeforeEach
    void checkDocker() throws InterruptedException {
        assumeDocker();
    }

    @Test
    void auroraMysqlClusterRunsWithItsClusterParameterGroup() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "engine-cpg-" + suffix;
        String clusterId = "engine-cpg-cluster-" + suffix;
        String writerId = "engine-cpg-writer-" + suffix;
        createClusterParameterGroup(group, "aurora-mysql8.0");
        try {
            rds("ModifyDBClusterParameterGroup")
                    .formParam("DBClusterParameterGroupName", group)
                    .formParam("Parameters.member.1.ParameterName", "max_connections")
                    .formParam("Parameters.member.1.ParameterValue", "500")
                    .formParam("Parameters.member.1.ApplyMethod", "immediate")
                    .formParam("Parameters.member.2.ParameterName", "slow_query_log")
                    .formParam("Parameters.member.2.ParameterValue", "1")
                    .formParam("Parameters.member.2.ApplyMethod", "immediate")
                    .formParam("Parameters.member.3.ParameterName", "long_query_time")
                    .formParam("Parameters.member.3.ParameterValue", "1")
                    .formParam("Parameters.member.3.ApplyMethod", "immediate")
                    // Exists only on Aurora: the engine must still start.
                    .formParam("Parameters.member.4.ParameterName", "aurora_binlog_replication_max_yield_seconds")
                    .formParam("Parameters.member.4.ParameterValue", "0")
                    .formParam("Parameters.member.4.ApplyMethod", "immediate")
                    // A formula is resolved by AWS from the instance class, so it is left out.
                    .formParam("Parameters.member.5.ParameterName", "innodb_buffer_pool_size")
                    .formParam("Parameters.member.5.ParameterValue", "{DBInstanceClassMemory*3/4}")
                    .formParam("Parameters.member.5.ApplyMethod", "pending-reboot")
                    // Aurora's way of turning binary logging off, which MySQL itself does not accept.
                    .formParam("Parameters.member.6.ParameterName", "binlog_format")
                    .formParam("Parameters.member.6.ParameterValue", "OFF")
                    .formParam("Parameters.member.6.ApplyMethod", "pending-reboot")
            .when().post("/").then().statusCode(200);

            XmlPath created = rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-mysql")
                    .formParam("DBClusterParameterGroupName", group)
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
            .when().post("/").then().statusCode(200).extract().xmlPath();
            int port = created.getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");

            assertThat(query(port, "SELECT @@GLOBAL.max_connections, @@GLOBAL.slow_query_log, "
                    + "@@GLOBAL.long_query_time, @@GLOBAL.log_bin"), equalTo("500 1 1.0 0"));

            String reported = rds("DescribeDBClusters")
                    .formParam("DBClusterIdentifier", clusterId)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters"
                            + ".DBCluster.DBClusterParameterGroup");
            assertThat(reported, equalTo(group));

            createWriter(writerId, clusterId, "aurora-mysql", null);
            modifyClusterParameterGroup(group, "max_connections", "400", "pending-reboot");
            rebootInstance(writerId);

            assertThat(query(port, "SELECT @@GLOBAL.max_connections"), equalTo("400"));
        } finally {
            deleteInstance(writerId);
            rds("DeleteDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds("DeleteDBClusterParameterGroup")
                    .formParam("DBClusterParameterGroupName", group)
            .when().post("/");
        }
    }

    @Test
    void auroraMysqlWriterRunsWithItsDbParameterGroupOverTheClusterGroup() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterGroup = "engine-wcpg-" + suffix;
        String switchedClusterGroup = "engine-wcpg2-" + suffix;
        String writerGroup = "engine-wpg-" + suffix;
        String clusterId = "engine-w-cluster-" + suffix;
        String writerId = "engine-w-writer-" + suffix;
        createClusterParameterGroup(clusterGroup, "max_connections", "500", "slow_query_log", "1");
        createClusterParameterGroup(switchedClusterGroup, "max_connections", "450", "slow_query_log", "0");
        createParameterGroup(writerGroup, "aurora-mysql8.0", "max_connections", "800");
        try {
            int port = rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-mysql")
                    .formParam("DBClusterParameterGroupName", clusterGroup)
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");
            assertThat(query(port, "SELECT @@GLOBAL.max_connections, @@GLOBAL.slow_query_log"),
                    equalTo("500 1"));

            createWriter(writerId, clusterId, "aurora-mysql", writerGroup);
            assertThat(query(port, "SELECT @@GLOBAL.max_connections, @@GLOBAL.slow_query_log"),
                    equalTo("800 1"));

            String reported = rds("ModifyDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("DBClusterParameterGroupName", switchedClusterGroup)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("ModifyDBClusterResponse.ModifyDBClusterResult.DBCluster.DBClusterParameterGroup");
            assertThat(reported, equalTo(switchedClusterGroup));
            rebootInstance(writerId);
            assertThat(query(port, "SELECT @@GLOBAL.max_connections, @@GLOBAL.slow_query_log"),
                    equalTo("800 0"));
        } finally {
            deleteInstance(writerId);
            rds("DeleteDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            for (String group : new String[] {clusterGroup, switchedClusterGroup}) {
                rds("DeleteDBClusterParameterGroup")
                        .formParam("DBClusterParameterGroupName", group)
                .when().post("/");
            }
            rds("DeleteDBParameterGroup")
                    .formParam("DBParameterGroupName", writerGroup)
            .when().post("/");
        }
    }

    @Test
    void mysqlInstanceRunsWithItsDbParameterGroup() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "engine-pg-" + suffix;
        String switchedGroup = "engine-pg2-" + suffix;
        String instanceId = "engine-pg-db-" + suffix;
        createParameterGroup(group, "mysql8.0", "max_connections", "300");
        createParameterGroup(switchedGroup, "mysql8.0", "max_connections", "600");
        try {
            int port = createInstance(instanceId, "mysql", "8.0", group);

            assertThat(query(port, "SELECT @@GLOBAL.max_connections"), equalTo("300"));

            String reported = rds("ModifyDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("DBParameterGroupName", switchedGroup)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("ModifyDBInstanceResponse.ModifyDBInstanceResult.DBInstance"
                            + ".DBParameterGroups.DBParameterGroup.DBParameterGroupName");
            assertThat(reported, equalTo(switchedGroup));
            rebootInstance(instanceId);
            assertThat(query(port, "SELECT @@GLOBAL.max_connections"), equalTo("600"));
        } finally {
            deleteInstance(instanceId);
            for (String name : new String[] {group, switchedGroup}) {
                rds("DeleteDBParameterGroup")
                        .formParam("DBParameterGroupName", name)
                .when().post("/");
            }
        }
    }

    @Test
    void mysqlInstanceWhoseServerRefusesAValueIsIncompatibleParametersUntilRebootedWithAFix() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "engine-bad-" + suffix;
        String instanceId = "engine-bad-db-" + suffix;
        createParameterGroup(group, "mysql8.0", "sql_mode", "NOT_A_MODE");
        try {
            int port = createInstance(instanceId, "mysql", "8.0", group);
            assertThat(instanceStatus(instanceId), equalTo("incompatible-parameters"));

            modifyParameterGroup(group, "sql_mode", "STRICT_TRANS_TABLES", "immediate");
            rebootInstance(instanceId);

            assertThat(instanceStatus(instanceId), equalTo("available"));
            assertThat(query(port, "SELECT @@GLOBAL.sql_mode"), equalTo("STRICT_TRANS_TABLES"));
        } finally {
            deleteInstance(instanceId);
            rds("DeleteDBParameterGroup")
                    .formParam("DBParameterGroupName", group)
            .when().post("/");
        }
    }

    @Test
    void auroraPostgresqlWriterRunsWithLogicalReplicationFromItsClusterParameterGroup() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "engine-pg-cpg-" + suffix;
        String clusterId = "engine-pg-cluster-" + suffix;
        String writerId = "engine-pg-writer-" + suffix;
        createClusterParameterGroup(group, "aurora-postgresql16");
        try {
            modifyClusterParameterGroup(group, "rds.logical_replication", "1", "pending-reboot");

            int port = rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBClusterParameterGroupName", group)
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");
            createWriter(writerId, clusterId, "aurora-postgresql", null);
            assertThat(postgresQuery(port, "SHOW wal_level"), equalTo("logical"));

            modifyClusterParameterGroup(group, "rds.logical_replication", "0", "pending-reboot");
            assertThat(postgresQuery(port, "SHOW wal_level"), equalTo("logical"));

            rebootInstance(writerId);
            assertThat(postgresQuery(port, "SHOW wal_level"), equalTo("replica"));
        } finally {
            deleteInstance(writerId);
            rds("DeleteDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds("DeleteDBClusterParameterGroup")
                    .formParam("DBClusterParameterGroupName", group)
            .when().post("/");
        }
    }

    @Test
    void postgresInstanceTurnsOnLogicalReplicationWhenRebooted() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "engine-pg-pg-" + suffix;
        String instanceId = "engine-pg-db-" + suffix;
        createParameterGroup(group, "postgres16");
        try {
            int port = createInstance(instanceId, "postgres", "16", group);
            assertThat(postgresQuery(port, "SHOW wal_level"), equalTo("replica"));

            modifyParameterGroup(group, "rds.logical_replication", "1", "pending-reboot");
            assertThat(postgresQuery(port, "SHOW wal_level"), equalTo("replica"));

            rebootInstance(instanceId);
            assertThat(postgresQuery(port, "SHOW wal_level"), equalTo("logical"));
        } finally {
            deleteInstance(instanceId);
            rds("DeleteDBParameterGroup")
                    .formParam("DBParameterGroupName", group)
            .when().post("/");
        }
    }

    private static int createInstance(
            String instanceId, String engine, String engineVersion, String parameterGroup) {
        return rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", instanceId)
                .formParam("Engine", engine)
                .formParam("EngineVersion", engineVersion)
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "20")
                .formParam("DBParameterGroupName", parameterGroup)
                .formParam("MasterUsername", MASTER_USER)
                .formParam("MasterUserPassword", MASTER_PASSWORD)
        .when().post("/").then().statusCode(200).extract().xmlPath()
                .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
    }

    private static void createWriter(String writerId, String clusterId, String engine, String parameterGroup) {
        RequestSpecification request = rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", writerId)
                .formParam("DBClusterIdentifier", clusterId)
                .formParam("Engine", engine)
                .formParam("DBInstanceClass", "db.r6g.large");
        if (parameterGroup != null) {
            request = request.formParam("DBParameterGroupName", parameterGroup);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static void rebootInstance(String instanceId) {
        rds("RebootDBInstance")
                .formParam("DBInstanceIdentifier", instanceId)
        .when().post("/").then().statusCode(200);
    }

    private static String instanceStatus(String instanceId) {
        return rds("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", instanceId)
        .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances"
                        + ".DBInstance.DBInstanceStatus");
    }

    private static void createParameterGroup(String name, String family, String parameter, String value) {
        createParameterGroup(name, family);
        modifyParameterGroup(name, parameter, value, "immediate");
    }

    private static void createParameterGroup(String name, String family) {
        rds("CreateDBParameterGroup")
                .formParam("DBParameterGroupName", name)
                .formParam("DBParameterGroupFamily", family)
                .formParam("Description", "engine parameters")
        .when().post("/").then().statusCode(200);
    }

    private static void modifyParameterGroup(String name, String parameter, String value, String applyMethod) {
        rds("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", name)
                .formParam("Parameters.member.1.ParameterName", parameter)
                .formParam("Parameters.member.1.ParameterValue", value)
                .formParam("Parameters.member.1.ApplyMethod", applyMethod)
        .when().post("/").then().statusCode(200);
    }

    private static void createClusterParameterGroup(
            String name, String parameter, String value, String secondParameter, String secondValue) {
        createClusterParameterGroup(name, "aurora-mysql8.0");
        rds("ModifyDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", name)
                .formParam("Parameters.member.1.ParameterName", parameter)
                .formParam("Parameters.member.1.ParameterValue", value)
                .formParam("Parameters.member.1.ApplyMethod", "immediate")
                .formParam("Parameters.member.2.ParameterName", secondParameter)
                .formParam("Parameters.member.2.ParameterValue", secondValue)
                .formParam("Parameters.member.2.ApplyMethod", "immediate")
        .when().post("/").then().statusCode(200);
    }

    private static void createClusterParameterGroup(String name, String family) {
        rds("CreateDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", name)
                .formParam("DBParameterGroupFamily", family)
                .formParam("Description", "engine parameters")
        .when().post("/").then().statusCode(200);
    }

    private static void modifyClusterParameterGroup(
            String name, String parameter, String value, String applyMethod) {
        rds("ModifyDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", name)
                .formParam("Parameters.member.1.ParameterName", parameter)
                .formParam("Parameters.member.1.ParameterValue", value)
                .formParam("Parameters.member.1.ApplyMethod", applyMethod)
        .when().post("/").then().statusCode(200);
    }

    /** The first row of a MySQL query, its columns joined by single spaces. */
    private static String query(int port, String sql) throws Exception {
        return retryUntilConnected(() -> queryOnce(port, sql));
    }

    private static String postgresQuery(int port, String sql) throws Exception {
        return retryUntilConnected(() -> {
            try (Connection connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port
                         + "/postgres?sslmode=disable&connectTimeout=5&socketTimeout=10",
                         MASTER_USER, MASTER_PASSWORD);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getString(1);
            }
        });
    }

    /**
     * Runs a query until the server answers it, for up to 90 seconds. A server still starting
     * refuses connections, and the MySQL image restarts its server once after initializing a new
     * data directory, so a refused connection is retried.
     */
    private static String retryUntilConnected(Callable<String> attempt) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            try {
                return attempt.call();
            } catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }

    private static String queryOnce(int port, String sql) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", MASTER_USER);
        properties.setProperty("password", MASTER_PASSWORD);
        properties.setProperty("useSSL", "false");
        properties.setProperty("allowPublicKeyRetrieval", "true");
        // A server still initializing can accept a connection and never answer it: give up and retry.
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "10000");
        try (Connection connection = DriverManager.getConnection(
                     "jdbc:mysql://127.0.0.1:" + port + "/", properties);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            StringBuilder row = new StringBuilder();
            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                if (column > 1) {
                    row.append(' ');
                }
                row.append(rows.getString(column));
            }
            return row.toString();
        }
    }
}
