package io.floci.aws.services.ecr;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;

import java.util.List;

/**
 * Checks an ECR request against the constraints vendored in {@code aws/ecr-request-shapes.json} by
 * {@code tools/aws/regen_ecr_shapes.py}. A violation is {@code InvalidParameterException} in ECR's
 * form, {@code Invalid parameter at '<member>' failed to satisfy constraint: '<rule>'}, with the
 * member's path from the request's top level.
 */
final class EcrRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/ecr-request-shapes.json", "ECR request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return invalid(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String measure = "integer".equals(type) || "long".equals(type) ? "value" : "length";
                    return min != null && size < min
                            ? invalid(path, "Member must have " + measure + " greater than or equal to " + min)
                            : invalid(path, "Member must have " + measure + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalid(path, "must satisfy regular expression '" + pattern + "'");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return invalid(path, "Member must satisfy enum value set: " + values);
                }

                @Override
                public AwsException required(String path) {
                    return invalid(path, "Member must not be null");
                }
            });

    private EcrRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }

    /** The member's path below the operation, with list elements folded into their list. */
    private static AwsException invalid(String path, String rule) {
        int dot = path.indexOf('.');
        String member = (dot < 0 ? "" : path.substring(dot + 1)).replace("[]", "");
        return new AwsException("InvalidParameterException",
                "Invalid parameter at '" + member + "' failed to satisfy constraint: '" + rule + "'", 400);
    }
}
