package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatementParser;
import io.github.hectorvent.floci.services.redshift.spectrum.GlueTableBuilder;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.github.hectorvent.floci.testing.SidecarS3AuthEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cold load, cached read and reload of a Spectrum external table with {@code s3.enforce-auth} on:
 * the floci-duck transfer runs under the role's signed session, and a bucket policy that denies the
 * role stops the next query with SQLSTATE 42501 even though the table is already cached.
 */
@QuarkusTest
@TestProfile(SidecarS3AuthEnforcedProfile.class)
class RedshiftSpectrumS3AuthEnforcedIntegrationTest {

    private static final String ROLE_NAME = "SpectrumAuthItRole";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/" + ROLE_NAME;
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    @Inject
    RedshiftService redshiftService;

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    @Inject
    GlueService glueService;

    private String clusterId;
    private String bucket;
    private String glueDatabase;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
        if (bucket != null) {
            s3Service.deleteBucketPolicy(bucket);
            s3Service.deleteObject(bucket, "events/part-1.csv");
            s3Service.deleteBucket(bucket);
        }
        if (glueDatabase != null) {
            glueService.deleteDatabase(glueDatabase);
        }
        iamService.deleteRolePolicy(ROLE_NAME, "AllowS3");
        iamService.deleteRole(ROLE_NAME);
    }

    @Test
    @Timeout(180)
    void coldLoadReloadAndCachedReadUseTheRoleSessionAndHonorABucketPolicyDeny() throws Exception {
        iamService.createRole(ROLE_NAME, "/", TRUST_POLICY, null, 0, null);
        iamService.putRolePolicy(ROLE_NAME, "AllowS3", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":["s3:GetObject","s3:ListBucket"],"Resource":"*"}]}""");
        bucket = "spectrum-auth-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "events/part-1.csv", "1,10\n2,7\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);
        glueDatabase = "spectrum_auth_" + System.nanoTime();
        Database database = new Database();
        database.setName(glueDatabase);
        glueService.createDatabase(database);
        clusterId = "it-auth-" + System.nanoTime();
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123",
                null, List.of(), List.of(ROLE_ARN));
        try (Connection connection = waitForConnection(cluster);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA lake FROM DATA CATALOG DATABASE '" + glueDatabase
                    + "' IAM_ROLE '" + ROLE_ARN + "'");
            String externalDdl = "CREATE EXTERNAL TABLE lake.events (id INTEGER, amount INTEGER) "
                    + "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE LOCATION 's3://"
                    + bucket + "/events/'";
            ExternalStatement.CreateTable external = (ExternalStatement.CreateTable)
                    new ExternalStatementParser().parse(externalDdl).orElseThrow();
            glueService.createTable(glueDatabase, GlueTableBuilder.toGlueTable(external));

            assertEquals(17L, sumAmount(statement));
            assertEquals(17L, sumAmount(statement));

            s3Service.putObject(bucket, "events/part-1.csv", "1,10\n2,30\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            assertEquals(40L, sumAmount(statement));

            RestAssuredJsonUtils.configureAwsContentTypes();
            s3Service.putObject(bucket, "events/part-1.csv", "1,10\n2,50\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            String queryId = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                    {"ClusterIdentifier":"%s","DbUser":"admin","Database":"dev",
                     "Sql":"SELECT SUM(amount) AS total FROM lake.events","WaitTimeSeconds":30}
                    """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
            assertEquals("FINISHED", RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                    "{\"Id\":\"" + queryId + "\"}").then().statusCode(200).extract().path("Status"));
            Number total = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                    "{\"Id\":\"" + queryId + "\"}").then().statusCode(200).extract().path("Records[0][0].longValue");
            assertEquals(60L, total.longValue());

            s3Service.putBucketPolicy(bucket, """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                    "Principal":{"AWS":"%s"},"Action":["s3:GetObject","s3:ListBucket"],
                    "Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"]}]}""".formatted(ROLE_ARN, bucket, bucket));
            SQLException denied = assertThrows(SQLException.class, () -> sumAmount(statement));
            assertEquals("42501", denied.getSQLState());
        }
    }

    private static long sumAmount(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT SUM(amount) FROM lake.events")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static Connection waitForConnection(Cluster cluster) {
        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
        return Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
    }
}
