package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.DataFormatConversionConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Deserializer;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.HiveJsonSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.InputFormatConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.OpenXJsonSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.OutputFormatConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ParquetSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.SchemaConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Serializer;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Conversion of the complex Hive types, kept apart from
 * {@link FirehoseParquetConverterTest} because the scalar suite there is already
 * a long one.
 *
 * The coercion asserted here was probed against real AWS on 2026-09-12 with a
 * table of exactly these four columns: a value that is not an array is wrapped
 * into one, an array fills a struct by position, map keys are lowercased, an
 * undeclared struct member is dropped and a missing one is null, and a mismatch
 * at any depth fails that record alone.
 */
class FirehoseParquetComplexTypesTest {

    private static final String BUCKET = "results";
    private static final String STAGING = "floci-firehose-staging";
    private static final Instant DELIVERY_TIME = Instant.parse("2026-01-01T00:00:00Z");

    private GlueService glueService;
    private FlociDuckClient duckClient;
    private S3Service s3Service;
    private FirehoseParquetConverter converter;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        glueService = mock(GlueService.class);
        duckClient = mock(FlociDuckClient.class);
        s3Service = mock(S3Service.class);
        converter = new FirehoseParquetConverter(glueService, duckClient, s3Service, mapper,
                new RegionResolver("us-east-1", "000000000000"), STAGING);
        when(s3Service.getObject(eq(STAGING), anyString())).thenReturn(new S3Object(STAGING,
                "staged.parquet", "PAR1staged".getBytes(StandardCharsets.UTF_8),
                "application/octet-stream"));
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("id", "string"),
                new Column("tags", "array<string>"),
                new Column("person", "struct<name:string,age:int>"),
                new Column("items", "array<struct<sku:string,qty:int>>"),
                new Column("attrs", "map<string,array<string>>")));
    }

    private static Table table(Column... columns) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setColumns(Arrays.asList(columns));
        Table table = new Table();
        table.setStorageDescriptor(descriptor);
        return table;
    }

    private static DeliveryStreamDescription stream() {
        return stream(new OpenXJsonSerDe());
    }

    private static DeliveryStreamDescription hiveStream() {
        DeliveryStreamDescription stream = stream(null);
        stream.s3Destination().getDataFormatConversionConfiguration().getInputFormatConfiguration()
                .getDeserializer().setHiveJsonSerDe(new HiveJsonSerDe());
        return stream;
    }

    private static DeliveryStreamDescription stream(OpenXJsonSerDe openX) {
        SchemaConfiguration schema = new SchemaConfiguration();
        schema.setRoleArn("arn:aws:iam::000000000000:role/firehose-role");
        schema.setDatabaseName("db");
        schema.setTableName("events");
        Deserializer deserializer = new Deserializer();
        deserializer.setOpenXJsonSerDe(openX);
        InputFormatConfiguration input = new InputFormatConfiguration();
        input.setDeserializer(deserializer);
        Serializer serializer = new Serializer();
        serializer.setParquetSerDe(new ParquetSerDe());
        OutputFormatConfiguration output = new OutputFormatConfiguration();
        output.setSerializer(serializer);
        DataFormatConversionConfiguration conversion = new DataFormatConversionConfiguration();
        conversion.setEnabled(true);
        conversion.setSchemaConfiguration(schema);
        conversion.setInputFormatConfiguration(input);
        conversion.setOutputFormatConfiguration(output);
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::" + BUCKET);
        s3.setPrefix("data/");
        s3.setErrorOutputPrefix("errors/!{firehose:error-output-type}/");
        s3.setCompressionFormat("UNCOMPRESSED");
        s3.setDataFormatConversionConfiguration(conversion);
        return new DeliveryStreamDescription("stream", "arn:aws:firehose:::stream", s3);
    }

    private static byte[] record(String json) {
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private String stagedNdjson() {
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq(STAGING), startsWith("firehose-staging/stream/"),
                body.capture(), eq("application/x-ndjson"), anyMap());
        return new String(body.getValue(), StandardCharsets.UTF_8);
    }

    private String duckColumns() {
        ArgumentCaptor<String> setupSql = ArgumentCaptor.forClass(String.class);
        verify(duckClient).execute(eq("SELECT 1 AS ok"), setupSql.capture(), isNull(), any());
        return setupSql.getValue();
    }

    private JsonNode singleErrorLine() throws Exception {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, atLeastOnce()).putObject(eq(BUCKET), key.capture(),
                body.capture(), eq("application/octet-stream"), anyMap());
        for (int i = 0; i < key.getAllValues().size(); i++) {
            if (key.getAllValues().get(i).startsWith("errors/format-conversion-failed/")) {
                String object = new String(body.getAllValues().get(i), StandardCharsets.UTF_8);
                return mapper.readTree(object.strip().split("\n")[0]);
            }
        }
        throw new AssertionError("no error object among " + key.getAllValues());
    }

    @Test
    void complexColumnsAreTypedForDuckFromTheGlueSchema() {
        converter.deliver(stream(), BUCKET, List.of(record("{\"id\": \"a\"}")), DELIVERY_TIME);

        assertTrue(duckColumns().contains("columns={'id': 'VARCHAR', 'tags': 'VARCHAR[]',"
                        + " 'person': 'STRUCT(\"name\" VARCHAR, \"age\" INTEGER)',"
                        + " 'items': 'STRUCT(\"sku\" VARCHAR, \"qty\" INTEGER)[]',"
                        + " 'attrs': 'MAP(VARCHAR, VARCHAR[])'}"),
                "setup sql was: " + duckColumns());
    }

    @Test
    void nestedValuesAreStagedAsTheyWereGiven() {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r1\", \"tags\": [\"a\", \"b\"], \"person\": {\"name\": \"Alice\","
                        + " \"age\": 30}, \"items\": [{\"sku\": \"s1\", \"qty\": 2}],"
                        + " \"attrs\": {\"k1\": [\"v1\", \"v2\"]}}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertTrue(stagedNdjson().contains("{\"id\":\"r1\",\"tags\":[\"a\",\"b\"],"
                        + "\"person\":{\"name\":\"Alice\",\"age\":30},"
                        + "\"items\":[{\"sku\":\"s1\",\"qty\":2}],\"attrs\":{\"k1\":[\"v1\",\"v2\"]}}"),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void aValueThatIsNotAnArrayIsWrappedIntoOne() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r6\", \"tags\": \"notanarray\","
                        + " \"items\": {\"sku\": \"x\", \"qty\": 1}}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"tags\":[\"notanarray\"]"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"items\":[{\"sku\":\"x\",\"qty\":1}]"), "staged rows were: " + ndjson);
    }

    @Test
    void anArrayFillsAStructByPositionAndIgnoresTheExtras() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r7\", \"person\": [\"Zoe\", 9, \"ignored\"]}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"person\":{\"name\":\"Zoe\",\"age\":9}"),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void aShorterArrayLeavesTheRemainingMembersNull() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r7\", \"person\": [\"Zoe\"]}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"person\":{\"name\":\"Zoe\",\"age\":null}"),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void structMembersMatchCaseInsensitivelyAtEveryDepth() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r2\", \"PERSON\": {\"NAME\": \"Bob\", \"AGE\": 41},"
                        + " \"ITEMS\": [{\"SKU\": \"s2\", \"QTY\": \"3\"}]}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"person\":{\"name\":\"Bob\",\"age\":41}"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"items\":[{\"sku\":\"s2\",\"qty\":3}]"), "staged rows were: " + ndjson);
    }

    @Test
    void mapKeysAreLowercased() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"s1\", \"attrs\": {\"MixedKey\": [\"v\"]}}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"attrs\":{\"mixedkey\":[\"v\"]}"),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void anUndeclaredStructMemberIsDroppedAndAMissingOneIsNull() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r3\", \"person\": {\"name\": \"Carol\", \"extra\": \"dropped\"}}")),
                DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"person\":{\"name\":\"Carol\",\"age\":null}"),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void anEmptyObjectIsAStructOfNullMembersRatherThanANullStruct() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r12\", \"person\": {}, \"tags\": [], \"attrs\": {}}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"person\":{\"name\":null,\"age\":null}"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"tags\":[]"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"attrs\":{}"), "staged rows were: " + ndjson);
    }

    @Test
    void nullsInsideContainersAreKept() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r8\", \"tags\": [\"a\", null, \"c\"], \"items\": [null],"
                        + " \"attrs\": {\"k\": null}}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"tags\":[\"a\",null,\"c\"]"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"items\":[null]"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"attrs\":{\"k\":null}"), "staged rows were: " + ndjson);
    }

    @Test
    void scalarsAreStillCoercedInsideContainers() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r10\", \"tags\": [1, 2, 3]}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"tags\":[\"1\",\"2\",\"3\"]"),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void aNonScalarInATextColumnKeepsItsCompactJson() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": {\"nested\": 1}, \"tags\": [[\"a\", \"b\"]]}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"id\":\"{\\\"nested\\\":1}\""), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"tags\":[\"[\\\"a\\\",\\\"b\\\"]\"]"), "staged rows were: " + ndjson);
    }

    /**
     * Probed 2026-09-12: OpenX lowercases the keys of every nested object before the
     * value is read, at any depth, so the JSON text a stringified object leaves in a
     * text column carries lowercased keys.
     */
    @Test
    void openXLowercasesTheKeysOfAStringifiedObjectAtEveryDepth() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": {\"Outer\": {\"Inner\": 1}}, \"tags\": [{\"MixedKey\": 1}],"
                        + " \"attrs\": {\"K\": [\"v\"]}}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"id\":\"{\\\"outer\\\":{\\\"inner\\\":1}}\""),
                "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"tags\":[\"{\\\"mixedkey\\\":1}\"]"),
                "staged rows were: " + ndjson);
    }

    @Test
    void aScalarForAStructFailsTheRecord() throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"id\": \"r5\", \"person\": 42}")), DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        JsonNode line = singleErrorLine();
        assertEquals("DataFormatConversion.MalformedData", line.get("lastErrorCode").asText());
        assertEquals("Data does not match the schema. Data is not JSONObject  but"
                + " java.lang.Long with value 42", line.get("lastErrorMessage").asText());
    }

    @Test
    void anArrayForAMapFailsTheRecord() throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"id\": \"r9\", \"attrs\": [[\"k\", [\"v\"]]]}")), DELIVERY_TIME);

        assertEquals(1, outcome.failedRecords());
        JsonNode line = singleErrorLine();
        assertEquals("DataFormatConversion.MalformedData", line.get("lastErrorCode").asText());
        assertEquals("Data does not match the schema. A map column cannot hold a JSON array.",
                line.get("lastErrorMessage").asText());
    }

    @Test
    void aMismatchInsideAnArrayFailsTheWholeRecordWithTheProbedMessage() throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"r5\", \"items\": [{\"sku\": \"s5\", \"qty\": \"oops\"}]}")),
                DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        JsonNode line = singleErrorLine();
        assertEquals("DataFormatConversion.MalformedData", line.get("lastErrorCode").asText());
        assertEquals("Data does not match the schema. For input string: \"oops\"",
                line.get("lastErrorMessage").asText());
    }

    @Test
    void oneFailingNestedRecordLeavesTheRestOfTheBatchConverting() {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"id\": \"good\", \"person\": {\"name\": \"Alice\", \"age\": 1}}"),
                record("{\"id\": \"bad\", \"person\": 42}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
    }

    /**
     * Probed 2026-09-12 on a separate HiveJsonSerDe rig: that SerDe resolves column
     * and struct member names case-insensitively just as OpenX does, but it leaves
     * a map entry key exactly as the record wrote it, where OpenX lowercases every
     * JSON key before deserializing.
     */
    @Test
    void hiveJsonSerDeKeepsMapKeysWhileStillMatchingMembersCaseInsensitively() {
        converter.deliver(hiveStream(), BUCKET, List.of(
                record("{\"id\": \"h1\", \"ATTRS\": {\"MixedKey\": [\"v\"]},"
                        + " \"PERSON\": {\"NAME\": \"Bob\", \"AGE\": 2}}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"attrs\":{\"MixedKey\":[\"v\"]}"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"person\":{\"name\":\"Bob\",\"age\":2}"), "staged rows were: " + ndjson);
    }

    /**
     * Probed 2026-09-12: with the option off, a struct member under a column that
     * did match is left null unless the record spells it exactly, and map keys keep
     * the case they were written in.
     */
    @Test
    void caseSensitiveOpenXMatchesNestedMembersExactlyAndKeepsMapKeys() {
        OpenXJsonSerDe openX = new OpenXJsonSerDe();
        openX.setCaseInsensitive(false);

        converter.deliver(stream(openX), BUCKET, List.of(
                record("{\"id\": \"s\", \"person\": {\"NAME\": \"Bob\", \"age\": 2},"
                        + " \"attrs\": {\"MixedKey\": [\"v\"]}}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"person\":{\"name\":null,\"age\":2}"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"attrs\":{\"MixedKey\":[\"v\"]}"), "staged rows were: " + ndjson);
    }

    @Test
    void nestedContainersAreTypedAllTheWayDown() {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("deep",
                "array<map<string,struct<a:int,b:array<decimal(10,2)>>>>")));

        converter.deliver(stream(), BUCKET, List.of(record("{\"deep\": []}")), DELIVERY_TIME);

        assertTrue(duckColumns().contains("columns={'deep': 'MAP(VARCHAR,"
                        + " STRUCT(\"a\" INTEGER, \"b\" DECIMAL(10,2)[]))[]'}"),
                "setup sql was: " + duckColumns());
    }

    /**
     * The SES event-publishing schema, the shape that motivated this: deeply
     * nested, with members named after SQL keywords, which is why every struct
     * member is quoted in the DuckDB type.
     */
    @Test
    void aRealSesEventSchemaIsTypedAllTheWayDown() {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("mail",
                "struct<timestamp:string,source:string,destination:array<string>,"
                        + "headers:array<struct<name:string,value:string>>,"
                        + "commonheaders:struct<from:array<string>,to:array<string>,subject:string>,"
                        + "tags:map<string,array<string>>>")));

        converter.deliver(stream(), BUCKET, List.of(record("{\"mail\": {\"subject\": \"hi\"}}")),
                DELIVERY_TIME);

        assertTrue(duckColumns().contains("columns={'mail': 'STRUCT(\"timestamp\" VARCHAR,"
                        + " \"source\" VARCHAR, \"destination\" VARCHAR[],"
                        + " \"headers\" STRUCT(\"name\" VARCHAR, \"value\" VARCHAR)[],"
                        + " \"commonheaders\" STRUCT(\"from\" VARCHAR[], \"to\" VARCHAR[],"
                        + " \"subject\" VARCHAR), \"tags\" MAP(VARCHAR, VARCHAR[]))'}"),
                "setup sql was: " + duckColumns());
    }

    /** Hive doubles a backtick to put one inside a quoted member name. */
    @Test
    void aDoubledBackQuoteIsOneBackQuoteInTheMemberName() {
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("row", "struct<`a``b`:string>")));

        converter.deliver(stream(), BUCKET, List.of(record("{\"row\": {\"a`b\": \"x\"}}")),
                DELIVERY_TIME);

        assertTrue(duckColumns().contains("columns={'row': 'STRUCT(\"a`b\" VARCHAR)'}"),
                "setup sql was: " + duckColumns());
        assertTrue(stagedNdjson().contains("\"row\":{\"a`b\":\"x\"}"),
                "staged rows were: " + stagedNdjson());
    }

    /** Space around the punctuation is fine; only space joining two numbers is not. */
    @Test
    void aWidthSpacedAroundItsPunctuationStillParses() {
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("amount", "decimal(10, 2)"), new Column("note", "varchar( 255 )")));

        converter.deliver(stream(), BUCKET, List.of(record("{\"amount\": 1.5}")), DELIVERY_TIME);

        assertTrue(duckColumns().contains("columns={'amount': 'DECIMAL(10,2)', 'note': 'VARCHAR'}"),
                "setup sql was: " + duckColumns());
    }

    @Test
    void aBackQuotedStructMemberKeepsItsName() {
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("row", "struct<`order`:string>")));

        converter.deliver(stream(), BUCKET, List.of(record("{\"row\": {\"order\": \"x\"}}")),
                DELIVERY_TIME);

        assertTrue(duckColumns().contains("columns={'row': 'STRUCT(\"order\" VARCHAR)'}"),
                "setup sql was: " + duckColumns());
        assertTrue(stagedNdjson().contains("\"row\":{\"order\":\"x\"}"),
                "staged rows were: " + stagedNdjson());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "binary",
        "array<binary>",
        "uniontype<int,string>",
        "struct<a:uniontype<int,string>>",
        "array<string",
        "struct<a:>",
        "varchar(6 5535)",
        "decimal(1 0,2)",
        "struct<a:int,a:string>",
        "struct<a:int,A:string>",
        "map<struct<a:string>,int>",
        "map<array<string>,int>",
        "struct<:string>",
        "map<string>",
        "array<string>garbage",
        "struct<a:string>,"
    })
    void aTypeFlociCannotRepresentFailsTheWholeBatch(String hiveType) throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("col", hiveType)));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"col\": null}")), DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        assertEquals("DataFormatConversion.UnsupportedSchema",
                singleErrorLine().get("lastErrorCode").asText());
    }

    /** A hostile schema must report an unsupported type rather than overflow the parse. */
    @Test
    void nestingDeeperThanTheParserAcceptsIsUnsupported() throws Exception {
        String hiveType = "array<".repeat(40) + "string" + ">".repeat(40);
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("col", hiveType)));

        converter.deliver(stream(), BUCKET, List.of(record("{\"col\": null}")), DELIVERY_TIME);

        assertEquals("DataFormatConversion.UnsupportedSchema",
                singleErrorLine().get("lastErrorCode").asText());
    }
}
