package io.floci.aws.services.rds;

import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static io.floci.aws.testing.RdsRequests.createPostgresInstance;
import static io.floci.aws.testing.RdsRequests.deleteInstance;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The initial load of an RDS zero-ETL integration end to end: a PostgreSQL instance as the source, a
 * Redshift Serverless workgroup as the target, and the destination database created over the
 * workgroup's PostgreSQL endpoint with {@code CREATE DATABASE ... FROM INTEGRATION}. Needs Docker.
 */
@QuarkusTest
class RdsZeroEtlInitialLoadIntegrationTest {

    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";
    private static final String ADMIN_PASSWORD = "Secret123";
    private static final String REGION = "us-east-1";

    @Inject
    RedshiftServerlessService serverless;

    @BeforeEach
    void requireDocker() throws InterruptedException {
        assumeDocker();
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theIncludedTablesWithAPrimaryKeyAreCopiedIntoTheDestinationDatabase() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dbId = "zetl-src-" + suffix;
        String namespace = "zetl-ns-" + suffix;
        String workgroup = "zetl-wg-" + suffix;
        int sourcePort = createPostgresInstance(dbId, MASTER_USER, MASTER_PASSWORD, false);
        String integrationArn = null;
        boolean namespaceCreated = false;
        boolean workgroupCreated = false;
        try {
            execute(sourcePort, "appdb", MASTER_USER, MASTER_PASSWORD,
                    "CREATE SCHEMA sales",
                    "CREATE TABLE sales.orders (id int8, line int4, note text, total numeric(10,2), "
                            + "attrs jsonb, PRIMARY KEY (id, line))",
                    "INSERT INTO sales.orders VALUES (1, 1, 'first', 12.30, '{\"rush\": true}'), "
                            + "(1, 2, NULL, 0.50, NULL)",
                    "CREATE TABLE no_key (id int)",
                    "CREATE TABLE left_out (id int PRIMARY KEY)");

            String namespaceArn = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                    {"namespaceName":"%s","adminUsername":"admin","adminUserPassword":"%s"}
                    """.formatted(namespace, ADMIN_PASSWORD)).then().statusCode(200)
                    .extract().path("namespace.namespaceArn");
            namespaceCreated = true;
            int workgroupPort = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateWorkgroup", """
                    {"workgroupName":"%s","namespaceName":"%s"}
                    """.formatted(workgroup, namespace)).then().statusCode(200)
                    .extract().path("workgroup.endpoint.port");
            workgroupCreated = true;

            integrationArn = rds("CreateIntegration")
                    .formParam("IntegrationName", "zetl-" + suffix)
                    .formParam("SourceArn", "arn:aws:rds:us-east-1:000000000000:db:" + dbId)
                    .formParam("TargetArn", namespaceArn)
                    .formParam("DataFilter", "include: appdb.*.*, exclude: appdb.public.left_out")
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<Status>creating</Status>"))
                    .extract().xmlPath().getString("CreateIntegrationResponse.CreateIntegrationResult.IntegrationArn");
            String integrationId = integrationArn.substring(integrationArn.lastIndexOf(':') + 1);

            execute(workgroupPort, "dev", "admin", ADMIN_PASSWORD,
                    "CREATE DATABASE appdb_dw FROM INTEGRATION '" + integrationId + "' DATABASE 'appdb'");

            String arn = integrationArn;
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> rds("DescribeIntegrations").formParam("IntegrationIdentifier", arn)
                            .when().post("/").then().statusCode(200)
                            .body(containsString("<Status>active</Status>")));

            assertThat(query(workgroupPort, "appdb_dw",
                            "SELECT id || ':' || line || ':' || COALESCE(note, '-') || ':' || total || ':' "
                                    + "|| COALESCE(attrs::text, '-') FROM sales.orders ORDER BY id, line"),
                    contains("1:1:first:12.30:{\"rush\": true}", "1:2:-:0.50:-"));
            assertThat(query(workgroupPort, "appdb_dw",
                            "SELECT string_agg(table_schema || '.' || table_name, ',' ORDER BY table_name) "
                                    + "FROM information_schema.tables WHERE table_schema IN ('public', 'sales')"),
                    contains("sales.orders"));
            assertThat(query(workgroupPort, "appdb_dw",
                            "SELECT data_type FROM information_schema.columns "
                                    + "WHERE table_name = 'orders' AND column_name = 'note'"),
                    contains("character varying"));

            SQLException again = assertThrows(SQLException.class,
                    () -> execute(workgroupPort, "dev", "admin", ADMIN_PASSWORD,
                            "CREATE DATABASE appdb_dw2 FROM INTEGRATION '" + integrationId + "' DATABASE 'appdb'"));
            assertThat(again.getSQLState(), equalTo("42P04"));
        } finally {
            if (integrationArn != null) {
                rds("DeleteIntegration").formParam("IntegrationIdentifier", integrationArn).when().post("/");
            }
            if (workgroupCreated) {
                serverless.deleteWorkgroup(workgroup, REGION);
            }
            if (namespaceCreated) {
                serverless.deleteNamespace(namespace, REGION);
            }
            deleteInstance(dbId);
        }
    }

    private static Connection connect(int port, String database, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        // 127.0.0.1 rather than the advertised address, which CI sets to a name the test JVM cannot resolve;
        // the driver itself rather than DriverManager, which may not see it from a test profile's class loader.
        return new Driver().connect("jdbc:postgresql://127.0.0.1:" + port + "/" + database, properties);
    }

    private static void execute(int port, String database, String user, String password, String... statements)
            throws SQLException {
        try (Connection connection = connect(port, database, user, password);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> query(int port, String database, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect(port, database, "admin", ADMIN_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
