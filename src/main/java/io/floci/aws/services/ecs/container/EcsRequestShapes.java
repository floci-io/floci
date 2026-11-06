package io.floci.aws.services.ecs.container;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestShapeCheck;
import io.floci.aws.core.common.RequestShapeCheck.ByPosition;
import io.floci.aws.core.common.RequestShapeModel;

import java.util.Set;

/**
 * Rejects an ECS request member of the wrong JSON type with the InvalidParameterException ECS's
 * deserializer answers before it validates anything (measured with raw requests on
 * RegisterTaskDefinition, eu-west-1, 2026-10-18). The shapes come from the vendored
 * {@code aws/ecs-request-shapes.json}, generated from botocore's ECS model by
 * {@code tools/aws/regen_ecs_shapes.py}, and are walked by {@link RequestShapeCheck}.
 *
 * <p>It checks the members Floci keeps unparsed, a container's {@code linuxParameters}, whose
 * scalars are strings, integers and booleans. A list where none is expected is refused with no
 * message at all.
 */
final class EcsRequestShapes {

    static final String RESOURCE_NAME = "aws/ecs-request-shapes.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String UNEXPECTED_FIELD_TYPE = "Unexpected field type";
    private static final ByPosition SCALAR_FOR_STRUCTURE =
            new ByPosition(UNEXPECTED_FIELD_TYPE, "Unexpected value type in payload");

    private static final RequestShapeCheck.Messages MESSAGES = new RequestShapeCheck.Messages(
            "InvalidParameterException",
            "Start of structure or map found where not expected",
            null,
            SCALAR_FOR_STRUCTURE,
            ByPosition.same(UNEXPECTED_FIELD_TYPE),
            SCALAR_FOR_STRUCTURE,
            "%s cannot be converted to %s",
            false,
            "Unexpected token received from parser",
            null,
            Set.of("string", "integer", "boolean"));

    private static final class Holder {
        static final RequestShapeModel MODEL = RequestShapeModel.load(RESOURCE_NAME, "ECS request shapes");
    }

    private EcsRequestShapes() {
    }

    /** Checks {@code value}, a structure member, against the shape {@code shapeName}. */
    static void check(String shapeName, Object value) {
        check(Holder.MODEL, shapeName, value);
    }

    static void check(RequestShapeModel model, String shapeName, Object value) {
        RequestShapeCheck.check(model, shapeName, MAPPER.valueToTree(value), MESSAGES);
    }
}
