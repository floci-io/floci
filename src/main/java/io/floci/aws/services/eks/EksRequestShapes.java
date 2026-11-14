package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.RequestShapeCheck;
import io.floci.aws.core.common.RequestShapeCheck.ByPosition;

import java.util.Set;

/**
 * Rejects an EKS request body member of the wrong JSON type before the operation reads it, so a member the model
 * types as a boolean, such as {@code resourcesVpcConfig.endpointPublicAccess}, is never a number Floci would read
 * as {@code true} while the IAM condition key of the same member carries no value. The body shapes come from the
 * vendored {@code aws/eks-request-shapes.json}, generated from botocore's EKS model by
 * {@code tools/aws/regen_eks_shapes.py}, and are walked by {@link RequestShapeCheck}.
 *
 * <p>EKS's own wording has not been measured. It answers as the REST JSON deserializer API Gateway and SES v2 were
 * measured to, a {@code SerializationException} naming the JSON token it could not convert, and only a boolean
 * member's scalar is checked: a structure, list or map of the wrong kind is refused too, any other scalar is left to
 * the operation, as before.
 */
final class EksRequestShapes {

    static final String RESOURCE_NAME = "aws/eks-request-shapes.json";

    private static final RequestShapeCheck.Messages MESSAGES = new RequestShapeCheck.Messages(
            "SerializationException",
            "Start of structure or map found where not expected.",
            "Start of list found where not expected",
            ByPosition.same("Expected null"),
            ByPosition.same("Expected list or null"),
            ByPosition.same("Expected map or null"),
            "%s can not be converted to a %s",
            false,
            null,
            null,
            Set.of("boolean"));

    private static final RequestShapeCheck.Operations OPERATIONS =
            new RequestShapeCheck.Operations(RESOURCE_NAME, "EKS request shapes", MESSAGES);

    private EksRequestShapes() {
    }

    /** Checks an EKS request body against the body shape of {@code operation}. */
    static void check(String operation, JsonNode request) {
        OPERATIONS.check(operation, request);
    }
}
