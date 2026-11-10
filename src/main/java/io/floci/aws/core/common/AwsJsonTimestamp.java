package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Parses numeric epoch-second members in AWS JSON requests at millisecond precision. */
public final class AwsJsonTimestamp {

    private AwsJsonTimestamp() {
    }

    public static Instant epochSeconds(JsonNode request, String field, String errorCode) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new AwsException(errorCode, field + " must be a timestamp.", 400);
        }
        double epochMillis = node.asDouble() * 1000;
        if (!Double.isFinite(epochMillis) || epochMillis < Long.MIN_VALUE || epochMillis >= 0x1.0p63) {
            throw new AwsException(errorCode, field + " is outside the timestamp range.", 400);
        }
        return Instant.ofEpochMilli(Math.round(epochMillis));
    }
}
