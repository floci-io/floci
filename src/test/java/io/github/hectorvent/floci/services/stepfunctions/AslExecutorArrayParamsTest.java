package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Parameters / ItemSelector payload template must resolve {@code .$} references at ANY nesting
 * depth, including inside arrays — e.g. the {@code Environment} array of an ECS
 * {@code Overrides.ContainerOverrides[]}. Without array recursion those {@code Value.$} entries pass
 * through verbatim and the launched container receives empty environment variables.
 */
class AslExecutorArrayParamsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AslExecutor newExecutor() {
        return new AslExecutor(null, null, null, null, null, null, null, null,
                null, null, mapper, null, null);
    }

    @Test
    void resolvesDotDollarReferencesInsideArrays() throws Exception {
        JsonNode parameters = mapper.readTree("""
                {
                  "Overrides": {
                    "ContainerOverrides": [
                      {
                        "Name": "runner",
                        "Environment": [
                          {"Name": "ACTION_TYPE", "Value.$": "$.solution.operation"},
                          {"Name": "PROVISION_TYPE", "Value.$": "$.solution.primaryProvisionType"},
                          {"Name": "STATIC", "Value": "CREATE_UPDATE"}
                        ]
                      }
                    ]
                  }
                }
                """);
        JsonNode input = mapper.readTree("""
                { "solution": { "operation": "create", "primaryProvisionType": "awscdk" } }
                """);

        JsonNode resolved = newExecutor().resolveParameters(parameters, input, mapper.createObjectNode());

        JsonNode env = resolved.path("Overrides").path("ContainerOverrides").path(0).path("Environment");
        assertEquals("create", env.path(0).path("Value").asText());
        assertEquals("awscdk", env.path(1).path("Value").asText());
        assertEquals("CREATE_UPDATE", env.path(2).path("Value").asText());
        // The unresolved ".$" key must be gone.
        assertTrue(env.path(0).path("Value.$").isMissingNode(), "Value.$ should have been resolved away");
    }
}
