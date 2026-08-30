package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.core.common.AwsException;
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

}
