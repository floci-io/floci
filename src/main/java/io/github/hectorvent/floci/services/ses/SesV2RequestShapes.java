package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RequestShapeCheck;
import io.github.hectorvent.floci.core.common.RequestShapeCheck.ByPosition;

import java.util.Set;

/**
 * Rejects an SES v2 request body member of the wrong JSON type with the SerializationException
 * SES answers before it validates anything. The body shapes come from the vendored
 * {@code aws/sesv2-request-shapes.json}, generated from botocore's SES v2 model by
 * {@code tools/aws/regen_sesv2_shapes.py}, and are walked by {@link RequestShapeCheck}.
 *
 * <p>An operation calls this when its body nests a structure in a structure or holds a list of
 * structures, where member-by-member checks drift apart. A flat body, a {@code Tags}-only body and
 * a list operation's map {@code Filter} keep the {@link SesV2Json} member helpers, except in the
 * reputation entity operations, which were added after this check and call it although their
 * bodies are flat.
 *
 * <p>AWS reads the body in order and reports the first bad member, without naming it. A map
 * container answers like a structure, except that a scalar for it is "Expected map or null". Only
 * the string, blob, list, structure and map messages have been probed, so a number, boolean or
 * timestamp member is still checked by its controller. The probes sent integers only: a
 * non-integral number is reported as {@code NUMBER_VALUE} too, although AWS may name it
 * differently.
 */
final class SesV2RequestShapes {

    static final String RESOURCE_NAME = "aws/sesv2-request-shapes.json";

    private static final RequestShapeCheck.Messages MESSAGES = new RequestShapeCheck.Messages(
            SesV2Json.SERIALIZATION_EXCEPTION,
            SesV2Json.OBJECT_NOT_EXPECTED,
            SesV2Json.LIST_NOT_EXPECTED,
            ByPosition.same(SesV2Json.EXPECTED_NULL),
            ByPosition.same(SesV2Json.EXPECTED_LIST),
            ByPosition.same(SesV2Json.EXPECTED_MAP),
            SesV2Json.CONVERSION,
            false,
            null,
            null,
            Set.of("string", "blob"));

    private static final RequestShapeCheck.Operations OPERATIONS =
            new RequestShapeCheck.Operations(RESOURCE_NAME, "SES v2 request shapes", MESSAGES);

    private SesV2RequestShapes() {
    }

    /** Checks an SES v2 request body against the body shape of {@code operation}. */
    static void check(String operation, JsonNode request) {
        OPERATIONS.check(operation, request);
    }
}
