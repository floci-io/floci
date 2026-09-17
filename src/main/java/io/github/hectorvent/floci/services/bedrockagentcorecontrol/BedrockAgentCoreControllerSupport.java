package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Helpers shared by the sibling REST controllers under this package (control, gateway, gateway
 * rule, identity, memory, resource policy, tools). Each used to carry its own copy of the same
 * error-response builder and JSON-to-map conversion, found duplicated by CPD across all seven.
 *
 * <p>{@code putInstant} deliberately stays out of here: different AgentCore resource types encode
 * timestamps differently (ISO-8601 strings for runtimes, epoch seconds for workload identities and
 * memories), so unlike {@code error} and {@code stringMap}/{@code text}, that one isn't actually
 * one shared behavior wearing several copies, it's genuinely different behavior per resource type.
 */
final class BedrockAgentCoreControllerSupport {

    private BedrockAgentCoreControllerSupport() {
    }

    /**
     * Renders an exception as this protocol's REST JSON error response: an {@link AwsException}
     * maps directly to its own status/code/message, anything else falls back to a 400
     * {@code ValidationException} and gets logged under the caller's own logger so log lines still
     * attribute to the right controller.
     */
    static Response error(Logger log, Exception e, String action) {
        if (e instanceof AwsException aws) {
            return Response.status(aws.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", aws.jsonType())
                    .entity(new AwsErrorResponse(aws.jsonType(), aws.getMessage()))
                    .build();
        }
        log.errorv(e, "Error {0}", action);
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", "ValidationException")
                .entity(new AwsErrorResponse("ValidationException", e.getMessage()))
                .build();
    }

    /** Converts a JSON object node into a flat string map, or null when node isn't an object. */
    static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> map = new HashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        return map;
    }

    /** Reads a text field from a JSON node, or null when the field is absent or JSON null. */
    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
