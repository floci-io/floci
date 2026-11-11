package io.floci.aws.services.rekognition;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;

import java.util.List;

/** Request constraints vendored from botocore by regen_rekognition_shapes.py. */
final class RekognitionRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/rekognition-request-shapes.json", "Rekognition request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return new AwsException("SerializationException", path + " must be a " + type + ".", 400);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, Number size) {
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
                    return invalid(path + " is required.");
                }
            });

    private RekognitionRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
