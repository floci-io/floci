package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;

import java.util.List;

/** Checks documentation-part requests against the pinned API Gateway service model. */
final class ApiGatewayDocumentationRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/apigateway-documentation-request-shapes.json", "API Gateway documentation request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return bad(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, Number size) {
                    return bad(path + " is outside the modeled bounds.");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return bad(path + " does not match the modeled pattern.");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return bad(path + " is not a modeled value.");
                }

                @Override
                public AwsException required(String path) {
                    return bad(path + " is required.");
                }
            });

    private ApiGatewayDocumentationRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }

    private static AwsException bad(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
