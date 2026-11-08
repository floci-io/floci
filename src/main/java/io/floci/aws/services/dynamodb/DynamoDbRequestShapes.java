package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestShapeCheck;
import io.floci.aws.core.common.RequestShapeCheck.ByPosition;
import io.floci.aws.core.common.RequestShapeModel;

import java.util.Set;

/**
 * Rejects a DynamoDB request member of the wrong JSON type with the SerializationException
 * DynamoDB answers before it runs the operation. The input shapes come from the vendored
 * {@code aws/dynamodb-request-shapes.json}, generated from botocore's DynamoDB model by
 * {@code tools/aws/regen_dynamodb_shapes.py}, and are walked by {@link RequestShapeCheck}.
 *
 * <p>AWS reads the body in order and reports the first bad member, without naming it.
 */
public final class DynamoDbRequestShapes {

    static final String RESOURCE_NAME = "aws/dynamodb-request-shapes.json";

    private static final String UNKNOWN_ERROR = "UnknownError";
    private static final ByPosition SCALAR_FOR_CONTAINER = new ByPosition("Unexpected field type", UNKNOWN_ERROR);

    private static final RequestShapeCheck.Messages MESSAGES = new RequestShapeCheck.Messages(
            "SerializationException",
            "Start of structure or map found where not expected",
            UNKNOWN_ERROR,
            new ByPosition("Unexpected field type", "Unexpected value type in payload"),
            SCALAR_FOR_CONTAINER,
            SCALAR_FOR_CONTAINER,
            "%s cannot be converted to %s",
            true,
            "Unexpected token received from parser",
            "only base-64-encoded strings are convertible to bytes",
            Set.of("string", "integer", "long", "double", "timestamp", "boolean", "blob"));

    private static final class Holder {
        static final RequestShapeModel MODEL = RequestShapeModel.load(RESOURCE_NAME, "DynamoDB request shapes");
    }

    private DynamoDbRequestShapes() {
    }

    /** Checks a DynamoDB request body against the input shape of {@code action}, if the model has one. */
    public static void check(String action, JsonNode request) {
        check(Holder.MODEL, action, request);
    }

    static void check(RequestShapeModel model, String action, JsonNode request) {
        String input = model.operations().get(action);
        if (input != null) {
            RequestShapeCheck.check(model, input, request, MESSAGES);
        }
    }
}
