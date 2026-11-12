package io.floci.aws.services.lambdamicrovms;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestConstraintCheck;

final class LambdaMicrovmImageRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/lambda-microvm-image-request-shapes.json", "MicroVM image request shapes",
            RequestConstraintCheck.standardMessages("ValidationException"));

    private LambdaMicrovmImageRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }
}
