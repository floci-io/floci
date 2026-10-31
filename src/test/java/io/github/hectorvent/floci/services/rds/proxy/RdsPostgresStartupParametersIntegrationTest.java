package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Properties;

import static io.github.hectorvent.floci.testing.RdsRequests.assumeDocker;
import static io.github.hectorvent.floci.testing.RdsRequests.createPostgresInstance;
import static io.github.hectorvent.floci.testing.RdsRequests.deleteInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parameters a client puts in its PostgreSQL StartupMessage reach the instance through the
 * proxy, as they reach an RDS for PostgreSQL endpoint: {@code options} and
 * {@code application_name} here, for a password login and for an IAM login whose session the
 * proxy hands over to the token's role.
 */
@QuarkusTest
@Tag("docker")
class RdsPostgresStartupParametersIntegrationTest {

    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";

    @BeforeEach
    void checkDocker() throws InterruptedException {
        assumeDocker();
    }

    /**
     * DriverManager only hands out a driver the caller's class loader can see. After a Quarkus
     * restart the first connection would find none, so PgJDBC is loaded through this test's loader first.
     */
    @BeforeEach
    void registerPostgresDriver() throws ClassNotFoundException {
        Class.forName("org.postgresql.Driver");
    }

    @Test
    void clientStartupParametersApplyToTheSession() throws Exception {
        String dbId = "startup-params-" + Long.toString(System.nanoTime(), 36);
        int port = createPostgresInstance(dbId, MASTER_USER, MASTER_PASSWORD, false);
        try {
            Properties properties = new Properties();
            properties.setProperty("user", MASTER_USER);
            properties.setProperty("password", MASTER_PASSWORD);
            properties.setProperty("sslmode", "disable");
            properties.setProperty("options", "-c search_path=floci_opts -c work_mem=64MB");
            properties.setProperty("ApplicationName", "floci-startup-params");

            try (Connection connection = DriverManager.getConnection(
                    "jdbc:postgresql://127.0.0.1:" + port + "/appdb", properties)) {
                assertThat(show(connection, "search_path"), equalTo("floci_opts"));
                assertThat(show(connection, "work_mem"), equalTo("64MB"));
                assertThat(show(connection, "application_name"), equalTo("floci-startup-params"));
            }
        } finally {
            deleteInstance(dbId);
        }
    }

    @Test
    void clientStartupParametersApplyToAnIamSessionAsTheTokenRole() throws Exception {
        String dbId = "startup-params-iam-" + Long.toString(System.nanoTime(), 36);
        int port = createPostgresInstance(dbId, MASTER_USER, MASTER_PASSWORD, true);
        try {
            asMaster(port,
                    "CREATE ROLE app_user LOGIN",
                    "GRANT rds_iam TO app_user",
                    "CREATE SCHEMA floci_opts AUTHORIZATION app_user",
                    // A database that refuses \' in string literals must still accept the parameters.
                    "ALTER DATABASE appdb SET backslash_quote = off");

            Properties iam = iamLogin(port, "app_user");
            iam.setProperty("options", "-c search_path=floci_opts -c work_mem=64MB");
            iam.setProperty("ApplicationName", "floci's startup params");

            try (Connection connection = DriverManager.getConnection(jdbcUrl(port), iam)) {
                assertThat(show(connection, "search_path"), equalTo("floci_opts"));
                assertThat(show(connection, "work_mem"), equalTo("64MB"));
                assertThat(show(connection, "application_name"), equalTo("floci's startup params"));
                assertThat(show(connection, "session_authorization"), equalTo("app_user"));
            }
        } finally {
            deleteInstance(dbId);
        }
    }

