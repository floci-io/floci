package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestShapeModel;
import io.github.hectorvent.floci.core.common.RequestShapeModel.Shape;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Rejects an SES v2 request body member of the wrong JSON type with the SerializationException
 * SES answers before it validates anything. The body shapes come from the vendored
 * {@code aws/sesv2-request-shapes.json}, generated from botocore's SES v2 model by
 * {@code tools/aws/regen_sesv2_shapes.py}.
 *
 * <p>An operation calls this when its body nests a structure in a structure or holds a list of
 * structures, where member-by-member checks drift apart. A flat body, a {@code Tags}-only body and
 * a list operation's map {@code Filter} keep the {@link SesV2Json} member helpers.
 *
 * <p>AWS reads the body in order and reports the first bad member, without naming it. A JSON null
 * counts as an absent member, a null list element is left to the operation's own validation, and
 * a member the model does not know is ignored. A map container answers like a structure, as the
 * list operations' {@code Filter} already does. Only the string, blob, list and structure messages
 * have been probed, so a number, boolean or timestamp member is still checked by its controller.
 * The probes sent integers only: a non-integral number is reported as {@code NUMBER_VALUE} too,
 * although AWS may name it differently.
 */
final class SesV2RequestShapes {

    static final String RESOURCE_NAME = "aws/sesv2-request-shapes.json";

    private static final Logger LOG = Logger.getLogger(SesV2RequestShapes.class);

    private static final class Holder {
        static final RequestShapeModel MODEL = RequestShapeModel.load(RESOURCE_NAME, "SES v2 request shapes");
    }

    private SesV2RequestShapes() {
    }

    /** Checks an SES v2 request body against the body shape of {@code operation}. */
    static void check(String operation, JsonNode request) {
        check(Holder.MODEL, operation, request);
    }

    static void check(RequestShapeModel model, String operation, JsonNode request) {
        String input = model.operations().get(operation);
        if (input == null) {
            LOG.errorv("No SES v2 input shape for operation {0} in {1}", operation, RESOURCE_NAME);
            throw new AwsException("InternalFailure",
                    "The request processing has failed because of an unknown error.", 500);
        }
        checkValue(model, model.shapes().get(input), request);
    }

    private static void checkValue(RequestShapeModel model, Shape shape, JsonNode value) {
        if (value.isNull()) {
            return;
        }
        switch (shape.type()) {
            case "structure" -> checkStructure(model, shape, value);
            case "map" -> checkMap(model, shape, value);
            case "list" -> checkList(model, shape, value);
            case "string" -> checkScalar(value, "String");
            case "blob" -> checkScalar(value, "Blob");
            default -> {
            }
        }
    }

    private static void checkStructure(RequestShapeModel model, Shape shape, JsonNode value) {
        requireObject(value);
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            String memberShape = shape.members().get(field.getKey());
            if (memberShape != null) {
                checkValue(model, model.shapes().get(memberShape), field.getValue());
            }
        }
    }

    private static void checkMap(RequestShapeModel model, Shape shape, JsonNode value) {
        requireObject(value);
        Shape element = model.shapes().get(shape.element());
        for (JsonNode item : value) {
            checkValue(model, element, item);
        }
    }

    private static void checkList(RequestShapeModel model, Shape shape, JsonNode value) {
        if (value.isObject()) {
            throw SesV2Json.unexpectedStartError(value);
        }
        if (!value.isArray()) {
            throw SesV2Json.expectedListError();
        }
        Shape element = model.shapes().get(shape.element());
        for (JsonNode item : value) {
            checkValue(model, element, item);
        }
    }

    private static void requireObject(JsonNode value) {
        if (value.isArray()) {
            throw SesV2Json.unexpectedStartError(value);
        }
        if (!value.isObject()) {
            throw SesV2Json.expectedNullError();
        }
    }

    private static void checkScalar(JsonNode value, String target) {
        if (!value.isTextual()) {
            throw SesV2Json.scalarTypeError(value, target);
        }
    }
}
