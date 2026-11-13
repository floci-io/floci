package io.floci.aws.services.lambdamicrovms;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestConstraintCheck;

final class LambdaMicrovmRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/lambda-microvm-request-shapes.json", "MicroVM request shapes",
            RequestConstraintCheck.standardMessages("ValidationException"));

    private LambdaMicrovmRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }
}
