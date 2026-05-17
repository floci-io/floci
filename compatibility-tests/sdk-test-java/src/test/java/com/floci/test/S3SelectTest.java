package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

@DisplayName("S3 SelectObjectContent")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3SelectTest {

    private static S3Client s3;
    private static S3AsyncClient s3Async;
    private static final String BUCKET = "compat-select-bucket";

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        s3Async = S3AsyncClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1))
                .build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {}
    }

    @AfterAll
    static void cleanup() {
        if (s3 == null) return;
        try {
            ListObjectsV2Response list = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(BUCKET).build());
            for (S3Object obj : list.contents()) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(BUCKET).key(obj.key()).build());
            }
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
        } catch (Exception ignored) {}
        s3Async.close();
        s3.close();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void put(String key, String body) {
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                RequestBody.fromString(body));
    }

    private static final InputSerialization CSV_USE = InputSerialization.builder()
            .csv(CSVInput.builder().fileHeaderInfo(FileHeaderInfo.USE).build()).build();
    private static final InputSerialization CSV_NONE = InputSerialization.builder()
            .csv(CSVInput.builder().fileHeaderInfo(FileHeaderInfo.NONE).build()).build();
    private static final InputSerialization CSV_IGNORE = InputSerialization.builder()
            .csv(CSVInput.builder().fileHeaderInfo(FileHeaderInfo.IGNORE).build()).build();
    private static final InputSerialization JSON_IN = InputSerialization.builder()
            .json(JSONInput.builder().type(JSONType.LINES).build()).build();
    private static final OutputSerialization CSV_OUT = OutputSerialization.builder()
            .csv(CSVOutput.builder().build()).build();
    private static final OutputSerialization JSON_OUT = OutputSerialization.builder()
            .json(JSONOutput.builder().build()).build();

    private String select(String key, String expression,
                          InputSerialization in, OutputSerialization out) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CompletableFuture<Void> future = s3Async.selectObjectContent(
                SelectObjectContentRequest.builder()
                        .bucket(BUCKET).key(key)
                        .expression(expression)
                        .expressionType(ExpressionType.SQL)
                        .inputSerialization(in)
                        .outputSerialization(out)
                        .build(),
                SelectObjectContentResponseHandler.builder()
                        .subscriber(SelectObjectContentResponseHandler.Visitor.builder()
                                .onRecords(event -> {
                                    try {
                                        baos.write(event.payload().asByteArray());
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                                .build())
                        .build());
        future.join();
        return baos.toString(StandardCharsets.UTF_8);
    }

    // ── CSV WHERE clause ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("CSV: WHERE with numeric comparison")
    void csvWhereNumeric() {
        put("where-num.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        String result = select("where-num.csv", "SELECT * FROM S3Object WHERE age > 30", CSV_USE, CSV_OUT);
        assertThat(result).contains("Charlie").doesNotContain("Alice").doesNotContain("Bob");
    }

    @Test
    @Order(2)
    @DisplayName("CSV: column projection")
    void csvProjection() {
        put("proj.csv", "name,age,city\nAlice,30,New York\nBob,25,London");
        String result = select("proj.csv", "SELECT name, city FROM S3Object", CSV_USE, CSV_OUT);
        assertThat(result).contains("Alice,New York").contains("Bob,London")
                .doesNotContain("30").doesNotContain("25");
    }

    @Test
    @Order(3)
    @DisplayName("CSV: LIMIT")
    void csvLimit() {
        put("limit.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        String result = select("limit.csv", "SELECT * FROM S3Object LIMIT 2", CSV_USE, CSV_OUT);
        assertThat(result).contains("Alice").contains("Bob").doesNotContain("Charlie");
    }

    // ── String equality / case sensitivity ───────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("CSV: string equality matches exact case")
    void csvStringEquality() {
        put("case.csv", "name,age\nAlice,30\nBob,25");
        String result = select("case.csv", "SELECT * FROM S3Object WHERE name = 'Alice'", CSV_USE, CSV_OUT);
        assertThat(result).contains("Alice").doesNotContain("Bob");
    }

    @Test
    @Order(5)
    @DisplayName("CSV: string equality is case-sensitive")
    void csvStringEqualityCaseSensitive() {
        put("case2.csv", "name,age\nAlice,30\nBob,25");
        String result = select("case2.csv", "SELECT * FROM S3Object WHERE name = 'alice'", CSV_USE, CSV_OUT);
        assertThat(result).doesNotContain("Alice").doesNotContain("Bob");
    }

    // ── FileHeaderInfo modes ──────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("CSV: FileHeaderInfo=NONE treats header line as data")
    void csvFileHeaderInfoNone() {
        put("none.csv", "name,age\nAlice,30\nBob,25");
        String result = select("none.csv", "SELECT _1 FROM S3Object", CSV_NONE, CSV_OUT);
        assertThat(result).contains("name").contains("Alice").contains("Bob");
    }

    @Test
    @Order(7)
    @DisplayName("CSV: FileHeaderInfo=IGNORE skips header row")
    void csvFileHeaderInfoIgnore() {
        put("ignore.csv", "name,age\nAlice,30\nBob,25");
        String result = select("ignore.csv", "SELECT _1 FROM S3Object", CSV_IGNORE, CSV_OUT);
        assertThat(result).contains("Alice").contains("Bob").doesNotContain("name");
    }

    // ── Quoted fields ─────────────────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("CSV: quoted field with embedded comma is not split")
    void csvQuotedField() {
        put("quoted.csv", "name,age\n\"Smith, John\",35\n\"Doe, Jane\",22");
        String result = select("quoted.csv", "SELECT * FROM S3Object WHERE age > 30", CSV_USE, CSV_OUT);
        assertThat(result).contains("Smith, John").doesNotContain("Doe, Jane");
    }

    // ── Enhanced WHERE operators ──────────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("CSV: WHERE AND")
    void csvWhereAnd() {
        put("and.csv", "name,age\nAlice,30\nBob,20\nCharlie,35");
        String result = select("and.csv", "SELECT * FROM S3Object WHERE age > 20 AND age < 35", CSV_USE, CSV_OUT);
        assertThat(result).contains("Alice").doesNotContain("Bob").doesNotContain("Charlie");
    }

    @Test
    @Order(10)
    @DisplayName("CSV: WHERE OR")
    void csvWhereOr() {
        put("or.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        String result = select("or.csv",
                "SELECT * FROM S3Object WHERE name = 'Alice' OR name = 'Charlie'", CSV_USE, CSV_OUT);
        assertThat(result).contains("Alice").contains("Charlie").doesNotContain("Bob");
    }

    @Test
    @Order(11)
    @DisplayName("CSV: WHERE LIKE")
    void csvWhereLike() {
        put("like.csv", "name,age\nAlice,30\nBob,25\nAlex,28");
        String result = select("like.csv", "SELECT * FROM S3Object WHERE name LIKE 'Al%'", CSV_USE, CSV_OUT);
        assertThat(result).contains("Alice").contains("Alex").doesNotContain("Bob");
    }

    @Test
    @Order(12)
    @DisplayName("CSV: WHERE BETWEEN")
    void csvWhereBetween() {
        put("between.csv", "name,age\nAlice,30\nBob,20\nCharlie,25");
        String result = select("between.csv",
                "SELECT * FROM S3Object WHERE age BETWEEN 25 AND 30", CSV_USE, CSV_OUT);
        assertThat(result).contains("Alice").contains("Charlie").doesNotContain("Bob");
    }

    @Test
    @Order(13)
    @DisplayName("CSV: WHERE IN")
    void csvWhereIn() {
        put("in.csv", "name,age\nAlice,30\nBob,25\nCharlie,35");
        String result = select("in.csv",
                "SELECT * FROM S3Object WHERE name IN ('Alice', 'Charlie')", CSV_USE, CSV_OUT);
        assertThat(result).contains("Alice").contains("Charlie").doesNotContain("Bob");
    }

    @Test
    @Order(14)
    @DisplayName("CSV: WHERE IS NULL")
    void csvWhereIsNull() {
        put("isnull.csv", "name,age,city\nAlice,30,NY\nBob,25");
        String result = select("isnull.csv",
                "SELECT name FROM S3Object WHERE city IS NULL", CSV_USE, CSV_OUT);
        assertThat(result).contains("Bob").doesNotContain("Alice");
    }

    // ── JSON input ────────────────────────────────────────────────────────────

    @Test
    @Order(15)
    @DisplayName("JSON Lines: WHERE clause")
    void jsonLinesWhere() {
        put("where.json",
                "{\"name\":\"Alice\",\"age\":30}\n{\"name\":\"Bob\",\"age\":25}\n{\"name\":\"Charlie\",\"age\":35}");
        String result = select("where.json", "SELECT * FROM S3Object WHERE age > 30", JSON_IN, JSON_OUT);
        assertThat(result).contains("Charlie").doesNotContain("Alice").doesNotContain("Bob");
    }

    @Test
    @Order(16)
    @DisplayName("JSON Lines: projection")
    void jsonLinesProjection() {
        put("proj.json",
                "{\"name\":\"Alice\",\"age\":30,\"city\":\"NY\"}\n{\"name\":\"Bob\",\"age\":25,\"city\":\"LA\"}");
        String result = select("proj.json", "SELECT name FROM S3Object", JSON_IN, JSON_OUT);
        assertThat(result).contains("Alice").contains("Bob")
                .doesNotContain("\"age\"").doesNotContain("\"city\"");
    }

    @Test
    @Order(17)
    @DisplayName("JSON Lines: IS NULL")
    void jsonLinesIsNull() {
        put("null.json",
                "{\"name\":\"Alice\",\"age\":30,\"city\":\"NY\"}\n{\"name\":\"Bob\",\"age\":25}");
        String result = select("null.json",
                "SELECT name FROM S3Object WHERE city IS NULL", JSON_IN, JSON_OUT);
        assertThat(result).contains("Bob").doesNotContain("Alice");
    }

    // ── Cross-format output ───────────────────────────────────────────────────

    @Test
    @Order(18)
    @DisplayName("CSV input → JSON output")
    void csvInputJsonOutput() {
        put("xfmt.csv", "name,age\nAlice,30\nBob,25");
        String result = select("xfmt.csv", "SELECT * FROM S3Object", CSV_USE, JSON_OUT);
        assertThat(result).contains("\"name\"").contains("\"Alice\"").contains("\"Bob\"");
    }

    @Test
    @Order(19)
    @DisplayName("JSON input → CSV output")
    void jsonInputCsvOutput() {
        put("xfmt.json", "{\"name\":\"Alice\",\"age\":30}\n{\"name\":\"Bob\",\"age\":25}");
        String result = select("xfmt.json", "SELECT name, age FROM S3Object", JSON_IN, CSV_OUT);
        assertThat(result).contains("Alice").contains("Bob");
    }
}
