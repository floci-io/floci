package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The error codes and messages asserted here were captured from real AWS's raw
 * HTTP responses, see https://github.com/floci-io/floci/issues/2328.
 */
class S3DestinationValidatorTest {

    private static final String ENUM_SET = "[ZIP, HADOOP_SNAPPY, Snappy, GZIP, UNCOMPRESSED]";

    private static S3Destination withCompressionFormat(String compressionFormat) {
        S3Destination config = new S3Destination();
        config.setCompressionFormat(compressionFormat);
        return config;
    }

    private static S3Destination withFileExtension(String fileExtension) {
        S3Destination config = new S3Destination();
        config.setFileExtension(fileExtension);
        return config;
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNCOMPRESSED", "GZIP", "ZIP", "Snappy", "HADOOP_SNAPPY"})
    void wireShapeAcceptsEveryFormatFlociDelivers(String compressionFormat) {
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withCompressionFormat(compressionFormat), "extendedS3DestinationConfiguration"));
    }

    /** Wrong case is a different value to AWS, so it fails the enum like any typo. */
    @ParameterizedTest
    @ValueSource(strings = {"BROTLI", "SNAPPY", "gzip", "", "snappy"})
    void wireShapeRejectsValuesOutsideTheEnumWithTheAwsMessage(String compressionFormat) {
        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withCompressionFormat(compressionFormat), "extendedS3DestinationConfiguration"));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("1 validation error detected: Value at "
                + "'extendedS3DestinationConfiguration.compressionFormat' failed to satisfy constraint: "
                + "Member must satisfy enum value set: " + ENUM_SET, error.getMessage());
    }

    /** AWS names the member that carried the value, which differs per request shape. */
    @Test
    void wireShapeReportsTheShapeTheValueArrivedIn() {
        for (String shape : new String[]{"s3DestinationConfiguration", "extendedS3DestinationUpdate",
                "s3DestinationUpdate"}) {
            AwsException error = assertThrows(AwsException.class,
                    () -> S3DestinationValidator.validateWireShape(withCompressionFormat("BROTLI"), shape));
            assertEquals("1 validation error detected: Value at '" + shape + ".compressionFormat' "
                    + "failed to satisfy constraint: Member must satisfy enum value set: " + ENUM_SET,
                    error.getMessage());
        }
    }

    /**
     * The dot is itself an allowed character after the leading one, so ".." passes
     * (confirmed against real AWS) however odd it looks as an extension.
     */
    @ParameterizedTest
    @ValueSource(strings = {".gz", ".custom.log", ".log", "", ".a-b_c!*'()", ".123", ".."})
    void wireShapeAcceptsFileExtensionsMatchingTheApiPattern(String fileExtension) {
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withFileExtension(fileExtension), "extendedS3DestinationConfiguration"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nodot", ".Custom.LOG", ".", ".with space", ".tab\t", "gz."})
    void wireShapeRejectsFileExtensionsBreakingTheApiPattern(String fileExtension) {
        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withFileExtension(fileExtension), "extendedS3DestinationConfiguration"));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("1 validation error detected: Value at "
                + "'extendedS3DestinationConfiguration.fileExtension' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: ^(|\\.[0-9a-z!\\-_.*'()]+)$",
                error.getMessage());
    }

    @Test
    void wireShapeRejectsFileExtensionsLongerThan128Characters() {
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withFileExtension("." + "a".repeat(127)), "extendedS3DestinationConfiguration"));

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withFileExtension("." + "a".repeat(128)), "extendedS3DestinationConfiguration"));

        assertEquals("1 validation error detected: Value at "
                + "'extendedS3DestinationConfiguration.fileExtension' failed to satisfy constraint: "
                + "Member must have length less than or equal to 128", error.getMessage());
    }

    @Test
    void wireShapeIgnoresDestinationsThatSpecifyNeitherMember() {
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(null, "s3DestinationConfiguration"));
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                new S3Destination(), "s3DestinationConfiguration"));
    }

    private static final String PARQUET_PATH = "extendedS3DestinationConfiguration"
            + ".dataFormatConversionConfiguration.outputFormatConfiguration.serializer.parquetSerDe";

    private static S3Destination withParquetSerDe(DeliveryStreamDescription.ParquetSerDe parquet) {
        DeliveryStreamDescription.Serializer serializer = new DeliveryStreamDescription.Serializer();
        serializer.setParquetSerDe(parquet);
        DeliveryStreamDescription.OutputFormatConfiguration output =
                new DeliveryStreamDescription.OutputFormatConfiguration();
        output.setSerializer(serializer);
        DeliveryStreamDescription.DataFormatConversionConfiguration conversion =
                new DeliveryStreamDescription.DataFormatConversionConfiguration();
        conversion.setOutputFormatConfiguration(output);
        S3Destination config = new S3Destination();
        config.setDataFormatConversionConfiguration(conversion);
        return config;
    }

    @Test
    void wireShapeAcceptsParquetSerDeAtItsExactBounds() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setBlockSizeBytes(67108864L);
        parquet.setPageSizeBytes(65536L);
        parquet.setCompression("SNAPPY");
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withParquetSerDe(parquet), "extendedS3DestinationConfiguration"));
    }

    /** Both bounds violated at once join into one message, as probed against real AWS. */
    @Test
    void wireShapeJoinsBothParquetBoundViolationsIntoOneError() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setBlockSizeBytes(1L);
        parquet.setPageSizeBytes(1L);

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withParquetSerDe(parquet), "extendedS3DestinationConfiguration"));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("2 validation errors detected: "
                + "Value at '" + PARQUET_PATH + ".blockSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 67108864; "
                + "Value at '" + PARQUET_PATH + ".pageSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 65536", error.getMessage());
    }

    @Test
    void wireShapeRejectsNegativeMaxPaddingBytes() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setMaxPaddingBytes(-1L);

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withParquetSerDe(parquet), "extendedS3DestinationConfiguration"));

        assertEquals("1 validation error detected: Value at '" + PARQUET_PATH + ".maxPaddingBytes' "
                + "failed to satisfy constraint: Member must have value greater than or equal to 0",
                error.getMessage());
        parquet.setMaxPaddingBytes(0L);
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withParquetSerDe(parquet), "extendedS3DestinationConfiguration"));
    }

    @Test
    void wireShapeRejectsWriterVersionOutsideTheEnum() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setWriterVersion("V3");

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withParquetSerDe(parquet), "extendedS3DestinationConfiguration"));

        assertEquals("1 validation error detected: Value at '" + PARQUET_PATH + ".writerVersion' "
                + "failed to satisfy constraint: Member must satisfy enum value set: [V1, V2]",
                error.getMessage());
    }

    /** The order AWS prints is neither declaration order nor alphabetical; it was probed. */
    @Test
    void allFiveParquetViolationsAreReportedInTheOrderAwsUses() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setMaxPaddingBytes(-1L);
        parquet.setBlockSizeBytes(1L);
        parquet.setPageSizeBytes(1L);
        parquet.setCompression("ZSTD");
        parquet.setWriterVersion("V3");

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withParquetSerDe(parquet), "extendedS3DestinationConfiguration"));

        assertEquals("5 validation errors detected: "
                + "Value at '" + PARQUET_PATH + ".maxPaddingBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0; "
                + "Value at '" + PARQUET_PATH + ".blockSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 67108864; "
                + "Value at '" + PARQUET_PATH + ".pageSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 65536; "
                + "Value at '" + PARQUET_PATH + ".compression' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [SNAPPY, GZIP, UNCOMPRESSED]; "
                + "Value at '" + PARQUET_PATH + ".writerVersion' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [V1, V2]", error.getMessage());
    }

    private static final String ORC_PATH = "extendedS3DestinationConfiguration"
            + ".dataFormatConversionConfiguration.outputFormatConfiguration.serializer.orcSerDe";

    private static S3Destination withOrcSerDe(DeliveryStreamDescription.OrcSerDe orc) {
        DeliveryStreamDescription.Serializer serializer = new DeliveryStreamDescription.Serializer();
        serializer.setOrcSerDe(orc);
        DeliveryStreamDescription.OutputFormatConfiguration output =
                new DeliveryStreamDescription.OutputFormatConfiguration();
        output.setSerializer(serializer);
        DeliveryStreamDescription.DataFormatConversionConfiguration conversion =
                new DeliveryStreamDescription.DataFormatConversionConfiguration();
        conversion.setOutputFormatConfiguration(output);
        S3Destination config = new S3Destination();
        config.setDataFormatConversionConfiguration(conversion);
        return config;
    }

    @Test
    void wireShapeAcceptsOrcSerDeAtItsExactBounds() {
        DeliveryStreamDescription.OrcSerDe orc = new DeliveryStreamDescription.OrcSerDe();
        orc.setBlockSizeBytes(67108864L);
        orc.setStripeSizeBytes(8388608L);
        orc.setRowIndexStride(1000);
        orc.setPaddingTolerance(0.0);
        orc.setDictionaryKeyThreshold(1.0);
        orc.setBloomFilterFalsePositiveProbability(0.0);
        orc.setCompression("NONE");
        orc.setFormatVersion("V0_12");
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withOrcSerDe(orc), "extendedS3DestinationConfiguration"));
    }

    /** Order and enum spellings probed with all eight violated in one request. */
    @Test
    void allEightOrcViolationsAreReportedInTheOrderAwsUses() {
        DeliveryStreamDescription.OrcSerDe orc = new DeliveryStreamDescription.OrcSerDe();
        orc.setStripeSizeBytes(1L);
        orc.setBlockSizeBytes(1L);
        orc.setRowIndexStride(1);
        orc.setPaddingTolerance(-1.0);
        orc.setCompression("ZSTD");
        orc.setBloomFilterFalsePositiveProbability(2.0);
        orc.setDictionaryKeyThreshold(2.0);
        orc.setFormatVersion("V0_10");

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withOrcSerDe(orc), "extendedS3DestinationConfiguration"));

        assertEquals("8 validation errors detected: "
                + "Value at '" + ORC_PATH + ".dictionaryKeyThreshold' failed to satisfy constraint: "
                + "Member must have value less than or equal to 1; "
                + "Value at '" + ORC_PATH + ".blockSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 67108864; "
                + "Value at '" + ORC_PATH + ".rowIndexStride' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1000; "
                + "Value at '" + ORC_PATH + ".paddingTolerance' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0; "
                + "Value at '" + ORC_PATH + ".stripeSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 8388608; "
                + "Value at '" + ORC_PATH + ".compression' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [ZLIB, SNAPPY, NONE]; "
                + "Value at '" + ORC_PATH + ".formatVersion' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [V0_11, V0_12]; "
                + "Value at '" + ORC_PATH + ".bloomFilterFalsePositiveProbability' failed to satisfy constraint: "
                + "Member must have value less than or equal to 1", error.getMessage());
    }

    /** PaddingTolerance is bounded on both sides, like the two probabilities. */
    @Test
    void orcPaddingToleranceRejectsValuesAboveOne() {
        DeliveryStreamDescription.OrcSerDe orc = new DeliveryStreamDescription.OrcSerDe();
        orc.setPaddingTolerance(999.0);

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withOrcSerDe(orc), "extendedS3DestinationConfiguration"));

        assertEquals("1 validation error detected: Value at '" + ORC_PATH + ".paddingTolerance' "
                + "failed to satisfy constraint: Member must have value less than or equal to 1",
                error.getMessage());
        orc.setPaddingTolerance(1.0);
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withOrcSerDe(orc), "extendedS3DestinationConfiguration"));
    }

    @Test
    void orcProbabilitiesReportTheBoundThatWasCrossed() {
        DeliveryStreamDescription.OrcSerDe orc = new DeliveryStreamDescription.OrcSerDe();
        orc.setDictionaryKeyThreshold(-1.0);
        orc.setBloomFilterFalsePositiveProbability(-1.0);

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withOrcSerDe(orc), "extendedS3DestinationConfiguration"));

        assertEquals("2 validation errors detected: "
                + "Value at '" + ORC_PATH + ".dictionaryKeyThreshold' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0; "
                + "Value at '" + ORC_PATH + ".bloomFilterFalsePositiveProbability' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0", error.getMessage());
    }

    /** Probed: a request carrying both serializers reports the Parquet violation first. */
    @Test
    void parquetViolationsPrecedeOrcOnesWhenBothSerializersAreSet() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setPageSizeBytes(1L);
        DeliveryStreamDescription.OrcSerDe orc = new DeliveryStreamDescription.OrcSerDe();
        orc.setRowIndexStride(1);
        S3Destination config = withParquetSerDe(parquet);
        config.getDataFormatConversionConfiguration().getOutputFormatConfiguration()
                .getSerializer().setOrcSerDe(orc);

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                config, "extendedS3DestinationConfiguration"));

        assertEquals("2 validation errors detected: "
                + "Value at '" + PARQUET_PATH + ".pageSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 65536; "
                + "Value at '" + ORC_PATH + ".rowIndexStride' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1000", error.getMessage());
    }

    private static final String MAPPINGS_PATH = "extendedS3DestinationConfiguration"
            + ".dataFormatConversionConfiguration.inputFormatConfiguration.deserializer"
            + ".openXJsonSerDe.columnToJsonKeyMappings";
    private static final String LENGTH_RULES = "[Member must have length less than or equal to 1024,"
            + " Member must have length greater than or equal to 1,"
            + " Member must satisfy regular expression pattern: ";

    private static S3Destination withColumnMappings(java.util.Map<String, String> mappings) {
        DeliveryStreamDescription.OpenXJsonSerDe openX = new DeliveryStreamDescription.OpenXJsonSerDe();
        openX.setColumnToJsonKeyMappings(mappings);
        DeliveryStreamDescription.Deserializer deserializer = new DeliveryStreamDescription.Deserializer();
        deserializer.setOpenXJsonSerDe(openX);
        DeliveryStreamDescription.InputFormatConfiguration input =
                new DeliveryStreamDescription.InputFormatConfiguration();
        input.setDeserializer(deserializer);
        DeliveryStreamDescription.DataFormatConversionConfiguration conversion =
                new DeliveryStreamDescription.DataFormatConversionConfiguration();
        conversion.setInputFormatConfiguration(input);
        S3Destination config = new S3Destination();
        config.setDataFormatConversionConfiguration(conversion);
        return config;
    }

    @Test
    void wireShapeAcceptsWellFormedColumnMappings() {
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("ticker", "sym", "price", "unit price")),
                "extendedS3DestinationConfiguration"));
    }

    /** Probed: keys may hold no whitespace, while values merely may not be blank. */
    @Test
    void mappingKeysRejectWhitespaceButValuesOnlyRejectBlanks() {
        AwsException keyError = assertThrows(AwsException.class,
                () -> S3DestinationValidator.validateWireShape(
                        withColumnMappings(java.util.Map.of("has space", "v")),
                        "extendedS3DestinationConfiguration"));
        assertEquals("1 validation error detected: Value at '" + MAPPINGS_PATH + "' failed to satisfy "
                + "constraint: Map keys must satisfy constraint: " + LENGTH_RULES + "^\\S+$]",
                keyError.getMessage());

        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("ticker", "has space")),
                "extendedS3DestinationConfiguration"));

        AwsException valueError = assertThrows(AwsException.class,
                () -> S3DestinationValidator.validateWireShape(
                        withColumnMappings(java.util.Map.of("ticker", "   ")),
                        "extendedS3DestinationConfiguration"));
        assertEquals("1 validation error detected: Value at '" + MAPPINGS_PATH + "' failed to satisfy "
                + "constraint: Map value must satisfy constraint: " + LENGTH_RULES + "^(?!\\s*$).+]",
                valueError.getMessage());
    }

    /** However many entries break the rule, AWS reports keys once and values once. */
    @Test
    void mappingViolationsAreReportedOncePerAspectWithKeysFirst() {
        java.util.Map<String, String> mappings = new java.util.LinkedHashMap<>();
        mappings.put("has space", "");
        mappings.put("also space", " ");

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withColumnMappings(mappings), "extendedS3DestinationConfiguration"));

        assertEquals("2 validation errors detected: "
                + "Value at '" + MAPPINGS_PATH + "' failed to satisfy constraint: "
                + "Map keys must satisfy constraint: " + LENGTH_RULES + "^\\S+$]; "
                + "Value at '" + MAPPINGS_PATH + "' failed to satisfy constraint: "
                + "Map value must satisfy constraint: " + LENGTH_RULES + "^(?!\\s*$).+]",
                error.getMessage());
    }

    /**
     * The value rule is the pattern itself, not a String.isBlank() approximation:
     * probed against AWS, which accepts an ideographic space here (its own later
     * SerDe check is what rejects that, a deviation Floci documents) and rejects any
     * value holding a line break, while isBlank() gets both backwards. A no-break
     * space is not whitespace to either rule, so a key may hold one.
     */
    @Test
    void mappingValuesFollowTheDeclaredPatternRatherThanIsBlank() {
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("ticker", "　")),
                "extendedS3DestinationConfiguration"));

        assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("ticker", "a\nb")),
                "extendedS3DestinationConfiguration"));
        assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("ticker", "ab\n")),
                "extendedS3DestinationConfiguration"));

        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("a b", "v")),
                "extendedS3DestinationConfiguration"));
        assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("a\nb", "v")),
                "extendedS3DestinationConfiguration"));
    }

    @Test
    void mappingLengthsAreBoundedAt1024() {
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("k".repeat(1024), "v".repeat(1024))),
                "extendedS3DestinationConfiguration"));

        assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withColumnMappings(java.util.Map.of("k".repeat(1025), "v")),
                "extendedS3DestinationConfiguration"));
    }

    /** Probed: the deserializer's violations are printed after the serializer's. */
    @Test
    void serializerViolationsPrecedeDeserializerOnes() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setPageSizeBytes(1L);
        S3Destination config = withParquetSerDe(parquet);
        DeliveryStreamDescription.OpenXJsonSerDe openX = new DeliveryStreamDescription.OpenXJsonSerDe();
        openX.setColumnToJsonKeyMappings(java.util.Map.of("has space", "v"));
        DeliveryStreamDescription.Deserializer deserializer = new DeliveryStreamDescription.Deserializer();
        deserializer.setOpenXJsonSerDe(openX);
        DeliveryStreamDescription.InputFormatConfiguration input =
                new DeliveryStreamDescription.InputFormatConfiguration();
        input.setDeserializer(deserializer);
        config.getDataFormatConversionConfiguration().setInputFormatConfiguration(input);

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                config, "extendedS3DestinationConfiguration"));

        assertEquals("2 validation errors detected: "
                + "Value at '" + PARQUET_PATH + ".pageSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 65536; "
                + "Value at '" + MAPPINGS_PATH + "' failed to satisfy constraint: "
                + "Map keys must satisfy constraint: " + LENGTH_RULES + "^\\S+$]", error.getMessage());
    }

    private static final String SCHEMA_PATH = "extendedS3DestinationConfiguration"
            + ".dataFormatConversionConfiguration.schemaConfiguration";

    private static S3Destination withSchema(DeliveryStreamDescription.SchemaConfiguration schema) {
        DeliveryStreamDescription.DataFormatConversionConfiguration conversion =
                new DeliveryStreamDescription.DataFormatConversionConfiguration();
        conversion.setSchemaConfiguration(schema);
        S3Destination config = new S3Destination();
        config.setDataFormatConversionConfiguration(conversion);
        return config;
    }

    /** Unlike the collection members, each broken schema rule is its own violation. */
    @Test
    void emptySchemaMemberReportsBothItsLengthAndItsPatternViolation() {
        DeliveryStreamDescription.SchemaConfiguration schema =
                new DeliveryStreamDescription.SchemaConfiguration();
        schema.setCatalogId("");

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withSchema(schema), "extendedS3DestinationConfiguration"));

        assertEquals("2 validation errors detected: "
                + "Value at '" + SCHEMA_PATH + ".catalogId' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1; "
                + "Value at '" + SCHEMA_PATH + ".catalogId' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: ^\\S+$", error.getMessage());
    }

    /**
     * Probed: an empty role ARN trips the same length and pattern rules as the other
     * members, reported under a path that keeps ARN capitalised. A malformed but
     * non-empty one never reaches them on AWS, which answers InvalidParameterValueException
     * first; Floci validates no ARNs, so it accepts that case.
     */
    @Test
    void emptySchemaRoleArnIsReportedUnderTheRoleArnPath() {
        DeliveryStreamDescription.SchemaConfiguration schema =
                new DeliveryStreamDescription.SchemaConfiguration();
        schema.setRoleArn("");

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withSchema(schema), "extendedS3DestinationConfiguration"));

        assertEquals("2 validation errors detected: "
                + "Value at '" + SCHEMA_PATH + ".roleARN' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1; "
                + "Value at '" + SCHEMA_PATH + ".roleARN' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: ^\\S+$", error.getMessage());

        schema.setRoleArn("arn:aws:iam::000000000000:role/firehose-role");
        assertDoesNotThrow(() -> S3DestinationValidator.validateWireShape(
                withSchema(schema), "extendedS3DestinationConfiguration"));
    }

    @Test
    void schemaMembersAreReportedInTheOrderAwsUses() {
        DeliveryStreamDescription.SchemaConfiguration schema =
                new DeliveryStreamDescription.SchemaConfiguration();
        schema.setCatalogId("c d");
        schema.setDatabaseName("e f");
        schema.setTableName("g h");
        schema.setRegion("i j");
        schema.setVersionId("k l");
        schema.setRoleArn("m n");

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withSchema(schema), "extendedS3DestinationConfiguration"));

        String pattern = "' failed to satisfy constraint: Member must satisfy regular expression pattern: ^\\S+$";
        assertEquals("6 validation errors detected: "
                + "Value at '" + SCHEMA_PATH + ".versionId" + pattern + "; "
                + "Value at '" + SCHEMA_PATH + ".catalogId" + pattern + "; "
                + "Value at '" + SCHEMA_PATH + ".databaseName" + pattern + "; "
                + "Value at '" + SCHEMA_PATH + ".roleARN" + pattern + "; "
                + "Value at '" + SCHEMA_PATH + ".region" + pattern + "; "
                + "Value at '" + SCHEMA_PATH + ".tableName" + pattern, error.getMessage());
    }

    @Test
    void schemaMembersAreBoundedAt1024() {
        DeliveryStreamDescription.SchemaConfiguration schema =
                new DeliveryStreamDescription.SchemaConfiguration();
        schema.setCatalogId("x".repeat(1100));

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withSchema(schema), "extendedS3DestinationConfiguration"));

        assertEquals("1 validation error detected: Value at '" + SCHEMA_PATH + ".catalogId' "
                + "failed to satisfy constraint: Member must have length less than or equal to 1024",
                error.getMessage());
    }

    /** Probed: schema violations are printed after the serializer's and deserializer's. */
    @Test
    void schemaViolationsComeLast() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setPageSizeBytes(1L);
        S3Destination config = withParquetSerDe(parquet);
        DeliveryStreamDescription.SchemaConfiguration schema =
                new DeliveryStreamDescription.SchemaConfiguration();
        schema.setTableName("g h");
        config.getDataFormatConversionConfiguration().setSchemaConfiguration(schema);

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                config, "extendedS3DestinationConfiguration"));

        assertEquals("2 validation errors detected: "
                + "Value at '" + PARQUET_PATH + ".pageSizeBytes' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 65536; "
                + "Value at '" + SCHEMA_PATH + ".tableName' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: ^\\S+$", error.getMessage());
    }

    /** NaN satisfies no comparison, so AWS reports both bounds; each infinity trips one. */
    @Test
    void nonFiniteProportionsReportTheBoundsAwsReports() {
        DeliveryStreamDescription.OrcSerDe nan = new DeliveryStreamDescription.OrcSerDe();
        nan.setDictionaryKeyThreshold(Double.NaN);
        AwsException nanError = assertThrows(AwsException.class,
                () -> S3DestinationValidator.validateWireShape(
                        withOrcSerDe(nan), "extendedS3DestinationConfiguration"));
        assertEquals("2 validation errors detected: "
                + "Value at '" + ORC_PATH + ".dictionaryKeyThreshold' failed to satisfy constraint: "
                + "Member must have value less than or equal to 1; "
                + "Value at '" + ORC_PATH + ".dictionaryKeyThreshold' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0", nanError.getMessage());

        DeliveryStreamDescription.OrcSerDe positiveInfinity = new DeliveryStreamDescription.OrcSerDe();
        positiveInfinity.setDictionaryKeyThreshold(Double.POSITIVE_INFINITY);
        assertEquals("1 validation error detected: Value at '" + ORC_PATH + ".dictionaryKeyThreshold' "
                + "failed to satisfy constraint: Member must have value less than or equal to 1",
                assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                        withOrcSerDe(positiveInfinity), "extendedS3DestinationConfiguration")).getMessage());

        DeliveryStreamDescription.OrcSerDe negativeInfinity = new DeliveryStreamDescription.OrcSerDe();
        negativeInfinity.setDictionaryKeyThreshold(Double.NEGATIVE_INFINITY);
        assertEquals("1 validation error detected: Value at '" + ORC_PATH + ".dictionaryKeyThreshold' "
                + "failed to satisfy constraint: Member must have value greater than or equal to 0",
                assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                        withOrcSerDe(negativeInfinity), "extendedS3DestinationConfiguration")).getMessage());
    }

    @Test
    void wireShapeRejectsParquetCompressionOutsideTheEnum() {
        DeliveryStreamDescription.ParquetSerDe parquet = new DeliveryStreamDescription.ParquetSerDe();
        parquet.setCompression("ZSTD");

        AwsException error = assertThrows(AwsException.class, () -> S3DestinationValidator.validateWireShape(
                withParquetSerDe(parquet), "extendedS3DestinationConfiguration"));

        assertEquals("1 validation error detected: Value at '" + PARQUET_PATH + ".compression' "
                + "failed to satisfy constraint: Member must satisfy enum value set: [SNAPPY, GZIP, UNCOMPRESSED]",
                error.getMessage());
    }

}
