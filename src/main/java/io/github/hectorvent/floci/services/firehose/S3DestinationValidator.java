package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.OpenXJsonSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.OrcSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ParquetSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.SchemaConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rejects S3 destination members AWS refuses, with the error code and message
 * shape captured from real AWS's raw wire responses (see
 * https://github.com/floci-io/floci/issues/2328).
 *
 * AWS names the member that carried the offending value, so the same bad
 * {@code CompressionFormat} reports
 * {@code extendedS3DestinationConfiguration.compressionFormat},
 * {@code s3DestinationConfiguration.compressionFormat} or
 * {@code extendedS3DestinationUpdate.compressionFormat} depending on the shape
 * it arrived in. That is why the shape name is a parameter and validation runs
 * from the handler, the layer that knows which shape was used.
 *
 * Multiple violations in one request are joined into a single
 * "N validation errors detected" message separated by "; ", as real AWS's
 * Smithy layer does (probe 2026-09-07: both ParquetSerDe bounds violated at
 * once report "2 validation errors detected: ...; ...").
 */
final class S3DestinationValidator {

    /** Listed in the order AWS prints it, which is neither declaration order nor alphabetical. */
    private static final String ENUM_VALUE_SET = "[ZIP, HADOOP_SNAPPY, Snappy, GZIP, UNCOMPRESSED]";
    private static final List<String> PARQUET_COMPRESSIONS = List.of("SNAPPY", "GZIP", "UNCOMPRESSED");
    private static final String PARQUET_COMPRESSION_VALUE_SET = "[SNAPPY, GZIP, UNCOMPRESSED]";
    private static final List<String> PARQUET_WRITER_VERSIONS = List.of("V1", "V2");
    private static final String PARQUET_WRITER_VERSION_VALUE_SET = "[V1, V2]";

    private static final String FILE_EXTENSION_REGEX = "^(|\\.[0-9a-z!\\-_.*'()]+)$";
    private static final Pattern FILE_EXTENSION = Pattern.compile(FILE_EXTENSION_REGEX);
    private static final int FILE_EXTENSION_MAX_LENGTH = 128;

    private static final long PARQUET_MIN_BLOCK_SIZE_BYTES = 67108864;
    private static final long PARQUET_MIN_PAGE_SIZE_BYTES = 65536;
    private static final long PARQUET_MIN_MAX_PADDING_BYTES = 0;

    private static final String SERIALIZER_PATH =
            "dataFormatConversionConfiguration.outputFormatConfiguration.serializer";
    private static final List<String> ORC_COMPRESSIONS = List.of("ZLIB", "SNAPPY", "NONE");
    private static final String ORC_COMPRESSION_VALUE_SET = "[ZLIB, SNAPPY, NONE]";
    private static final List<String> ORC_FORMAT_VERSIONS = List.of("V0_11", "V0_12");
    private static final String ORC_FORMAT_VERSION_VALUE_SET = "[V0_11, V0_12]";
    private static final long ORC_MIN_BLOCK_SIZE_BYTES = 67108864;
    private static final long ORC_MIN_STRIPE_SIZE_BYTES = 8388608;
    private static final int ORC_MIN_ROW_INDEX_STRIDE = 1000;
    private static final double ORC_MIN_PROBABILITY = 0;
    private static final double ORC_MAX_PROBABILITY = 1;

    private static final int MAPPING_MAX_LENGTH = 1024;
    private static final String NO_WHITESPACE_PATTERN = "^\\S+$";
    private static final Pattern NO_WHITESPACE = Pattern.compile(NO_WHITESPACE_PATTERN);
    // Applied as written rather than approximated with String.isBlank(): the two
    // disagree in both directions, and AWS follows the pattern (probed). isBlank()
    // would reject an ideographic space the pattern accepts, since \s here is ASCII
    // only, and would accept a value holding a line break that "." cannot match.
    private static final String NOT_BLANK_PATTERN = "^(?!\\s*$).+";
    private static final Pattern NOT_BLANK = Pattern.compile(NOT_BLANK_PATTERN);

    private S3DestinationValidator() {}

