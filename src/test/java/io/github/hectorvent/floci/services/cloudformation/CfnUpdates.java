package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An UpdateStack whose template changes none of the stack's resources and outputs is refused with
 * "No updates are to be performed.", as on AWS, a new Description included. A test that re-applies
 * the same resources to exercise an update adds an output instead: on AWS that is an update which
 * leaves every resource as it is (measured on CloudFormation, eu-west-1, 2026-10-16).
 */
final class CfnUpdates {

    /** The output {@link #forceUpdate} adds, for a test that compares a stack's outputs. */
    static final String FORCED_OUTPUT = "FlociForcedUpdate";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CfnUpdates() {
    }

    /** {@code template}, JSON or YAML, as JSON with an output no deployed template carries. */
    static String forceUpdate(String template) {
        try {
            JsonNode parsed = template.stripLeading().startsWith("{")
                    ? MAPPER.readTree(template)
                    : new CloudFormationYamlParser(MAPPER).parse(template);
            ObjectNode node = (ObjectNode) parsed;
            ObjectNode outputs = node.has("Outputs") ? (ObjectNode) node.get("Outputs") : node.putObject("Outputs");
            outputs.putObject(FORCED_OUTPUT).put("Value", String.valueOf(System.nanoTime()));
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a CloudFormation template", e);
        }
    }
}
