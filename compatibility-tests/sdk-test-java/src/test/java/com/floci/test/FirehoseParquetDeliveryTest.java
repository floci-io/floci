package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.Datum;
import software.amazon.awssdk.services.athena.model.QueryExecutionState;
import software.amazon.awssdk.services.athena.model.QueryExecutionStatus;
import software.amazon.awssdk.services.athena.model.ResultConfiguration;
import software.amazon.awssdk.services.athena.model.StartQueryExecutionRequest;
import software.amazon.awssdk.services.athena.model.StartQueryExecutionResponse;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.BufferingHints;
import software.amazon.awssdk.services.firehose.model.CompressionFormat;
import software.amazon.awssdk.services.firehose.model.CreateDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DataFormatConversionConfiguration;
import software.amazon.awssdk.services.firehose.model.DeleteDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DeliveryStreamStatus;
import software.amazon.awssdk.services.firehose.model.DeliveryStreamType;
import software.amazon.awssdk.services.firehose.model.DescribeDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.Deserializer;
import software.amazon.awssdk.services.firehose.model.ExtendedS3DestinationConfiguration;
import software.amazon.awssdk.services.firehose.model.InputFormatConfiguration;
import software.amazon.awssdk.services.firehose.model.OpenXJsonSerDe;
import software.amazon.awssdk.services.firehose.model.OutputFormatConfiguration;
import software.amazon.awssdk.services.firehose.model.ParquetSerDe;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchRequest;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.awssdk.services.firehose.model.SchemaConfiguration;
import software.amazon.awssdk.services.firehose.model.Serializer;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.CreateDatabaseRequest;
import software.amazon.awssdk.services.glue.model.CreateTableRequest;
import software.amazon.awssdk.services.glue.model.DatabaseInput;
import software.amazon.awssdk.services.glue.model.DeleteDatabaseRequest;
import software.amazon.awssdk.services.glue.model.DeleteTableRequest;
import software.amazon.awssdk.services.glue.model.SerDeInfo;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.TableInput;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that a data-format-converting stream delivers a real
 * Parquet object: records put through the SDK come back out of S3 as a file
 * carrying the PAR1 magic under a .parquet key, and the records that cannot
 * convert land under the evaluated ErrorOutputPrefix as AWS-shaped NDJSON
 * error lines. Against Floci this exercises the floci-duck sidecar for real,
 * which the emulator's unit tests mock.
 */