    static void validateWireShape(S3Destination config, String shapeName) {
        if (config == null) {
            return;
        }
        List<String> violations = new ArrayList<>();
        String compressionFormat = config.getCompressionFormat();
        if (compressionFormat != null && FirehoseCompression.fromWireValue(compressionFormat).isEmpty()) {
            violations.add(violation(shapeName, "compressionFormat",
                    "Member must satisfy enum value set: " + ENUM_VALUE_SET));
        }
        String fileExtension = config.getFileExtension();
        if (fileExtension != null) {
            if (fileExtension.length() > FILE_EXTENSION_MAX_LENGTH) {
                violations.add(violation(shapeName, "fileExtension",
                        "Member must have length less than or equal to " + FILE_EXTENSION_MAX_LENGTH));
            } else if (!FILE_EXTENSION.matcher(fileExtension).matches()) {
                violations.add(violation(shapeName, "fileExtension",
                        "Member must satisfy regular expression pattern: " + FILE_EXTENSION_REGEX));
            }
        }
        Serializer serializer = serializer(config);
        if (serializer != null) {
            // Parquet first, then ORC, the order AWS prints when both carry a
            // violation (they can only both be present on a request the semantic
            // "more than one serializer" check rejects afterwards).
            collectParquetSerDeViolations(serializer.getParquetSerDe(), shapeName, violations);
            collectOrcSerDeViolations(serializer.getOrcSerDe(), shapeName, violations);
        }
        // Serializer, then deserializer, then schema: the order AWS prints when
        // violations sit in more than one of them.
        collectOpenXMappingViolations(config, shapeName, violations);
        collectSchemaViolations(config, shapeName, violations);
        if (!violations.isEmpty()) {
            throw constraintViolations(violations);
        }
    }

    /**
     * Every SchemaConfiguration string is a 1 to 1024 character value without
     * whitespace, and unlike the collection members each broken rule is its own
     * violation rather than one bracketed set. Member order is AWS's (probed), as is
     * the {@code roleARN} spelling, which keeps ARN capitalised where the semantic
     * message for the same member spells it RoleArn.
     *
     * A malformed but non-empty role ARN never reaches these rules on AWS: an
     * InvalidParameterValueException ("Invalid role ARN.") preempts them. Floci
     * validates no ARNs, so it answers only the length and pattern rules here, which
     * is what an empty role ARN trips on AWS too.
     */
    private static void collectSchemaViolations(S3Destination config, String shapeName,
                                                List<String> violations) {
        var conversion = config.getDataFormatConversionConfiguration();
        if (conversion == null || conversion.getSchemaConfiguration() == null) {
            return;
        }
        SchemaConfiguration schema = conversion.getSchemaConfiguration();
        String path = "dataFormatConversionConfiguration.schemaConfiguration";
        collectSchemaMember(schema.getVersionId(), shapeName, path + ".versionId", violations);
        collectSchemaMember(schema.getCatalogId(), shapeName, path + ".catalogId", violations);
        collectSchemaMember(schema.getDatabaseName(), shapeName, path + ".databaseName", violations);
        collectSchemaMember(schema.getRoleArn(), shapeName, path + ".roleARN", violations);
        collectSchemaMember(schema.getRegion(), shapeName, path + ".region", violations);
        collectSchemaMember(schema.getTableName(), shapeName, path + ".tableName", violations);
    }

