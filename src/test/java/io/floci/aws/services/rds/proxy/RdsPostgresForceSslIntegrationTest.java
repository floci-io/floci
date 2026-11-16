package io.floci.aws.services.rds.proxy;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static io.floci.aws.testing.RdsRequests.deleteInstance;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@Tag("docker")
class RdsPostgresForceSslIntegrationTest {

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
    void instanceParameterGroupForceSslRefusesPlaintextAndFollowsGroupChanges() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "force-ssl-" + suffix;
        String dbId = "force-ssl-" + suffix;
        rds("CreateDBParameterGroup")
                .formParam("DBParameterGroupName", group)
                .formParam("DBParameterGroupFamily", "postgres16")
                .formParam("Description", "force ssl")
        .when().post("/").then().statusCode(200);
        try {
            setInstanceForceSsl(group, "1");
            int port = rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", dbId)
                    .formParam("Engine", "postgres")
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
                    .formParam("DBName", "appdb")
                    .formParam("AllocatedStorage", "20")
                    .formParam("DBInstanceClass", "db.t3.micro")
                    .formParam("DBParameterGroupName", group)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");

            assertPlaintextRefused(port);
            assertThat(selectOne(port, "require"), equalTo(1));

            setInstanceForceSsl(group, "0");
            assertThat(selectOne(port, "disable"), equalTo(1));
        } finally {
            deleteInstance(dbId);
            rds("DeleteDBParameterGroup")
                    .formParam("DBParameterGroupName", group)
            .when().post("/");
        }
    }

    @Test
    void auroraClusterParameterGroupForceSslRefusesPlaintext() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "force-ssl-cpg-" + suffix;
        String clusterId = "force-ssl-cluster-" + suffix;
        rds("CreateDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", group)
                .formParam("DBParameterGroupFamily", "aurora-postgresql16")
                .formParam("Description", "force ssl")
        .when().post("/").then().statusCode(200);
        try {
            rds("ModifyDBClusterParameterGroup")
                    .formParam("DBClusterParameterGroupName", group)
                    .formParam("Parameters.member.1.ParameterName", "rds.force_ssl")
                    .formParam("Parameters.member.1.ParameterValue", "1")
                    .formParam("Parameters.member.1.ApplyMethod", "immediate")
            .when().post("/").then().statusCode(200);
            int port = rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBClusterParameterGroupName", group)
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");

            assertPlaintextRefused(port);
            assertThat(selectOne(port, "require"), equalTo(1));
        } finally {
            rds("DeleteDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds("DeleteDBClusterParameterGroup")
                    .formParam("DBClusterParameterGroupName", group)
            .when().post("/");
        }
    }

    private static void setInstanceForceSsl(String group, String value) {
        rds("ModifyDBParameterGroup")
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
