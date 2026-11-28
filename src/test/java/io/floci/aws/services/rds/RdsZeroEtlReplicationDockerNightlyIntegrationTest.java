package io.floci.aws.services.rds;

import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.testing.RdsZeroEtlFixture;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

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

    @Inject
    RedshiftServerlessService serverless;

    @BeforeEach
    void requireDocker() throws InterruptedException {
        assumeDocker();
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void changesMadeOnTheSourceAfterTheLoadReachTheDestinationDatabase() throws Exception {
        try (RdsZeroEtlFixture fixture = RdsZeroEtlFixture.create(serverless, "zetl-repl", true)) {
            fixture.executeSource(
                    "CREATE TABLE orders (id int PRIMARY KEY, total numeric(10,2), note text)",
                    "INSERT INTO orders VALUES (1, 9.99, NULL)",
                    "CREATE TABLE no_key (id int, n int)",
                    "INSERT INTO no_key VALUES (1, 1)");

            fixture.createIntegrationAndAwaitActive("include: appdb.*.*");
            assertThat(orders(fixture), contains("1:9.99:-"));

            fixture.executeSource(
                    "INSERT INTO orders VALUES (2, 19.99, 'second'), (3, 1.00, 'gone soon')",
                    "UPDATE orders SET total = 10.50, note = 'changed' WHERE id = 1",
                    "DELETE FROM orders WHERE id = 3",
                    // Only the replicated tables are published, so a table without a key still takes updates.
                    "UPDATE no_key SET n = 2");
            awaitOrders(fixture, "1:10.50:changed", "2:19.99:second");

            fixture.executeSource("UPDATE orders SET id = 4 WHERE id = 2");
            awaitOrders(fixture, "1:10.50:changed", "4:19.99:second");

            fixture.deleteIntegration();
            Awaitility.await().atMost(RdsZeroEtlFixture.DEFAULT_WAIT).pollInterval(Duration.ofMillis(500))
                    .until(() -> fixture.querySource(
                            "SELECT (SELECT count(*) FROM pg_replication_slots) "
                                    + "+ (SELECT count(*) FROM pg_publication)").equals(List.of("0")));
        }
    }

    private static List<String> orders(RdsZeroEtlFixture fixture) throws SQLException {
        return fixture.queryTarget(
                "SELECT id || ':' || total || ':' || COALESCE(note, '-') FROM public.orders ORDER BY id");
    }

    private static void awaitOrders(RdsZeroEtlFixture fixture, String... expected) {
        Awaitility.await().atMost(RdsZeroEtlFixture.DEFAULT_WAIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(orders(fixture), contains(expected)));
    }
}
