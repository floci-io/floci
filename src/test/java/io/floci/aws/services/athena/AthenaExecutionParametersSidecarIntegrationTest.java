package io.floci.aws.services.athena;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A query's {@code ExecutionParameters}, bound as Athena binds them, run on the real floci-duck sidecar as the
 * expressions Athena reads them as.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaExecutionParametersSidecarIntegrationTest {

    @Inject
    FlociDuckClient duck;

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    /**
     * An event search binds a date range, a tenant, a time range and a keyset to resume after, each value the text of
     * a Trino expression: quoted strings and {@code timestamp '...'} literals.
     */
    @Test
    void anEventSearchRunsWithItsDatesTenantTimesAndKeysetBound() {
        String search = "SELECT uid FROM (VALUES "
                + "(DATE '2026-11-10', 't1', TIMESTAMP '2026-11-10 09:00:00', 'a'), "
                + "(DATE '2026-11-10', 't1', TIMESTAMP '2026-11-10 10:00:00', 'b'), "
                + "(DATE '2026-11-10', 't1', TIMESTAMP '2026-11-10 10:00:00', 'c'), "
                + "(DATE '2026-11-10', 't2', TIMESTAMP '2026-11-10 11:00:00', 'd'), "
                + "(DATE '2026-11-11', 't1', TIMESTAMP '2026-11-11 08:00:00', 'e')"
                + ") AS events (event_date, tenant_id, event_time, uid)"
                + " WHERE event_date BETWEEN date(?) AND date(?) AND tenant_id = ?"
                + " AND event_time BETWEEN ? and ? AND (event_time, uid) > (?,?)"
                + " ORDER BY event_time ASC, uid ASC LIMIT 10";
        List<String> parameters = List.of("'2026-11-10'", "'2026-11-10'", "'t1'",
                "timestamp '2026-11-10 00:00:00'", "timestamp '2026-11-10 23:59:59.999'",
                "timestamp '2026-11-10 10:00:00'", "'b'");

        try (AthenaSidecarFixture fixture = new AthenaSidecarFixture(duck, s3Service, "parameters")) {
            List<Map<String, String>> rows = fixture.query(TrinoSqlCompatibility.bindParameters(search, parameters));

            assertEquals(List.of(Map.of("uid", "c")), rows);
        }
    }

    /** Athena (engine v3) returns 2 and 3 for this, and 1 for the JSON path given as a parameter. */
    @Test
    void rowCountsAndAJsonPathRunAsBound() {
        try (AthenaSidecarFixture fixture = new AthenaSidecarFixture(duck, s3Service, "parameters")) {
            assertEquals(List.of(Map.of("x", "2"), Map.of("x", "3")), fixture.query(TrinoSqlCompatibility.bindParameters(
                    "SELECT x FROM UNNEST(ARRAY[1, 2, 3, 4]) AS t (x) ORDER BY x OFFSET ? LIMIT ?", List.of("1", "2"))));
        }
        String jsonPath = TrinoSqlCompatibility.translate(TrinoSqlCompatibility.bindParameters(
                "SELECT json_extract_scalar('{\"a b\":1}', ?) AS v", List.of("'$[\"a b\"]'")));
        assertEquals("1", String.valueOf(duck.query(jsonPath, TrinoSqlCompatibility.FUNCTION_MACROS).getFirst().get("v")));
    }

    /** A value is an expression of its own, so it keeps its precedence inside the statement. */
    @Test
    void aValueKeepsItsPrecedenceInsideTheStatement() {
        try (AthenaSidecarFixture fixture = new AthenaSidecarFixture(duck, s3Service, "parameters")) {
            List<Map<String, String>> rows = fixture.query(
                    TrinoSqlCompatibility.bindParameters("SELECT ? * 2 AS a", List.of("1 + 1")));

            assertEquals(List.of(Map.of("a", "4")), rows);
        }
    }
}
