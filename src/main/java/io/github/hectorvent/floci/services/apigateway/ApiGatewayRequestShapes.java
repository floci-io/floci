package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RequestShapeCheck;
import io.github.hectorvent.floci.core.common.RequestShapeCheck.ByPosition;

import java.util.Map;
import java.util.Set;

/**
 * Rejects an API Gateway request body member of the wrong JSON type with the SerializationException
 * API Gateway answers before it validates anything. The body shapes come from the vendored
 * {@code aws/apigateway-request-shapes.json}, generated from botocore's API Gateway model by
 * {@code tools/aws/regen_apigateway_shapes.py}, and are walked by {@link RequestShapeCheck}.
 *
 * <p>The messages were measured with raw {@code CreateUsagePlan} requests against API Gateway in
 * eu-west-1 on 2026-10-24. Its deserializer differs from SES v2's in two ways. It names the Java
 * class it read rather than the JSON token: a JSON number is a
 * {@code com.amazon.coral.value.json.numbers.TruncatingBigNumber}, text a {@code java.lang.String}
 * and true or false a {@code java.lang.Boolean}, and a double member words the refusal as "is not an
 * expected Json type". And it reads a structure's members in member-name order, at every level, so
 * the first bad member by name is reported whatever order the body has. An integer member takes any
 * JSON number but refuses a numeric string such as {@code "100"}; the operation truncates the number
 * from its decimal digits, clamps it to the int range and refuses a negative fraction as negative. A
 * double member refuses text too. Only string, integer and double members were probed, so a member
 * of another scalar type is left to the operation.
 */
final class ApiGatewayRequestShapes {

    static final String RESOURCE_NAME = "aws/apigateway-request-shapes.json";

    private static final String NUMBER = "class com.amazon.coral.value.json.numbers.TruncatingBigNumber";
    private static final String BOOLEAN = "class java.lang.Boolean";

    private static final RequestShapeCheck.Messages MESSAGES = new RequestShapeCheck.Messages(
            "SerializationException",
            "Start of structure or map found where not expected.",
            "Start of list found where not expected",
            ByPosition.same("Expected null"),
            ByPosition.same("Expected list or null"),
            ByPosition.same("Expected map or null"),
            "%s can not be converted to an %s",
            false,
            null,
            null,
            Set.of("string", "integer", "double"),
            Map.of("NUMBER_VALUE", NUMBER, "DECIMAL_VALUE", NUMBER, "TRUE_VALUE", BOOLEAN, "FALSE_VALUE", BOOLEAN,
                    "STRING_VALUE", "class java.lang.String"),
            Map.of("double", "%s is not an expected Json type"),
            true);

    private static final RequestShapeCheck.Operations OPERATIONS =
            new RequestShapeCheck.Operations(RESOURCE_NAME, "API Gateway request shapes", MESSAGES);

    private ApiGatewayRequestShapes() {
    }

    /** Checks an API Gateway request body against the body shape of {@code operation}. */
    static void check(String operation, JsonNode request) {
        OPERATIONS.check(operation, request);
    }
}
