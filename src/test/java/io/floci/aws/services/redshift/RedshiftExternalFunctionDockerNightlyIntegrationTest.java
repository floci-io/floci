package io.floci.aws.services.redshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.spectrum.ExternalFunctionRegistry;
import io.floci.aws.services.redshift.spectrum.SpectrumSession;
import io.floci.aws.services.redshiftdata.RedshiftDataService;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.floci.aws.services.redshift.RedshiftConnections.waitForConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Creates, invokes and drops scalar Lambda UDFs through both the PostgreSQL wire and Redshift Data API. */
@QuarkusTest
class RedshiftExternalFunctionDockerNightlyIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/FunctionItRole";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FINAL_STATUSES = Set.of("FINISHED", "FAILED", "ABORTED");

    @Inject
    RedshiftService redshiftService;

    @Inject
    RedshiftDataService redshiftDataService;

    @Inject
    ExternalFunctionRegistry functions;

    @InjectMock
    LambdaService lambdaService;

    private String clusterId;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for Redshift integration tests");
    }

    @BeforeEach
    void stubLambda() {
        when(lambdaService.invokeArn(anyString(), any(byte[].class), any(InvocationType.class)))
                .thenAnswer(invocation -> handleLambda(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
    }

    private static InvokeResult handleLambda(String functionArn, byte[] payload) throws Exception {
        JsonNode request = JSON.readTree(payload);
        JsonNode arguments = request.get("arguments");
        ObjectNode response = JSON.createObjectNode();
        if (functionArn.endsWith(":function:lambda_fail")) {
            response.put("success", false);
            response.put("error_msg", "boom from lambda");
            return okResult(response);
        }
        response.put("success", true);
        response.put("num_records", arguments.size());
        ArrayNode results = response.putArray("results");
        for (JsonNode row : arguments) {
            if (functionArn.endsWith(":function:lambda_double")) {
                if (row.get(0).isNull()) {
                    results.addNull();
                } else {
                    results.add(row.get(0).asLong() * 2);
                }
            } else {
                // lambda_sum / lambda_sum_v2: sum two integers, propagating NULL
                if (row.size() >= 2 && !row.get(0).isNull() && !row.get(1).isNull()) {
                    long bonus = functionArn.endsWith(":function:lambda_sum_v2") ? 100 : 0;
                    results.add(row.get(0).asLong() + row.get(1).asLong() + bonus);
                } else {
                    results.addNull();
                }
            }
        }
        return okResult(response);
    }

    private static InvokeResult okResult(ObjectNode response) {
        InvokeResult result = new InvokeResult();
        result.setStatusCode(200);
        result.setPayload(response.toString().getBytes(StandardCharsets.UTF_8));
        return result;
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
    void createsReplacesCallsAndDropsAFunctionByItsSignature() throws Exception {
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

            try (ResultSet rows = statement.executeQuery("SELECT exfunc_sum(1, 2)")) {
                assertTrue(rows.next());
                assertEquals(103, rows.getInt(1));
            }

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

            try (ResultSet rows = statement.executeQuery("SELECT analytics.exfunc_sum(1, 2)")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
            }

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

    @Test
    @Timeout(120)
    void keepsTransactionTimeArgumentsStableInAutocommit() throws Exception {
        clusterId = "it-fn-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String sameNow = "SELECT exfunc_sum(extract(microseconds FROM now())::int, 0) "
                + "= extract(microseconds FROM now())::int";
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute(create("exfunc_sum", "INT, INT", "lambda_sum"));
            statement.execute(create("exfunc_fail", "INT, INT", "lambda_fail"));
            statement.execute("CREATE TABLE stamped (val INT)");
        }

        try (Connection simple = waitForConnection(
                RedshiftConnections.jdbcUrl(cluster) + "?preferQueryMode=simple", "admin", "Secret123");
             Statement statement = simple.createStatement()) {
            try (ResultSet rows = statement.executeQuery(sameNow)) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
            }
            statement.execute("INSERT INTO stamped SELECT exfunc_sum(extract(microseconds FROM current_timestamp)::int, 1)");
            SQLException failed = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT exfunc_fail(extract(microseconds FROM now())::int, 1)"));
            assertEquals("XX000", failed.getSQLState());
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM stamped")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }

        ObjectNode request = JSON.createObjectNode();
        request.put("ClusterIdentifier", clusterId);
        request.put("Database", "dev");
        request.put("DbUser", "admin");
        request.put("Sql", sameNow);
        String statementId = redshiftDataService.executeStatement(request, "us-east-1").get("Id").asText();
        ObjectNode described = awaitDataStatement(statementId);
        assertEquals("FINISHED", described.get("Status").asText(), described.toString());
        ObjectNode result = redshiftDataService.getStatementResult(JSON.createObjectNode().put("Id", statementId));
        assertTrue(result.get("Records").get(0).get(0).get("booleanValue").asBoolean(), result.toString());
    }

    @Test
    @Timeout(120)
    void evaluatesNestedWhereWriteTransactionExtendedQueryAndDataApi() throws Exception {
        clusterId = "it-fn-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute(create("exfunc_sum", "INT, INT", "lambda_sum"));
            statement.execute("CREATE EXTERNAL FUNCTION exfunc_double(INT) RETURNS INT STABLE "
                    + "LAMBDA 'lambda_double' IAM_ROLE '" + ROLE_ARN + "'");
            statement.execute(create("exfunc_fail", "INT, INT", "lambda_fail"));

            statement.execute("CREATE TABLE nums (a INT, b INT)");
            statement.execute("INSERT INTO nums VALUES (1, 2), (10, 5), (NULL, 4)");

            // Multi-row + WHERE + nested UDF + NULL propagation
            List<Integer> nested = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT exfunc_double(exfunc_sum(a, b)) FROM nums WHERE exfunc_sum(a, b) > 5 ORDER BY a")) {
                while (rows.next()) {
                    nested.add(rows.getInt(1));
                }
            }
            assertEquals(List.of(30), nested);

            // Write statement (INSERT ... SELECT) runs collect in a rolled-back transaction so rows are inserted once
            statement.execute("CREATE TABLE out_sums (val INT)");
            statement.execute("INSERT INTO out_sums SELECT exfunc_sum(a, b) FROM nums");
            List<Integer> inserted = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT val FROM out_sums ORDER BY val NULLS LAST")) {
                while (rows.next()) {
                    Object value = rows.getObject(1);
                    inserted.add(value == null ? null : ((Number) value).intValue());
                }
            }
            assertEquals(Arrays.asList(3, 15, null), inserted);

            // Explicit transaction block preserves open transaction
            connection.setAutoCommit(false);
            try {
                try (ResultSet rows = statement.executeQuery("SELECT exfunc_sum(7, 8)")) {
                    assertTrue(rows.next());
                    assertEquals(15, rows.getInt(1));
                }
                statement.execute("INSERT INTO out_sums VALUES (99)");
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM out_sums WHERE val = 99")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }

            // Extended query protocol with bind parameters (including NULL)
            try (PreparedStatement prepared = connection.prepareStatement("SELECT exfunc_sum(?, ?)")) {
                prepared.setInt(1, 20);
                prepared.setInt(2, 22);
                try (ResultSet rows = prepared.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(42, rows.getInt(1));
                }
                prepared.setNull(1, Types.INTEGER);
                prepared.setInt(2, 5);
                try (ResultSet rows = prepared.executeQuery()) {
                    assertTrue(rows.next());
                    assertNull(rows.getObject(1));
                }
            }

            // Lambda failure surfaces as SQLSTATE XX000
            SQLException failed = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT exfunc_fail(1, 2)"));
            assertEquals("XX000", failed.getSQLState());
            assertTrue(failed.getMessage().contains("boom from lambda"), failed.getMessage());

            // Security: a data-modifying CTE cannot forge rows in pg_temp._floci_udf_cache
            SQLException forged = assertThrows(SQLException.class, () -> statement.executeQuery(
                    "WITH forge AS (INSERT INTO pg_temp._floci_udf_cache VALUES "
                            + "('public.exfunc_sum(integer,integer)', '[\"9\",\"9\"]', '999') RETURNING 1) "
                            + "SELECT exfunc_sum(9, 9) FROM forge"));
            assertEquals("42501", forged.getSQLState());

            // Indirect call through a view after a UDF statement must not reuse stale execute mode or cache
            statement.execute("CREATE VIEW v_indirect_udf AS SELECT exfunc_sum(20, 22) AS val");
            SQLException indirect = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT val FROM v_indirect_udf"));
            assertEquals("0A000", indirect.getSQLState());

            // Multi-statement Simple Query with transaction control is rejected before collect so no NULL rows commit
            try (Connection simpleConn = waitForConnection(
                    RedshiftConnections.jdbcUrl(cluster) + "?preferQueryMode=simple", "admin", "Secret123");
                 Statement simpleStmt = simpleConn.createStatement()) {
                SQLException multi = assertThrows(SQLException.class, () -> simpleStmt.execute(
                        "BEGIN; INSERT INTO out_sums SELECT exfunc_sum(1, 2); COMMIT;"));
                assertEquals("0A000", multi.getSQLState());
            }

            // Volatile argument expression (nextval) is rejected without advancing the sequence
            statement.execute("CREATE SEQUENCE udf_seq START 1");
            SQLException volatileErr = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT exfunc_sum(nextval('udf_seq')::int, 1)"));
            assertEquals("0A000", volatileErr.getSQLState());
            try (ResultSet rows = statement.executeQuery("SELECT nextval('udf_seq')")) {
                assertTrue(rows.next());
                assertEquals(1L, rows.getLong(1));
            }
        }

        // Redshift Data API with named parameters
        ObjectNode request = JSON.createObjectNode();
        request.put("ClusterIdentifier", clusterId);
        request.put("Database", "dev");
        request.put("DbUser", "admin");
        request.put("Sql", "SELECT exfunc_double(exfunc_sum(:a, :b)) AS total");
        ArrayNode params = request.putArray("Parameters");
        params.addObject().put("name", "a").put("value", "4");
        params.addObject().put("name", "b").put("value", "6");

        String statementId = redshiftDataService.executeStatement(request, "us-east-1").get("Id").asText();
        ObjectNode described = awaitDataStatement(statementId);
        assertEquals("FINISHED", described.get("Status").asText(), described.toString());

        ObjectNode resultReq = JSON.createObjectNode().put("Id", statementId);
        ObjectNode result = redshiftDataService.getStatementResult(resultReq);
        assertEquals(20L, result.get("Records").get(0).get(0).get("longValue").asLong());
    }

    private ObjectNode awaitDataStatement(String statementId) {
        ObjectNode describeReq = JSON.createObjectNode().put("Id", statementId);
        return Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> redshiftDataService.describeStatement(describeReq),
                        status -> FINAL_STATUSES.contains(status.get("Status").asText()));
    }
}
