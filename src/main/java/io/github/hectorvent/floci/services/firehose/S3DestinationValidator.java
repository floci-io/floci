package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;

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
 */
final class S3DestinationValidator {

    /** Listed in the order AWS prints it, which is neither declaration order nor alphabetical. */
    private static final String ENUM_VALUE_SET = "[ZIP, HADOOP_SNAPPY, Snappy, GZIP, UNCOMPRESSED]";

    private static final String FILE_EXTENSION_REGEX = "^(|\\.[0-9a-z!\\-_.*'()]+)$";
    private static final Pattern FILE_EXTENSION = Pattern.compile(FILE_EXTENSION_REGEX);
    private static final int FILE_EXTENSION_MAX_LENGTH = 128;

    private S3DestinationValidator() {}

    static void validateWireShape(S3Destination config, String shapeName) {
        if (config == null) {
            return;
        }
        String compressionFormat = config.getCompressionFormat();
        if (compressionFormat != null && FirehoseCompression.fromWireValue(compressionFormat).isEmpty()) {
            throw constraintViolation(shapeName, "compressionFormat",
                    "Member must satisfy enum value set: " + ENUM_VALUE_SET);
        }
        String fileExtension = config.getFileExtension();
        if (fileExtension == null) {
            return;
        }
        if (fileExtension.length() > FILE_EXTENSION_MAX_LENGTH) {
            throw constraintViolation(shapeName, "fileExtension",
                    "Member must have length less than or equal to " + FILE_EXTENSION_MAX_LENGTH);
        }
        if (!FILE_EXTENSION.matcher(fileExtension).matches()) {
            throw constraintViolation(shapeName, "fileExtension",
                    "Member must satisfy regular expression pattern: " + FILE_EXTENSION_REGEX);
        }
    }

    private static AwsException constraintViolation(String shapeName, String member, String constraint) {
        return new AwsException("ValidationException",
                "1 validation error detected: Value at '" + shapeName + "." + member
                        + "' failed to satisfy constraint: " + constraint, 400);
    }
}
