package io.floci.aws.services.athena;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Trino JSON functions Athena queries call, answered by the real floci-duck sidecar through
 * {@link TrinoSqlCompatibility}, with the results Trino gives.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaTrinoJsonFunctionsSidecarIntegrationTest {

    @Inject
    FlociDuckClient duck;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    private Map<String, Object> row(String trinoSql) {
        return duck.query(TrinoSqlCompatibility.translate(trinoSql), TrinoSqlCompatibility.FUNCTION_MACROS).getFirst();
    }

    @Test
    void jsonExtractScalarReturnsScalarsAsTextAndNullForAnythingElse() {
        Map<String, Object> row = row("SELECT json_extract_scalar('{\"a\":\"x\"}', '$.a') AS s, "
                + "json_extract_scalar('{\"a\":2}', '$.a') AS n, json_extract_scalar('{\"a\":true}', '$.a') AS b, "
                + "json_extract_scalar('{\"a\":{\"b\":1}}', '$.a') AS o, json_extract_scalar('{\"a\":[1]}', '$.a') AS arr, "
                + "json_extract_scalar('{\"a\":null}', '$.a') AS nul, json_extract_scalar('{\"a\":1}', '$.b') AS missing, "
                + "json_extract_scalar('{\"a b\":{\"c\":[5]}}', '$[\"a b\"].c[0]') AS bracket");

        assertEquals("x", row.get("s"));
        assertEquals("2", row.get("n"));
        assertEquals("true", row.get("b"));
        assertNull(row.get("o"));
        assertNull(row.get("arr"));
        assertNull(row.get("nul"));
        assertNull(row.get("missing"));
        assertEquals("5", row.get("bracket"));
    }

    @Test
    void jsonParseFormatSizeAndScalarCheckAnswerAsTrinoDoes() {
        Map<String, Object> row = row("SELECT json_format(json_parse('{ \"a\" : [1, 2] }')) AS f, "
                + "json_size('{\"x\":[1,2,3]}', '$.x') AS arr, json_size('{\"x\":{\"a\":1}}', '$.x') AS obj, "
                + "json_size('{\"x\":1}', '$.x') AS scalar, is_json_scalar('1') AS yes, is_json_scalar('[1]') AS no, "
                + "json_array_length('[1,2,3]') AS len, json_extract_scalar(json_extract('{\"a\":{\"b\":\"c\"}}', '$.a'), '$.b') AS e");

        assertEquals("{\"a\":[1,2]}", row.get("f"));
        assertEquals(3, ((Number) row.get("arr")).intValue());
        assertEquals(1, ((Number) row.get("obj")).intValue());
        assertEquals(0, ((Number) row.get("scalar")).intValue());
        assertEquals(true, row.get("yes"));
        assertEquals(false, row.get("no"));
        assertEquals(3, ((Number) row.get("len")).intValue());
        assertEquals("c", row.get("e"));
    }

    @Test
    void jsonSizeIsNullForANullArgumentOrAMissingPathAndZeroForAJsonNull() {
        Map<String, Object> row = row("SELECT json_size(NULL, '$.x') AS null_json, json_size('{\"x\":1}', NULL) AS null_path, "
                + "json_size('{\"x\":1}', '$.y') AS missing, json_size('{\"x\":null}', '$.x') AS json_null");

        assertNull(row.get("null_json"));
        assertNull(row.get("null_path"));
        assertNull(row.get("missing"));
        assertEquals(0, ((Number) row.get("json_null")).intValue());
    }

    @Test
    void aBracketedMemberNameCanHoldAnEscapedQuoteOrASlash() {
        Map<String, Object> row = row("SELECT json_extract_scalar('{\"a\\\"b\":7}', '$[\"a\\\"b\"]') AS quote, "
                + "json_extract_scalar('{\"a/b\":{\"c~d\":8}}', '$[\"a/b\"][\"c~d\"]') AS slash");

        assertEquals("7", row.get("quote"));
        assertEquals("8", row.get("slash"));
    }

    @Test
    void aTableNamedLikeAMacroKeepsBothItsViewAndTheFunction() {
        Map<String, Object> row = duck.query("SELECT (SELECT n FROM json_size) AS view_value, "
                        + "json_size('[1,2]', '$') AS function_value",
                TrinoSqlCompatibility.FUNCTION_MACROS + "CREATE OR REPLACE VIEW \"json_size\" AS SELECT 42 AS n;").getFirst();

        assertEquals(42, ((Number) row.get("view_value")).intValue());
        assertEquals(2, ((Number) row.get("function_value")).intValue());
    }

    @Test
    void aCastToAnUnnamedRowTypeRuns() {
        Map<String, Object> row = row("SELECT typeof(CAST(ROW(1, 'x') AS ROW(BIGINT, VARCHAR))) AS t");

        assertEquals("STRUCT(field0 BIGINT, field1 VARCHAR)", row.get("t"));
    }

    @Test
    void aCastToArrayOfJsonUnnestsIntoJsonValues() {
        List<Map<String, Object>> rows = duck.query(TrinoSqlCompatibility.translate(
                "SELECT json_extract_scalar(item, '$.id') AS id "
                        + "FROM (VALUES ('[{\"id\":1},{\"id\":2}]')) AS t(col) "
                        + "CROSS JOIN UNNEST(CAST(json_parse(col) AS ARRAY<JSON>)) AS u(item) ORDER BY id"),
                TrinoSqlCompatibility.FUNCTION_MACROS);

        assertEquals(List.of("1", "2"), rows.stream().map(r -> r.get("id")).toList());
    }

    @Test
    void jsonResultColumnsAreTypedAsAthenaTypesThem() {
        List<String> types = duck.query("DESCRIBE " + TrinoSqlCompatibility.translate(
                        "SELECT json_parse('[1]') AS p, json_extract_scalar('{\"a\":1}', '$.a') AS s, "
                                + "json_array_length('[1]') AS n, json_size('[1]', '$') AS z, "
                                + "CAST(json_parse('[1]') AS ARRAY<JSON>) AS a"), TrinoSqlCompatibility.FUNCTION_MACROS)
                .stream()
                .map(column -> AthenaService.athenaType(String.valueOf(column.get("column_type"))))
                .toList();

        assertEquals(List.of("json", "varchar", "bigint", "bigint", "array"), types);
    }
}
