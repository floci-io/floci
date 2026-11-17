package io.floci.aws.services.redshift;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RedshiftS3AutoCopyIntegrationTest {
    @Inject
    RedshiftService service;
    @Inject
    IamService iam;
    @Inject
    RedshiftS3AutoCopyConsumer consumer;
    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for Redshift auto-copy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void sdkUploadsAreLoadedOnceAndDeletionPreservesManualExecution(String mode) throws Exception {
        String id = "auto-copy-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "AutoCopy" + UUID.randomUUID().toString().replace("-", "");
        String role = iam.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 0, null).getArn();
        iam.putRolePolicy(roleName, "S3", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":["s3:GetObject","s3:ListBucket"],"Resource":"*"}]}
                """);
        Cluster cluster = service.createCluster(id, "dc2.large", "admin", "Secret123", null, List.of(), List.of(role));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (S3Client s3 = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).forcePathStyle(true).build();
                RedshiftClient redshift = RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build();
                Connection connection = RedshiftConnections.waitForConnection(RedshiftConnections.jdbcUrl(cluster)
                        + "?preferQueryMode=" + mode, "admin", "Secret123");
                Statement statement = connection.createStatement()) {
            s3.createBucket(request -> request.bucket(id));
            put(s3, id, "prefix/old", "99\n");
            statement.execute("CREATE TABLE events (id integer)");
            statement.execute("COPY events FROM 's3://" + id + "/prefix/' IAM_ROLE '" + role + "' JOB CREATE ingest");
            String integration = redshift.createIntegration(request -> request.integrationName(id)
                    .sourceArn("arn:aws:s3:::" + id).targetArn("arn:aws:redshift:us-east-1:000000000000:cluster:" + id))
                    .integrationArn();
            try {
                put(s3, id, "elsewhere/ignored", "98\n");
                put(s3, id, "prefix/first", "1\n");
                consumer.pollOnce();
                assertEquals(1, count(statement, "events"));
                put(s3, id, "prefix/first", "2\n");
                consumer.pollOnce();
                consumer.clear();
                consumer.pollOnce();
                assertEquals(1, count(statement, "events"));
                assertEquals(1, count(statement, "floci_internal.copy_job_detail"));
            } finally {
                redshift.deleteIntegration(request -> request.integrationArn(integration));
            }
            try (ResultSet job = statement.executeQuery("COPY JOB SHOW ingest")) {
                assertTrue(job.next());
                assertEquals(0, job.getInt("is_auto"));
            }
            put(s3, id, "prefix/second", "3\n");
            consumer.pollOnce();
            assertEquals(1, count(statement, "events"));
            connection.setAutoCommit(false);
            statement.execute("COPY JOB RUN ingest");
            assertEquals(2, count(statement, "events"));
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals(1, count(statement, "events"));
            statement.execute("COPY JOB RUN ingest");
            assertEquals(2, count(statement, "events"));
            put(s3, id, "prefix/bad", "invalid\n");
            put(s3, id, "prefix/good", "4\n");
            statement.execute("COPY JOB RUN ingest");
            assertEquals(3, count(statement, "events"));
            assertEquals(4, count(statement, "floci_internal.copy_job_detail"));
            assertEquals("E", fileStatus(statement, "prefix/bad"));
            assertEquals("I", fileStatus(statement, "prefix/good"));
            put(s3, id, "prefix/bad", "5\n");
            statement.execute("COPY JOB RUN ingest");
            assertEquals(4, count(statement, "events"));
            assertEquals("I", fileStatus(statement, "prefix/bad"));
            statement.execute("COPY JOB DROP ingest");
            assertEquals(0, count(statement, "floci_internal.copy_job_detail"));

            statement.execute("CREATE USER blocked_owner PASSWORD 'Secret123'");
            statement.execute("SET SESSION AUTHORIZATION blocked_owner");
            statement.execute("COPY public.events FROM 's3://" + id + "/protected/' IAM_ROLE '"
                    + role + "' JOB CREATE blocked_job AUTO OFF");
            statement.execute("RESET SESSION AUTHORIZATION");
            put(s3, id, "protected/file", "6\n");
            SQLException denied = assertThrows(SQLException.class, () -> statement.execute("COPY JOB RUN blocked_job"));
            assertEquals("42501", denied.getSQLState());
            assertEquals(4, count(statement, "events"));
            assertEquals(0, count(statement, "floci_internal.copy_job_detail"));
        } finally {
            service.deleteCluster(id);
        }
    }

    private static void put(S3Client client, String bucket, String key, String body) {
        client.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(body));
    }

    private static long count(Statement statement, String table) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String fileStatus(Statement statement, String fileName) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
                "SELECT status FROM sys_copy_job_detail WHERE file_name = '" + fileName + "'")) {
            assertTrue(rows.next());
            return rows.getString(1).trim();
        }
    }
}
