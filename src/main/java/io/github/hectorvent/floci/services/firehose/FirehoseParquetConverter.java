package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.DataFormatConversionConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.OpenXJsonSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ParquetSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.SchemaConfiguration;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Delivers a flushed batch of a data-format-converting stream: each record is
 * parsed and type-checked against the stream's Glue table schema in Java, the
 * valid rows are staged as normalized NDJSON and written to the destination as
 * Parquet by the floci-duck sidecar's {@code COPY ... (FORMAT PARQUET)}, and
 * the failing records go to the error output, one NDJSON line each, under the
 * evaluated ErrorOutputPrefix.
 *
 * The per-record split mirrors real AWS, which routes both unparseable records
 * ({@code DataFormatConversion.ParseError}, with a raw Jackson message: AWS
 * itself parses with Jackson) and schema mismatches
 * ({@code DataFormatConversion.MalformedData}, "Data does not match the
 * schema. For input string: ...") to the error output individually while the
 * rest of the batch still converts. Failures of the batch as a whole, a
 * missing Glue table, a schema Floci cannot convert, or a DuckDB failure,
 * send every buffered record to the error output instead of dropping them;
 * the batch-level error codes are Floci's own and are documented as such.
 *
 * Typing comes from the Glue schema, never from JSON inference, so a batch's
 * column types cannot drift with its data. The staged rows are already
 * normalized to the target types, which keeps the DuckDB {@code COPY}
 * deterministic; {@code read_json} receives the explicit column list.
 */
@ApplicationScoped
public class FirehoseParquetConverter {

    private static final Logger LOG = Logger.getLogger(FirehoseParquetConverter.class);
    private static final String ERROR_OUTPUT_TYPE = "format-conversion-failed";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final Pattern DECIMAL_TYPE = Pattern.compile("decimal\\((\\d+),\\s*(\\d+)\\)");
    private static final Pattern SIZED_TEXT_TYPE = Pattern.compile("(char|varchar)\\((\\d+)\\)");
    private static final int MAX_CHAR_LENGTH = 255;
    private static final int MAX_VARCHAR_LENGTH = 65535;
    private static final int MAX_DECIMAL_PRECISION = 38;
    private static final BigDecimal EPOCH_MILLIS_FLOOR = new BigDecimal("1000000000000");
    private static final BigDecimal MAX_TIMESTAMP_MILLIS = BigDecimal.valueOf(Long.MAX_VALUE / 1000);
    private static final int MAX_TIMESTAMP_DIGITS = String.valueOf(Long.MAX_VALUE / 1000).length();
    private static final int MAX_BUFFER_HINT = 8 * 1024 * 1024;
    // Probed: AWS reads a space-separated timestamp leniently, one-digit month, day,
    // hour, minute and second included, and a date column the same way, while its
    // 'T' form is the strict ISO one and rejects "2026-9-7T12:00:02Z".
    private static final Pattern DATE_VALUE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern LENIENT_TIMESTAMP = Pattern.compile(
            "(\\d{4})-(\\d{1,2})-(\\d{1,2}) (\\d{1,2}):(\\d{1,2}):(\\d{1,2})(\\.\\d{1,9})?");
    private static final Pattern ISO_TIMESTAMP =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?)Z?");
    // A "yyyy" pattern signs any year wider than four digits, and DuckDB cannot cast
    // the "+33658-.." that an out-of-the-ordinary epoch then produces, which would
    // fail the COPY for the whole batch rather than just that record. NORMAL drops
    // that plus while keeping the minus a year before 0000 needs.
    private static final DateTimeFormatter EPOCH_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4, 10, SignStyle.NORMAL)
            .appendPattern("-MM-dd HH:mm:ss.SSS")
            .toFormatter(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final GlueService glueService;
    private final FlociDuckClient duckClient;
    private final S3Service s3Service;
    private final ObjectMapper mapper;
    private final ObjectReader recordReader;
    private final RegionResolver regionResolver;
    private final String stagingBucket;

    @Inject
    public FirehoseParquetConverter(GlueService glueService, FlociDuckClient duckClient,
                                    S3Service s3Service, ObjectMapper mapper,
                                    RegionResolver regionResolver, EmulatorConfig config) {
        this(glueService, duckClient, s3Service, mapper, regionResolver,
                config.services().firehose().stagingBucket());
    }

    FirehoseParquetConverter(GlueService glueService, FlociDuckClient duckClient,
                             S3Service s3Service, ObjectMapper mapper,
                             RegionResolver regionResolver, String stagingBucket) {
        this.glueService = glueService;
        this.duckClient = duckClient;
        this.s3Service = s3Service;
        this.mapper = mapper;
        // Trailing content after the first value makes the whole record malformed;
        // the shared mapper keeps its own settings, so the strictness is scoped here.
        // USE_BIG_DECIMAL_FOR_FLOATS as well: a DECIMAL column must keep the value the
        // record carried, and a double cannot hold more than about 17 digits of it.
        this.recordReader = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.regionResolver = regionResolver;
        this.stagingBucket = stagingBucket;
    }

    /** What a delivery produced, for the flush log line. */
    public record Outcome(int convertedRecords, int failedRecords, String dataKey, String errorKey) {}

    public Outcome deliver(DeliveryStreamDescription stream, String bucket, List<byte[]> records,
                           Instant deliveryTime) {
        S3Destination s3 = stream.s3Destination();
        DataFormatConversionConfiguration conversion = s3.getDataFormatConversionConfiguration();
        SchemaConfiguration schemaConfig = conversion.getSchemaConfiguration();

        List<ColumnSpec> schema;
        try {
            schema = resolveSchema(schemaConfig);
        } catch (AwsException e) {
            return failWholeBatch(stream, s3, bucket, records, deliveryTime, schemaConfig,
                    "DataFormatConversion." + e.getErrorCode(), e.getMessage());
        } catch (UnsupportedSchemaException e) {
            return failWholeBatch(stream, s3, bucket, records, deliveryTime, schemaConfig,
                    "DataFormatConversion.UnsupportedSchema", e.getMessage());
        }

        KeyResolver keyResolver = keyResolver(conversion);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<FailedRecord> failures = new ArrayList<>();
        for (byte[] record : records) {
            try {
                rows.add(project(record, schema, keyResolver));
            } catch (RecordConversionException e) {
                failures.add(new FailedRecord(record, e.errorCode, e.getMessage()));
            }
        }

        // The conversion runs against the staging bucket first, so the batch's outcome
        // is settled before anything reaches the destination. Each destination key is
        // then written exactly once: no overwrite, and no consumer of the bucket's
        // notifications sees a partial error object replaced by a batch-level one.
        String stagedParquetKey = null;
        if (!rows.isEmpty()) {
            try {
                stagedParquetKey = convertToStagedParquet(stream, s3, schema, rows, deliveryTime,
                        conversion.getOutputFormatConfiguration().getSerializer().getParquetSerDe());
            } catch (Exception e) {
                LOG.errorv("Parquet conversion for stream {0} failed, routing the batch to the error output: {1}",
                        stream.getDeliveryStreamName(), e.getMessage());
                return failWholeBatch(stream, s3, bucket, records, deliveryTime, schemaConfig,
                        "DataFormatConversion.InternalError",
                        "Floci could not convert the batch: " + e.getMessage());
            }
        }

        try {
            byte[] parquet = null;
            if (stagedParquetKey != null) {
                try {
                    parquet = s3Service.getObject(stagingBucket, stagedParquetKey).getData();
                } catch (Exception e) {
                    // Read before either destination write, so a sidecar that reported
                    // success without a readable object still fails the batch as a whole
                    // rather than after its per-record errors are already published.
                    LOG.errorv("Staged Parquet for stream {0} could not be read back,"
                            + " routing the batch to the error output: {1}",
                            stream.getDeliveryStreamName(), e.getMessage());
                    return failWholeBatch(stream, s3, bucket, records, deliveryTime, schemaConfig,
                            "DataFormatConversion.InternalError",
                            "Floci could not convert the batch: " + e.getMessage());
                }
            }

            // Still before the Parquet: a failure here delivers nothing at all and the
            // flush fails as a whole. Nothing is kept for a retry, the staged object goes
            // on the way out either way, so recovery belongs to the source: a Kinesis
            // stream re-reads its uncommitted checkpoint, a DirectPut buffer cannot.
            String errorKey = failures.isEmpty()
                    ? null
                    : writeErrorOutput(stream, s3, bucket, failures, deliveryTime, schemaConfig);
            String dataKey = null;
            if (parquet != null) {
                dataKey = S3ObjectKeyResolver.resolveKey(s3, stream.getDeliveryStreamName(),
                        stream.getVersionId(), deliveryTime, ".parquet");
                // Put rather than copy: a delivery notification is ObjectCreated:Put on
                // AWS and on the unconverted path, and copyObject would announce this one
                // as ObjectCreated:Copy to any subscriber filtering on the event name.
                s3Service.putObject(bucket, dataKey, parquet, CONTENT_TYPE, Map.of());
            }
            return new Outcome(rows.size(), failures.size(), dataKey, errorKey);
        } finally {
            if (stagedParquetKey != null) {
                safeDelete(stagedParquetKey);
            }
        }
    }

    // ── schema resolution ────────────────────────────────────────────────────

    /**
     * The Glue store is not region- or catalog-partitioned, so
     * SchemaConfiguration.Region and CatalogId only flow into the error-output
     * metadata; VersionId is not resolved (the live table is always used).
     */
    private List<ColumnSpec> resolveSchema(SchemaConfiguration schemaConfig) {
        Table table = glueService.getTable(schemaConfig.getDatabaseName(), schemaConfig.getTableName());
        List<Column> columns = table.getStorageDescriptor() == null
                ? null : table.getStorageDescriptor().getColumns();
        if (columns == null || columns.isEmpty()) {
            throw new UnsupportedSchemaException("Glue table " + schemaConfig.getDatabaseName() + "."
                    + schemaConfig.getTableName() + " has no columns.");
        }
        List<ColumnSpec> schema = new ArrayList<>(columns.size());
        for (Column column : columns) {
            if (column == null) {
                throw new UnsupportedSchemaException("Table " + schemaConfig.getDatabaseName() + "."
                        + schemaConfig.getTableName() + " has a null column.");
            }
            String name = column.getName();
            if (name == null || name.isBlank()) {
                throw new UnsupportedSchemaException("Column name " + name
                        + " is not supported for Parquet conversion.");
            }
            ColumnSpec spec = ColumnSpec.fromHiveType(name, column.getType());
            if (spec == null) {
                throw new UnsupportedSchemaException("Column " + name + " has Hive type " + column.getType()
                        + ", which Floci cannot convert to Parquet.");
            }
            schema.add(spec);
        }
        return schema;
    }

    // ── per-record projection ────────────────────────────────────────────────

    private Map<String, Object> project(byte[] record, List<ColumnSpec> schema, KeyResolver keyResolver) {
        JsonNode node;
        try {
            // readValue rather than readTree: an empty or blank record makes the tree
            // reader answer with nothing at all, which would then be mistaken for a
            // parsed non-object, where this raises Jackson's own end-of-input error.
            node = recordReader.readValue(record, JsonNode.class);
        } catch (Exception e) {
            throw new RecordConversionException("DataFormatConversion.ParseError",
                    "Encountered malformed JSON. " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new RecordConversionException("DataFormatConversion.MalformedData",
                    "Data does not match the schema. A JSON object is required.");
        }
        Map<String, JsonNode> index = keyResolver.index(node);
        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnSpec column : schema) {
            JsonNode value = keyResolver.find(node, index, column.name());
            row.put(column.name(), value == null || value.isNull() ? null : column.coerce(value));
        }
        return row;
    }

    /**
     * Maps a schema column to the JSON member holding its value. OpenX's
     * ColumnToJsonKeyMappings renames the key that is looked up; the lookup
     * itself is an exact match first, then a case-insensitive one unless
     * OpenX's CaseInsensitive is explicitly false (case-insensitive is the
     * SerDe's documented default, and it lowercases keys before deserializing,
     * so a mapped key matches whatever case the record carries). HiveJsonSerDe
     * follows the same path; its TimestampFormats are not honored (documented
     * deviation).
     */
    private static KeyResolver keyResolver(DataFormatConversionConfiguration conversion) {
        OpenXJsonSerDe openX = conversion.getInputFormatConfiguration().getDeserializer().getOpenXJsonSerDe();
        Map<String, String> mappings = openX == null || openX.getColumnToJsonKeyMappings() == null
                ? Map.of() : openX.getColumnToJsonKeyMappings();
        boolean caseInsensitive = openX == null || !Boolean.FALSE.equals(openX.getCaseInsensitive());
        return new KeyResolver() {
            @Override
            public Map<String, JsonNode> index(JsonNode record) {
                if (!caseInsensitive) {
                    return Map.of();
                }
                // Built once per record rather than rescanned per column: a wide
                // schema over a 128 MiB batch would otherwise cost columns x fields.
                // First occurrence wins, as the per-column scan it replaces did.
                Map<String, JsonNode> byLowerKey = new LinkedHashMap<>();
                var fields = record.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    byLowerKey.putIfAbsent(field.getKey().toLowerCase(Locale.ROOT), field.getValue());
                }
                return byLowerKey;
            }

            @Override
            public JsonNode find(JsonNode record, Map<String, JsonNode> index, String column) {
                String key = mappings.getOrDefault(column, column);
                JsonNode exact = record.get(key);
                if (exact != null || !caseInsensitive) {
                    return exact;
                }
                return index.get(key.toLowerCase(Locale.ROOT));
            }
        };
    }

    // ── parquet write via floci-duck ─────────────────────────────────────────

    /**
     * Converts the batch into a Parquet object in the staging bucket and returns its
     * key. Delivery copies it to the destination only once the whole batch's outcome
     * is known, so a conversion failure never has to undo a destination write.
     */
    private String convertToStagedParquet(DeliveryStreamDescription stream, S3Destination s3,
                                          List<ColumnSpec> schema, List<Map<String, Object>> rows,
                                          Instant deliveryTime, ParquetSerDe parquetSerDe) throws Exception {
        String runId = UUID.randomUUID().toString();
        String parquetKey = "firehose-staging/" + stream.getDeliveryStreamName() + "/" + runId + ".parquet";
        String stagingKey = "firehose-staging/" + stream.getDeliveryStreamName() + "/" + runId + ".ndjson";
        ensureStagingBucket();

        // Straight to bytes: a default converting batch buffers 128 MiB, and going
        // through a StringBuilder and a String would hold two more copies of it.
        ByteArrayOutputStream ndjson = new ByteArrayOutputStream(initialSize(rows.size(), 128));
        for (Map<String, Object> row : rows) {
            ndjson.write(mapper.writeValueAsBytes(row));
            ndjson.write('\n');
        }
        s3Service.putObject(stagingBucket, stagingKey, ndjson.toByteArray(),
                "application/x-ndjson", Map.of());
        try {
            StringBuilder columns = new StringBuilder();
            for (ColumnSpec column : schema) {
                if (columns.length() > 0) {
                    columns.append(", ");
                }
                // Glue column names are broader than SQL identifiers, so the name is
                // escaped like any other literal rather than restricted up front.
                columns.append('\'').append(escapeSqlLiteral(column.name()))
                        .append("': '").append(column.duckType()).append('\'');
            }
            // floci-duck's /execute wraps the main sql field in a CSV-emitting COPY
            // for Athena compatibility, so the Parquet COPY goes in setup_sql and the
            // wrapped sql is a throwaway SELECT. Passing no output path keeps that
            // export from running at all: it would be a second way to fail after the
            // Parquet object is already written, and its failure would send a batch
            // that converted fine to the error output.
            String setupSql = "COPY (SELECT * FROM read_json('"
                    + escapeSqlLiteral("s3://" + stagingBucket + "/" + stagingKey)
                    + "', format='newline_delimited', columns={" + columns + "})) TO '"
                    + escapeSqlLiteral("s3://" + stagingBucket + "/" + parquetKey)
                    + "' (FORMAT PARQUET, COMPRESSION " + parquetCompression(parquetSerDe) + ")";
            // The account S3Service itself resolved, so the sidecar reads the staged
            // NDJSON from, and writes the Parquet to, the same partition. A scheduled
            // flush establishes the stream's own account before reaching here, so this
            // is the owner in that path as much as in a request.
            try {
                duckClient.execute("SELECT 1 AS ok", setupSql, null, regionResolver.getAccountId());
            } catch (Exception e) {
                // The COPY may have completed even though the call reports failure, so
                // the object is removed here: the caller never learns its key and could
                // not clean it up, and a repeating failure would fill the staging bucket.
                safeDelete(parquetKey);
                throw e;
            }
            return parquetKey;
        } finally {
            safeDelete(stagingKey);
        }
    }

    /** Real AWS defaults ParquetSerDe compression to SNAPPY; create-time validation pins the enum. */
    private static String parquetCompression(ParquetSerDe parquetSerDe) {
        String compression = parquetSerDe == null || parquetSerDe.getCompression() == null
                ? "SNAPPY" : parquetSerDe.getCompression();
        return switch (compression) {
            case "GZIP" -> "GZIP";
            case "UNCOMPRESSED" -> "UNCOMPRESSED";
            default -> "SNAPPY";
        };
    }

    // ── error output ─────────────────────────────────────────────────────────

    private Outcome failWholeBatch(DeliveryStreamDescription stream, S3Destination s3, String bucket,
                                   List<byte[]> records, Instant deliveryTime,
                                   SchemaConfiguration schemaConfig, String errorCode, String errorMessage) {
        List<FailedRecord> failures = new ArrayList<>(records.size());
        for (byte[] record : records) {
            failures.add(new FailedRecord(record, errorCode, errorMessage));
        }
        String errorKey = writeErrorOutput(stream, s3, bucket, failures, deliveryTime, schemaConfig);
        return new Outcome(0, failures.size(), null, errorKey);
    }

    /**
     * One NDJSON line per failed record, in the shape real AWS writes (probed):
     * timestamps, code, message, base64 rawData, and the dataCatalogTable block.
     * Deviations: both timestamps are the delivery time (Floci does not track
     * per-record arrival), and AWS's sequenceNumber/subSequenceNumber members
     * are omitted (DirectPut records have no sequence in Floci).
     */
    private String writeErrorOutput(DeliveryStreamDescription stream, S3Destination s3, String bucket,
                                    List<FailedRecord> failures, Instant deliveryTime,
                                    SchemaConfiguration schemaConfig) {
        String errorKey = S3ObjectKeyResolver.resolveErrorKey(s3, stream.getDeliveryStreamName(),
                stream.getVersionId(), deliveryTime, ERROR_OUTPUT_TYPE);
        Map<String, Object> catalogTable = new LinkedHashMap<>();
        // Omitted members fall back to the stream's own account and region, recorded
        // when it was created, rather than to whatever is ambient at delivery time.
        catalogTable.put("catalogId", schemaConfig.getCatalogId() != null
                ? schemaConfig.getCatalogId()
                : stream.getAccountId() != null ? stream.getAccountId() : regionResolver.getAccountId());
        catalogTable.put("databaseName", schemaConfig.getDatabaseName());
        catalogTable.put("tableName", schemaConfig.getTableName());
        catalogTable.put("region", schemaConfig.getRegion() != null
                ? schemaConfig.getRegion()
                : AwsArnUtils.regionOrDefault(stream.getDeliveryStreamARN(),
                        regionResolver.getDefaultRegion()));
        catalogTable.put("versionId", schemaConfig.getVersionId() != null
                ? schemaConfig.getVersionId() : "LATEST");
        catalogTable.put("roleArn", schemaConfig.getRoleArn());

        ByteArrayOutputStream body = new ByteArrayOutputStream(initialSize(failures.size(), 256));
        for (FailedRecord failure : failures) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("attemptsMade", 1);
            line.put("arrivalTimestamp", deliveryTime.toEpochMilli());
            line.put("lastErrorCode", failure.errorCode());
            line.put("lastErrorMessage", failure.errorMessage());
            line.put("attemptEndingTimestamp", deliveryTime.toEpochMilli());
            line.put("rawData", Base64.getEncoder().encodeToString(failure.data()));
            line.put("dataCatalogTable", catalogTable);
            try {
                body.write(mapper.writeValueAsBytes(line));
                body.write('\n');
            } catch (Exception e) {
                throw new AwsException("InternalServerException",
                        "Failed to serialize a Firehose error-output record: " + e.getMessage(), 500);
            }
        }
        s3Service.putObject(bucket, errorKey, body.toByteArray(), CONTENT_TYPE, Map.of());
        return errorKey;
    }

    private void ensureStagingBucket() {
        try {
            s3Service.createBucket(stagingBucket, regionResolver.getDefaultRegion());
        } catch (AwsException e) {
            if (!"BucketAlreadyOwnedByYou".equals(e.getErrorCode())
                    && !"BucketAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    /** Escapes a value for embedding inside a DuckDB single-quoted SQL literal. */
    /**
     * A sizing hint only, so it is capped: a batch of many small records would
     * otherwise ask for a multi-gigabyte array, or overflow the product to a
     * negative int and fail before any record is written.
     */
    private static int initialSize(int count, int perEntry) {
        return (int) Math.min((long) count * perEntry, MAX_BUFFER_HINT);
    }

    private static String escapeSqlLiteral(String raw) {
        return raw.replace("'", "''");
    }

    private void safeDelete(String key) {
        try {
            s3Service.deleteObject(stagingBucket, key);
        } catch (Exception e) {
            LOG.debugv(e, "Staging cleanup failed for s3://{0}/{1}", stagingBucket, key);
        }
    }

    // ── column typing and coercion ───────────────────────────────────────────

    private interface KeyResolver {
        /** The record's fields by lowercased key, empty when case-insensitive matching is off. */
        Map<String, JsonNode> index(JsonNode record);

        JsonNode find(JsonNode record, Map<String, JsonNode> index, String column);
    }

    private record FailedRecord(byte[] data, String errorCode, String errorMessage) {}

    private static final class RecordConversionException extends RuntimeException {
        final String errorCode;

        RecordConversionException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    private static final class UnsupportedSchemaException extends RuntimeException {
        UnsupportedSchemaException(String message) {
            super(message);
        }
    }

    enum ColumnKind { BOOLEAN, INTEGRAL, FLOATING, DECIMAL, TEXT, DATE, TIMESTAMP }

    record ColumnSpec(String name, String duckType, ColumnKind kind) {

        /** Primitive Hive types only; complex and binary types return null (unsupported). */
        static ColumnSpec fromHiveType(String name, String hiveType) {
            String type = hiveType == null ? "" : hiveType.trim().toLowerCase(Locale.ROOT);
            Matcher decimal = DECIMAL_TYPE.matcher(type);
            if (decimal.matches()) {
                return decimalSpec(name, decimal.group(1), decimal.group(2));
            }
            return switch (type) {
                case "boolean" -> new ColumnSpec(name, "BOOLEAN", ColumnKind.BOOLEAN);
                case "tinyint" -> new ColumnSpec(name, "TINYINT", ColumnKind.INTEGRAL);
                case "smallint" -> new ColumnSpec(name, "SMALLINT", ColumnKind.INTEGRAL);
                case "int", "integer" -> new ColumnSpec(name, "INTEGER", ColumnKind.INTEGRAL);
                case "bigint" -> new ColumnSpec(name, "BIGINT", ColumnKind.INTEGRAL);
                case "float", "real" -> new ColumnSpec(name, "FLOAT", ColumnKind.FLOATING);
                case "double", "double precision" -> new ColumnSpec(name, "DOUBLE", ColumnKind.FLOATING);
                case "decimal" -> new ColumnSpec(name, "DECIMAL(10,0)", ColumnKind.DECIMAL);
                case "string" -> new ColumnSpec(name, "VARCHAR", ColumnKind.TEXT);
                case "date" -> new ColumnSpec(name, "DATE", ColumnKind.DATE);
                case "timestamp" -> new ColumnSpec(name, "TIMESTAMP", ColumnKind.TIMESTAMP);
                default -> sizedTextSpec(name, type);
            };
        }

        /**
         * {@code char(n)} and {@code varchar(n)} map to VARCHAR, but only when the
         * whole type parses and its length is one Hive accepts. Glue does not check
         * the type string, so a prefix match would let {@code varchar(foo)} or
         * {@code char(10)garbage} through as text instead of reporting the schema.
         */
        private static ColumnSpec sizedTextSpec(String name, String type) {
            Matcher sized = SIZED_TEXT_TYPE.matcher(type);
            if (!sized.matches() || sized.group(2).length() > 5) {
                return null;
            }
            int length = Integer.parseInt(sized.group(2));
            int max = "char".equals(sized.group(1)) ? MAX_CHAR_LENGTH : MAX_VARCHAR_LENGTH;
            if (length < 1 || length > max) {
                return null;
            }
            return new ColumnSpec(name, "VARCHAR", ColumnKind.TEXT);
        }

        /**
         * A precision or scale outside what DECIMAL can hold is a property of the
         * schema, not of any one record, so an out-of-range width is unsupported
         * rather than left for the COPY to reject as a batch-level failure.
         */
        private static ColumnSpec decimalSpec(String name, String precisionText, String scaleText) {
            if (precisionText.length() > 2 || scaleText.length() > 2) {
                return null;
            }
            int precision = Integer.parseInt(precisionText);
            int scale = Integer.parseInt(scaleText);
            if (precision < 1 || precision > MAX_DECIMAL_PRECISION || scale < 0 || scale > precision) {
                return null;
            }
            return new ColumnSpec(name, "DECIMAL(" + precision + "," + scale + ")", ColumnKind.DECIMAL);
        }

        /**
         * Coerces one JSON value to this column's type or reports the record as
         * MalformedData. The "For input string" cause mirrors real AWS, whose own
         * failure for a non-numeric string in a double column is "Data does not
         * match the schema. For input string: \"oops\"", the raw
         * NumberFormatException message.
         */
        Object coerce(JsonNode value) {
            try {
                return switch (kind) {
                    case BOOLEAN -> coerceBoolean(value);
                    case INTEGRAL -> coerceIntegral(value);
                    case FLOATING -> coerceFloating(value);
                    case DECIMAL -> coerceDecimal(value);
                    case TEXT -> value.isValueNode() ? value.asText() : value.toString();
                    case DATE -> coerceDate(value);
                    case TIMESTAMP -> coerceTimestamp(value);
                };
            } catch (RecordConversionException e) {
                throw e;
            } catch (Exception e) {
                throw new RecordConversionException("DataFormatConversion.MalformedData",
                        "Data does not match the schema. " + e.getMessage());
            }
        }

        /**
         * A Hive {@code float} column is 32-bit, and DuckDB refuses to cast a value
         * the type cannot hold, which would fail the COPY for the whole batch rather
         * than this record. A {@code double} column takes the value as it stands.
         */
        private Object coerceFloating(JsonNode value) {
            double parsed;
            if (value.isNumber()) {
                parsed = value.doubleValue();
                if (!Double.isFinite(parsed)) {
                    // A finite record value past the double range arrives here already
                    // turned to infinity, which would stage as a value the record
                    // never wrote.
                    throw outOfRange(value.decimalValue());
                }
            } else {
                String text = requireScalar(value).trim();
                parsed = Double.parseDouble(text);
                if (!Double.isFinite(parsed)) {
                    throw new NumberFormatException("For input string: \"" + text + "\"");
                }
            }
            if ("FLOAT".equals(duckType) && Float.isInfinite((float) parsed)) {
                throw outOfRange(parsed);
            }
            return parsed;
        }

        /**
         * Range-checks integral values here rather than leaving them to DuckDB: a
         * value the CAST rejects fails the COPY, and with it every record in the
         * batch, where AWS fails only the record that does not fit the column.
         */
        private Object coerceIntegral(JsonNode value) {
            BigInteger parsed = value.isIntegralNumber() ? value.bigIntegerValue()
                    : new BigInteger(requireScalar(value).trim());
            long min;
            long max;
            switch (duckType) {
                case "TINYINT" -> {
                    min = Byte.MIN_VALUE;
                    max = Byte.MAX_VALUE;
                }
                case "SMALLINT" -> {
                    min = Short.MIN_VALUE;
                    max = Short.MAX_VALUE;
                }
                case "INTEGER" -> {
                    min = Integer.MIN_VALUE;
                    max = Integer.MAX_VALUE;
                }
                default -> {
                    min = Long.MIN_VALUE;
                    max = Long.MAX_VALUE;
                }
            }
            if (parsed.compareTo(BigInteger.valueOf(min)) < 0
                    || parsed.compareTo(BigInteger.valueOf(max)) > 0) {
                throw outOfRange(parsed);
            }
            return parsed.longValue();
        }

        /**
         * Rounds to the column's scale the way the DuckDB cast would, and rejects
         * the record when the result no longer fits the precision, for the same
         * blast-radius reason as {@link #coerceIntegral(JsonNode)}.
         */
        private Object coerceDecimal(JsonNode value) {
            BigDecimal parsed = value.isNumber() ? value.decimalValue()
                    : new BigDecimal(requireScalar(value).trim());
            Matcher bounds = DECIMAL_TYPE.matcher(duckType.toLowerCase(Locale.ROOT));
            if (!bounds.matches()) {
                return parsed;
            }
            int precision = Integer.parseInt(bounds.group(1));
            int scale = Integer.parseInt(bounds.group(2));
            if (parsed.signum() == 0) {
                // Zero fits every DECIMAL, but "0e1000000000" carries a scale that would
                // make the digit count below read it as enormous.
                return BigDecimal.ZERO.setScale(scale);
            }
            // Decided from precision and scale before setScale, which would otherwise
            // write out every digit of an exponent like 1e1000000000 to find that out.
            // The subtraction is long: a scale near Integer.MIN_VALUE, which "1e2147483647"
            // carries, would overflow an int back into the range this is guarding.
            long integerDigits = (long) parsed.precision() - parsed.scale();
            if (integerDigits > (long) precision - scale) {
                throw outOfRange(parsed);
            }
            if (integerDigits < (long) -scale - 1) {
                return BigDecimal.ZERO.setScale(scale);
            }
            BigDecimal rounded = parsed.setScale(scale, RoundingMode.HALF_UP);
            if (rounded.precision() > precision) {
                throw outOfRange(parsed);
            }
            return rounded;
        }

        private RecordConversionException outOfRange(Number value) {
            return new RecordConversionException("DataFormatConversion.MalformedData",
                    "Data does not match the schema. Value " + value + " is out of range for column "
                            + name + " of type " + duckType + ".");
        }

        private String requireScalar(JsonNode value) {
            if (!value.isValueNode()) {
                throw new RecordConversionException("DataFormatConversion.MalformedData",
                        "Data does not match the schema. Column " + name + " cannot hold a JSON "
                                + (value.isArray() ? "array." : "object."));
            }
            return value.asText();
        }

        private Object coerceBoolean(JsonNode value) {
            if (value.isBoolean()) {
                return value.booleanValue();
            }
            String text = requireScalar(value);
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return Boolean.parseBoolean(text);
            }
            throw new NumberFormatException("For input string: \"" + text + "\"");
        }

        private Object coerceDate(JsonNode value) {
            String text = requireScalar(value);
            Matcher date = DATE_VALUE.matcher(text);
            if (!date.matches()) {
                throw new NumberFormatException("For input string: \"" + text + "\"");
            }
            return LocalDate.parse(padded(date.group(1), date.group(2), date.group(3))).toString();
        }

        private static String padded(String year, String month, String day) {
            return year + "-" + pad(month) + "-" + pad(day);
        }

        private static String pad(String field) {
            return field.length() == 1 ? "0" + field : field;
        }

        /**
         * Accepts the probed "yyyy-MM-dd HH:mm:ss[.fraction]" shape, ISO 'T'
         * variants, and epoch numbers, which real AWS also accepts written as
         * strings (all probed 2026-09-08 against us-west-2).
         */
        private Object coerceTimestamp(JsonNode value) {
            if (value.isNumber()) {
                return fromEpoch(value.decimalValue());
            }
            String text = requireScalar(value);
            Matcher iso = ISO_TIMESTAMP.matcher(text);
            if (iso.matches()) {
                String normalized = iso.group(1) + " " + iso.group(2);
                LocalDateTime.parse(normalized.replace(' ', 'T'));
                return normalized;
            }
            Matcher lenient = LENIENT_TIMESTAMP.matcher(text);
            if (lenient.matches()) {
                String normalized = padded(lenient.group(1), lenient.group(2), lenient.group(3))
                        + " " + pad(lenient.group(4)) + ":" + pad(lenient.group(5))
                        + ":" + pad(lenient.group(6))
                        + (lenient.group(7) == null ? "" : lenient.group(7));
                LocalDateTime.parse(normalized.replace(' ', 'T'));
                return normalized;
            }
            try {
                return fromEpoch(new BigDecimal(text.trim()));
            } catch (NumberFormatException e) {
                throw new NumberFormatException("For input string: \"" + text + "\"");
            }
        }

        /**
         * Probed against real AWS: an epoch number below 1e12 in absolute value is
         * read as seconds and one at or above it as milliseconds, so 1518033528 is
         * February 2018 rather than January 1970, and a fractional value keeps its
         * sub-second part. A value that overflows the int64 nanoseconds AWS's
         * writer holds wraps there into an unrelated instant; the instant is kept
         * here wherever a TIMESTAMP can hold it, and only a value beyond that
         * fails its record, which docs/services/firehose.md records as a deviation.
         */
        private Object fromEpoch(BigDecimal epoch) {
            if (epoch.signum() == 0) {
                // As in coerceDecimal: zero is the epoch whatever exponent wrote it.
                return EPOCH_TIMESTAMP.format(Instant.EPOCH);
            }
            // The digit count settles the range before setScale materializes the value:
            // an exponent like 1e1000000000 is out of range without writing it out. The
            // subtraction is long, since "1e2147483647" would overflow it as an int.
            if ((long) epoch.precision() - epoch.scale() > MAX_TIMESTAMP_DIGITS) {
                throw outOfRange(epoch);
            }
            BigDecimal millis = epoch.abs().compareTo(EPOCH_MILLIS_FLOOR) < 0
                    ? epoch.multiply(BigDecimal.valueOf(1000))
                    : epoch;
            if ((long) millis.precision() - millis.scale() < 0) {
                // Under a tenth of a millisecond, so it rounds to the epoch. Rounding it
                // the long way would write out an exponent like 1e-1000000000 in full.
                return EPOCH_TIMESTAMP.format(Instant.EPOCH);
            }
            BigDecimal rounded = millis.setScale(0, RoundingMode.HALF_UP);
            if (rounded.abs().compareTo(MAX_TIMESTAMP_MILLIS) > 0) {
                throw outOfRange(epoch);
            }
            return EPOCH_TIMESTAMP.format(Instant.ofEpochMilli(rounded.longValueExact()));
        }
    }
}
