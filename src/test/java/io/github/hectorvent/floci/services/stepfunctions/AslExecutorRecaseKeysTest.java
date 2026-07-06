package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the PascalCase ↔ lowerCamelCase key bridge used by the Step Functions
 * ecs:runTask integration (Step Functions optimized integrations use PascalCase member
 * names; Floci's ECS handlers use the lowerCamelCase of the data-plane API).
 */
class AslExecutorRecaseKeysTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void camelToPascal_recasesNestedObjectsAndArrays() throws Exception {
        JsonNode camel = mapper.readTree("""
                {
                    "taskArn": "arn:aws:ecs:...:task/abc",
                    "lastStatus": "STOPPED",
                    "containers": [
                        { "name": "runner", "exitCode": 0 }
                    ]
                }
                """);

        JsonNode pascal = AslExecutor.recaseKeys(mapper, camel, true);

        assertEquals("arn:aws:ecs:...:task/abc", pascal.path("TaskArn").asText());
        assertEquals("STOPPED", pascal.path("LastStatus").asText());
        assertEquals("runner", pascal.path("Containers").get(0).path("Name").asText());
        assertEquals(0, pascal.path("Containers").get(0).path("ExitCode").asInt());
        // Original camelCase keys must be gone.
        assertTrue(pascal.path("taskArn").isMissingNode());
    }

    @Test
    void pascalToCamel_isInverseForContainerOverrides() throws Exception {
        JsonNode pascal = mapper.readTree("""
                [
                    {
                        "Name": "runner",
                        "Command": ["terraform", "apply"],
                        "Environment": [ { "Name": "TF_VAR_x", "Value": "1" } ]
                    }
                ]
                """);

        JsonNode camel = AslExecutor.recaseKeys(mapper, pascal, false);

        JsonNode first = camel.get(0);
        assertEquals("runner", first.path("name").asText());
        assertEquals("terraform", first.path("command").get(0).asText());
        assertEquals("TF_VAR_x", first.path("environment").get(0).path("name").asText());
        assertEquals("1", first.path("environment").get(0).path("value").asText());
    }

    @Test
    void roundTrip_preservesScalarValuesAndTypes() throws Exception {
        JsonNode original = mapper.readTree("""
                { "count": 2, "enabled": true, "ratio": 1.5, "note": null }
                """);

        JsonNode back = AslExecutor.recaseKeys(mapper,
                AslExecutor.recaseKeys(mapper, original, true), false);

        assertEquals(2, back.path("count").asInt());
        assertTrue(back.path("enabled").asBoolean());
        assertEquals(1.5, back.path("ratio").asDouble());
        assertTrue(back.path("note").isNull());
    }
}
