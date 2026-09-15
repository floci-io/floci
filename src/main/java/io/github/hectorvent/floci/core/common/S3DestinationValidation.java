package io.github.hectorvent.floci.core.common;

/**
 * S3 bucket-name and key-segment validation shared by services that accept an S3 destination for
 * an export configuration: BCM Data Exports and CUR both validated the same character-set and
 * length rules independently before this class existed.
 */
public final class S3DestinationValidation {

    private S3DestinationValidation() {
    }

    public static void requireValidBucketName(String bucket, String field) {
        if (bucket.length() < 3 || bucket.length() > 63) {
            throw new AwsException("ValidationException",
                    field + " must be between 3 and 63 characters.", 400);
        }
        for (int i = 0; i < bucket.length(); i++) {
            char c = bucket.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '.';
            if (!valid) {
                throw new AwsException("ValidationException",
                        field + " contains invalid characters.", 400);
            }
        }
        if (bucket.startsWith("-") || bucket.endsWith("-")
                || bucket.startsWith(".") || bucket.endsWith(".")
                || bucket.contains("..")) {
            throw new AwsException("ValidationException",
                    field + " is not a valid S3 bucket name.", 400);
        }
    }

    public static void requireSafeKeySegment(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '/';
            if (!ok) {
                throw new AwsException("ValidationException",
                        field + " contains characters not permitted in an S3 key segment.", 400);
            }
        }
    }
}
