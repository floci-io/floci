package io.floci.aws.services.redshift;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.SidecarContainersProfile;
import io.floci.aws.testing.SidecarS3AuthEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SidecarS3AuthEnforcedProfile.class)
class RedshiftUnloadParquetS3AuthIntegrationTest {
    @Inject
    RedshiftService redshift;
    @Inject
    S3Service s3;
    @Inject
    IamService iam;

    @BeforeAll
    static void requireDocker() throws ClassNotFoundException {
        SidecarContainersProfile.requireDocker();
        Class.forName("org.postgresql.Driver");
    }

    @Test
    @Timeout(180)
    void destinationOnlyRoleCanConvertButDestinationDeniesStillApply() throws Exception {
        String name = "unload-auth-" + UUID.randomUUID().toString().substring(0, 8);
        String role = iam.createRole(name, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 0, null).getArn();
        s3.createBucket(name, "us-east-1");
        iam.putRolePolicy(name, "destination-only", """
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::%s"},
                {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/*"},
                {"Effect":"Deny","Action":"s3:*","Resource":["arn:aws:s3:::floci-internal-redshift-unload-staging",
                "arn:aws:s3:::floci-internal-redshift-unload-staging/*"]}]}
                """.formatted(name, name));
        try {
            Cluster cluster = redshift.createCluster(name, "dc2.large", "admin", "Secret123", null, List.of(), List.of(role));
            await().atMost(Duration.ofSeconds(120)).until(() ->
                    "available".equals(redshift.describeClusters(name).getFirst().getClusterStatus()));
            String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev?socketTimeout=60";
            try (Connection connection = DriverManager.getConnection(url, "admin", "Secret123");
                 Statement statement = connection.createStatement()) {
                statement.execute("UNLOAD ('SELECT 1 AS id') TO 's3://" + name + "/out/' IAM_ROLE '" + role
                        + "' PARQUET MANIFEST PARALLEL OFF");
                assertTrue(s3.getObject(name, "out/000.parquet").getSize() > 0);
                assertTrue(s3.getObject(name, "out/manifest").getSize() > 0);
                assertTrue(s3.listObjects(S3Service.REDSHIFT_UNLOAD_STAGING_BUCKET, null, null, 100).isEmpty());
                assertTrue(s3.listBuckets().stream().noneMatch(bucket ->
                        bucket.getName().equals(S3Service.REDSHIFT_UNLOAD_STAGING_BUCKET)));
                s3.putBucketPolicy(name, """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":{"AWS":"%s"},
                        "Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/denied/*"}]}
                        """.formatted(role, name));
                SQLException denied = assertThrows(SQLException.class, () -> statement.execute(
                        "UNLOAD ('SELECT 2 AS id') TO 's3://" + name + "/denied/' IAM_ROLE '" + role + "' PARQUET"));
                assertEquals("42501", denied.getSQLState());
                assertTrue(s3.listObjects(name, "denied/", null, 100).isEmpty());
            }
        } finally {
            redshift.deleteCluster(name);
            s3.deleteBucketPolicy(name);
            for (S3Object object : s3.listObjects(name, null, null, 100)) {
                s3.deleteObject(name, object.getKey());
            }
            s3.deleteBucket(name);
            iam.deleteRolePolicy(name, "destination-only");
            iam.deleteRole(name);
        }
    }
}
