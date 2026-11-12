package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;

import java.util.List;

/** Request constraints vendored from botocore by regen_kinesis_shapes.py. */
final class KinesisRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/kinesis-request-shapes.json", "Kinesis request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    if (path.endsWith(".MaxRecordSizeInKiB")) {
                        return new AwsException("InvalidArgumentException",
                                "MaxRecordSizeInKiB must be an integer", 400);
                    }
                    if ("ListStreams.Limit".equals(path)) {
                        return new AwsException("InvalidArgumentException",
                                "Limit must be an integer between 1 and 10000", 400);
                    }
                    if ("ListStreams.ExclusiveStartStreamName".equals(path)) {
                        return new AwsException("InvalidArgumentException",
                                "ExclusiveStartStreamName must be a valid stream name", 400);
                    }
                    if ("ListStreams.NextToken".equals(path)) {
                        return new AwsException("InvalidArgumentException", "The NextToken is not valid.", 400);
                    }
                    if ("UpdateShardCount.TargetShardCount".equals(path)) {
                        return new AwsException("InvalidArgumentException", "TargetShardCount must be an integer", 400);
                    }
                    if (path.endsWith(".Tags") || path.contains(".Tags.")) {
                        return new AwsException("SerializationException",
                                "Tags must be a map of string values.", 400);
                    }
                    if (path.contains(".TagKeys")) {
                        return new AwsException("SerializationException",
                                "TagKeys must be a list of strings.", 400);
                    }
                    return new AwsException("SerializationException", path + " must be a " + type + ".", 400);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, Number size) {
                    if ("UpdateMaxRecordSize.MaxRecordSizeInKiB".equals(path)) {
                        return new AwsException("ValidationException",
                                "MaxRecordSizeInKiB is outside the modeled bounds", 400);
                    }
                    return invalid(path + " is outside the modeled bounds.");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalid(path + " does not match the modeled pattern.");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return invalid(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    if ("UpdateMaxRecordSize.MaxRecordSizeInKiB".equals(path)) {
                        return new AwsException("InvalidArgumentException", "MaxRecordSizeInKiB is required", 400);
                    }
                    if ("PutRecord.Data".equals(path)) {
                        return new AwsException("SerializationException", "Data must be a base64-encoded string.", 400);
                    }
                    return invalid(path + " is required.");
                }
            });

    private KinesisRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }
}
