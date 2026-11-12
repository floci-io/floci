package io.floci.aws.services.athena;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Athena reads its result column types from floci-duck describing the query, so this pins the shape
 * of that answer and its mapping to Athena type names against the real sidecar.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaColumnTypesSidecarIntegrationTest {

    @Inject
    FlociDuckClient duck;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    @Test
    void describedResultColumnsMapToAthenaTypeNames() {
        List<String> types = duck.query("DESCRIBE SELECT CAST(1 AS BIGINT) AS n, CAST(1.5 AS DOUBLE) AS d, "
                        + "true AS b, DATE '2026-01-01' AS dt, CAST(2.50 AS DECIMAL(10,2)) AS p, 'x' AS s, "
                        + "TIME '12:00:00' AS t, CAST(TIMESTAMP '2026-01-01 00:00:00' AS TIMESTAMP_MS) AS ts_ms, "
                        + "CAST(TIMESTAMP '2026-01-01 00:00:00' AS TIMESTAMPTZ) AS ts_tz", null)
                .stream()
                .map(column -> AthenaService.athenaType(String.valueOf(column.get("column_type"))))
                .toList();

        assertEquals(List.of("bigint", "double", "boolean", "date", "decimal", "varchar", "time", "timestamp",
                "timestamp with time zone"), types);
    }
}