    @Test
    void anIamSessionCannotTakeTheMasterIdentityThroughStartupParameters() throws Exception {
        String dbId = "startup-params-iam-escalation-" + Long.toString(System.nanoTime(), 36);
        int port = createPostgresInstance(dbId, MASTER_USER, MASTER_PASSWORD, true);
        try {
            asMaster(port, "CREATE ROLE app_user LOGIN", "GRANT rds_iam TO app_user");

            for (String options : new String[] {
                    "-c session_authorization=" + MASTER_USER,
                    "-c session_replication_role=replica --Session-Authorization=" + MASTER_USER,
                    "-c role=" + MASTER_USER}) {
                Properties iam = iamLogin(port, "app_user");
                iam.setProperty("options", options);
                SQLException refused = assertThrows(SQLException.class,
                        () -> DriverManager.getConnection(jdbcUrl(port), iam).close(), options);
                assertThat(options, refused.getSQLState(), equalTo("42501"));
            }
        } finally {
            deleteInstance(dbId);
        }
    }

    @Test
    void anIamSessionLoadsTheTokenRolesOwnDefaultsBeneathTheClientsParameters() throws Exception {
        String dbId = "startup-params-iam-defaults-" + Long.toString(System.nanoTime(), 36);
        int port = createPostgresInstance(dbId, MASTER_USER, MASTER_PASSWORD, true);
        try {
            asMaster(port,
                    "CREATE ROLE reader NOLOGIN",
                    "CREATE ROLE app_user LOGIN",
                    "GRANT rds_iam TO app_user",
                    "GRANT reader TO app_user",
                    "ALTER ROLE app_user SET work_mem = '32MB'",
                    "ALTER ROLE app_user IN DATABASE appdb SET work_mem = '48MB'",
                    "ALTER ROLE app_user SET statement_timeout = '7s'",
                    // Superuser-only: applies at login although app_user could not set it itself.
                    "ALTER ROLE app_user SET log_min_duration_statement = '1s'",
                    "ALTER ROLE app_user SET role = 'reader'",
                    // Read on a session whose encoding the master's default would otherwise set.
                    "ALTER ROLE app_user SET floci.note = 'café'",
                    "ALTER ROLE " + MASTER_USER + " SET client_encoding = 'LATIN1'");

            Properties iam = iamLogin(port, "app_user");
            iam.setProperty("options", "-c statement_timeout=9s");

            try (Connection connection = DriverManager.getConnection(jdbcUrl(port), iam)) {
                assertThat(show(connection, "work_mem"), equalTo("48MB"));
                assertThat(show(connection, "statement_timeout"), equalTo("9s"));
                assertThat(show(connection, "log_min_duration_statement"), equalTo("1s"));
                assertThat(show(connection, "role"), equalTo("reader"));
                assertThat(show(connection, "session_authorization"), equalTo("app_user"));
                assertThat(show(connection, "floci.note"), equalTo("café"));

                // As at login, the role's defaults are what RESET returns to, so the role cannot
                // shed a superuser-only one its administrator set.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("RESET ALL");
                }
                assertThat(show(connection, "work_mem"), equalTo("48MB"));
                assertThat(show(connection, "log_min_duration_statement"), equalTo("1s"));
                assertThat(show(connection, "statement_timeout"), equalTo("7s"));
            }
        } finally {
            deleteInstance(dbId);
        }
    }

    /** Runs {@code statements} in order as the master user. */
    private static void asMaster(int port, String... statements) throws SQLException {
        Properties master = new Properties();
        master.setProperty("user", MASTER_USER);
        master.setProperty("password", MASTER_PASSWORD);
        master.setProperty("sslmode", "disable");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(port), master);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static String jdbcUrl(int port) {
        return "jdbc:postgresql://localhost:" + port + "/appdb";
    }

    private static Properties iamLogin(int port, String user) throws Exception {
        Properties iam = new Properties();
        iam.setProperty("user", user);
        iam.setProperty("password", SigV4TokenTestHelper.createRdsToken("localhost", port, user,
                "test", "test", Instant.now(), 900));
        iam.setProperty("sslmode", "disable");
        return iam;
    }

    private static String show(Connection connection, String parameter) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SHOW " + parameter)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
