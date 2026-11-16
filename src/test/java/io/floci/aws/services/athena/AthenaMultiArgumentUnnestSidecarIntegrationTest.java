package io.floci.aws.services.athena;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Trino's multi-argument {@code UNNEST}, run on the real floci-duck sidecar through {@link TrinoSqlCompatibility}. The
 * tests marked so expect the rows Athena (engine v3) returned for the same query.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaMultiArgumentUnnestSidecarIntegrationTest {

    @Inject
    FlociDuckClient duck;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    /**
     * Each row's values in {@code columns} order, as text, {@code null} for NULL. A number is given in its plain
     * form, since the sidecar's JSON gives DuckDB's HUGEINT counts as {@code 1.0}.
     */
    private List<List<String>> rows(String trinoSql, String... columns) {
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, Object> row : duck.query(TrinoSqlCompatibility.translate(trinoSql),
                TrinoSqlCompatibility.FUNCTION_MACROS)) {
            rows.add(Arrays.stream(columns).map(column -> text(row.get(column))).toList());
        }
        return rows;
    }

    private static String text(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return value == null ? null : String.valueOf(value);
    }

    /** As on Athena. */
    @Test
    void theArraysAreZippedByPositionAndTheShorterPaddedWithNull() {
        assertEquals(List.of(List.of("1", "a"), Arrays.asList("2", null), Arrays.asList("3", null)),
                rows("SELECT x, y FROM UNNEST(ARRAY[1, 2, 3], ARRAY['a']) AS t (x, y) ORDER BY x", "x", "y"));
    }

    /** As on Athena. */
    @Test
    void aCrossJoinedUnnestReadsTheColumnsOfEachRowItJoins() {
        assertEquals(List.of(List.of("1", "1", "a"), List.of("1", "2", "b"), Arrays.asList("1", null, "c")),
                rows("SELECT s.id, t.x, t.y FROM (VALUES (1, ARRAY[1, 2], ARRAY['a', 'b', 'c'])) AS s (id, xs, ys) "
                        + "CROSS JOIN UNNEST(xs, ys) AS t (x, y) ORDER BY t.y", "id", "x", "y"));
    }

    /** As on Athena: a NULL array is an empty one, padded beside another. */
    @Test
    void aNullArrayIsPaddedBesideAnother() {
        assertEquals(List.of(Arrays.asList("1", "1", null), Arrays.asList("1", "2", null)),
                rows("SELECT s.id, t.x, t.y FROM (VALUES (1, ARRAY[1, 2], CAST(NULL AS ARRAY(VARCHAR)))) "
                        + "AS s (id, xs, ys) CROSS JOIN UNNEST(xs, ys) AS t (x, y) ORDER BY t.x", "id", "x", "y"));
    }

    /** As on Athena: empty arrays give no rows to a cross join and one row of NULLs to a left join. */
    @Test
    void emptyArraysGiveNoRowsToACrossJoinAndOneOfNullsToALeftJoin() {
        String source = "FROM (VALUES (2, CAST(ARRAY[] AS ARRAY(INTEGER)), CAST(ARRAY[] AS ARRAY(VARCHAR)))) "
                + "AS s (id, xs, ys) ";

        assertEquals(List.of(), rows("SELECT s.id, t.x, t.y " + source + "CROSS JOIN UNNEST(xs, ys) AS t (x, y)",
                "id", "x", "y"));
        assertEquals(List.of(Arrays.asList("2", null, null)),
                rows("SELECT s.id, t.x, t.y " + source + "LEFT JOIN UNNEST(xs, ys) AS t (x, y) ON TRUE", "id", "x", "y"));
    }

    /**
     * As on Athena: an array naming an outer column reads that column, even when an earlier column of the source has
     * its name. Aliases inside the subquery would have taken it.
     */
    @Test
    void anArrayReadsTheOuterColumnItNamesWhateverTheSourcesColumnsAreCalled() {
        assertEquals(List.of(List.of("id", "i1"), List.of("name", "n1")),
                rows("SELECT f.name, f.value FROM (VALUES (ARRAY['id', 'name'], 'i1', 'n1')) AS s (keys, id, name) "
                        + "CROSS JOIN UNNEST(keys, ARRAY[id, name]) AS f (name, value) ORDER BY 1", "name", "value"));
    }

    /**
     * As on Athena: an array of rows unnests into one row column beside another array, its fields not expanded,
     * whether they are named or not. Athena returns {@code {v=1}} for {@code x}, typed {@code row(v integer)}; the
     * sidecar's JSON gives no struct values, so its type is compared.
     */
    @Test
    void anArrayOfRowsUnnestsIntoOneRowColumn() {
        assertEquals(List.of(List.of("STRUCT(v INTEGER)", "a")),
                rows("SELECT typeof(x) AS tx, y FROM UNNEST(ARRAY[CAST(ROW(1) AS ROW(v INTEGER))], ARRAY['a']) "
                        + "AS t (x, y)", "tx", "y"));
    }

    /** Access Graph's facet query: each scanned row unpivoted into (column name, value) pairs, then counted. */
    @Test
    void aFacetQueryCountsEachColumnsValues() {
        assertEquals(List.of(List.of("identity", "u1", "1", "1"), List.of("identity", "u2", "1", "0"),
                        List.of("target_resource", "r1", "1", "1")),
                rows("WITH raw_data AS (SELECT * FROM (VALUES ('u1', 'r1', 'db'), ('u2', NULL, 'ssh')) "
                        + "AS v (identity, target_resource, target_kind)), "
                        + "flagged_raw_data AS (SELECT identity, target_resource, target_kind, "
                        + "(target_kind = 'db') AS matched FROM \"raw_data\") "
                        + "SELECT facet.column_name, facet.value, COUNT(*) AS unfiltered_count, "
                        + "COUNT_IF(matched) AS filtered_count FROM \"flagged_raw_data\" "
                        + "CROSS JOIN UNNEST(ARRAY['identity', 'target_resource'], ARRAY[CAST(identity AS VARCHAR), "
                        + "CASE WHEN target_kind IN ('ssh') THEN NULL ELSE CAST(target_resource AS VARCHAR) END]) "
                        + "AS facet (column_name, value) WHERE facet.value <> '' "
                        + "GROUP BY facet.column_name, facet.value ORDER BY 1, 2",
                        "column_name", "value", "unfiltered_count", "filtered_count"));
    }

    @Test
    void anArrayCastFromJsonUnnestsBesideAnotherArray() {
        assertEquals(List.of(List.of("1", "a"), List.of("2", "b")),
                rows("SELECT json_extract_scalar(item, '$.id') AS id, tag FROM (VALUES ('[{\"id\":1},{\"id\":2}]')) "
                        + "AS t (col) CROSS JOIN UNNEST(CAST(json_parse(col) AS ARRAY<JSON>), ARRAY['a', 'b']) "
                        + "AS u (item, tag) ORDER BY id", "id", "tag"));
    }
}
