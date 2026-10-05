package io.github.hectorvent.floci.compat;

import com.floci.test.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.DatabaseInput;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.GetStatementResultResponse;
import software.amazon.awssdk.services.s3.S3Client;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RedshiftRelationalSpectrumTest {
    @Test
    @DisplayName("Glue external tables join internal tables through JDBC and the SDK Data API")
    void joinsGlueCsvTablesThroughJdbcAndDataApi() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String clusterId = "spectrum-rel-" + suffix;
        String bucket = "spectrum-rel-" + suffix;
        String database = "spectrum_rel_" + suffix;
        String roleName = "SpectrumRel" + suffix;
        boolean clusterCreated = false;
        boolean bucketCreated = false;
        boolean databaseCreated = false;
        boolean roleCreated = false;
        boolean policyCreated = false;
        try (RedshiftClient redshift = TestFixtures.redshiftClient();
             RedshiftDataClient data = TestFixtures.redshiftDataClient();
             GlueClient glue = TestFixtures.glueClient();
             S3Client s3 = TestFixtures.s3Client();
             IamClient iam = TestFixtures.iamClient()) {
            try {
                String roleArn = iam.createRole(r -> r.roleName(roleName).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                         "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role().arn();
                roleCreated = true;
                iam.putRolePolicy(r -> r.roleName(roleName).policyName("SpectrumRead").policyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                         "Action":["s3:GetObject","s3:ListBucket","glue:GetTable","glue:GetPartitions"],"Resource":"*"}]}
                        """));
                policyCreated = true;
                s3.createBucket(r -> r.bucket(bucket));
                bucketCreated = true;
                s3.putObject(r -> r.bucket(bucket).key("events/data.csv"), RequestBody.fromString("1,10\n1,5\n2,7\n"));
                glue.createDatabase(r -> r.databaseInput(DatabaseInput.builder().name(database).build()));
                databaseCreated = true;
                redshift.createCluster(r -> r.clusterIdentifier(clusterId).nodeType("dc2.large")
                        .masterUsername("admin").masterUserPassword("Password123").iamRoles(roleArn));
                clusterCreated = true;
                Cluster cluster = redshift.describeClusters(r -> r.clusterIdentifier(clusterId)).clusters().get(0);
                String url = "jdbc:postgresql://" + cluster.endpoint().address() + ":" + cluster.endpoint().port() + "/dev";
                try (Connection connection = DriverManager.getConnection(url, "admin", "Password123");
                     Statement setup = connection.createStatement()) {
                    setup.execute("CREATE EXTERNAL SCHEMA lake FROM DATA CATALOG DATABASE '" + database
                            + "' IAM_ROLE '" + roleArn + "'");
                    setup.execute("CREATE EXTERNAL TABLE lake.events (id INTEGER, amount INTEGER) "
                            + "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE LOCATION 's3://"
                            + bucket + "/events/'");
                    setup.execute("CREATE EXTERNAL TABLE lake.mirror_events (id INTEGER, amount INTEGER) "
                            + "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE LOCATION 's3://"
                            + bucket + "/events/'");
                    assertEquals("events", glue.getTable(r -> r.databaseName(database).name("events")).table().name());
                    setup.execute("CREATE TABLE customers(id INTEGER, country VARCHAR(2))");
                    setup.execute("INSERT INTO customers VALUES (1,'VN'),(2,'US')");
                    String sql = "SELECT c.country, SUM(e.amount) AS total FROM lake.events e "
                            + "JOIN (SELECT DISTINCT id FROM lake.mirror_events) m ON m.id=e.id "
                            + "JOIN customers c ON c.id=e.id GROUP BY c.country ORDER BY total DESC";
                    try (PreparedStatement query = connection.prepareStatement(sql);
                         ResultSet rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("VN", rows.getString(1));
                        assertEquals(15L, rows.getLong(2));
                        assertTrue(rows.next());
                        assertEquals("US", rows.getString(1));
                        assertEquals(7L, rows.getLong(2));
                        assertFalse(rows.next());
                    }
                    String id = data.executeStatement(r -> r.clusterIdentifier(clusterId).database("dev")
                            .dbUser("admin").sql(sql)).id();
                    Instant deadline = Instant.now().plusSeconds(30);
                    DescribeStatementResponse status = data.describeStatement(r -> r.id(id));
                    while (!status.statusAsString().equals("FINISHED") && Instant.now().isBefore(deadline)) {
                        assertNotEquals("FAILED", status.statusAsString(), status.error());
                        Thread.sleep(100);
                        status = data.describeStatement(r -> r.id(id));
                    }
                    assertEquals("FINISHED", status.statusAsString(), status.error());
                    GetStatementResultResponse result = data.getStatementResult(r -> r.id(id));
                    assertEquals(2L, result.totalNumRows());
                    assertEquals("VN", result.records().get(0).get(0).stringValue());
                    assertEquals(15L, result.records().get(0).get(1).longValue());
                    assertEquals("US", result.records().get(1).get(0).stringValue());
                    String failedBatch = data.batchExecuteStatement(r -> r.clusterIdentifier(clusterId).database("dev")
                            .dbUser("admin").sqls("INSERT INTO customers VALUES (3,'JP')", "SELECT * FROM lake.absent")).id();
                    Instant failureDeadline = Instant.now().plusSeconds(30);
                    DescribeStatementResponse failed = data.describeStatement(r -> r.id(failedBatch));
                    while (!failed.statusAsString().equals("FAILED") && Instant.now().isBefore(failureDeadline)) {
                        assertNotEquals("FINISHED", failed.statusAsString());
                        Thread.sleep(100);
                        failed = data.describeStatement(r -> r.id(failedBatch));
                    }
                    assertEquals("FAILED", failed.statusAsString());
                    try (ResultSet rows = setup.executeQuery("SELECT COUNT(*) FROM customers WHERE id=3")) {
                        assertTrue(rows.next());
                        assertEquals(0L, rows.getLong(1));
                    }
                }
            } finally {
                if (clusterCreated) {
                    redshift.deleteCluster(r -> r.clusterIdentifier(clusterId).skipFinalClusterSnapshot(true));
                }
                if (databaseCreated) {
                    glue.deleteDatabase(r -> r.name(database));
                }
                if (bucketCreated) {
                    s3.deleteObject(r -> r.bucket(bucket).key("events/data.csv"));
                    s3.deleteBucket(r -> r.bucket(bucket));
                }
                if (policyCreated) {
                    iam.deleteRolePolicy(r -> r.roleName(roleName).policyName("SpectrumRead"));
                }
                if (roleCreated) {
                    iam.deleteRole(r -> r.roleName(roleName));
                }
            }
        }
    }
}
