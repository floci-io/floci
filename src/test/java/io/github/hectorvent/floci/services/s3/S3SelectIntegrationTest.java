package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class S3SelectIntegrationTest {

    // ── helpers ───────────────────────────────────────────────────────────

    private static void createBucketAndPut(String bucket, String key, String body) {
        given().header("Host", bucket + ".localhost").when().put("/").then().statusCode(200);
        given().header("Host", bucket + ".localhost").body(body).when().put("/" + key).then().statusCode(200);
    }

    private static io.restassured.response.ValidatableResponse select(
            String bucket, String key, String expression,
            String inputSerialization, String outputSerialization) {
        // XML-escape the expression so operators like < and & are valid inside <Expression>
        String safeExpr = expression.replace("&", "&amp;").replace("<", "&lt;");
        String requestXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <SelectObjectContentRequest>
                    <Expression>%s</Expression>
                    <ExpressionType>SQL</ExpressionType>
                    <InputSerialization>%s</InputSerialization>
                    <OutputSerialization>%s</OutputSerialization>
                </SelectObjectContentRequest>
                """.formatted(safeExpr, inputSerialization, outputSerialization);
        return given()
                .header("Host", bucket + ".localhost")
                .queryParam("select", "")
                .queryParam("select-type", "2")
                .body(requestXml)
                .when().post("/" + key)
                .then().statusCode(200);
    }

    private static final String CSV_OUT = "<CSV/>";
    private static final String JSON_OUT = "<JSON/>";
    private static final String CSV_USE = "<CSV><FileHeaderInfo>USE</FileHeaderInfo></CSV>";
    private static final String CSV_NONE = "<CSV><FileHeaderInfo>NONE</FileHeaderInfo></CSV>";
    private static final String CSV_IGNORE = "<CSV><FileHeaderInfo>IGNORE</FileHeaderInfo></CSV>";
    private static final String JSON_IN = "<JSON/>";

    // ── Existing tests (preserved) ────────────────────────────────────────

    @Test
    void select_withWhereClause() {
        String bucket = "select-bucket";
        createBucketAndPut(bucket, "data.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        select(bucket, "data.csv", "SELECT * FROM S3Object s WHERE s.age > 30", CSV_USE, CSV_OUT)
                .body(containsString("Charlie,35"))
                .body(not(containsString("Alice,30")))
                .body(not(containsString("Bob,25")));
    }

    @Test
    void select_withProjection() {
        String bucket = "select-bucket-proj";
        createBucketAndPut(bucket, "data.csv", "name,age,city\nAlice,30,New York\nBob,25,London");
        select(bucket, "data.csv", "SELECT name, city FROM S3Object", CSV_USE, CSV_OUT)
                .body(containsString("Alice,New York"))
                .body(containsString("Bob,London"))
                .body(not(containsString("30")))
                .body(not(containsString("25")));
    }

    @Test
    void select_withLimit() {
        String bucket = "select-bucket-limit";
        createBucketAndPut(bucket, "data.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        select(bucket, "data.csv", "SELECT * FROM S3Object LIMIT 2", CSV_USE, CSV_OUT)
                .body(containsString("Alice,30"))
                .body(containsString("Bob,25"))
                .body(not(containsString("Charlie,35")));
    }

    // ── A: String literal / toUpperCase fix ───────────────────────────────

    @Test
    void select_whereStringEquality() {
        String bucket = "sel-str-eq";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE name = 'Alice'", CSV_USE, CSV_OUT)
                .body(containsString("Alice,30"))
                .body(not(containsString("Bob")))
                .body(not(containsString("Charlie")));
    }

    @Test
    void select_whereStringEquality_caseSensitive() {
        String bucket = "sel-str-case";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25");
        // 'alice' (lowercase) must NOT match 'Alice' — S3 Select = is case-sensitive
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE name = 'alice'", CSV_USE, CSV_OUT)
                .body(not(containsString("Alice")))
                .body(not(containsString("Bob")));
    }

    // ── B: FileHeaderInfo ─────────────────────────────────────────────────

    @Test
    void select_fileHeaderInfoNone() {
        String bucket = "sel-hdr-none";
        // With NONE, the first line is treated as data, positional refs must be used
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25");
        select(bucket, "d.csv", "SELECT _1 FROM S3Object", CSV_NONE, CSV_OUT)
                // header line itself appears as a data row
                .body(containsString("name"))
                .body(containsString("Alice"))
                .body(containsString("Bob"));
    }

    @Test
    void select_fileHeaderInfoIgnore() {
        String bucket = "sel-hdr-ignore";
        // With IGNORE, header row is skipped from output; positional refs work for data rows
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25");
        select(bucket, "d.csv", "SELECT _1 FROM S3Object", CSV_IGNORE, CSV_OUT)
                .body(containsString("Alice"))
                .body(containsString("Bob"))
                .body(not(containsString("name"))); // header row excluded
    }

    // ── C: Quoted CSV fields ──────────────────────────────────────────────

    @Test
    void select_quotedCsvField() {
        String bucket = "sel-quoted";
        // "Smith, John" contains a comma inside quotes — must not be split
        createBucketAndPut(bucket, "d.csv", "name,age\n\"Smith, John\",35\n\"Doe, Jane\",22");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE age > 30", CSV_USE, CSV_OUT)
                .body(containsString("Smith, John"))
                .body(not(containsString("Doe, Jane")));
    }

    // ── D: Enhanced WHERE operators ───────────────────────────────────────

    @Test
    void select_whereAnd() {
        String bucket = "sel-and";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,20\nCharlie,35");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE age > 20 AND age < 35", CSV_USE, CSV_OUT)
                .body(containsString("Alice,30"))
                .body(not(containsString("Bob")))
                .body(not(containsString("Charlie")));
    }

    @Test
    void select_whereOr() {
        String bucket = "sel-or";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE name = 'Alice' OR name = 'Charlie'", CSV_USE, CSV_OUT)
                .body(containsString("Alice"))
                .body(containsString("Charlie"))
                .body(not(containsString("Bob")));
    }

    @Test
    void select_whereNot() {
        String bucket = "sel-not";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE NOT age = 30", CSV_USE, CSV_OUT)
                .body(not(containsString("Alice")))
                .body(containsString("Bob,25"))
                .body(containsString("Charlie,35"));
    }

    @Test
    void select_whereLike() {
        String bucket = "sel-like";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25\nAlex,28");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE name LIKE 'Al%'", CSV_USE, CSV_OUT)
                .body(containsString("Alice"))
                .body(containsString("Alex"))
                .body(not(containsString("Bob")));
    }

    @Test
    void select_whereIsNull() {
        String bucket = "sel-isnull";
        // Bob's row has no city field → city IS NULL
        createBucketAndPut(bucket, "d.csv", "name,age,city\nAlice,30,NY\nBob,25");
        select(bucket, "d.csv", "SELECT name FROM S3Object WHERE city IS NULL", CSV_USE, CSV_OUT)
                .body(containsString("Bob"))
                .body(not(containsString("Alice")));
    }

    @Test
    void select_whereBetween() {
        String bucket = "sel-between";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,20\nCharlie,25");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE age BETWEEN 25 AND 30", CSV_USE, CSV_OUT)
                .body(containsString("Alice,30"))
                .body(containsString("Charlie,25"))
                .body(not(containsString("Bob,20")));
    }

    @Test
    void select_whereIn() {
        String bucket = "sel-in";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE name IN ('Alice', 'Charlie')", CSV_USE, CSV_OUT)
                .body(containsString("Alice"))
                .body(containsString("Charlie"))
                .body(not(containsString("Bob")));
    }

    @Test
    void select_whereGreaterOrEqual() {
        String bucket = "sel-gte";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE age >= 30", CSV_USE, CSV_OUT)
                .body(containsString("Alice,30"))   // exactly 30 must be included
                .body(containsString("Charlie,35"))
                .body(not(containsString("Bob,25")));
    }

    @Test
    void select_whereLessOrEqual() {
        String bucket = "sel-lte";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE age <= 25", CSV_USE, CSV_OUT)
                .body(containsString("Bob,25"))     // exactly 25 must be included
                .body(not(containsString("Alice,30")))
                .body(not(containsString("Charlie,35")));
    }

    // ── E: JSON input evaluation ──────────────────────────────────────────

    @Test
    void select_jsonLinesWithWhere() {
        String bucket = "sel-json-where";
        String jsonLines = "{\"name\":\"Alice\",\"age\":30}\n{\"name\":\"Bob\",\"age\":25}\n{\"name\":\"Charlie\",\"age\":35}";
        createBucketAndPut(bucket, "d.json", jsonLines);
        select(bucket, "d.json", "SELECT * FROM S3Object WHERE age > 30", JSON_IN, JSON_OUT)
                .body(containsString("Charlie"))
                .body(not(containsString("Alice")))
                .body(not(containsString("Bob")));
    }

    @Test
    void select_jsonLinesWithProjection() {
        String bucket = "sel-json-proj";
        String jsonLines = "{\"name\":\"Alice\",\"age\":30,\"city\":\"NY\"}\n{\"name\":\"Bob\",\"age\":25,\"city\":\"LA\"}";
        createBucketAndPut(bucket, "d.json", jsonLines);
        select(bucket, "d.json", "SELECT name FROM S3Object", JSON_IN, JSON_OUT)
                .body(containsString("Alice"))
                .body(containsString("Bob"))
                // check JSON key absence rather than raw substring to avoid binary CRC false-positives
                .body(not(containsString("\"age\":")))
                .body(not(containsString("\"city\":")));
    }

    @Test
    void select_jsonArrayWithWhere() {
        String bucket = "sel-json-arr";
        String jsonArray = "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":25}]";
        createBucketAndPut(bucket, "d.json", jsonArray);
        select(bucket, "d.json", "SELECT * FROM S3Object WHERE age < 30", JSON_IN, JSON_OUT)
                .body(containsString("Bob"))
                .body(not(containsString("Alice")));
    }

    @Test
    void select_jsonLinesIsNull() {
        String bucket = "sel-json-null";
        String jsonLines = "{\"name\":\"Alice\",\"age\":30,\"city\":\"NY\"}\n{\"name\":\"Bob\",\"age\":25}";
        createBucketAndPut(bucket, "d.json", jsonLines);
        select(bucket, "d.json", "SELECT name FROM S3Object WHERE city IS NULL", JSON_IN, JSON_OUT)
                .body(containsString("Bob"))
                .body(not(containsString("Alice")));
    }

    // ── F: Output format ──────────────────────────────────────────────────

    @Test
    void select_csvInputJsonOutput() {
        String bucket = "sel-csv-json-out";
        createBucketAndPut(bucket, "d.csv", "name,age\nAlice,30\nBob,25");
        select(bucket, "d.csv", "SELECT * FROM S3Object", CSV_USE, JSON_OUT)
                .body(containsString("\"name\""))
                .body(containsString("\"Alice\""))
                .body(containsString("\"Bob\""));
    }

    @Test
    void select_jsonInputCsvOutput() {
        String bucket = "sel-json-csv-out";
        String jsonLines = "{\"name\":\"Alice\",\"age\":30}\n{\"name\":\"Bob\",\"age\":25}";
        createBucketAndPut(bucket, "d.json", jsonLines);
        select(bucket, "d.json", "SELECT name, age FROM S3Object", JSON_IN, CSV_OUT)
                .body(containsString("Alice,30"))
                .body(containsString("Bob,25"));
    }

    // ── G: Real stats bytes ───────────────────────────────────────────────

    @Test
    void select_statsReflectsActualBytes() {
        String bucket = "sel-stats";
        String csvData = "name,age\nAlice,30\nBob,25\nCharlie,35";
        createBucketAndPut(bucket, "d.csv", csvData);
        // WHERE filters out 2 of 3 rows, so BytesReturned < BytesScanned
        select(bucket, "d.csv", "SELECT * FROM S3Object WHERE age > 30", CSV_USE, CSV_OUT)
                // Stats XML is embedded as UTF-8 in the binary event stream
                .body(containsString("<BytesScanned>"))
                .body(containsString("<BytesReturned>"))
                // Must not be the old hardcoded value
                .body(not(containsString("<BytesScanned>100</BytesScanned>")));
    }

    // Note: Parquet S3 Select tests (group H) require Docker + floci-duck and
    // are covered in compatibility-tests/ where the full stack is available.
}
