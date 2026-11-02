package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.RdsRequests.assumeDocker;
import static io.github.hectorvent.floci.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@Tag("docker")
class RdsManagedPasswordRotationDockerIntegrationTest {

    /**
     * DriverManager only hands out a driver the caller's class loader can see. After a Quarkus
     * restart the first connection would find none, so PgJDBC is loaded through this test's loader first.
     */
    @BeforeEach
    void registerPostgresDriver() throws ClassNotFoundException {
        Class.forName("org.postgresql.Driver");
    }

    @Test
    void freshPostgresConnectionsUseTheRotatedManagedSecret() throws Exception {
        assumeDocker();
        String id = "sql-rotate-" + UUID.randomUUID().toString().substring(0, 8);
        try (RdsClient rds = rdsClient("us-east-1", "000000000000");
                SecretsManagerClient secrets = SecretsManagerClient.builder()
                        .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                        .region(Region.US_EAST_1).httpClientBuilder(UrlConnectionHttpClient.builder())
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                        .build()) {
            DBCluster cluster = rds.createDBCluster(request -> request.dbClusterIdentifier(id).engine("aurora-postgresql")
                    .engineVersion("16.4").masterUsername("clusteradmin").databaseName("appdb")
                    .manageMasterUserPassword(true)).dbCluster();
            try {
                rds.createDBInstance(request -> request.dbInstanceIdentifier(id + "-member").dbClusterIdentifier(id)
                        .engine("aurora-postgresql").dbInstanceClass("db.r5.large"));
                try {
                    String arn = cluster.masterUserSecret().secretArn();
                    GetSecretValueResponse before = secrets.getSecretValue(request -> request.secretId(arn));
                    String oldPassword = password(before);
                    String url = "jdbc:postgresql://127.0.0.1:" + cluster.port()
                            + "/appdb?connectTimeout=5&socketTimeout=5";
                    verifyLogin(url, oldPassword);
                    rds.modifyDBCluster(request -> request.dbClusterIdentifier(id)
                            .rotateMasterUserPassword(true).applyImmediately(true));
                    GetSecretValueResponse after = secrets.getSecretValue(request -> request.secretId(arn));
                    assertNotEquals(before.versionId(), after.versionId());
                    verifyLogin(url, password(after));
                    SQLException refused = assertThrows(SQLException.class, () -> verifyLogin(url, oldPassword));
                    assertEquals("28P01", refused.getSQLState());
                } finally {
                    rds.deleteDBInstance(request -> request.dbInstanceIdentifier(id + "-member").skipFinalSnapshot(true));
                }
            } finally {
                rds.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    private static String password(GetSecretValueResponse response) throws Exception {
        return new ObjectMapper().readTree(response.secretString()).path("password").asText();
    }

    private static void verifyLogin(String url, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "clusteradmin", password)) {
            assertEquals(1, connection.createStatement().executeQuery("select 1").getMetaData().getColumnCount());
        }
    }
}
