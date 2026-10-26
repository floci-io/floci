package io.github.hectorvent.floci.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.services.dsql.dataplane.DsqlEndpoint;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLWarning;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.services.dsql.DsqlWireClient.bind;
import static io.github.hectorvent.floci.services.dsql.DsqlWireClient.close;
import static io.github.hectorvent.floci.services.dsql.DsqlWireClient.execute;
import static io.github.hectorvent.floci.services.dsql.DsqlWireClient.parse;
import static io.github.hectorvent.floci.services.dsql.DsqlWireClient.query;
import static io.github.hectorvent.floci.services.dsql.DsqlWireClient.sync;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aurora DSQL's data plane against a real PostgreSQL container: a client connects to the cluster
 * endpoint with TLS and an IAM token, with the refusals measured on AWS (eu-west-1, 2026-10-16).
 */
@QuarkusTest
@TestProfile(DsqlDatabasesProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlDataPlaneIntegrationTest {

    private static final String ACCOUNT = DsqlClusterFixture.ACCOUNT;
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/service/app-role";

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @Inject
    IamService iamService;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private DsqlClusterFixture cluster;

    @BeforeAll
    void createCluster() {
        cluster = new DsqlClusterFixture(httpPort, endpoint);
        cluster.create(dockerClient);
    }

    @AfterAll
    void deleteCluster() {
        cluster.delete();
    }

    @Test
    void adminConnectsWithAnAdminTokenToDsqlsDatabase() throws Exception {
        try (Connection connection = admin();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_user, current_setting('transaction_isolation'),"
                     + " (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'public'),"
                     + " sys.iam_session_user(), length(sys.current_session_id())")) {
            assertTrue(row.next());
            assertEquals("admin", row.getString(1));
            assertEquals("repeatable read", row.getString(2));
            assertEquals("admin", row.getString(3));
            assertEquals("arn:aws:iam::" + ACCOUNT + ":root", row.getString(4));
            assertEquals(26, row.getInt(5));
        }
    }

    @Test
    void aConnectionIsRefusedAsDsqlRefusesIt() throws Exception {
        PSQLException plain = assertThrows(PSQLException.class, () -> {
            Properties properties = cluster.properties("admin", cluster.adminToken());
            properties.setProperty("sslmode", "disable");
            DriverManager.getConnection(cluster.url(), properties).close();
        });
        assertEquals("unable to accept connection, SSL is mandatory. AWS Authentication is required.",
                plain.getServerErrorMessage().getMessage());
        assertEquals("08P01", plain.getSQLState());
        assertTrue(plain.getServerErrorMessage().getDetail().startsWith("Session Id: "));

        // Measured: a connection that names no server is refused once its password is in, whatever it is.
        for (String password : List.of(cluster.adminToken(), "not-a-token")) {
            PSQLException unnamed = assertThrows(PSQLException.class, () -> {
                Properties properties = cluster.properties("admin", password);
                properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
                DriverManager.getConnection(cluster.url(), properties).close();
            });
            assertEquals("unable to accept connection, sni was not received",
                    unnamed.getServerErrorMessage().getMessage());
            assertEquals("08P01", unnamed.getSQLState());
            assertTrue(unnamed.getServerErrorMessage().getDetail().startsWith("Session Id: "));
        }

        PSQLException wrongAction = assertThrows(PSQLException.class, () ->
                cluster.connect("admin", cluster.token("DbConnect", "test", "test", Instant.now(), 900, null)).close());
        assertEquals("unable to accept connection, access denied", wrongAction.getServerErrorMessage().getMessage());
        assertEquals("08006", wrongAction.getSQLState());
        assertTrue(wrongAction.getServerErrorMessage().getDetail().startsWith("Session Id: "));
        assertEquals("Wrong user to action mapping. user: admin, action: DbConnect",
                wrongAction.getServerErrorMessage().getHint());

        PSQLException expired = assertThrows(PSQLException.class, () -> cluster.connect("admin",
                cluster.token("DbConnectAdmin", "test", "test", Instant.now().minusSeconds(120), 60, null)).close());
        assertTrue(expired.getServerErrorMessage().getHint().startsWith("Signature expired: "),
                expired.getServerErrorMessage().getHint());

        PSQLException garbage = assertThrows(PSQLException.class, () ->
                cluster.connect("admin", "not-a-token").close());
        assertEquals("unable to accept connection, access denied", garbage.getServerErrorMessage().getMessage());
        assertEquals(null, garbage.getServerErrorMessage().getHint());

        PSQLException otherDatabase = assertThrows(PSQLException.class, () -> {
            Properties properties = cluster.properties("admin", cluster.adminToken());
            DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + endpoint.port() + "/other", properties)
                    .close();
        });
        assertEquals("database \"other\" is not currently accepting connections",
                otherDatabase.getServerErrorMessage().getMessage());
        assertEquals("55000", otherDatabase.getSQLState());
    }

    /**
     * DSQL's limits (measured): a startup packet of 10,000 bytes or more, or under 8, and a client
     * message over 10 MiB or under 4, are refused as soon as their length arrives, before signing in
     * and after, and the connection closes.
     */
    @Test
    void aPacketOutsideDsqlsLimitsIsRefusedAsItsLengthArrives() throws Exception {
        try (DsqlWireClient client = DsqlWireClient.tls(endpoint.port(), cluster.host())) {
            client.send(startupOfLength(9_999));
            PostgresWire.Message password = client.next();
            assertEquals('R', password.type());
            assertEquals(3, PostgresWire.int32(password.body(), 0));
        }
        try (DsqlWireClient client = DsqlWireClient.tls(endpoint.port(), cluster.host())) {
            client.send(startupOfLength(10_000));
            assertRefused(client, "invalid length of startup packet");
        }
        try (DsqlWireClient client = DsqlWireClient.tls(endpoint.port(), cluster.host())) {
            client.send(PostgresWire.int32(7), new byte[3]);
            assertRefused(client, "invalid length of startup packet");
        }
        try (DsqlWireClient client = DsqlWireClient.tls(endpoint.port(), cluster.host())) {
            client.send(startupOfLength(100));
            assertEquals('R', client.next().type());
            client.send(header('p', 10 * 1024 * 1024 + 1));
            assertRefused(client, "invalid message length");
        }
        for (int length : new int[] {10 * 1024 * 1024 + 1, 3}) {
            try (DsqlWireClient client = cluster.wireClient("admin", cluster.adminToken())) {
                client.send(header('Q', length));
                assertRefused(client, "invalid message length");
            }
        }
    }

    /**
     * Before TLS, DSQL reads no more of a packet than its length and code (measured): a startup
     * packet is refused as not TLS whatever length it claims, after a GSSENCRequest too, and one
     * under 8 bytes ends the connection without a word. A length whose first byte is TLS's is read
     * as TLS (see {@link #tlsWithNoSslRequestFirstIsTakenForTheAlpnProtocolPostgresql}).
     */
    @Test
    void aPacketBeforeTlsIsRefusedAsNotTlsWhateverItsLength() throws Exception {
        for (int length : new int[] {9_999, 10_000, 20_000}) {
            for (boolean gssFirst : new boolean[] {false, true}) {
                try (Socket socket = new Socket("127.0.0.1", endpoint.port())) {
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    if (gssFirst) {
                        out.write(PostgresWire.int32(8));
                        out.write(PostgresWire.int32(PostgresWire.GSSENC_REQUEST));
                        assertEquals('N', in.read());
                    }
                    out.write(startupOfLength(length));
                    PostgresWire.Message error = PostgresWire.read(in, Integer.MAX_VALUE, "message");
                    Map<Character, String> fields = PostgresWire.parseFields(error.body(), 0);
                    assertEquals("08P01", fields.get('C'), length + (gssFirst ? " after GSSENCRequest" : ""));
                    assertEquals("unable to accept connection, SSL is mandatory. AWS Authentication is required.",
                            fields.get('M'));
                }
            }
        }
        try (Socket socket = new Socket("127.0.0.1", endpoint.port())) {
            socket.getOutputStream().write(PostgresWire.int32(7));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    /**
     * DSQL takes TLS started at once, with no SSLRequest, from a client offering the ALPN protocol
     * postgresql, as libpq's and pgjdbc's direct negotiation do, and picks postgresql whenever a
     * client offers it; a direct handshake without it completes and the connection closes without a
     * word. A connection is told apart by its first byte alone: a startup packet claiming a length
     * that starts with 0x16 is read as TLS, and answered with a TLS alert (measured).
     */
    @Test
    void tlsWithNoSslRequestFirstIsTakenForTheAlpnProtocolPostgresql() throws Exception {
        Properties properties = cluster.properties("admin", cluster.adminToken());
        properties.setProperty("sslNegotiation", "direct");
        try (Connection connection = DriverManager.getConnection(cluster.url(), properties);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_user")) {
            assertTrue(row.next());
            assertEquals("admin", row.getString(1));
        }
        try (DsqlWireClient client = DsqlWireClient.direct(endpoint.port(), cluster.host(), "http/1.1", "postgresql")) {
            assertEquals("postgresql", client.applicationProtocol());
            client.signIn("admin", cluster.adminToken());
            client.send(query("SELECT 1"));
            assertEquals(List.of("SELECT 1"), client.untilReady());
        }
        for (String[] alpn : new String[][] {{}, {"http/1.1"}}) {
            try (DsqlWireClient client = DsqlWireClient.direct(endpoint.port(), cluster.host(), alpn)) {
                assertEquals("", client.applicationProtocol(), List.of(alpn).toString());
                // The endpoint's 30 s handshake timeout would close it too, later: only a close at once is this quick.
                assertThrows(EOFException.class, () -> assertTimeoutPreemptively(Duration.ofSeconds(5), client::next),
                        List.of(alpn).toString());
            }
        }
        try (DsqlWireClient client = DsqlWireClient.tls(endpoint.port(), cluster.host(), "postgresql")) {
            assertEquals("postgresql", client.applicationProtocol());
            client.signIn("admin", cluster.adminToken());
        }
        try (DsqlWireClient client = DsqlWireClient.tls(endpoint.port(), cluster.host(), "http/1.1")) {
            assertEquals("", client.applicationProtocol());
            client.signIn("admin", cluster.adminToken());
        }
        try (Socket socket = new Socket("127.0.0.1", endpoint.port())) {
            byte[] startup = startupOfLength(100);
            System.arraycopy(PostgresWire.int32(0x16000000), 0, startup, 0, 4);
            socket.getOutputStream().write(startup);
            socket.setSoTimeout(5_000);
            byte[] answer = socket.getInputStream().readAllBytes();
            assertEquals(0x15, answer[0], "a TLS alert record, not a PostgreSQL message");
        }
    }

    @Test
    void aMessageOfTenMibIsTaken() throws Exception {
        try (DsqlWireClient client = cluster.wireClient("admin", cluster.adminToken())) {
            String padding = "x".repeat(10 * 1024 * 1024 - 4 - 1 - "SELECT 1 /**/".length());
            client.send(query("SELECT 1 /*" + padding + "*/"));
            assertEquals(List.of("SELECT 1"), client.untilReady());
            client.send(query("SELECT 2"));
            assertEquals(List.of("SELECT 1"), client.untilReady());
        }
    }

    @Test
    void aRoleLogsInOnlyForTheIamRoleMappedToIt() throws Exception {
        String accessKeyId = "ASIA" + Long.toString(System.nanoTime(), 36).toUpperCase();
        String secret = "app+session/secret=key";
        String sessionToken = "IQoJb3JpZ2luX2VjEJr+app/role+session==";
        iamService.registerLambdaExecutionRoleSession(ACCOUNT, accessKeyId, secret, sessionToken, ROLE_ARN);
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE app_role WITH LOGIN");

            PSQLException unmapped = assertThrows(PSQLException.class, () -> cluster.connect("app_role",
                    cluster.token("DbConnect", accessKeyId, secret, Instant.now(), 900, sessionToken)).close());
            assertEquals("unable to accept connection, access denied", unmapped.getServerErrorMessage().getMessage());
            assertEquals("28000", unmapped.getSQLState());

            statement.execute("AWS IAM GRANT app_role TO '" + ROLE_ARN + "'");
            statement.execute("CREATE TABLE owned_by_admin (id int PRIMARY KEY, n int)");
            statement.executeQuery("CREATE INDEX ASYNC owned_by_admin_n ON owned_by_admin (n)").close();
            try (Connection app = cluster.connect("app_role",
                         cluster.token("DbConnect", accessKeyId, secret, Instant.now(), 900, sessionToken));
                 Statement query = app.createStatement()) {
                try (ResultSet row = query.executeQuery("SELECT current_user, sys.iam_session_user()")) {
                    assertTrue(row.next());
                    assertEquals("app_role", row.getString(1));
                    assertEquals(ROLE_ARN, row.getString(2));
                }
                PSQLException notOwner = assertThrows(PSQLException.class, () -> query.executeQuery(
                        "SELECT sys._invalidate_index('public.owned_by_admin_n'::regclass)").close());
                assertEquals("42501", notOwner.getSQLState());
            }

            statement.execute("AWS IAM GRANT app_role TO '" + ROLE_ARN + "'");
            assertEquals("IAM role \"" + ROLE_ARN + "\" has already been granted access to role \"app_role\" by role"
                    + " \"admin\"", ((PSQLWarning) statement.getWarnings()).getServerErrorMessage().getMessage());
            PSQLException mapped = assertThrows(PSQLException.class, () -> statement.execute("DROP ROLE app_role"));
            assertEquals("2BP01", mapped.getSQLState());
            assertEquals("role \"app_role\" cannot be dropped because some objects depend on it",
                    mapped.getServerErrorMessage().getMessage());
            assertEquals("privileges for trust relationship of role app_role with arn " + ROLE_ARN,
                    mapped.getServerErrorMessage().getDetail());
            assertNull(mapped.getServerErrorMessage().getWhere());

            PSQLException adminAction = assertThrows(PSQLException.class, () -> cluster.connect("app_role",
                    cluster.token("DbConnectAdmin", accessKeyId, secret, Instant.now(), 900, sessionToken)).close());
            assertEquals("Wrong user to action mapping. user: app_role, action: DbConnectAdmin",
                    adminAction.getServerErrorMessage().getHint());

            PSQLException superuser = assertThrows(PSQLException.class, () ->
                    statement.execute("AWS IAM GRANT dbowner TO '" + ROLE_ARN + "'"));
            assertEquals("0LP01", superuser.getSQLState());
            assertEquals("cannot establish trust with role \"dbowner\"", superuser.getServerErrorMessage().getMessage());
            PSQLException itself = assertThrows(PSQLException.class, () ->
                    statement.execute("AWS IAM GRANT admin TO '" + ROLE_ARN + "'"));
            assertEquals("42501", itself.getSQLState());
            assertEquals("Only roles with the ADMIN option on role \"admin\" may grant IAM trust to this role.",
                    itself.getServerErrorMessage().getDetail());
            assertThrows(PSQLException.class, () -> cluster.connect("dbowner",
                    cluster.token("DbConnect", accessKeyId, secret, Instant.now(), 900, sessionToken)).close());

            statement.execute("AWS IAM REVOKE app_role FROM '" + ROLE_ARN + "'");
            assertThrows(PSQLException.class, () -> cluster.connect("app_role",
                    cluster.token("DbConnect", accessKeyId, secret, Instant.now(), 900, sessionToken)).close());
            statement.execute("DROP ROLE app_role");
        } finally {
            iamService.unregisterSession(ACCOUNT, accessKeyId);
        }
    }

    @Test
    void anIamUserLogsInForTheRoleMappedToIt() throws Exception {
        String userName = "dsql-" + System.nanoTime();
        EnforcementFixtures.UserCredentials user = EnforcementFixtures.createUser(cleanup, httpPort,
                EnforcementFixtures.auth(ACCOUNT, "iam"), userName);
        String userArn = "arn:aws:iam::" + ACCOUNT + ":user/" + userName;
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE user_role WITH LOGIN");
            statement.execute("AWS IAM GRANT user_role TO '" + userArn + "'");
            try (Connection app = cluster.connect("user_role", cluster.token("DbConnect", user.accessKeyId(),
                         user.secretAccessKey(), Instant.now(), 900, null));
                 Statement query = app.createStatement();
                 ResultSet row = query.executeQuery("SELECT current_user, sys.iam_session_user()")) {
                assertTrue(row.next());
                assertEquals("user_role", row.getString(1));
                assertEquals(userArn, row.getString(2));
            }

            PSQLException group = assertThrows(PSQLException.class, () ->
                    statement.execute("AWS IAM GRANT user_role TO 'arn:aws:iam::" + ACCOUNT + ":group/g'"));
            assertEquals("0P000", group.getSQLState());
            statement.execute("AWS IAM REVOKE user_role FROM '" + userArn + "'");
            statement.execute("DROP ROLE user_role");
        }
    }

    @Test
    void nothingASessionRunsChangesWhoItIs() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION spoof() RETURNS text LANGUAGE sql AS"
                    + " $$ SELECT set_config('dsql.iam_session_user', 'spoofed', false) $$");
            statement.execute("SELECT spoof()");
            try (ResultSet row = statement.executeQuery("SELECT sys.iam_session_user()")) {
                assertTrue(row.next());
                assertEquals("arn:aws:iam::" + ACCOUNT + ":root", row.getString(1));
            }
            PSQLException written = assertThrows(PSQLException.class, () ->
                    statement.execute("UPDATE sys.session_identity SET iam_session_user = 'spoofed'"));
            assertEquals("42501", written.getSQLState());
            statement.execute("DROP FUNCTION spoof()");
        }
    }

    @Test
    void aRoleDroppedInsideAFunctionTakesItsMappingsWithIt() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE dropped_role");
            statement.execute("AWS IAM GRANT dropped_role TO '" + ROLE_ARN + "'");
            statement.execute("CREATE FUNCTION drop_it() RETURNS void LANGUAGE sql AS 'DROP ROLE dropped_role'");
            statement.execute("SELECT drop_it()");
            statement.execute("CREATE ROLE dropped_role");

            String mappings = "SELECT count(*) FROM sys.iam_pg_role_mappings WHERE arn = '" + ROLE_ARN + "'"
                    + " AND pg_role_name = 'dropped_role'";
            try (ResultSet row = statement.executeQuery(mappings)) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
            PSQLException none = assertThrows(PSQLException.class, () ->
                    statement.execute("AWS IAM REVOKE dropped_role FROM '" + ROLE_ARN + "'"));
            assertEquals("42704", none.getSQLState());
            statement.execute("AWS IAM GRANT dropped_role TO '" + ROLE_ARN + "'");
            try (ResultSet row = statement.executeQuery(mappings)) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
            statement.execute("AWS IAM REVOKE dropped_role FROM '" + ROLE_ARN + "'");
            statement.execute("DROP ROLE dropped_role");
            statement.execute("DROP FUNCTION drop_it()");
        }
    }

    /**
     * What a pipelining client sends before reading any answer: each statement shows its own command
     * tag, and a Parse the database refuses leaves the statement it named as it was.
     */
    @Test
    void pipelinedStatementsShowTheirOwnCommandTags() throws Exception {
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE pipelined_role");
        }
        String grant = "AWS IAM GRANT pipelined_role TO '" + ROLE_ARN + "'";
        String revoke = "AWS IAM REVOKE pipelined_role FROM '" + ROLE_ARN + "'";
        try (DsqlWireClient client = cluster.wireClient("admin", cluster.adminToken())) {
            client.send(query("SELECT 1"), query(grant), query(revoke + "; SELECT 2"));
            assertEquals(List.of("SELECT 1"), client.untilReady());
            assertEquals(List.of("AWS IAM GRANT"), client.untilReady());
            assertEquals(List.of("AWS IAM REVOKE", "SELECT 1"), client.untilReady());

            client.send(parse("s1", "SELECT 1"), sync(), parse("s1", grant), sync(),
                    bind("s1"), execute(), sync());
            assertEquals(List.of(), client.untilReady());
            assertEquals(List.of("ERROR 42P05"), client.untilReady());
            assertEquals(List.of("SELECT 1"), client.untilReady());

            // Each grant is DDL, of which DSQL runs one per transaction, so each has a Sync of its own.
            client.send(close("s1"), parse("s1", grant), bind("s1"), execute(), sync(), parse("s2", revoke),
                    bind("s2"), execute(), sync(), query("SELECT 3"));
            assertEquals(List.of("AWS IAM GRANT"), client.untilReady());
            assertEquals(List.of("AWS IAM REVOKE"), client.untilReady());
            assertEquals(List.of("SELECT 1"), client.untilReady());
        }
        try (Connection admin = admin(); Statement statement = admin.createStatement()) {
            statement.execute("DROP ROLE pipelined_role");
        }
    }

    /** Floci's own index function runs one index statement and nothing else, whoever calls it. */
    @Test
    void theAsyncIndexFunctionRunsNothingButAnIndex() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE direct_call (id int PRIMARY KEY)");
            statement.execute("INSERT INTO direct_call VALUES (1)");
            for (String sql : List.of("TRUNCATE direct_call",
                    "CREATE INDEX x ON direct_call (id); TRUNCATE direct_call")) {
                PSQLException refused = assertThrows(PSQLException.class, () -> statement.execute(
                        "SELECT * FROM sys.create_index_async('" + sql + "', 'x', 'direct_call')"), sql);
                assertEquals("42883", refused.getSQLState(), sql);
            }
            try (ResultSet row = statement.executeQuery("SELECT count(*) FROM direct_call")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
            statement.execute("DROP TABLE direct_call");
        }
    }

    @Test
    void dsqlsParametersAreReservedAsDsqlReservesThem() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            for (String sql : List.of("SET dsql.x = 1", "RESET dsql.x", "SELECT set_config('dsql.x', '1', false)",
                    "ALTER ROLE admin SET dsql.x = 1",
                    "CREATE FUNCTION reserved() RETURNS int LANGUAGE sql SET dsql.x = 1 AS 'SELECT 1'")) {
                PSQLException reserved = assertThrows(PSQLException.class, () -> statement.execute(sql), sql);
                assertEquals("42602", reserved.getSQLState(), sql);
                assertEquals("invalid configuration parameter name \"dsql.x\"",
                        reserved.getServerErrorMessage().getMessage(), sql);
                assertEquals("\"dsql\" is a reserved prefix.", reserved.getServerErrorMessage().getDetail(), sql);
            }
            PSQLException shown = assertThrows(PSQLException.class, () -> statement.execute("SHOW dsql.session_id"));
            assertEquals("42704", shown.getSQLState());
            assertEquals("unrecognized configuration parameter \"dsql.session_id\"",
                    shown.getServerErrorMessage().getMessage());

            PSQLException custom = assertThrows(PSQLException.class, () ->
                    statement.execute("SELECT set_config('myapp.foo', 'x', false)"));
            assertEquals("0A000", custom.getSQLState());
            assertEquals("setting configuration parameter \"myapp.foo\" not supported",
                    custom.getServerErrorMessage().getMessage());
            statement.execute("SET myapp.foo = 'x'");
        }
    }

    @Test
    void aStartupSettingIsReadAsDsqlReadsIt() throws Exception {
        for (String name : List.of("dsql.iam_session_user", "myapp.foo")) {
            Properties custom = cluster.properties("admin", cluster.adminToken());
            custom.setProperty("options", "-c " + name + "=x");
            PSQLException refused = assertThrows(PSQLException.class, () ->
                    DriverManager.getConnection(cluster.url(), custom).close());
            assertEquals("42704", refused.getSQLState());
            assertEquals("unrecognized configuration parameter \"" + name + "\"",
                    refused.getServerErrorMessage().getMessage());
            assertTrue(refused.getServerErrorMessage().getDetail().startsWith("Session Id: "));
        }

        Properties unknown = cluster.properties("admin", cluster.adminToken());
        unknown.setProperty("options", "-c nosuch_parameter=1");
        PSQLException fromTheDatabase = assertThrows(PSQLException.class, () ->
                DriverManager.getConnection(cluster.url(), unknown).close());
        assertEquals("42704", fromTheDatabase.getSQLState());
        assertEquals("unrecognized configuration parameter \"nosuch_parameter\"",
                fromTheDatabase.getServerErrorMessage().getMessage());
        assertTrue(fromTheDatabase.getServerErrorMessage().getDetail().startsWith("Session Id: "));
        assertEquals(null, fromTheDatabase.getServerErrorMessage().getFile());

        Properties ignored = cluster.properties("admin", cluster.adminToken());
        ignored.setProperty("options", "--dsql.iam_session_user=spoofed --search_path=elsewhere"
                + " -c search_path=first -c search_path=second");
        try (Connection connection = DriverManager.getConnection(cluster.url(), ignored);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_setting('search_path'), sys.iam_session_user()")) {
            assertTrue(row.next());
            assertEquals("first", row.getString(1));
            assertEquals("arn:aws:iam::" + ACCOUNT + ":root", row.getString(2));
        }
    }

    @Test
    void theDatabaseSpeaksDsqlsDialect() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA dialect");
            statement.execute("CREATE TABLE dialect.t (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), n int)");

            String jobId;
            try (ResultSet job = statement.executeQuery("CREATE INDEX ASYNC t_n ON dialect.t (n)")) {
                assertTrue(job.next());
                jobId = job.getString("job_id");
            }
            assertEquals(26, jobId.length());
            try (ResultSet waited = statement.executeQuery("CALL sys.wait_for_job('" + jobId + "')")) {
                assertTrue(waited.next());
                assertTrue(waited.getBoolean(1));
            }

            SQLException plainIndex = assertThrows(SQLException.class, () ->
                    statement.execute("CREATE INDEX t_plain ON dialect.t (n)"));
            assertEquals("0A000", plainIndex.getSQLState());
            assertEquals("unsupported mode. please use CREATE INDEX ASYNC.",
                    ((PSQLException) plainIndex).getServerErrorMessage().getMessage());
            assertEquals(null, ((PSQLException) plainIndex).getServerErrorMessage().getWhere());

            SQLException truncate = assertThrows(SQLException.class, () -> statement.execute("TRUNCATE dialect.t"));
            assertEquals("0A000", truncate.getSQLState());
            assertEquals("unsupported statement: Truncate",
                    ((PSQLException) truncate).getServerErrorMessage().getMessage());

            connection.setAutoCommit(false);
            statement.execute("CREATE TABLE dialect.a (id int PRIMARY KEY)");
            SQLException twoDdl = assertThrows(SQLException.class, () ->
                    statement.execute("CREATE TABLE dialect.b (id int PRIMARY KEY)"));
            assertEquals("multiple ddl statements not supported in a transaction",
                    ((PSQLException) twoDdl).getServerErrorMessage().getMessage());
            connection.rollback();

            SQLException rowLimit = assertThrows(SQLException.class, () ->
                    statement.execute("INSERT INTO dialect.t (n) SELECT g FROM generate_series(1, 3001) g"));
            assertEquals("54000", rowLimit.getSQLState());
            assertEquals("transaction row limit exceeded",
                    ((PSQLException) rowLimit).getServerErrorMessage().getMessage());
            connection.rollback();
        }
    }

    /**
     * Measured on DSQL (2026-10-21): table copies refused, every key's type checked, role statements
     * counted as DDL.
     */
    @Test
    void tableCopiesKeysAndRolesAreReadAsDsqlReadsThem() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            for (String sql : List.of("CREATE TABLE copied AS SELECT 1 AS n", "SELECT 1 AS n INTO copied",
                    "CREATE MATERIALIZED VIEW copied AS SELECT 1 AS n")) {
                PSQLException refused = assertThrows(PSQLException.class, () -> statement.execute(sql), sql);
                assertEquals("unsupported statement: CreateTableAs", refused.getServerErrorMessage().getMessage(), sql);
            }
            statement.execute("CREATE TABLE keyed (id int PRIMARY KEY, j jsonb)");
            PSQLException expression = assertThrows(PSQLException.class, () ->
                    statement.executeQuery("CREATE INDEX ASYNC keyed_j ON keyed ((j -> 'a'))"));
            assertEquals("datatype jsonb is not supported in a key", expression.getServerErrorMessage().getMessage());
            statement.execute("CREATE DOMAIN jdomain AS jsonb");
            PSQLException domain = assertThrows(PSQLException.class, () ->
                    statement.execute("CREATE TABLE domain_keyed (k jdomain PRIMARY KEY)"));
            assertEquals("datatype jdomain is not supported in a key", domain.getServerErrorMessage().getMessage());

            statement.execute("CREATE ROLE ddl_role");
            connection.setAutoCommit(false);
            statement.execute("ALTER ROLE ddl_role WITH LOGIN");
            PSQLException withDml = assertThrows(PSQLException.class, () ->
                    statement.execute("INSERT INTO keyed (id) VALUES (1)"));
            assertEquals("ddl and dml are not supported in the same transaction",
                    withDml.getServerErrorMessage().getMessage());
            assertEquals("ddl count: 1, dml count: 1", withDml.getServerErrorMessage().getDetail());
            connection.rollback();
            statement.execute("GRANT ddl_role TO admin");
            PSQLException twoDdl = assertThrows(PSQLException.class, () ->
                    statement.execute("CREATE TABLE keyed_too (id int PRIMARY KEY)"));
            assertEquals("multiple ddl statements not supported in a transaction",
                    twoDdl.getServerErrorMessage().getMessage());
            connection.rollback();
            connection.setAutoCommit(true);
            statement.execute("DROP ROLE ddl_role");
            statement.execute("DROP TABLE keyed");
            statement.execute("DROP DOMAIN jdomain");
        }
    }

    /**
     * The limits hold whatever a client does to its own table or to its session's settings: DSQL
     * has neither the triggers nor the settings Floci keeps them with.
     */
    @Test
    void theLimitsHoldWhateverTheClientChanges() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE guarded (id int PRIMARY KEY)");
            statement.execute("ALTER TABLE guarded DISABLE TRIGGER USER");
            statement.execute("ALTER TABLE guarded DISABLE TRIGGER ALL");
            PSQLException dropped = assertThrows(PSQLException.class, () ->
                    statement.execute("DROP TRIGGER dsql_rows_inserted ON guarded"));
            assertEquals("42704", dropped.getSQLState());
            assertEquals("trigger \"dsql_rows_inserted\" for table \"guarded\" does not exist",
                    dropped.getServerErrorMessage().getMessage());
            assertEquals(null, dropped.getServerErrorMessage().getWhere());

            statement.execute("SELECT set_config(chr(100) || 'sql.async_index', 'on', false)");
            PSQLException plainIndex = assertThrows(PSQLException.class, () ->
                    statement.execute("CREATE INDEX guarded_plain ON guarded (id)"));
            assertEquals("unsupported mode. please use CREATE INDEX ASYNC.",
                    plainIndex.getServerErrorMessage().getMessage());

            connection.setAutoCommit(false);
            statement.execute("SELECT set_config(chr(100) || 'sql.row_count', '-100000', true)");
            PSQLException rowLimit = assertThrows(PSQLException.class, () ->
                    statement.execute("INSERT INTO guarded SELECT g FROM generate_series(1, 3001) g"));
            assertEquals("54000", rowLimit.getSQLState());
            connection.rollback();
            statement.execute("SELECT set_config(chr(100) || 'sql.internal', 'on', true)");
            PSQLException trigger = assertThrows(PSQLException.class, () -> statement.execute(
                    "CREATE TRIGGER mine AFTER INSERT ON guarded FOR EACH STATEMENT EXECUTE FUNCTION sys._count_dml()"));
            assertEquals("CREATE TRIGGER not supported", trigger.getServerErrorMessage().getMessage());
            connection.rollback();
        }
    }

    @Test
    void anUnnamedUniqueIndexOverDuplicatesFailsItsJob() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE dupes (id int PRIMARY KEY, n int)");
            statement.execute("INSERT INTO dupes VALUES (1, 7), (2, 7)");
            String jobId;
            try (ResultSet job = statement.executeQuery("CREATE UNIQUE INDEX ASYNC ON dupes (n)")) {
                assertTrue(job.next());
                jobId = job.getString("job_id");
            }
            try (ResultSet job = statement.executeQuery(
                    "SELECT status, details, object_name FROM sys.jobs WHERE job_id = '" + jobId + "'")) {
                assertTrue(job.next());
                assertEquals("failed", job.getString(1));
                assertEquals("found duplicate key(s) while validating index uniqueness", job.getString(2));
                assertEquals("public.dupes_n_idx", job.getString(3));
            }

            PSQLException cache = assertThrows(PSQLException.class, () ->
                    statement.execute("CREATE SEQUENCE huge CACHE 99999999999999999999"));
            assertEquals("22003", cache.getSQLState());
            assertEquals("value \"99999999999999999999\" is out of range for type bigint",
                    cache.getServerErrorMessage().getMessage());
        }
    }

    @Test
    void aForeignKeyIsEnforcedAndItsCascadeCountsTowardTheRowLimit() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fk_parent (id int PRIMARY KEY)");
            statement.execute("CREATE TABLE fk_child (id int PRIMARY KEY,"
                    + " parent_id int REFERENCES fk_parent (id) ON DELETE CASCADE)");

            PSQLException orphan = assertThrows(PSQLException.class, () ->
                    statement.execute("INSERT INTO fk_child VALUES (1, 999)"));
            assertEquals("23503", orphan.getSQLState());
            assertEquals(null, orphan.getServerErrorMessage().getFile());

            statement.execute("INSERT INTO fk_parent VALUES (1)");
            statement.execute("INSERT INTO fk_child SELECT g, 1 FROM generate_series(1, 1500) g");
            statement.execute("INSERT INTO fk_child SELECT g, 1 FROM generate_series(1501, 3000) g");
            PSQLException tooMany = assertThrows(PSQLException.class, () ->
                    statement.execute("DELETE FROM fk_parent WHERE id = 1"));

            assertEquals("54000", tooMany.getSQLState());
            assertEquals("transaction row limit exceeded", tooMany.getServerErrorMessage().getMessage());
            assertEquals(null, tooMany.getServerErrorMessage().getWhere());
            assertEquals(null, tooMany.getServerErrorMessage().getFile());
        }
    }

    @Test
    void aWriteConflictIsDsqlsOc000() throws Exception {
        try (Connection setup = admin(); Statement statement = setup.createStatement()) {
            statement.execute("CREATE TABLE conflict (id int PRIMARY KEY, n int)");
            statement.execute("INSERT INTO conflict VALUES (1, 0)");
        }
        try (Connection first = admin(); Connection second = admin()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try (Statement firstStatement = first.createStatement(); Statement secondStatement = second.createStatement()) {
                secondStatement.executeQuery("SELECT n FROM conflict WHERE id = 1").close();
                firstStatement.executeUpdate("UPDATE conflict SET n = 1 WHERE id = 1");
                CompletableFuture<SQLException> blocked = CompletableFuture.supplyAsync(() -> {
                    try {
                        secondStatement.executeUpdate("UPDATE conflict SET n = 2 WHERE id = 1");
                        return null;
                    } catch (SQLException e) {
                        return e;
                    }
                });
                first.commit();
                SQLException conflict = blocked.get(30, TimeUnit.SECONDS);
                assertEquals("40001", conflict.getSQLState());
                assertEquals("change conflicts with another transaction (OC000)",
                        ((PSQLException) conflict).getServerErrorMessage().getMessage());
                second.rollback();
            }
        }
    }

    /**
     * A long-term key signs in to its own account's clusters and to no other account's, whichever
     * account is Floci's default: the cluster here is not in the default one.
     */
    @Test
    void aLongTermKeySignsInOnlyToItsOwnAccountsClusters() throws Exception {
        EnforcementFixtures.UserCredentials own = accessKey(ACCOUNT);
        EnforcementFixtures.UserCredentials other = accessKey("000000000000");

        cluster.connect("admin", cluster.token("DbConnectAdmin", own.accessKeyId(), own.secretAccessKey(),
                Instant.now(), 900, null)).close();
        PSQLException foreign = assertThrows(PSQLException.class, () -> cluster.connect("admin",
                cluster.token("DbConnectAdmin", other.accessKeyId(), other.secretAccessKey(), Instant.now(), 900,
                        null)).close());
        assertEquals("08006", foreign.getSQLState());
        assertEquals("unable to accept connection, access denied", foreign.getServerErrorMessage().getMessage());
    }

    /** A connection signed in as {@code admin}. */
    private Connection admin() throws Exception {
        return cluster.connect("admin", cluster.adminToken());
    }

    /** A new IAM user's access key in {@code account}, removed with the user after the test. */
    private EnforcementFixtures.UserCredentials accessKey(String account) {
        return EnforcementFixtures.createUser(cleanup, httpPort, EnforcementFixtures.auth(account, "iam"),
                "dsql-" + System.nanoTime());
    }

    /** A startup packet signing in as {@code admin}, its application_name padded to make {@code length} bytes. */
    private static byte[] startupOfLength(int length) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("user", "admin");
        parameters.put("database", "postgres");
        parameters.put("application_name", "");
        parameters.put("application_name", "a".repeat(length - PostgresWire.startupMessage(parameters).length));
        return PostgresWire.startupMessage(parameters);
    }

    /** A message's type and the length it announces, without any of its body. */
    private static byte[] header(char type, int length) {
        byte[] header = new byte[5];
        header[0] = (byte) type;
        System.arraycopy(PostgresWire.int32(length), 0, header, 1, 4);
        return header;
    }

    /** The endpoint answers DSQL's FATAL with {@code message} and the session's id, then closes. */
    private static void assertRefused(DsqlWireClient client, String message) throws IOException {
        PostgresWire.Message error = client.next();
        assertEquals('E', error.type());
        Map<Character, String> fields = PostgresWire.parseFields(error.body(), 0);
        assertEquals("FATAL", fields.get('S'));
        assertEquals("08P01", fields.get('C'));
        assertEquals(message, fields.get('M'));
        assertTrue(fields.get('D').startsWith("Session Id: "), fields.get('D'));
        assertThrows(IOException.class, client::next);
    }
}
