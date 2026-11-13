package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestConstraintCheck;

/**
 * Checks a {@code cloudfront-keyvaluestore} request against the constraints vendored in
 * {@code aws/cloudfront-keyvaluestore-request-shapes.json} by
 * {@code tools/aws/regen_cloudfront_keyvaluestore_shapes.py}. The request is the members the path, the
 * {@code If-Match} header, the query and the body carry, under the names the model gives them. A violation is a
 * {@code ValidationException}.
 */
final class CloudFrontKeyValueStoreRequestShapes {

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.load(
            "aws/cloudfront-keyvaluestore-request-shapes.json",
            "CloudFront KeyValueStore request shapes",
            RequestConstraintCheck.standardMessages("ValidationException"));

    private CloudFrontKeyValueStoreRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }
}