@DisplayName("Firehose data format conversion delivery (JSON to Parquet)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseParquetDeliveryTest {

    private static final int BUFFERING_INTERVAL_SECONDS = 5;
    private static final long POLL_TIMEOUT_MILLIS = 240_000L;
    private static final byte[] PARQUET_MAGIC = "PAR1".getBytes(StandardCharsets.US_ASCII);

    private static final String VALID_RECORD_1 =
            "{\"ticker\": \"AAA\", \"price\": 1.5, \"qty\": 10, \"active\": true, \"ts\": \"2026-09-07 12:00:00\"}\n";
    private static final String VALID_RECORD_2 = "{\"ticker\": \"MISSING_FIELDS\"}\n";
    private static final String MISMATCH_RECORD = "{\"ticker\": \"BAD\", \"price\": \"oops\"}\n";
    private static final String UNPARSEABLE_RECORD = "this is not json at all\n";

    private static FirehoseClient firehose;
    private static GlueClient glue;
    private static S3Client s3;
    private static String bucket;
    private static String streamName;
    private static String database;
    private static final String TABLE = "events";

    @BeforeAll
    static void setup() throws InterruptedException {
        String roleArn = TestFixtures.isRealAws()
                ? System.getenv("FIREHOSE_ROLE_ARN")
                : "arn:aws:iam::000000000000:role/firehose-role";
        if (TestFixtures.isRealAws() && roleArn == null) {
            Assumptions.abort("FIREHOSE_ROLE_ARN not set");
        }

        firehose = TestFixtures.firehoseClient();
        glue = TestFixtures.glueClient();
        s3 = TestFixtures.s3Client();
        bucket = TestFixtures.uniqueName("firehose-parquet");
        streamName = TestFixtures.uniqueName("sdk-parquet");
        database = TestFixtures.uniqueName("firehose_parquet_db").replace('-', '_');

        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        glue.createDatabase(CreateDatabaseRequest.builder()
                .databaseInput(DatabaseInput.builder().name(database).build()).build());
        glue.createTable(CreateTableRequest.builder()
                .databaseName(database)
                .tableInput(TableInput.builder()
                        .name(TABLE)
                        .tableType("EXTERNAL_TABLE")
                        .storageDescriptor(StorageDescriptor.builder()
                                .columns(
                                        Column.builder().name("ticker").type("string").build(),
                                        Column.builder().name("price").type("double").build(),
                                        Column.builder().name("qty").type("int").build(),
                                        Column.builder().name("active").type("boolean").build(),
                                        Column.builder().name("ts").type("timestamp").build())
                                // The destination prefix with a Parquet SerDe, as a real
                                // Firehose-to-Parquet setup defines it, so the delivered
                                // objects can be read back through Athena.
                                .location("s3://" + bucket + "/data/")
                                .inputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat")
                                .outputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat")
                                .serdeInfo(SerDeInfo.builder()
                                        .serializationLibrary(
                                                "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe")
                                        .build())
                                .build())
                        .build())
                .build());

        firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                .deliveryStreamName(streamName)
                .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                .extendedS3DestinationConfiguration(ExtendedS3DestinationConfiguration.builder()
                        .roleARN(roleArn)
                        .bucketARN("arn:aws:s3:::" + bucket)
                        .prefix("data/")
                        .errorOutputPrefix("errors/!{firehose:error-output-type}/")
                        .compressionFormat(CompressionFormat.UNCOMPRESSED)
                        .bufferingHints(BufferingHints.builder()
                                .sizeInMBs(64)
                                .intervalInSeconds(BUFFERING_INTERVAL_SECONDS)
                                .build())
                        .dataFormatConversionConfiguration(DataFormatConversionConfiguration.builder()
                                .enabled(true)
                                .schemaConfiguration(SchemaConfiguration.builder()
                                        .roleARN(roleArn)
                                        .databaseName(database)
                                        .tableName(TABLE)
                                        .build())
                                .inputFormatConfiguration(InputFormatConfiguration.builder()
                                        .deserializer(Deserializer.builder()
                                                .openXJsonSerDe(OpenXJsonSerDe.builder().build())
                                                .build())
                                        .build())
                                .outputFormatConfiguration(OutputFormatConfiguration.builder()
                                        .serializer(Serializer.builder()
                                                .parquetSerDe(ParquetSerDe.builder().build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build());

        awaitStreamActive();

        assertThat(firehose.putRecordBatch(PutRecordBatchRequest.builder()
                .deliveryStreamName(streamName)
                .records(
                        record(VALID_RECORD_1),
                        record(VALID_RECORD_2),
                        record(MISMATCH_RECORD),
                        record(UNPARSEABLE_RECORD))
                .build()).failedPutCount()).isZero();
    }

    // Real AWS returns from CreateDeliveryStream while the stream is still CREATING
    // and rejects PutRecordBatch until it turns ACTIVE; Floci creates it active, so
    // this returns on the first poll locally.
    private static void awaitStreamActive() throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
        while (true) {
            DeliveryStreamStatus status = firehose.describeDeliveryStream(
                            DescribeDeliveryStreamRequest.builder()
                                    .deliveryStreamName(streamName).build())
                    .deliveryStreamDescription().deliveryStreamStatus();
            if (status == DeliveryStreamStatus.ACTIVE) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("delivery stream stayed " + status + " for "
                        + POLL_TIMEOUT_MILLIS / 1000 + "s");
            }
            Thread.sleep(5_000L);
        }
    }

    private static Record record(String data) {
        return Record.builder().data(SdkBytes.fromString(data, StandardCharsets.UTF_8)).build();
    }

    @AfterAll
    static void cleanup() {
        if (firehose != null) {
            try {
                firehose.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder()
                        .deliveryStreamName(streamName).build());
            } catch (Exception e) {
                // Best effort: a stream that never got created must not fail the run.
                System.out.println("Cleanup of delivery stream " + streamName + " failed: " + e);
            }
            firehose.close();
        }
        if (glue != null) {
            try {
                glue.deleteTable(DeleteTableRequest.builder().databaseName(database).name(TABLE).build());
                glue.deleteDatabase(DeleteDatabaseRequest.builder().name(database).build());
            } catch (Exception e) {
                // Best effort cleanup of the probe schema.
                System.out.println("Cleanup of Glue database " + database + " failed: " + e);
            }
            glue.close();
        }
        if (s3 != null) {
            try {
                s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents()
                        .forEach(object -> s3.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket).key(object.key()).build()));
                s3.deleteBucket(builder -> builder.bucket(bucket));
            } catch (Exception e) {
                // Best effort cleanup of the probe bucket.
                System.out.println("Cleanup of bucket " + bucket + " failed: " + e);
            }
            s3.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Valid records are delivered as one Parquet object under a .parquet key")
    void deliversARealParquetObject() throws Exception {
        String key = waitForDeliveredKey("data/");

        assertThat(key).endsWith(".parquet");
        byte[] body = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .readAllBytes();
        assertThat(body.length).isGreaterThan(PARQUET_MAGIC.length * 2);
        assertThat(java.util.Arrays.copyOfRange(body, 0, PARQUET_MAGIC.length))
                .as("delivered object must start with the Parquet magic")
                .isEqualTo(PARQUET_MAGIC);
        assertThat(java.util.Arrays.copyOfRange(body, body.length - PARQUET_MAGIC.length, body.length))
                .as("delivered object must end with the Parquet magic")
                .isEqualTo(PARQUET_MAGIC);
    }

    @Test
    @Order(2)
    @DisplayName("Unconvertible records land under errors/format-conversion-failed/ as NDJSON")
    void routesFailedRecordsToTheErrorOutput() throws Exception {
        String key = waitForDeliveredKey("errors/format-conversion-failed/");

        String lastSegment = key.substring(key.lastIndexOf('/') + 1);
        assertThat(lastSegment).as("error object carries no file extension").doesNotContain(".");

        byte[] body = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .readAllBytes();
        List<String> lines = new String(body, StandardCharsets.UTF_8).strip().lines().toList();
        assertThat(lines).hasSize(2);

        String mismatch = lines.stream().filter(l -> l.contains("MalformedData")).findFirst().orElseThrow();
        assertThat(mismatch).contains("\"lastErrorCode\":\"DataFormatConversion.MalformedData\"");
        assertThat(mismatch).contains(
                Base64.getEncoder().encodeToString(MISMATCH_RECORD.getBytes(StandardCharsets.UTF_8)));
        assertThat(mismatch).contains("\"databaseName\":\"" + database + "\"");

        String unparseable = lines.stream().filter(l -> l.contains("ParseError")).findFirst().orElseThrow();
        assertThat(unparseable).contains("\"lastErrorCode\":\"DataFormatConversion.ParseError\"");
        assertThat(unparseable).contains(
                Base64.getEncoder().encodeToString(UNPARSEABLE_RECORD.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The PAR1 framing only proves the object is a Parquet file. Reading it back
     * through Athena, which resolves the Glue table to the delivery prefix, is what
     * shows the projection itself is right: both valid records are present, the
     * absent members became null rather than being dropped, and the values kept
     * their column types instead of arriving as strings.
     */
    @Test
    @Order(3)
    @DisplayName("The delivered Parquet reads back through Athena with typed values")
    void deliveredParquetReadsBackThroughAthena() throws Exception {
        waitForDeliveredKey("data/");

        try (AthenaClient athena = TestFixtures.athenaClient()) {
            StartQueryExecutionResponse started = athena.startQueryExecution(
                    StartQueryExecutionRequest.builder()
                            .queryString("SELECT ticker, price, qty, active, ts FROM \"" + database
                                    + "\".\"" + TABLE + "\" ORDER BY ticker")
                            .workGroup("primary")
                            .resultConfiguration(ResultConfiguration.builder()
                                    .outputLocation("s3://" + bucket + "/athena-results/")
                                    .build())
                            .build());

            QueryExecutionStatus status = TestFixtures.awaitAthenaQueryTerminal(
                    athena, started.queryExecutionId(), Duration.ofSeconds(120));
            assertThat(status.state())
                    .as("Athena read-back did not succeed: %s", status.stateChangeReason())
                    .isEqualTo(QueryExecutionState.SUCCEEDED);

            List<List<String>> rows = athena.getQueryResults(r -> r
                            .queryExecutionId(started.queryExecutionId()))
                    .resultSet().rows().stream()
                    .map(row -> row.data().stream().map(Datum::varCharValue).toList())
                    .filter(row -> !"ticker".equals(row.get(0)))
                    .toList();

            assertThat(rows).as("both valid records must be in the Parquet object").hasSize(2);

            List<String> typed = rows.stream().filter(row -> "AAA".equals(row.get(0))).findFirst()
                    .orElseThrow();
            assertThat(typed.get(1)).isEqualTo("1.5");
            assertThat(typed.get(2)).isEqualTo("10");
            assertThat(typed.get(3)).isIn("true", "TRUE");
            assertThat(typed.get(4)).startsWith("2026-09-07 12:00:00");

            List<String> sparse = rows.stream().filter(row -> "MISSING_FIELDS".equals(row.get(0)))
                    .findFirst().orElseThrow();
            assertThat(sparse).as("no column may be dropped for a sparse record").hasSize(5);
            // Real Athena omits VarCharValue for a null cell, which the SDK maps to
            // null; Floci returns an empty string, so both spellings are accepted.
            assertThat(sparse.subList(1, sparse.size()))
                    .as("members absent from the record must be null, not dropped")
                    .allSatisfy(value -> assertThat(value).isNullOrEmpty());
        }
    }

    private String waitForDeliveredKey(String prefix) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            List<S3Object> contents = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).build()).contents();
            if (!contents.isEmpty()) {
                return contents.get(0).key();
            }
            Thread.sleep(5_000L);
        }
        throw new AssertionError("no object delivered under " + prefix + " within "
                + POLL_TIMEOUT_MILLIS / 1000 + "s");
    }
}
