package io.floci.aws.services.rds;

import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import static org.hamcrest.Matchers.not;

/**
 * An RDS zero-ETL integration replicating its source's changes end to end: a PostgreSQL instance whose DB parameter
 * group sets {@code rds.logical_replication} to 1 as the source, a Redshift Serverless workgroup as the target, and
 * inserts, updates and deletes made on the source after the initial load read back from the destination database.
 * Needs Docker.
 */
@QuarkusTest
@Tag("docker")
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class RdsZeroEtlReplicationDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(RdsZeroEtlReplicationDockerNightlyIntegrationTest.class);
    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";
    private static final String ADMIN_PASSWORD = "Secret123";
    private static final String REGION = "us-east-1";
    private static final Duration REPLICATION_WAIT = Duration.ofSeconds(60);

    @Inject
    RedshiftServerlessService serverless;

    @BeforeEach
    void requireDocker() throws InterruptedException {
        assumeDocker();
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void changesMadeOnTheSourceAfterTheLoadReachTheDestinationDatabase() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String group = "zetl-pg-" + suffix;
        String dbId = "zetl-repl-" + suffix;
        String namespace = "zetl-rns-" + suffix;
        String workgroup = "zetl-rwg-" + suffix;
        rds("CreateDBParameterGroup")
                .formParam("DBParameterGroupName", group)
                .formParam("DBParameterGroupFamily", "postgres16")
                .formParam("Description", "zero-ETL source")
        .when().post("/").then().statusCode(200);
        rds("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", group)
                .formParam("Parameters.member.1.ParameterName", "rds.logical_replication")
                .formParam("Parameters.member.1.ParameterValue", "1")
                .formParam("Parameters.member.1.ApplyMethod", "pending-reboot")
        .when().post("/").then().statusCode(200);
        int sourcePort = createPostgresInstance(dbId, MASTER_USER, MASTER_PASSWORD, false, group);
        String integrationArn = null;
        boolean namespaceCreated = false;
        boolean workgroupCreated = false;
        try {
            execute(sourcePort, "appdb", MASTER_USER, MASTER_PASSWORD,
                    "CREATE TABLE orders (id int PRIMARY KEY, total numeric(10,2), note text)",
                    "INSERT INTO orders VALUES (1, 9.99, NULL)",
                    "CREATE TABLE no_key (id int, n int)",
                    "INSERT INTO no_key VALUES (1, 1)");

            Response namespaceResponse = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                    {"namespaceName":"%s","adminUsername":"admin","adminUserPassword":"%s"}
                    """.formatted(namespace, ADMIN_PASSWORD));
            namespaceCreated = namespaceResponse.statusCode() < 300;
            String namespaceArn = namespaceResponse.then().statusCode(200)
                    .extract().path("namespace.namespaceArn");
            Response workgroupResponse = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateWorkgroup", """
                    {"workgroupName":"%s","namespaceName":"%s"}
                    """.formatted(workgroup, namespace));
            workgroupCreated = workgroupResponse.statusCode() < 300;
            int workgroupPort = workgroupResponse.then().statusCode(200)
                    .extract().path("workgroup.endpoint.port");

            integrationArn = rds("CreateIntegration")
                    .formParam("IntegrationName", "zetl-repl-" + suffix)
                    .formParam("SourceArn", "arn:aws:rds:us-east-1:000000000000:db:" + dbId)
                    .formParam("TargetArn", namespaceArn)
                    .formParam("DataFilter", "include: appdb.*.*")
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("CreateIntegrationResponse.CreateIntegrationResult.IntegrationArn");
            String integrationId = integrationArn.substring(integrationArn.lastIndexOf(':') + 1);
            execute(workgroupPort, "dev", "admin", ADMIN_PASSWORD,
                    "CREATE DATABASE appdb_dw FROM INTEGRATION '" + integrationId + "' DATABASE 'appdb'");
            String arn = integrationArn;
            Awaitility.await().atMost(REPLICATION_WAIT).pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        String body = rds("DescribeIntegrations").formParam("IntegrationIdentifier", arn)
                                .when().post("/").then().statusCode(200).extract().asString();
                        assertThat("the load failed: " + body, body,
                                not(containsString("<Status>failed</Status>")));
                        return body.contains("<Status>active</Status>");
                    });
            assertThat(orders(workgroupPort), contains("1:9.99:-"));

            execute(sourcePort, "appdb", MASTER_USER, MASTER_PASSWORD,
                    "INSERT INTO orders VALUES (2, 19.99, 'second'), (3, 1.00, 'gone soon')",
                    "UPDATE orders SET total = 10.50, note = 'changed' WHERE id = 1",
                    "DELETE FROM orders WHERE id = 3",
                    // Only the replicated tables are published, so a table without a key still takes updates.
                    "UPDATE no_key SET n = 2");
            awaitOrders(workgroupPort, "1:10.50:changed", "2:19.99:second");

            execute(sourcePort, "appdb", MASTER_USER, MASTER_PASSWORD, "UPDATE orders SET id = 4 WHERE id = 2");
            awaitOrders(workgroupPort, "1:10.50:changed", "4:19.99:second");

            rds("DeleteIntegration").formParam("IntegrationIdentifier", integrationArn).when().post("/")
                    .then().statusCode(200);
            integrationArn = null;
            Awaitility.await().atMost(REPLICATION_WAIT).pollInterval(Duration.ofMillis(500))
                    .until(() -> query(sourcePort, "appdb", MASTER_USER, MASTER_PASSWORD,
                            "SELECT (SELECT count(*) FROM pg_replication_slots) "
                                    + "+ (SELECT count(*) FROM pg_publication)").equals(List.of("0")));
        } finally {
            String arnToDelete = integrationArn;
            if (arnToDelete != null) {
                cleanUpStep("DeleteIntegration " + arnToDelete, () -> rds("DeleteIntegration")
                        .formParam("IntegrationIdentifier", arnToDelete).when().post("/"));
            }
            if (workgroupCreated) {
                cleanUpStep("deleteWorkgroup " + workgroup, () -> serverless.deleteWorkgroup(workgroup, REGION));
            }
            if (namespaceCreated) {
                cleanUpStep("deleteNamespace " + namespace, () -> serverless.deleteNamespace(namespace, REGION));
            }
            cleanUpStep("deleteInstance " + dbId, () -> deleteInstance(dbId));
            cleanUpStep("DeleteDBParameterGroup " + group, () -> rds("DeleteDBParameterGroup")
                    .formParam("DBParameterGroupName", group).when().post("/"));
        }
    }

    private static List<String> orders(int workgroupPort) throws SQLException {
        return query(workgroupPort, "appdb_dw", "admin", ADMIN_PASSWORD,
                "SELECT id || ':' || total || ':' || COALESCE(note, '-') FROM public.orders ORDER BY id");
    }

    private static void awaitOrders(int workgroupPort, String... expected) {
        Awaitility.await().atMost(REPLICATION_WAIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(orders(workgroupPort), contains(expected)));
    }

    private static void cleanUpStep(String description, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Teardown step failed: {0}", description);
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

    private static List<String> query(int port, String database, String user, String password, String sql)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect(port, database, user, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
