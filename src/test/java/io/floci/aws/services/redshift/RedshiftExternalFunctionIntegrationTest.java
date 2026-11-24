package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.spectrum.ExternalFunctionRegistry;
import io.floci.aws.services.redshift.spectrum.SpectrumSession;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.services.redshift.RedshiftConnections.waitForConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Creates and drops scalar Lambda UDFs through a real cluster connection. */
@QuarkusTest
class RedshiftExternalFunctionIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/FunctionItRole";

    @Inject
    RedshiftService redshiftService;

    @Inject
    ExternalFunctionRegistry functions;

    private String clusterId;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for Redshift integration tests");
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
    }

    private boolean bound(String schema, String name, List<String> argumentTypes) {
        SpectrumSession session = new SpectrumSession(ACCOUNT, ACCOUNT + ":" + clusterId, "dev", List.of(), false);
        return functions.findFunction(session, schema, name, argumentTypes).isPresent();
    }

    private static int stubCount(Statement statement, String name) throws SQLException {
        return stubCount(statement, "public", name);
    }

    private static int stubCount(Statement statement, String schema, String name) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                + "ON n.oid = p.pronamespace WHERE n.nspname = '" + schema + "' AND p.proname = '" + name + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String create(String name, String args, String lambda) {
        return "CREATE EXTERNAL FUNCTION " + name + "(" + args + ") RETURNS INT VOLATILE LAMBDA '" + lambda
                + "' IAM_ROLE '" + ROLE_ARN + "'";
    }

    @Test
    @Timeout(120)
    void createsReplacesAndDropsAFunctionByItsSignature() throws Exception {
        clusterId = "it-fn-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute(create("exfunc_sum", "INT, INT", "lambda_sum"));

            assertTrue(bound("public", "exfunc_sum", List.of("integer", "integer")));
            assertEquals(1, stubCount(statement, "exfunc_sum"));

            SQLException duplicate = assertThrows(SQLException.class,
                    () -> statement.execute(create("exfunc_sum", "INT, INT", "lambda_sum")));
            assertEquals("42723", duplicate.getSQLState());

            statement.execute(create("exfunc_sum", "BIGINT", "lambda_sum_big"));
            assertEquals(2, stubCount(statement, "exfunc_sum"));

            statement.execute(create("exfunc_sum", "INT, INT", "lambda_sum_v2")
                    .replace("CREATE EXTERNAL", "CREATE OR REPLACE EXTERNAL"));
            assertEquals(2, stubCount(statement, "exfunc_sum"));

            SQLException call = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT exfunc_sum(1, 2)"));
            assertEquals("0A000", call.getSQLState());

            statement.execute("DROP FUNCTION exfunc_sum(INT, INT)");

            assertFalse(bound("public", "exfunc_sum", List.of("integer", "integer")));
            assertTrue(bound("public", "exfunc_sum", List.of("bigint")));
            assertEquals(1, stubCount(statement, "exfunc_sum"));

            statement.execute("DROP FUNCTION exfunc_sum(BIGINT)");
            assertFalse(bound("public", "exfunc_sum", List.of("bigint")));
            assertEquals(0, stubCount(statement, "exfunc_sum"));
        }
    }

    @Test
    @Timeout(120)
    void refusesToCreateAFunctionInsideATransaction() throws Exception {
        clusterId = "it-fn-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                SQLException rejected = assertThrows(SQLException.class,
                        () -> statement.execute(create("exfunc_tx", "INT", "lambda_tx")));
                assertEquals("25001", rejected.getSQLState());
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            assertFalse(bound("public", "exfunc_tx", List.of("integer")));
            assertEquals(0, stubCount(statement, "exfunc_tx"));
        }
    }

    @Test
    @Timeout(120)
    void createsCallsAndDropsUnqualifiedFunctionInSessionSearchPath() throws Exception {
        clusterId = "it-fn-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA analytics");
            statement.execute("CREATE SCHEMA other");
            statement.execute("SET search_path TO analytics");

            statement.execute(create("exfunc_sum", "INT, INT", "lambda_sum"));

            assertTrue(bound("analytics", "exfunc_sum", List.of("integer", "integer")));
            assertFalse(bound("public", "exfunc_sum", List.of("integer", "integer")));
            assertEquals(1, stubCount(statement, "analytics", "exfunc_sum"));
            assertEquals(0, stubCount(statement, "public", "exfunc_sum"));

            SQLException qualifiedCall = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT analytics.exfunc_sum(1, 2)"));
            assertEquals("0A000", qualifiedCall.getSQLState());

            statement.execute("DROP FUNCTION analytics.exfunc_sum(INT, INT)");
            assertFalse(bound("analytics", "exfunc_sum", List.of("integer", "integer")));
            assertEquals(0, stubCount(statement, "analytics", "exfunc_sum"));

            statement.execute(create("exfunc_sum", "INT, INT", "lambda_sum"));
            statement.execute("SET search_path TO other, analytics");
            statement.execute("DROP FUNCTION exfunc_sum(INT, INT)");
            assertFalse(bound("analytics", "exfunc_sum", List.of("integer", "integer")));
            assertEquals(0, stubCount(statement, "analytics", "exfunc_sum"));

            statement.execute("SET search_path TO nonexistent_schema");
            SQLException noSchema = assertThrows(SQLException.class,
                    () -> statement.execute(create("exfunc_none", "INT", "lambda_none")));
            assertEquals("3F000", noSchema.getSQLState());
        }
    }
}
