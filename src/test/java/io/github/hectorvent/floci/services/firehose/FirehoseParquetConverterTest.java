package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.DataFormatConversionConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Deserializer;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The error codes, message shapes, and the per-record routing asserted here
 * follow the delivery behavior probed against real AWS (see okinaka/floci
 * discussion 118): parse failures and schema mismatches fail individually
 * while the rest of the batch converts; batch-level failure codes are Floci's
 * own, documented in docs/services/firehose.md.
 */
class FirehoseParquetConverterTest {

    private static final String BUCKET = "results";
    private static final String STAGING = "floci-firehose-staging";
    private static final Instant DELIVERY_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] STAGED_PARQUET = "PAR1staged".getBytes(StandardCharsets.UTF_8);

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
        // The staged Parquet the sidecar would have written, read back at delivery.
        when(s3Service.getObject(eq(STAGING), anyString())).thenReturn(
                new S3Object(STAGING, "staged.parquet", STAGED_PARQUET, "application/octet-stream"));
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("ticker", "string"),
                new Column("price", "double"),
                new Column("qty", "int"),
                new Column("active", "boolean"),
                new Column("ts", "timestamp")));
    }

    private static Table table(Column... columns) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setColumns(java.util.Arrays.asList(columns));
        Table table = new Table();
        table.setStorageDescriptor(descriptor);
        return table;
    }

    private static DeliveryStreamDescription stream() {
        SchemaConfiguration schema = new SchemaConfiguration();
        schema.setRoleArn("arn:aws:iam::000000000000:role/firehose-role");
        schema.setDatabaseName("db");
        schema.setTableName("events");
        Deserializer deserializer = new Deserializer();
        deserializer.setOpenXJsonSerDe(new OpenXJsonSerDe());
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

    /** The one error object among the destination writes, which now also carry the Parquet. */
    private List<JsonNode> errorLines() throws Exception {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, org.mockito.Mockito.atLeastOnce()).putObject(eq(BUCKET), key.capture(),
                body.capture(), eq("application/octet-stream"), anyMap());

        List<Integer> errorWrites = new ArrayList<>();
        for (int i = 0; i < key.getAllValues().size(); i++) {
            if (key.getAllValues().get(i).startsWith("errors/format-conversion-failed/")) {
                errorWrites.add(i);
            }
        }
        assertEquals(1, errorWrites.size(), "error writes were " + key.getAllValues());

        List<JsonNode> lines = new ArrayList<>();
        String object = new String(body.getAllValues().get(errorWrites.get(0)), StandardCharsets.UTF_8);
        for (String line : object.strip().split("\n")) {
            lines.add(mapper.readTree(line));
        }
        return lines;
    }

    @Test
    void convertsValidRecordsAndNormalizesValuesForDuck() {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"ticker\": \"AAA\", \"price\": 1.5, \"qty\": 10, \"active\": true,"
                        + " \"ts\": \"2026-09-07 12:00:00\"}"),
                record("{\"ticker\": \"MISSING_FIELDS\"}")), DELIVERY_TIME);

        assertEquals(2, outcome.convertedRecords());
        assertEquals(0, outcome.failedRecords());
        assertTrue(outcome.dataKey().startsWith("data/2026/01/01/00/stream-1-2026-01-01-00-00-00-"));
        assertTrue(outcome.dataKey().endsWith(".parquet"));
        assertNull(outcome.errorKey());

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("{\"ticker\":\"AAA\",\"price\":1.5,\"qty\":10,\"active\":true,"
                + "\"ts\":\"2026-09-07 12:00:00\"}"), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("{\"ticker\":\"MISSING_FIELDS\",\"price\":null,\"qty\":null,"
                + "\"active\":null,\"ts\":null}"), "staged rows were: " + ndjson);

        ArgumentCaptor<String> setupSql = ArgumentCaptor.forClass(String.class);
        // No output path: the sidecar's CSV export would be a second failure point
        // after the Parquet object is already written.
        verify(duckClient).execute(eq("SELECT 1 AS ok"), setupSql.capture(), isNull(), any());
        assertTrue(setupSql.getValue().contains(
                "columns={'ticker': 'VARCHAR', 'price': 'DOUBLE', 'qty': 'INTEGER',"
                        + " 'active': 'BOOLEAN', 'ts': 'TIMESTAMP'}"),
                "setup sql was: " + setupSql.getValue());
        assertTrue(setupSql.getValue().contains("TO 's3://" + STAGING + "/firehose-staging/stream/"),
                "setup sql was: " + setupSql.getValue());
        assertTrue(setupSql.getValue().contains(".parquet' (FORMAT PARQUET, COMPRESSION SNAPPY)"),
                "setup sql was: " + setupSql.getValue());

        // The destination key is written once, and with a Put so its notification
        // matches the unconverted delivery path.
        ArgumentCaptor<byte[]> delivered = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq(BUCKET), eq(outcome.dataKey()), delivered.capture(),
                eq("application/octet-stream"), anyMap());
        assertEquals("PAR1staged", new String(delivered.getValue(), StandardCharsets.UTF_8));
        verify(s3Service).deleteObject(eq(STAGING), endsWithSuffix(".ndjson"));
        verify(s3Service).deleteObject(eq(STAGING), endsWithSuffix(".parquet"));
    }

    private static String endsWithSuffix(String suffix) {
        return org.mockito.ArgumentMatchers.argThat(key -> key != null && key.endsWith(suffix));
    }

    @Test
    void schemaMismatchAndUnparseableRecordsFailIndividually() throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"ticker\": \"AAA\", \"price\": 1.5, \"qty\": 10, \"active\": true,"
                        + " \"ts\": \"2026-09-07 12:00:00\"}"),
                record("{\"ticker\": \"BAD\", \"price\": \"oops\"}"),
                "this is not json at all\n".getBytes(StandardCharsets.UTF_8)), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertEquals(2, outcome.failedRecords());
        assertNotNull(outcome.dataKey());

        List<JsonNode> lines = errorLines();
        assertEquals(2, lines.size());
        JsonNode mismatch = lines.get(0);
        assertEquals("DataFormatConversion.MalformedData", mismatch.get("lastErrorCode").asText());
        assertEquals("Data does not match the schema. For input string: \"oops\"",
                mismatch.get("lastErrorMessage").asText());
        assertEquals(1, mismatch.get("attemptsMade").asInt());
        assertEquals(DELIVERY_TIME.toEpochMilli(), mismatch.get("arrivalTimestamp").asLong());
        assertEquals("{\"ticker\": \"BAD\", \"price\": \"oops\"}\n",
                new String(Base64.getDecoder().decode(mismatch.get("rawData").asText()),
                        StandardCharsets.UTF_8));
        assertEquals("000000000000", mismatch.get("dataCatalogTable").get("catalogId").asText());
        assertEquals("db", mismatch.get("dataCatalogTable").get("databaseName").asText());
        assertEquals("LATEST", mismatch.get("dataCatalogTable").get("versionId").asText());

        JsonNode unparseable = lines.get(1);
        assertEquals("DataFormatConversion.ParseError", unparseable.get("lastErrorCode").asText());
        assertTrue(unparseable.get("lastErrorMessage").asText().startsWith("Encountered malformed JSON."),
                "message was: " + unparseable.get("lastErrorMessage").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n"})
    void emptyRecordsFailAsParseErrorRatherThanMalformedData(String content) throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(content.getBytes(StandardCharsets.UTF_8)), DELIVERY_TIME);

        assertEquals(1, outcome.failedRecords());
        assertEquals("DataFormatConversion.ParseError", errorLines().get(0).get("lastErrorCode").asText());
    }

    @Test
    void trailingContentAfterTheJsonObjectFailsTheRecordAsParseError() throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"ticker\": \"AAA\"}"),
                record("{\"ticker\": \"BAD\"} and then some")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        assertEquals("DataFormatConversion.ParseError", errorLines().get(0).get("lastErrorCode").asText());
    }

    // Values DuckDB's CAST would reject must fail their own record, not the COPY:
    // a batch-wide InternalError would lose the records that do fit the column.
    @Test
    void integralValuesOutsideTheColumnWidthFailOnlyTheirOwnRecord() throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("qty", "tinyint")));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"qty\": 127}"),
                record("{\"qty\": 128}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        assertTrue(stagedNdjson().contains("{\"qty\":127}"), "staged rows were: " + stagedNdjson());

        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.MalformedData", line.get("lastErrorCode").asText());
        assertEquals("Data does not match the schema. Value 128 is out of range for column qty"
                + " of type TINYINT.", line.get("lastErrorMessage").asText());
    }

    @Test
    void decimalValuesRoundToScaleAndFailOnlyWhenThePrecisionOverflows() throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("amount", "decimal(4,2)")));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"amount\": 12.345}"),
                record("{\"amount\": 12345.67}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        assertTrue(stagedNdjson().contains("{\"amount\":12.35}"), "staged rows were: " + stagedNdjson());

        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.MalformedData", line.get("lastErrorCode").asText());
        assertEquals("Data does not match the schema. Value 12345.67 is out of range for column amount"
                + " of type DECIMAL(4,2).", line.get("lastErrorMessage").asText());
    }

    @Test
    void epochMillisBeyondTheTimestampRangeFailOnlyTheirOwnRecord() throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"ticker\": \"AAA\", \"ts\": 1757246400000}"),
                record("{\"ticker\": \"BAD\", \"ts\": 18446744073709551616}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        assertTrue(stagedNdjson().contains("\"ts\":\"2025-09-07 12:00:00.000\""),
                "staged rows were: " + stagedNdjson());

        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.MalformedData", line.get("lastErrorCode").asText());
        assertEquals("Data does not match the schema. Value 18446744073709551616 is out of range"
                + " for column ts of type TIMESTAMP.", line.get("lastErrorMessage").asText());
    }

    @Test
    void errorMetadataFallsBackToTheStreamsOwnAccountAndRegion() throws Exception {
        DeliveryStreamDescription stream = new DeliveryStreamDescription("stream",
                "arn:aws:firehose:eu-west-1:111122223333:deliverystream/stream",
                stream().s3Destination());
        stream.setAccountId("111122223333");

        converter.deliver(stream, BUCKET, List.of(record("not json")), DELIVERY_TIME);

        JsonNode catalogTable = errorLines().get(0).get("dataCatalogTable");
        assertEquals("111122223333", catalogTable.get("catalogId").asText());
        assertEquals("eu-west-1", catalogTable.get("region").asText());
    }

    // Probed against real AWS (us-west-2, 2026-09-08): an epoch number below 1e12
    // is seconds and one at or above it is milliseconds, in both the numeric and
    // the string spelling, and a fractional value keeps its sub-second part.
    @ParameterizedTest
    @CsvSource({
            "1518033528, 2018-02-07 19:58:48.000",
            "1518033528123, 2018-02-07 19:58:48.123",
            "999999999999, 33658-09-27 01:46:39.000",
            "1000000000000, 2001-09-09 01:46:40.000",
            "1518033528.123, 2018-02-07 19:58:48.123",
            "0, 1970-01-01 00:00:00.000",
            "-1000, 1969-12-31 23:43:20.000",
    })
    void epochNumbersFollowTheProbedSecondsOrMillisRule(String epoch, String expected) {
        converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\", \"ts\": " + epoch + "}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"ts\":\"" + expected + "\""),
                "staged rows were: " + stagedNdjson());
    }

    @ParameterizedTest
    @CsvSource({
            "1518033528, 2018-02-07 19:58:48.000",
            "1518033528123, 2018-02-07 19:58:48.123",
    })
    void epochNumbersAreAlsoAcceptedAsStrings(String epoch, String expected) {
        converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\", \"ts\": \"" + epoch + "\"}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"ts\":\"" + expected + "\""),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void columnNamesOutsideSqlIdentifiersAreConvertedAndEscaped() {
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("event-id", "string"),
                new Column("it's odd", "string")));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"event-id\": \"E1\", \"it's odd\": \"yes\"}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertTrue(stagedNdjson().contains("{\"event-id\":\"E1\",\"it's odd\":\"yes\"}"),
                "staged rows were: " + stagedNdjson());

        ArgumentCaptor<String> setupSql = ArgumentCaptor.forClass(String.class);
        verify(duckClient).execute(anyString(), setupSql.capture(), isNull(), any());
        assertTrue(setupSql.getValue().contains("columns={'event-id': 'VARCHAR', 'it''s odd': 'VARCHAR'}"),
                "setup sql was: " + setupSql.getValue());
    }

    // The range check accepts it, so the formatter must not reject the year's sign.
    // Probed against real AWS: a space-separated timestamp is read leniently, down to
    // one-digit hours, while the 'T' form stays strict ISO and rejects a short month.
    @ParameterizedTest
    @CsvSource({
            "'2026-09-07 12:00:02', '2026-09-07 12:00:02'",
            "'2026-9-7 12:00:02', '2026-09-07 12:00:02'",
            "'2026-9-07 12:00:02', '2026-09-07 12:00:02'",
            "'2026-09-07 1:2:3', '2026-09-07 01:02:03'",
            "'2026-9-7 12:00:02.5', '2026-09-07 12:00:02.5'",
            "'2018-02-07T20:38:48Z', '2018-02-07 20:38:48'",
    })
    void spaceSeparatedTimestampsAreReadLeniently(String written, String staged) {
        converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\", \"ts\": \"" + written + "\"}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"ts\":\"" + staged + "\""),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void anIsoTimestampWithAShortMonthFailsItsRecord() throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\", \"ts\": \"2026-9-7T12:00:02Z\"}")), DELIVERY_TIME);

        assertEquals(1, outcome.failedRecords());
        assertEquals("DataFormatConversion.MalformedData",
                errorLines().get(0).get("lastErrorCode").asText());
    }

    @ParameterizedTest
    @CsvSource({"'2026-09-07', '2026-09-07'", "'2026-9-7', '2026-09-07'", "'2026-9-07', '2026-09-07'"})
    void dateColumnsAreReadLenientlyToo(String written, String staged) {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("d", "date")));

        converter.deliver(stream(), BUCKET,
                List.of(record("{\"d\": \"" + written + "\"}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"d\":\"" + staged + "\""),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void epochsBeforeYearZeroKeepTheirSign() {
        converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\", \"ts\": -62200000000}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"ts\":\"-0002-12-17 14:13:20.000\""),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void jsonDecimalsKeepTheirDigitsForDecimalColumns() {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("amount", "decimal(22,2)")));

        converter.deliver(stream(), BUCKET,
                List.of(record("{\"amount\": 12345678901234567890.12}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("{\"amount\":12345678901234567890.12}"),
                "staged rows were: " + stagedNdjson());
    }

    // A short record can name an enormous value. The range has to be settled from the
    // digit count, or materializing it to find out exhausts the heap.
    @ParameterizedTest
    @ValueSource(strings = {"decimal(22,2)", "timestamp"})
    void hugeExponentsFailTheirRecordWithoutBeingMaterialized(String hiveType) throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("value", hiveType)));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"value\": \"1e1000000000\"}")), DELIVERY_TIME);

        assertEquals(1, outcome.failedRecords());
        assertEquals("DataFormatConversion.MalformedData",
                errorLines().get(0).get("lastErrorCode").asText());
    }

    // A scale near Integer.MIN_VALUE overflows the digit count if it is taken as an int,
    // which would fold an enormous value back into the range the guard is checking.
    @ParameterizedTest
    @ValueSource(strings = {"decimal(22,2)", "timestamp"})
    void exponentsThatOverflowTheDigitCountStillFailTheirRecord(String hiveType) throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("value", hiveType)));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"value\": \"1e2147483647\"}")), DELIVERY_TIME);

        assertEquals(1, outcome.failedRecords());
        assertEquals("DataFormatConversion.MalformedData",
                errorLines().get(0).get("lastErrorCode").asText());
    }

    // Rounding these the long way would write the exponent out in full.
    @Test
    void exponentsTooSmallToSurviveRoundingBecomeZeroWithoutBeingMaterialized() {
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("amount", "decimal(22,2)"),
                new Column("ts", "timestamp")));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"amount\": \"1e-1000000000\", \"ts\": \"1e-1000000000\"}")),
                DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertTrue(stagedNdjson().contains("\"amount\":0.00"), "staged rows were: " + stagedNdjson());
        assertTrue(stagedNdjson().contains("\"ts\":\"1970-01-01 00:00:00.000\""),
                "staged rows were: " + stagedNdjson());
    }

    // Zero fits every column, whatever exponent the record used to write it.
    @ParameterizedTest
    @ValueSource(strings = {"0e1000000000", "0e-1000000000", "0", "0.0"})
    void exponentEncodedZeroIsAcceptedEverywhere(String written) {
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("amount", "decimal(22,2)"),
                new Column("ts", "timestamp")));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"amount\": \"" + written + "\", \"ts\": \"" + written + "\"}")),
                DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertTrue(stagedNdjson().contains("\"amount\":0.00"), "staged rows were: " + stagedNdjson());
        assertTrue(stagedNdjson().contains("\"ts\":\"1970-01-01 00:00:00.000\""),
                "staged rows were: " + stagedNdjson());
    }

    @Test
    void anUnreadableStagedParquetFailsTheBatchBeforeAnyDestinationWrite() throws Exception {
        when(s3Service.getObject(eq(STAGING), anyString()))
                .thenThrow(new RuntimeException("staged object is gone"));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"ticker\": \"AAA\"}"),
                record("not json")), DELIVERY_TIME);

        assertEquals(2, outcome.failedRecords());
        assertNull(outcome.dataKey());
        List<JsonNode> lines = errorLines();
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(line ->
                "DataFormatConversion.InternalError".equals(line.get("lastErrorCode").asText())),
                "error lines were: " + lines);
    }

    @Test
    void theSidecarRunsAsTheAccountTheRowsWereStagedAs() {
        DeliveryStreamDescription stream = stream();
        stream.setAccountId("111122223333");

        converter.deliver(stream, BUCKET, List.of(record("{\"ticker\": \"AAA\"}")), DELIVERY_TIME);

        // What routes the write is the account S3Service resolved, not the one
        // recorded on the stream: the sidecar must read back the partition the
        // staged rows landed in. Outside a request both are the default account.
        verify(duckClient).execute(anyString(), anyString(), isNull(), eq("000000000000"));
    }

    // The two writes are not coordinated, so their order decides what a failure of
    // the second one leaves behind. Writing the error output first means a failure
    // delivers nothing at all, which a Kinesis-backed stream can re-read, instead of
    // leaving the valid rows delivered and the failed records gone with the buffer.
    @Test
    void aFailedErrorOutputWriteLeavesNothingDelivered() {
        doThrow(new RuntimeException("s3 unavailable")).when(s3Service)
                .putObject(eq(BUCKET), anyString(), any(), anyString(), anyMap());

        assertThrows(RuntimeException.class, () -> converter.deliver(stream(), BUCKET, List.of(
                record("{\"ticker\": \"AAA\"}"),
                record("not json")), DELIVERY_TIME));

        verify(s3Service, never()).putObject(eq(BUCKET), endsWithSuffix(".parquet"), any(),
                anyString(), anyMap());
    }

    @Test
    void aSidecarFailureAfterPerRecordErrorsWritesOneErrorObject() throws Exception {
        doThrow(new RuntimeException("floci-duck is unreachable")).when(duckClient)
                .execute(anyString(), anyString(), isNull(), any());

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"ticker\": \"AAA\"}"),
                record("not json")), DELIVERY_TIME);

        assertEquals(2, outcome.failedRecords());
        assertNull(outcome.dataKey());

        // The conversion is settled before anything reaches the destination, so the
        // per-record failures never became an object that a batch failure must replace.
        List<JsonNode> lines = errorLines();
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(line ->
                "DataFormatConversion.InternalError".equals(line.get("lastErrorCode").asText())),
                "error lines were: " + lines);
        verify(s3Service, never()).putObject(eq(BUCKET), endsWithSuffix(".parquet"), any(),
                anyString(), anyMap());
    }

    // The COPY may have produced the object even when the call reports failure, and
    // the caller never learns its key, so cleanup has to happen where it is known.
    @Test
    void aFailedConversionRemovesTheStagedParquet() {
        doThrow(new RuntimeException("floci-duck is unreachable")).when(duckClient)
                .execute(anyString(), anyString(), isNull(), any());

        converter.deliver(stream(), BUCKET, List.of(record("{\"ticker\": \"AAA\"}")), DELIVERY_TIME);

        verify(s3Service).deleteObject(eq(STAGING), endsWithSuffix(".parquet"));
        verify(s3Service).deleteObject(eq(STAGING), endsWithSuffix(".ndjson"));
    }

    @Test
    void missingGlueTableFailsTheWholeBatchWithoutCallingDuck() throws Exception {
        when(glueService.getTable("db", "events")).thenThrow(
                new AwsException("EntityNotFoundException", "Table not found: db.events", 400));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\"}")), DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        verify(duckClient, never()).execute(anyString(), anyString(), isNull(), any());
        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.EntityNotFoundException", line.get("lastErrorCode").asText());
        assertEquals("Table not found: db.events", line.get("lastErrorMessage").asText());
    }

    @Test
    void unsupportedColumnTypeFailsTheWholeBatch() throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(
                new Column("ticker", "string"), new Column("payload", "binary")));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\"}")), DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.UnsupportedSchema", line.get("lastErrorCode").asText());
        assertTrue(line.get("lastErrorMessage").asText().contains("binary"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"char(10)", "varchar(255)", "varchar(65535)"})
    void sizedTextTypesConvertAsVarchar(String hiveType) {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("note", hiveType)));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"note\": \"hello\"}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertTrue(stagedNdjson().contains("{\"note\":\"hello\"}"), "staged rows were: " + stagedNdjson());
    }

    // Glue stores the type string without checking it, so a prefix match would take
    // these for text columns rather than reporting the schema.
    @ParameterizedTest
    @ValueSource(strings = {"varchar(", "varchar(foo)", "char(10)garbage", "char(0)", "char(256)",
            "varchar(65536)", "varchar(999999)"})
    void malformedOrOversizedTextTypesFailTheWholeBatch(String hiveType) throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("note", hiveType)));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"note\": \"hello\"}")), DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.UnsupportedSchema", line.get("lastErrorCode").asText());
        assertTrue(line.get("lastErrorMessage").asText().contains(hiveType),
                "message was: " + line.get("lastErrorMessage").asText());
    }

    // Glue stores the column list without checking its elements, and a crash here
    // would escape the schema handling and take the batch with it.
    @Test
    void aNullColumnEntryFailsTheWholeBatchAsUnsupportedSchema() throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("ticker", "string"), null));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\"}")), DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        assertEquals("DataFormatConversion.UnsupportedSchema",
                errorLines().get(0).get("lastErrorCode").asText());
    }

    // Past the double range the value is already infinity by the time it is read, so
    // staging it would write something the record never carried.
    @ParameterizedTest
    @CsvSource({"float, 1e1000", "double, 1e1000", "float, '\"Infinity\"'", "double, '\"NaN\"'"})
    void nonFiniteFloatingValuesFailTheirOwnRecord(String hiveType, String written) throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("ratio", hiveType)));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"ratio\": 1.5}"),
                record("{\"ratio\": " + written + "}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        assertEquals("DataFormatConversion.MalformedData",
                errorLines().get(0).get("lastErrorCode").asText());
    }

    // DuckDB refuses the cast, which would take the whole batch rather than this record.
    @Test
    void valuesTooLargeForAFloatColumnFailOnlyTheirOwnRecord() throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("ratio", "float")));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"ratio\": 1.5}"),
                record("{\"ratio\": 1e100}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        assertTrue(stagedNdjson().contains("{\"ratio\":1.5}"), "staged rows were: " + stagedNdjson());

        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.MalformedData", line.get("lastErrorCode").asText());
        assertTrue(line.get("lastErrorMessage").asText().contains("of type FLOAT"),
                "message was: " + line.get("lastErrorMessage").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"decimal(39,2)", "decimal(2,3)", "decimal(999999999999,0)"})
    void decimalWidthsThatCannotBeRepresentedFailTheWholeBatch(String hiveType) throws Exception {
        when(glueService.getTable("db", "events")).thenReturn(table(new Column("amount", hiveType)));

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"amount\": 1}")), DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.UnsupportedSchema", line.get("lastErrorCode").asText());
        assertTrue(line.get("lastErrorMessage").asText().contains(hiveType),
                "message was: " + line.get("lastErrorMessage").asText());
    }

    @Test
    void duckFailureRoutesTheWholeBatchToTheErrorOutput() throws Exception {
        doThrow(new RuntimeException("floci-duck is unreachable")).when(duckClient)
                .execute(anyString(), anyString(), isNull(), any());

        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET,
                List.of(record("{\"ticker\": \"AAA\"}")), DELIVERY_TIME);

        assertEquals(0, outcome.convertedRecords());
        assertEquals(1, outcome.failedRecords());
        assertNull(outcome.dataKey());
        JsonNode line = errorLines().get(0);
        assertEquals("DataFormatConversion.InternalError", line.get("lastErrorCode").asText());
        assertTrue(line.get("lastErrorMessage").asText().contains("floci-duck is unreachable"));

        verify(s3Service).deleteObject(eq(STAGING), endsWithSuffix(".ndjson"));
    }

    @Test
    void openXKeyMappingsAndCaseInsensitivityResolveJsonMembers() {
        DeliveryStreamDescription stream = stream();
        OpenXJsonSerDe openX = stream.s3Destination().getDataFormatConversionConfiguration()
                .getInputFormatConfiguration().getDeserializer().getOpenXJsonSerDe();
        openX.setColumnToJsonKeyMappings(Map.of("ticker", "sym"));

        // The mapped key is matched case-insensitively too: OpenX lowercases
        // keys before deserializing, so a mapping cannot depend on the record's case.
        converter.deliver(stream, BUCKET, List.of(
                record("{\"SYM\": \"AAA\", \"PRICE\": 1.5}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"ticker\":\"AAA\""), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"price\":1.5"), "staged rows were: " + ndjson);
    }

    @Test
    void explicitCaseSensitivityLeavesDifferentlyCasedMembersUnmatched() {
        DeliveryStreamDescription stream = stream();
        OpenXJsonSerDe openX = stream.s3Destination().getDataFormatConversionConfiguration()
                .getInputFormatConfiguration().getDeserializer().getOpenXJsonSerDe();
        openX.setCaseInsensitive(false);

        converter.deliver(stream, BUCKET, List.of(record("{\"PRICE\": 1.5}")), DELIVERY_TIME);

        assertTrue(stagedNdjson().contains("\"price\":null"));
    }

    @Test
    void parquetSerDeCompressionIsForwardedToTheCopy() {
        DeliveryStreamDescription stream = stream();
        stream.s3Destination().getDataFormatConversionConfiguration().getOutputFormatConfiguration()
                .getSerializer().getParquetSerDe().setCompression("GZIP");

        converter.deliver(stream, BUCKET, List.of(record("{\"ticker\": \"AAA\"}")), DELIVERY_TIME);

        verify(duckClient).execute(anyString(), contains("COMPRESSION GZIP"), isNull(), any());
    }

    @Test
    void timestampsAcceptEpochMillisAndIsoTVariants() {
        converter.deliver(stream(), BUCKET, List.of(
                record("{\"ts\": 1788781695000}"),
                record("{\"ts\": \"2026-09-07T12:00:02\"}")), DELIVERY_TIME);

        String ndjson = stagedNdjson();
        assertTrue(ndjson.contains("\"ts\":\"2026-09-07 11:48:15.000\""), "staged rows were: " + ndjson);
        assertTrue(ndjson.contains("\"ts\":\"2026-09-07 12:00:02\""), "staged rows were: " + ndjson);
    }

    @Test
    void stringNumbersCoerceLikeAwsAndBadValuesReportTheParseCause() throws Exception {
        FirehoseParquetConverter.Outcome outcome = converter.deliver(stream(), BUCKET, List.of(
                record("{\"price\": \"2.25\", \"qty\": \"20\"}"),
                record("{\"active\": \"maybe\"}")), DELIVERY_TIME);

        assertEquals(1, outcome.convertedRecords());
        assertTrue(stagedNdjson().contains("\"price\":2.25,\"qty\":20"));
        assertEquals("Data does not match the schema. For input string: \"maybe\"",
                errorLines().get(0).get("lastErrorMessage").asText());
    }
}
