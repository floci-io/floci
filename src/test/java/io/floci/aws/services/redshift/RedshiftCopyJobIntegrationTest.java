package io.floci.aws.services.redshift;

import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.RedshiftTestCluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftCopyJobIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/LoadRole";
    private static final String COPY = "COPY public.events FROM 's3://copy-job-bucket/staging/' IAM_ROLE '" + ROLE + "'";

    @Inject
    RedshiftService service;

    private RedshiftTestCluster cluster;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for Redshift copy job tests");
    }

    @AfterEach
    void cleanUp() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void createListShowAlterAndDropACopyJob(String mode) throws Exception {
        cluster = RedshiftTestCluster.create(service, "copy-job");

        try (Connection connection = cluster.connect(RedshiftTestCluster.MASTER_USER, mode);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.events (id integer)");
            statement.execute(COPY + " JOB CREATE my_copy_job AUTO ON");

            try (ResultSet rows = statement.executeQuery("COPY JOB LIST")) {
                assertTrue(rows.next());
                assertEquals("my_copy_job", rows.getString("job_name").trim());
                assertEquals(ROLE, rows.getString("iam_role").trim());
                assertEquals(1, rows.getInt("is_auto"));
                assertFalse(rows.next());
            }

            statement.execute("COPY JOB ALTER my_copy_job AUTO OFF");
            try (ResultSet rows = statement.executeQuery("COPY JOB SHOW my_copy_job")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt("is_auto"));
            }
            try (ResultSet rows = statement.executeQuery("SELECT job_name FROM sys_copy_job")) {
                assertTrue(rows.next());
            }

            statement.execute("CREATE USER job_owner PASSWORD 'Secret123'");
            statement.execute("GRANT USAGE ON SCHEMA public TO job_owner");
            statement.execute("GRANT INSERT ON public.events TO job_owner");
            try (Connection user = cluster.connect("job_owner", mode);
                    Statement userStatement = user.createStatement()) {
                userStatement.execute(COPY + " JOB CREATE user_job");
                try (ResultSet rows = userStatement.executeQuery("COPY JOB LIST")) {
                    assertTrue(rows.next());
                    assertEquals("user_job", rows.getString("job_name").trim());
                    assertFalse(rows.next());
                }
                userStatement.execute("COPY JOB ALTER user_job AUTO OFF");
                userStatement.execute("COPY JOB DROP user_job");
            }

            SQLException duplicate = assertThrows(SQLException.class,
                    () -> statement.execute(COPY + " JOB CREATE my_copy_job"));
            assertEquals("42710", duplicate.getSQLState());
            SQLException tolerant = assertThrows(SQLException.class,
                    () -> statement.execute(COPY + " MAXERROR 5 JOB CREATE other_job"));
            assertEquals("0A000", tolerant.getSQLState());

            statement.execute("COPY JOB DROP my_copy_job");
            try (ResultSet rows = statement.executeQuery("COPY JOB LIST")) {
                assertFalse(rows.next());
            }
            SQLException missing = assertThrows(SQLException.class,
                    () -> statement.execute("COPY JOB DROP my_copy_job"));
            assertEquals("42704", missing.getSQLState());
        }
    }
}
