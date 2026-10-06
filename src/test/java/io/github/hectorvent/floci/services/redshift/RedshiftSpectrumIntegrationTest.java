package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatementParser;
import io.github.hectorvent.floci.services.redshift.spectrum.GlueTableBuilder;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalCatalogRegistry;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Savepoint;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import org.postgresql.PGStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RedshiftSpectrumIntegrationTest.SpectrumProfile.class)
class RedshiftSpectrumIntegrationTest {

    public static class SpectrumProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.test-port", "17866", "floci.base-url", "http://localhost:17866",
                    "floci.docker.resource-namespace", "spectrum-relational-tests");
        }
    }

    @Inject
    RedshiftService redshiftService;

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    @Inject
    GlueService glueService;

    @Inject
    ExternalCatalogRegistry externalRegistry;

    private static final String ROLE_NAME = "SpectrumItRole";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/" + ROLE_NAME;
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    private String clusterId;
    private String bucket;
    private String glueDatabase;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Redshift Spectrum integration tests");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Cluster createClusterWithRole(String identifier) {
        if (iamService.findRole("000000000000", ROLE_NAME).isEmpty()) {
            iamService.createRole(ROLE_NAME, "/", TRUST_POLICY, null, 0, null);
            iamService.putRolePolicy(ROLE_NAME, "AllowS3", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Action":["s3:GetObject","s3:ListBucket"],"Resource":"*"}]}""");
        }
        return redshiftService.createCluster(identifier, "dc2.large", "admin", "Secret123",
                null, List.of(), List.of(ROLE_ARN));
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
        if (bucket != null) {
            s3Service.deleteObject(bucket, "events/part-1.csv");
            s3Service.deleteObject(bucket, "invalid/part-1.csv");
            s3Service.deleteBucket(bucket);
        }
        if (glueDatabase != null) {
            glueService.deleteDatabase(glueDatabase);
        }
    }

    @Test
    @Timeout(120)
    void joinsExternalAndInternalTablesWithAggregation() throws Exception {
        bucket = "spectrum-relational-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "events/part-1.csv", "1,10\n1,5\n2,7\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);
        glueDatabase = "spectrum_relational_" + System.nanoTime();
        Database database = new Database();
        database.setName(glueDatabase);
        glueService.createDatabase(database);
        clusterId = "it-relational-" + System.nanoTime();
        Cluster cluster = createClusterWithRole(clusterId);
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA lake FROM DATA CATALOG DATABASE '" + glueDatabase
                    + "' IAM_ROLE '" + ROLE_ARN + "'");
            String externalDdl = "CREATE EXTERNAL TABLE lake.events (id INTEGER, amount INTEGER) "
                    + "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE LOCATION 's3://"
                    + bucket + "/events/'";
            ExternalStatement.CreateTable external = (ExternalStatement.CreateTable)
                    new ExternalStatementParser().parse(externalDdl).orElseThrow();
            glueService.createTable(glueDatabase, GlueTableBuilder.toGlueTable(external));
            statement.execute("CREATE TABLE customers (id INTEGER, country VARCHAR(2))");
            statement.execute("INSERT INTO customers VALUES (1, 'VN'), (2, 'US')");
            try (PreparedStatement ddl = connection.prepareStatement("CREATE EXTERNAL SCHEMA rolledback_lake "
                    + "FROM DATA CATALOG DATABASE '" + glueDatabase + "' IAM_ROLE '" + ROLE_ARN + "'")) {
                ddl.unwrap(PGStatement.class).setPrepareThreshold(1);
                assertEquals(0, ddl.getParameterMetaData().getParameterCount());
                connection.setAutoCommit(false);
                try {
                    SQLException rejected = assertThrows(SQLException.class, ddl::execute);
                    assertEquals("0A000", rejected.getSQLState());
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                assertTrue(externalRegistry.find("000000000000", "000000000000:" + clusterId,
                        "dev", "rolledback_lake").isEmpty());
            }
            connection.setAutoCommit(false);
            try {
                SQLException rejected = assertThrows(SQLException.class, () -> statement.execute(
                        externalDdl.replace("lake.events", "lake.in_transaction")));
                assertEquals("25001", rejected.getSQLState());
                assertTrue(rejected.getMessage().contains("cannot run inside a transaction block"));
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            assertThrows(AwsException.class, () -> glueService.getTable(glueDatabase, "in_transaction"));
            try (PreparedStatement query = connection.prepareStatement("SELECT c.country, SUM(e.amount) AS total "
                    + "FROM lake.events e JOIN customers c ON c.id=e.id WHERE e.amount > ? "
                    + "GROUP BY c.country ORDER BY total DESC")) {
                query.setInt(1, 0);
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("VN", rows.getString(1));
                    assertEquals(15L, rows.getLong(2));
                    assertTrue(rows.next());
                    assertEquals("US", rows.getString(1));
                    assertEquals(7L, rows.getLong(2));
                    assertTrue(!rows.next());
                }
                query.unwrap(PGStatement.class).setPrepareThreshold(1);
                statement.execute("CREATE VIEW event_amounts AS SELECT amount FROM lake.events");
                s3Service.putObject(bucket, "events/part-1.csv", "1,20\n2,7\n".getBytes(StandardCharsets.UTF_8),
                        "text/csv", null);
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(20L, rows.getLong(2));
                }
                s3Service.putObject(bucket, "events/part-1.csv", "1,30\n2,7\n".getBytes(StandardCharsets.UTF_8),
                        "text/csv", null);
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(30L, rows.getLong(2));
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT SUM(a.amount) FROM lake.events a "
                    + "JOIN lake.events b ON a.id=b.id HAVING SUM(a.amount)>10 ORDER BY 1 LIMIT 1")) {
                assertTrue(rows.next());
                assertEquals(37L, rows.getLong(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT SUM(amount) FROM event_amounts")) {
                assertTrue(rows.next());
                assertEquals(37L, rows.getLong(1));
            }
            connection.setAutoCommit(false);
            s3Service.putObject(bucket, "events/part-1.csv", "1,35\n2,7\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            try (PreparedStatement cursor = connection.prepareStatement("SELECT id, amount FROM lake.events ORDER BY id")) {
                cursor.setFetchSize(1);
                try (ResultSet rows = cursor.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    try (PreparedStatement another = connection.prepareStatement("SELECT SUM(amount) FROM lake.events");
                         ResultSet sum = another.executeQuery()) {
                        assertTrue(sum.next());
                        assertEquals(42L, sum.getLong(1));
                    }
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                    assertTrue(!rows.next());
                }
            }
            Savepoint beforeReload = connection.setSavepoint();
            s3Service.putObject(bucket, "events/part-1.csv", "1,40\n2,7\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            try (ResultSet rows = statement.executeQuery("SELECT SUM(amount) FROM lake.events")) {
                assertTrue(rows.next());
                assertEquals(47L, rows.getLong(1));
            }
            connection.rollback(beforeReload);
            try (ResultSet rows = statement.executeQuery("SELECT SUM(amount) FROM lake.events")) {
                assertTrue(rows.next());
                assertEquals(47L, rows.getLong(1));
            }
            connection.rollback();
            connection.setAutoCommit(true);
            try (ResultSet rows = statement.executeQuery("SELECT SUM(amount) FROM lake.events")) {
                assertTrue(rows.next());
                assertEquals(47L, rows.getLong(1));
            }
            s3Service.putObject(bucket, "events/part-1.csv", "1,30\n2,7\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            RestAssuredJsonUtils.configureAwsContentTypes();
            String queryId = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                    {"ClusterIdentifier":"%s","DbUser":"admin","Database":"dev",
                     "Sql":"SELECT SUM(amount) AS total FROM lake.events"}
                    """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
            assertEquals("FINISHED", RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                    "{\"Id\":\"" + queryId + "\"}").then().statusCode(200).extract().path("Status"));
            Number total = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                    "{\"Id\":\"" + queryId + "\"}").then().statusCode(200).extract().path("Records[0][0].longValue");
            assertEquals(37L, total.longValue());
            iamService.updateAssumeRolePolicy(ROLE_NAME, """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                    "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""");
            try {
                SQLException denied = assertThrows(SQLException.class,
                        () -> statement.executeQuery("SELECT SUM(amount) FROM lake.events"));
                assertEquals("42501", denied.getSQLState());
            } finally {
                iamService.updateAssumeRolePolicy(ROLE_NAME, TRUST_POLICY);
            }
            statement.execute("CREATE USER spectrum_reader PASSWORD 'Password123'");
            statement.execute("GRANT USAGE ON SCHEMA lake TO spectrum_reader");
            statement.execute("GRANT SELECT ON lake.events TO spectrum_reader");
            try (Connection reader = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"
                    + cluster.getEndpoint().getPort() + "/dev?socketTimeout=20", "spectrum_reader", "Password123");
                 Statement readerQuery = reader.createStatement();
                 ResultSet rows = readerQuery.executeQuery("SELECT SUM(amount) FROM lake.events")) {
                assertTrue(rows.next());
                assertEquals(37L, rows.getLong(1));
            }
            s3Service.putObject(bucket, "events/part-1.csv", "1,40\n2,7\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService queries = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Long> first = queries.submit(() -> concurrentTotal(cluster, ready, start));
                Future<Long> second = queries.submit(() -> concurrentTotal(cluster, ready, start));
                assertTrue(ready.await(20, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(47L, first.get(30, TimeUnit.SECONDS));
                assertEquals(47L, second.get(30, TimeUnit.SECONDS));
            }
            s3Service.putObject(bucket, "events/part-1.csv", "bad,40\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT SUM(amount) FROM lake.events"));
            s3Service.putObject(bucket, "events/part-1.csv", "1,40\n2,7\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            try (ResultSet rows = statement.executeQuery("SELECT SUM(amount) FROM lake.events")) {
                assertTrue(rows.next());
                assertEquals(47L, rows.getLong(1));
            }
            glueService.deleteTable(glueDatabase, "events");
            SQLException missing = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT * FROM lake.events"));
            assertEquals("42P01", missing.getSQLState());
            try (ResultSet rows = statement.executeQuery("SELECT 1")) {
                assertTrue(rows.next());
            }
        }
        String deleted = clusterId;
        redshiftService.deleteCluster(deleted);
        clusterId = null;
        assertTrue(externalRegistry.list("000000000000", "000000000000:" + deleted, "dev").isEmpty());
    }

    @Test
    @Timeout(60)
    void queriesCsvExternalTableThroughRedshiftWireProxy() throws SQLException {
        bucket = "spectrum-it-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "events/part-1.csv",
                "id,name\n1,Alice\n2,Bob\n".getBytes(StandardCharsets.UTF_8), "text/csv", null);
        clusterId = "it-spectrum-" + System.nanoTime();
        Cluster cluster = createClusterWithRole(clusterId);

        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
            Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA analytics FROM DATA CATALOG DATABASE 'dev' IAM_ROLE '" + ROLE_ARN + "'");
            SQLException duplicateSchema = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE EXTERNAL SCHEMA analytics FROM DATA CATALOG DATABASE 'dev' IAM_ROLE '" + ROLE_ARN + "'"));
            assertEquals("42P06", duplicateSchema.getSQLState());
            statement.execute("CREATE EXTERNAL TABLE analytics.events (id INTEGER, name VARCHAR) "
                    + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/events/' "
                    + "TBLPROPERTIES ('skip.header.line.count'='1')");
            try (ResultSet rows = statement.executeQuery("SELECT id, name FROM analytics.events")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt("id"));
                assertEquals("Alice", rows.getString("name"));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt("id"));
                assertEquals("Bob", rows.getString("name"));
                assertTrue(!rows.next());
            }
            s3Service.putObject(bucket, "invalid/part-1.csv", "invalid,Bad\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            statement.execute("CREATE EXTERNAL TABLE analytics.invalid_events (id INTEGER, name VARCHAR) "
                    + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/invalid/'");
            SQLException invalidRow = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT * FROM analytics.invalid_events"));
            assertEquals("22000", invalidRow.getSQLState());
            try (ResultSet healthCheck = statement.executeQuery("SELECT 1")) {
                assertTrue(healthCheck.next());
                assertEquals(1, healthCheck.getInt(1));
            }
        }
    }

    @Test
    @Timeout(60)
    void queriesCsvExternalTableThroughExtendedQueryProtocol() throws SQLException {
        bucket = "spectrum-it-ext-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "events/part-1.csv",
                "id,name\n1,Alice\n2,Bob\n".getBytes(StandardCharsets.UTF_8), "text/csv", null);
        clusterId = "it-spectrum-ext-" + System.nanoTime();
        Cluster cluster = createClusterWithRole(clusterId);

        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort()
                + "/dev?socketTimeout=20&loginTimeout=20";
        try (Connection connection = Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
            Statement setup = connection.createStatement()) {
            setup.execute("CREATE EXTERNAL SCHEMA analytics_ext FROM DATA CATALOG DATABASE 'dev' IAM_ROLE '" + ROLE_ARN + "'");
            setup.execute("CREATE EXTERNAL TABLE analytics_ext.events (id INTEGER, name VARCHAR) "
                    + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/events/' "
                    + "TBLPROPERTIES ('skip.header.line.count'='1')");
            // Phase 1 does not support bind-parameterized Spectrum predicates (SpectrumQueryClassifier
            // rejects them), so this exercises Extended Query with a literal predicate: pgjdbc still
            // uses Parse/Bind/Describe/Execute/Sync for PreparedStatement even without a '?'.
            try (java.sql.PreparedStatement prepared = connection.prepareStatement(
                    "SELECT id, name FROM analytics_ext.events WHERE id = 1")) {
                // Forces pgjdbc to send Describe('S', ...) (Describe Statement, not just Describe
                // Portal): the response must be ParameterDescription followed by RowDescription/NoData.
                assertEquals(0, prepared.getParameterMetaData().getParameterCount());
                try (ResultSet rows = prepared.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt("id"));
                    assertEquals("Alice", rows.getString("name"));
                    assertTrue(!rows.next());
                }
            }
        }
    }

    @Test
    @Timeout(60)
    void pagesSpectrumRowsAcrossMultipleExecutesWhenFetchSizeIsSet() throws SQLException {
        bucket = "spectrum-it-fetch-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "events/part-1.csv",
                "id,name\n1,Alice\n2,Bob\n3,Carol\n4,Dave\n5,Eve\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);
        clusterId = "it-spectrum-fetch-" + System.nanoTime();
        Cluster cluster = createClusterWithRole(clusterId);

        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort()
                + "/dev?socketTimeout=20&loginTimeout=20";
        try (Connection connection = Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
             Statement setup = connection.createStatement()) {
            setup.execute("CREATE EXTERNAL SCHEMA analytics_fetch FROM DATA CATALOG DATABASE 'dev' IAM_ROLE '" + ROLE_ARN + "'");
            setup.execute("CREATE EXTERNAL TABLE analytics_fetch.events (id INTEGER, name VARCHAR) "
                    + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/events/' "
                    + "TBLPROPERTIES ('skip.header.line.count'='1')");

            // Fetch size only becomes a real server-side cursor (Execute with maxRows > 0, answered
            // with PortalSuspended) in pgjdbc when autoCommit is off.
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement prepared = connection.prepareStatement(
                    "SELECT id, name FROM analytics_fetch.events")) {
                prepared.setFetchSize(2);
                List<Integer> ids = new ArrayList<>();
                try (ResultSet rows = prepared.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getInt("id"));
                    }
                }
                assertEquals(List.of(1, 2, 3, 4, 5), ids);
            }
            connection.commit();
        }
    }

    private static Connection waitForConnection(Cluster cluster, String username, String password) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
        return Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, username, password), Objects::nonNull);
    }

    private static long concurrentTotal(Cluster cluster, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             PreparedStatement query = connection.prepareStatement("SELECT SUM(amount) FROM lake.events")) {
            ready.countDown();
            assertTrue(start.await(20, TimeUnit.SECONDS));
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }
}