    private static void collectSchemaMember(String value, String shapeName, String member,
                                            List<String> violations) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            violations.add(violation(shapeName, member, "Member must have length greater than or equal to 1"));
        } else if (value.length() > MAPPING_MAX_LENGTH) {
            violations.add(violation(shapeName, member,
                    "Member must have length less than or equal to " + MAPPING_MAX_LENGTH));
        }
        if (!NO_WHITESPACE.matcher(value).matches()) {
            violations.add(violation(shapeName, member,
                    "Member must satisfy regular expression pattern: " + NO_WHITESPACE_PATTERN));
        }
    }

    /**
     * The only collection member whose element constraints Floci enforces; the ones
     * on OrcSerDe.BloomFilterColumns and HiveJsonSerDe.TimestampFormats are a
     * documented gap. AWS reports the whole constraint set whichever part failed, at
     * most once for the keys and once for the values, and applies a different pattern
     * to each: keys may hold no whitespace at all, values merely may not be blank.
     */
    private static void collectOpenXMappingViolations(S3Destination config, String shapeName,
                                                      List<String> violations) {
        var conversion = config.getDataFormatConversionConfiguration();
        if (conversion == null || conversion.getInputFormatConfiguration() == null
                || conversion.getInputFormatConfiguration().getDeserializer() == null) {
            return;
        }
        OpenXJsonSerDe openX = conversion.getInputFormatConfiguration().getDeserializer().getOpenXJsonSerDe();
        if (openX == null || openX.getColumnToJsonKeyMappings() == null) {
            return;
        }
        String member = "dataFormatConversionConfiguration.inputFormatConfiguration.deserializer"
                + ".openXJsonSerDe.columnToJsonKeyMappings";
        boolean keyViolation = false;
        boolean valueViolation = false;
        for (Map.Entry<String, String> mapping : openX.getColumnToJsonKeyMappings().entrySet()) {
            keyViolation |= !isMappingKeyValid(mapping.getKey());
            valueViolation |= !isMappingValueValid(mapping.getValue());
        }
        if (keyViolation) {
            violations.add(violation(shapeName, member,
                    "Map keys must satisfy constraint: " + constraintSet(NO_WHITESPACE_PATTERN)));
        }
        if (valueViolation) {
            violations.add(violation(shapeName, member,
                    "Map value must satisfy constraint: " + constraintSet(NOT_BLANK_PATTERN)));
        }
    }

    private static boolean isMappingKeyValid(String key) {
        return key != null && lengthInRange(key) && NO_WHITESPACE.matcher(key).matches();
    }

    private static boolean isMappingValueValid(String value) {
        return value != null && lengthInRange(value) && NOT_BLANK.matcher(value).matches();
    }

    private static boolean lengthInRange(String value) {
        return !value.isEmpty() && value.length() <= MAPPING_MAX_LENGTH;
    }

    private static String constraintSet(String pattern) {
        return "[Member must have length less than or equal to " + MAPPING_MAX_LENGTH
                + ", Member must have length greater than or equal to 1"
                + ", Member must satisfy regular expression pattern: " + pattern + "]";
    }

    private static Serializer serializer(S3Destination config) {
        var conversion = config.getDataFormatConversionConfiguration();
        if (conversion == null || conversion.getOutputFormatConfiguration() == null) {
            return null;
        }
        return conversion.getOutputFormatConfiguration().getSerializer();
    }

    /**
     * Smithy-level bounds on ParquetSerDe, enforced whenever the member is present,
     * even on a disabled conversion configuration: they are model constraints, not
     * the semantic checks {@link DataFormatConversionValidator} gates on Enabled.
     */
    private static void collectParquetSerDeViolations(ParquetSerDe parquet, String shapeName,
                                                      List<String> violations) {
        if (parquet == null) {
            return;
        }
        String parquetPath = SERIALIZER_PATH + ".parquetSerDe";
        // Member order follows the one AWS prints when several constraints fail at
        // once, which is neither declaration order nor alphabetical (probed with all
        // five violated in a single request).
        if (parquet.getMaxPaddingBytes() != null && parquet.getMaxPaddingBytes() < PARQUET_MIN_MAX_PADDING_BYTES) {
            violations.add(violation(shapeName, parquetPath + ".maxPaddingBytes",
                    "Member must have value greater than or equal to " + PARQUET_MIN_MAX_PADDING_BYTES));
        }
        if (parquet.getBlockSizeBytes() != null && parquet.getBlockSizeBytes() < PARQUET_MIN_BLOCK_SIZE_BYTES) {
            violations.add(violation(shapeName, parquetPath + ".blockSizeBytes",
                    "Member must have value greater than or equal to " + PARQUET_MIN_BLOCK_SIZE_BYTES));
        }
        if (parquet.getPageSizeBytes() != null && parquet.getPageSizeBytes() < PARQUET_MIN_PAGE_SIZE_BYTES) {
            violations.add(violation(shapeName, parquetPath + ".pageSizeBytes",
                    "Member must have value greater than or equal to " + PARQUET_MIN_PAGE_SIZE_BYTES));
        }
        if (parquet.getCompression() != null && !PARQUET_COMPRESSIONS.contains(parquet.getCompression())) {
            violations.add(violation(shapeName, parquetPath + ".compression",
                    "Member must satisfy enum value set: " + PARQUET_COMPRESSION_VALUE_SET));
        }
        if (parquet.getWriterVersion() != null && !PARQUET_WRITER_VERSIONS.contains(parquet.getWriterVersion())) {
            violations.add(violation(shapeName, parquetPath + ".writerVersion",
                    "Member must satisfy enum value set: " + PARQUET_WRITER_VERSION_VALUE_SET));
        }
    }

    /**
     * The ORC counterpart. Floci rejects an enabled OrcSerDe semantically, but these
     * are model constraints: they apply to a disabled configuration too, and AWS
     * reports them before any semantic error. Member order and the enum spellings
     * (note {@code [ZLIB, SNAPPY, NONE]}) were probed with all eight violated at once.
     */
    private static void collectOrcSerDeViolations(OrcSerDe orc, String shapeName, List<String> violations) {
        if (orc == null) {
            return;
        }
        String orcPath = SERIALIZER_PATH + ".orcSerDe";
        collectRange(orc.getDictionaryKeyThreshold(), shapeName, orcPath + ".dictionaryKeyThreshold",
                ORC_MIN_PROBABILITY, ORC_MAX_PROBABILITY, violations);
        if (orc.getBlockSizeBytes() != null && orc.getBlockSizeBytes() < ORC_MIN_BLOCK_SIZE_BYTES) {
            violations.add(violation(shapeName, orcPath + ".blockSizeBytes",
                    "Member must have value greater than or equal to " + ORC_MIN_BLOCK_SIZE_BYTES));
        }
        if (orc.getRowIndexStride() != null && orc.getRowIndexStride() < ORC_MIN_ROW_INDEX_STRIDE) {
            violations.add(violation(shapeName, orcPath + ".rowIndexStride",
                    "Member must have value greater than or equal to " + ORC_MIN_ROW_INDEX_STRIDE));
        }
        collectRange(orc.getPaddingTolerance(), shapeName, orcPath + ".paddingTolerance",
                ORC_MIN_PROBABILITY, ORC_MAX_PROBABILITY, violations);
        if (orc.getStripeSizeBytes() != null && orc.getStripeSizeBytes() < ORC_MIN_STRIPE_SIZE_BYTES) {
            violations.add(violation(shapeName, orcPath + ".stripeSizeBytes",
                    "Member must have value greater than or equal to " + ORC_MIN_STRIPE_SIZE_BYTES));
        }
        if (orc.getCompression() != null && !ORC_COMPRESSIONS.contains(orc.getCompression())) {
            violations.add(violation(shapeName, orcPath + ".compression",
                    "Member must satisfy enum value set: " + ORC_COMPRESSION_VALUE_SET));
        }
        if (orc.getFormatVersion() != null && !ORC_FORMAT_VERSIONS.contains(orc.getFormatVersion())) {
            violations.add(violation(shapeName, orcPath + ".formatVersion",
                    "Member must satisfy enum value set: " + ORC_FORMAT_VERSION_VALUE_SET));
        }
        collectRange(orc.getBloomFilterFalsePositiveProbability(), shapeName,
                orcPath + ".bloomFilterFalsePositiveProbability",
                ORC_MIN_PROBABILITY, ORC_MAX_PROBABILITY, violations);
    }

    /** AWS reports only the bound that was crossed, never both. */
    private static void collectRange(Double value, String shapeName, String member,
                                     double min, double max, List<String> violations) {
        if (value == null) {
            return;
        }
        // NaN satisfies neither comparison, so it would slip through a plain
        // greater/less pair. AWS reports both bounds for it, the upper one first,
        // and one bound for each infinity (probed).
        boolean notANumber = Double.isNaN(value);
        if (notANumber || value > max) {
            violations.add(violation(shapeName, member,
                    "Member must have value less than or equal to " + (long) max));
        }
        if (notANumber || value < min) {
            violations.add(violation(shapeName, member,
                    "Member must have value greater than or equal to " + (long) min));
        }
    }

    private static String violation(String shapeName, String member, String constraint) {
        return "Value at '" + shapeName + "." + member + "' failed to satisfy constraint: " + constraint;
    }

    private static AwsException constraintViolations(List<String> violations) {
        String header = violations.size() == 1
                ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        return new AwsException("ValidationException", header + String.join("; ", violations), 400);
    }
}
