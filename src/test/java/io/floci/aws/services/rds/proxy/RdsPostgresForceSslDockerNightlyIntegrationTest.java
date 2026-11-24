package io.floci.aws.services.rds.proxy;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static io.floci.aws.testing.RdsRequests.createPostgresInstance;
import static io.floci.aws.testing.RdsRequests.deleteInstance;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@Tag("docker")
class RdsPostgresForceSslDockerNightlyIntegrationTest {

    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";

    @BeforeEach
    void checkDocker() throws InterruptedException {
        assumeDocker();
    }

    @BeforeEach
    void registerPostgresDriver() throws ClassNotFoundException {
        Class.forName("org.postgresql.Driver");
    }

    @Test
    void instanceParameterGroupForceSslFollowsGroupChangesOnARunningInstance() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "force-ssl-" + suffix;
        String dbId = "force-ssl-" + suffix;
        createInstanceParameterGroup(group, "postgres16");
        try {
            int port = createPostgresInstance(dbId, MASTER_USER, MASTER_PASSWORD, false, group);

            assertThat(selectOne(port, "disable"), equalTo(1));

            setInstanceForceSsl(group, "1");
            assertPlaintextRefused(port);
            assertThat(selectOne(port, "require"), equalTo(1));

            setInstanceForceSsl(group, "0");
            assertThat(selectOne(port, "disable"), equalTo(1));
        } finally {
            deleteInstance(dbId);
            deleteInstanceParameterGroup(group);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "222222222222"})
    void auroraClusterParameterGroupForceSslRefusesPlaintextOnClusterAndMemberEndpoints(String accessKey)
            throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterGroup = "force-ssl-cpg-" + suffix;
        String memberGroup = "force-ssl-mpg-" + suffix;
        String clusterId = "force-ssl-cluster-" + suffix;
        String memberId = clusterId + "-member";
        rds("CreateDBClusterParameterGroup", accessKey)
                .formParam("DBClusterParameterGroupName", clusterGroup)
                .formParam("DBParameterGroupFamily", "aurora-postgresql16")
                .formParam("Description", "force ssl")
        .when().post("/").then().statusCode(200);
        createInstanceParameterGroup(memberGroup, "aurora-postgresql16", accessKey);
        try {
            rds("ModifyDBClusterParameterGroup", accessKey)
                    .formParam("DBClusterParameterGroupName", clusterGroup)
                    .formParam("Parameters.member.1.ParameterName", "rds.force_ssl")
                    .formParam("Parameters.member.1.ParameterValue", "1")
                    .formParam("Parameters.member.1.ApplyMethod", "immediate")
            .when().post("/").then().statusCode(200);
            setInstanceForceSsl(memberGroup, "0", accessKey);
            int clusterPort = rds("CreateDBCluster", accessKey)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBClusterParameterGroupName", clusterGroup)
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");
            int memberPort = rds("CreateDBInstance", accessKey)
                    .formParam("DBInstanceIdentifier", memberId)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("DBInstanceClass", "db.r5.large")
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBParameterGroupName", memberGroup)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");

            assertPlaintextRefused(clusterPort);
            assertThat(selectOne(clusterPort, "require"), equalTo(1));
            assertPlaintextRefused(memberPort);
            assertThat(selectOne(memberPort, "require"), equalTo(1));
        } finally {
            rds("DeleteDBInstance", accessKey)
                    .formParam("DBInstanceIdentifier", memberId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds("DeleteDBCluster", accessKey)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds("DeleteDBClusterParameterGroup", accessKey)
                    .formParam("DBClusterParameterGroupName", clusterGroup)
            .when().post("/");
            deleteInstanceParameterGroup(memberGroup, accessKey);
        }
    }

    private static void createInstanceParameterGroup(String group, String family) {
        createInstanceParameterGroup(group, family, "test");
    }

    private static void createInstanceParameterGroup(String group, String family, String accessKey) {
        rds("CreateDBParameterGroup", accessKey)
                .formParam("DBParameterGroupName", group)
                .formParam("DBParameterGroupFamily", family)
                .formParam("Description", "force ssl")
        .when().post("/").then().statusCode(200);
    }

    private static void deleteInstanceParameterGroup(String group) {
        deleteInstanceParameterGroup(group, "test");
    }

    private static void deleteInstanceParameterGroup(String group, String accessKey) {
        rds("DeleteDBParameterGroup", accessKey)
                .formParam("DBParameterGroupName", group)
        .when().post("/");
    }

    private static void setInstanceForceSsl(String group, String value) {
        setInstanceForceSsl(group, value, "test");
    }

    private static void setInstanceForceSsl(String group, String value, String accessKey) {
        rds("ModifyDBParameterGroup", accessKey)
                .formParam("DBParameterGroupName", group)
                .formParam("Parameters.member.1.ParameterName", "rds.force_ssl")
                .formParam("Parameters.member.1.ParameterValue", value)
                .formParam("Parameters.member.1.ApplyMethod", "immediate")
        .when().post("/").then().statusCode(200);
    }

    private static void assertPlaintextRefused(int port) {
        SQLException refused = assertThrows(SQLException.class, () -> selectOne(port, "disable"));
        assertThat(refused.getSQLState(), equalTo("28000"));
        assertThat(refused.getMessage(), containsString("no pg_hba.conf entry"));
        assertThat(refused.getMessage(), containsString("no encryption"));
    }

    private static int selectOne(int port, String sslMode) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", MASTER_USER);
        properties.setProperty("password", MASTER_PASSWORD);
        properties.setProperty("sslmode", sslMode);
        try (Connection connection = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:" + port + "/postgres", properties);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            result.next();
            return result.getInt(1);
        }
    }
}
