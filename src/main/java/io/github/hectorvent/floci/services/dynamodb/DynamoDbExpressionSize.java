package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;

import java.nio.charset.StandardCharsets;

// AWS counts the raw expression text, before placeholders are resolved. Write APIs wrap
// the error in the validation envelope, read APIs do not, and only Scan reports the size.
final class DynamoDbExpressionSize {

    static final int MAX_BYTES = 4096;

    private static final String EXCEEDED = "Expression size has exceeded the maximum allowed size;";

    private DynamoDbExpressionSize() {}

    static void checkWrite(String expression, String exprType) {
        if (exceeds(expression)) {
            throw validationEx("1 validation error detected: Invalid " + exprType + ": " + EXCEEDED);
        }
    }

    static void checkRead(String expression, String exprType) {
        if (exceeds(expression)) {
            throw validationEx("Invalid " + exprType + ": " + EXCEEDED);
        }
    }

    static void checkScanFilter(String expression) {
        if (exceeds(expression)) {
            throw validationEx("Invalid FilterExpression: " + EXCEEDED
                    + " expression size: " + byteLength(expression));
        }
    }

    private static boolean exceeds(String expression) {
        return expression != null && byteLength(expression) > MAX_BYTES;
    }

    private static int byteLength(String expression) {
        return expression.getBytes(StandardCharsets.UTF_8).length;
    }

    private static AwsException validationEx(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
