package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.RdsException;
import software.amazon.jdbc.authentication.AwsCredentialsManager;
import software.amazon.jdbc.wrapper.ConnectionWrapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connects to an Aurora PostgreSQL cluster through the AWS Advanced JDBC Wrapper.
 * Floci runs plain PostgreSQL behind the cluster endpoint, so the aurora_* topology
 * functions are absent and topology-based plugins (failover, failover2, efm, gdbFailover)
 * are out of scope. The test uses the plain PostgreSQL dialect and the IAM plugin.
 */
@DisplayName("RDS AWS Advanced JDBC Wrapper")
class RdsAwsAdvancedJdbcWrapperTest {
    private static final Logger LOG = Logger.getLogger(RdsAwsAdvancedJdbcWrapperTest.class);
    private static final String USERNAME = "clusteradmin";
    private static final String PASSWORD = "ClusterPassword1";
    private static final String IAM_USERNAME = "iam_user";
    private static final String DATABASE = "app";

    private static RdsClient rds;
    private static String clusterId;
    private static String memberId;
    private static int port;

    @BeforeAll
    static void createCluster() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Connects through the Floci proxy host with test credentials");
        rds = TestFixtures.rdsClient();
        clusterId = TestFixtures.uniqueName("jdbc-wrapper");
        memberId = clusterId + "-1";
        DBCluster cluster = rds.createDBCluster(b -> b.dbClusterIdentifier(clusterId)
                .engine("aurora-postgresql").masterUsername(USERNAME).masterUserPassword(PASSWORD)
                .databaseName(DATABASE).enableIAMDatabaseAuthentication(true)).dbCluster();
        rds.createDBInstance(b -> b.dbInstanceIdentifier(memberId).dbClusterIdentifier(clusterId)
                .engine("aurora-postgresql").dbInstanceClass("db.serverless"));
        port = cluster.port();
    }

    @AfterAll
    static void cleanup() {
        if (rds == null) {
            return;
        }
        try {
            rds.deleteDBInstance(b -> b.dbInstanceIdentifier(memberId).skipFinalSnapshot(true));
        } catch (RdsException e) {
            LOG.warnv(e, "Could not clean up member {0}", memberId);
        }
        try {
            rds.deleteDBCluster(b -> b.dbClusterIdentifier(clusterId).skipFinalSnapshot(true));
        } catch (RdsException e) {
            LOG.warnv(e, "Could not clean up cluster {0}", clusterId);
        }
        rds.close();
    }

    @Test
    @DisplayName("Writes and reads through the wrapper with password authentication")
    void writeAndReadWithPassword() throws Exception {
        Properties properties = wrapperProperties("");
        properties.setProperty("user", USERNAME);
        properties.setProperty("password", PASSWORD);

        try (Connection connection = awaitConnection(properties)) {
            assertThat(connection).isInstanceOf(ConnectionWrapper.class);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS wrapper_items (id INT PRIMARY KEY, name TEXT)");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO wrapper_items (id, name) VALUES (?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "floci");
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT name FROM wrapper_items WHERE id = 1")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("floci");
            }
        }
    }

    @Test
    @DisplayName("Connects through the wrapper IAM plugin")
    void connectWithIamPlugin() throws Exception {
        Properties admin = wrapperProperties("");
        admin.setProperty("user", USERNAME);
        admin.setProperty("password", PASSWORD);
        try (Connection connection = awaitConnection(admin);
             Statement statement = connection.createStatement()) {
            statement.execute("DO $$ BEGIN CREATE ROLE " + IAM_USERNAME + " LOGIN; "
                    + "EXCEPTION WHEN duplicate_object THEN NULL; END $$");
            statement.execute("GRANT rds_iam TO " + IAM_USERNAME);
        }

        AwsCredentialsManager.setCustomHandler((hostSpec, props) ->
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        Properties properties = wrapperProperties("iam");
        properties.setProperty("user", IAM_USERNAME);
        properties.setProperty("iamRegion", TestFixtures.region().id());

        try (Connection connection = awaitConnection(properties);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_user")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo(IAM_USERNAME);
        } finally {
            AwsCredentialsManager.resetCustomHandler();
        }
    }

    private static Properties wrapperProperties(String plugins) {
        Properties properties = new Properties();
        properties.setProperty("wrapperPlugins", plugins);
        properties.setProperty("wrapperDialect", "pg");
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "5");
        return properties;
    }

    private static Connection awaitConnection(Properties properties) throws Exception {
        return TestFixtures.awaitJdbcConnection(
                "jdbc:aws-wrapper:postgresql://" + TestFixtures.proxyHost() + ":" + port + "/" + DATABASE, properties);
    }
}
