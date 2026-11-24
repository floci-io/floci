package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static io.floci.aws.services.redshift.RedshiftConnections.waitForConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The VARBYTE and SUPER types and their functions, with the examples of the Amazon Redshift documentation. */
@QuarkusTest
class RedshiftVarbyteSuperDockerNightlyIntegrationTest {

    @Inject
    RedshiftService service;

    private String clusterId;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker daemon must be available for Redshift integration tests");
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            service.deleteCluster(clusterId);
        }
    }

    private Connection connect() throws Exception {
        clusterId = "varbyte-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        return waitForConnection(cluster, "admin", "Secret123");
    }

    private static boolean bool(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getBoolean(1);
        }
    }

    private static String string(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getString(1);
        }
    }

    @Test
    void fromVarbyteWritesABinaryValueInEachFormat() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertEquals("6162", string(statement, "SELECT FROM_VARBYTE('ab', 'hex')"));
            assertEquals("01001101", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('4d', 'hex'), 'binary')"));
            assertEquals("a", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('61', 'hex'), 'UTF-8')"));
            assertEquals("a", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('61', 'hex'), 'utf8')"));
            assertEquals("a", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('61', 'hex'), 'utf_8')"));
            assertEquals("YWJj", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('abc', 'utf8'), 'base64')"));
        }
    }

    @Test
    void toVarbyteReadsAStringInEachFormat() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertEquals("6162", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('6162', 'hex'), 'hex')"));
            assertEquals("4d", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('01001101', 'binary'), 'hex')"));
            assertEquals("61", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('a', 'utf8'), 'hex')"));
            // An odd number of hexadecimal digits gets a leading 0.
            assertEquals("04", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('4', 'hex'), 'hex')"));
            assertEquals("abc", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('YWJj', 'base64'), 'utf8')"));
            // Fewer than eight bits are the low bits of one byte.
            assertEquals("05", string(statement, "SELECT FROM_VARBYTE(TO_VARBYTE('101', 'binary'), 'hex')"));
        }
    }

    @Test
    void anUnknownFormatOrADigitThatIsNotBinaryIsRefused() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            SQLException format = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT TO_VARBYTE('61', 'octal')"));
            assertEquals("22023", format.getSQLState());
            SQLException digit = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT TO_VARBYTE('012', 'binary')"));
            assertEquals("22023", digit.getSQLState());
        }
    }

    @Test
    void jsonParseMakesASuperValueThatJsonSerializeAndJsonTypeofRead() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertEquals("[10001,10002,\"abc\"]",
                    string(statement, "SELECT JSON_SERIALIZE(JSON_PARSE('[10001,10002,\"abc\"]'))"));
            assertEquals("array", string(statement, "SELECT JSON_TYPEOF(JSON_PARSE('[10001,10002,\"abc\"]'))"));
            assertEquals("object", string(statement, "SELECT JSON_TYPEOF(JSON_PARSE('{\"a\":1}'))"));
            assertEquals("{\"k\":\"a\\\"b\",\"n\":null,\"o\":{\"x\":[1,2.5,true]}}", string(statement,
                    "SELECT JSON_SERIALIZE(JSON_PARSE('{ \"k\" : \"a\\\"b\", \"n\" : null, \"o\" : {\"x\" : [1, 2.5, true]} }'))"));
            assertEquals("{\"a\":1}",
                    string(statement, "SELECT JSON_SERIALIZE(JSON_PARSE(TO_VARBYTE('{\"a\":1}', 'utf8')))"));
        }
    }

    @Test
    void aNullSuperValueSerializesAsTheJsonStringNull() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertEquals("null", string(statement, "SELECT JSON_SERIALIZE(NULL::super)"));
            assertNull(string(statement, "SELECT JSON_PARSE(NULL::varchar)"));
        }
    }

    @Test
    void jsonParseRefusesWhatIsNotJson() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT JSON_PARSE('This is a string.')"));
        }
    }

    @Test
    void canJsonParseTellsWhatJsonParseWouldTake() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertTrue(bool(statement, "SELECT CAN_JSON_PARSE('[10001,10002,\"abc\"]')"));
            assertFalse(bool(statement, "SELECT CAN_JSON_PARSE('This is a string.')"));
            assertFalse(bool(statement, "SELECT CAN_JSON_PARSE('')"));
            assertNull(string(statement, "SELECT CAN_JSON_PARSE(NULL::varchar)"));
            assertTrue(bool(statement, "SELECT CAN_JSON_PARSE(TO_VARBYTE('{\"a\":1}', 'utf8'))"));
            assertFalse(bool(statement, "SELECT CAN_JSON_PARSE(TO_VARBYTE('ff', 'hex'))"));
            // The documented use: parse only what can be parsed.
            assertEquals("\"This is not JSON.\"", string(statement, "SELECT JSON_SERIALIZE(CASE "
                    + "WHEN CAN_JSON_PARSE('This is a string.') THEN JSON_PARSE('This is a string.') "
                    + "ELSE JSON_PARSE('\"This is not JSON.\"') END)"));
        }
    }

    @Test
    void aTableKeepsSuperAndSizedVarbyteColumns() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE events (id INT, doc SUPER, raw VARBYTE(16), blob VARBINARY(8)) DISTSTYLE EVEN");
            statement.execute("INSERT INTO events VALUES (1, JSON_PARSE('{\"a\":1}'), TO_VARBYTE('ff00', 'hex'), "
                    + "TO_VARBYTE('0a', 'hex'))");
            statement.execute("INSERT INTO events VALUES (2, JSON_PARSE('[1,2]'), NULL, NULL)");

            try (ResultSet rows = statement.executeQuery("SELECT id, JSON_SERIALIZE(doc), FROM_VARBYTE(raw, 'hex'), "
                    + "FROM_VARBYTE(blob, 'hex') FROM events ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("{\"a\":1}", rows.getString(2));
                assertEquals("ff00", rows.getString(3));
                assertEquals("0a", rows.getString(4));
                assertTrue(rows.next());
                assertEquals("[1,2]", rows.getString(2));
                assertNull(rows.getString(3));
                assertFalse(rows.next());
            }
        }
    }
}
