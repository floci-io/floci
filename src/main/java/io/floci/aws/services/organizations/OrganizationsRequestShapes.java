package io.floci.aws.services.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestShapeCheck;
import io.floci.aws.core.common.RequestShapeCheck.ByPosition;
import io.floci.aws.core.common.RequestShapeModel;

import java.util.Set;

/**
 * Rejects an Organizations request member of the wrong JSON type before the operation reads it. The
 * input shapes come from the vendored {@code aws/organizations-request-shapes.json}, generated from
 * botocore's Organizations model by {@code tools/aws/regen_organizations_shapes.py}, and are walked by
 * {@link RequestShapeCheck}.
 *
 * <p>Organizations' own answers have not been measured. The SerializationException and its wording are
 * those DynamoDB's deserializer gives, and the JSON 1.1 dispatcher already answers a body that is not a
 * JSON object with a SerializationException. Only strings and integers are checked, the only scalars in
 * the requests of the operations Floci serves.
 */
final class OrganizationsRequestShapes {

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
            null,
            null,
            Set.of("string", "integer"));

    private static final class Holder {
        static final RequestShapeModel MODEL =
                RequestShapeModel.load("aws/organizations-request-shapes.json", "Organizations request shapes");
    }

    private OrganizationsRequestShapes() {
    }

    /** Checks a request body against the input shape of {@code operation}, if the operation takes input. */
    static void check(String operation, JsonNode request) {
        String input = Holder.MODEL.operations().get(operation);
        if (input != null) {
            RequestShapeCheck.check(Holder.MODEL, input, request, MESSAGES);
        }
    }
}
