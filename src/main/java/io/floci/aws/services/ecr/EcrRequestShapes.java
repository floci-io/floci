package io.floci.aws.services.ecr;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestConstraintCheck;

/**
 * Checks an ECR request against the constraints vendored in {@code aws/ecr-request-shapes.json} by
 * {@code tools/aws/regen_ecr_shapes.py}. A violation is {@code InvalidParameterException} in ECR's
 * form, {@code Invalid parameter at '<member>' failed to satisfy constraint: '<rule>'}, with the
 * member's path from the request's top level.
 */
final class EcrRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/ecr-request-shapes.json", "ECR request shapes",
            RequestConstraintCheck.standardMessages("InvalidParameterException"));

    private EcrRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }
}
