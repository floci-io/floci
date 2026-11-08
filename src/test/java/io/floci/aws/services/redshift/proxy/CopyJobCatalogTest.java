package io.floci.aws.services.redshift.proxy;

import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.RedshiftTestCluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGStatement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real catalog on a cluster's PostgreSQL, so it needs a Docker daemon. */
@QuarkusTest
class CopyJobCatalogTest {
    private static final String COPY = "COPY public.events FROM 's3://bucket/prefix/' IAM_ROLE 'load-role'";
    private static final String READER = "copy_job_reader";
    private static RedshiftTestCluster cluster;

    @Inject
    RedshiftService service;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for Redshift copy job tests");
    }

    @BeforeEach
    void startClusterAndReset() throws SQLException {
        if (cluster == null) {
            cluster = RedshiftTestCluster.create(service, "copy-job-catalog");
            try (Connection connection = open(RedshiftTestCluster.MASTER_USER);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE public.events (id integer)");
                statement.execute("CREATE USER " + READER + " PASSWORD '" + RedshiftTestCluster.PASSWORD + "'");
                statement.execute("GRANT USAGE ON SCHEMA public TO " + READER);
                statement.execute("GRANT INSERT ON public.events TO " + READER);
            }
        }
        truncateJobs();
    }

    @AfterAll
    static void stopCluster() {
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
    }

    private static Connection open() throws SQLException {
        return open(RedshiftTestCluster.MASTER_USER);
    }

    private static Connection open(String user) throws SQLException {
        return cluster.connect(user);
    }

    @AfterEach
    void truncateJobs() throws SQLException {
        if (cluster != null) {
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE floci_internal.copy_job");
            }
        }
    }

    @Test
    void ordinaryUserCanManageOwnJobsButCannotSeeAnotherUsersJobs() throws SQLException {
        try (Connection admin = open(); Statement adminStatement = admin.createStatement();
                Connection user = open(READER);
                Statement statement = user.createStatement()) {
            adminStatement.execute(CopyJobSql.rewrite(COPY + " JOB CREATE admin_job", false));
            statement.execute(CopyJobSql.rewrite(COPY + " JOB CREATE user_job", false));
            try (ResultSet rows = statement.executeQuery(CopyJobSql.rewrite("COPY JOB LIST", false))) {
                assertTrue(rows.next());
                assertEquals("user_job", rows.getString("job_name").trim());
                assertFalse(rows.next());
            }
            statement.execute(CopyJobSql.rewrite("COPY JOB ALTER user_job AUTO OFF", false));
            statement.execute(CopyJobSql.rewrite("COPY JOB DROP user_job", false));
        }
    }

    @Test
    void grantingJobAccessDoesNotExposeExternalCatalogMutation() throws SQLException {
        try (Connection user = open(READER);
                Statement statement = user.createStatement()) {
            for (String sql : new String[]{"SELECT floci_internal.purge_external_schema('other_schema')",
                    "SELECT floci_internal.refresh_external_catalog('other_schema', '{}'::jsonb)"}) {
                SQLException error = assertThrows(SQLException.class, () -> statement.execute(sql));
                assertEquals("42501", error.getSQLState());
            }
        }
    }

    @Test
    void jobNamesAreSafeWhenStandardConformingStringsIsDisabled() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SET standard_conforming_strings = off");
            statement.execute(CopyJobSql.rewrite(COPY + " JOB CREATE \"slash\\'job\"", false));
            try (ResultSet rows = statement.executeQuery(CopyJobSql.rewrite("COPY JOB SHOW \"slash\\'job\"", false))) {
                assertTrue(rows.next());
                assertEquals("slash\\'job", rows.getString("job_name").trim());
            }
        }
    }

    @Test
    void transactionRestrictionIsCheckedWhenPreparedStatementExecutes() throws SQLException {
        for (String sql : new String[]{COPY + " JOB CREATE saved_job", "COPY JOB DROP saved_job"}) {
            try (Connection connection = open();
                    PreparedStatement statement = connection.prepareStatement(CopyJobSql.rewrite(sql, false))) {
                statement.unwrap(PGStatement.class).setPrepareThreshold(1);
                statement.execute();
                if (sql.contains("DROP")) {
                    try (Statement seed = connection.createStatement()) {
                        seed.execute(CopyJobSql.rewrite(COPY + " JOB CREATE saved_job", false));
                    }
                }
                connection.setAutoCommit(false);
                SQLException error = assertThrows(SQLException.class, statement::execute);
                assertTrue(error.getSQLState().startsWith("2"), error.getMessage());
                connection.rollback();
            }
        }
    }

    @Test
    void concurrentCreatesCannotExceedTheJobLimit() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO floci_internal.copy_job "
                    + "(job_name, owner, iam_role, job_text, is_auto, target_table, bucket, prefix) "
                    + "SELECT 'job_' || i, session_user, 'role', 'COPY', true, 't', 'b', '' "
                    + "FROM generate_series(1, 199) i");
        }
        CountDownLatch created = new CountDownLatch(1);
        CompletableFuture<Void> second;
        try (Connection first = open(); Statement statement = first.createStatement()) {
            first.setAutoCommit(false);
            // Hold the first insert open so the other session must serialize before counting.
            statement.execute("LOCK TABLE floci_internal.copy_job IN SHARE ROW EXCLUSIVE MODE");
            second = CompletableFuture.runAsync(() -> {
                try (Connection connection = open(); Statement other = connection.createStatement()) {
                    created.countDown();
                    other.execute(CopyJobSql.rewrite(COPY + " JOB CREATE second_job", false));
                } catch (SQLException error) {
                    assertEquals("54000", error.getSQLState(), error.getMessage());
                }
            });
            assertTrue(created.await(5, TimeUnit.SECONDS));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE query LIKE '%second_job%' AND wait_event_type = 'Lock'")) {
                    return rows.next() && rows.getInt(1) > 0;
                }
            });
            statement.execute("INSERT INTO floci_internal.copy_job "
                    + "(job_name, owner, iam_role, job_text, is_auto, target_table, bucket, prefix) "
                    + "VALUES ('first_job', session_user, 'role', 'COPY', true, 't', 'b', '')");
            first.commit();
        }
        second.get(10, TimeUnit.SECONDS);
        try (Connection connection = open(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_catalog.sys_copy_job")) {
            assertTrue(rows.next());
            assertEquals(200, rows.getInt(1));
        }
    }
}
