package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudFormationTemplateEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getAzsReturnsStackRegionZones() {
        assertEquals("us-east-1a,us-east-1b,us-east-1c",
                engine().resolve(json("{\"Fn::GetAZs\": \"\"}")));
    }

    @Test
    void getAzsHonoursExplicitRegion() {
        assertEquals("eu-west-1a,eu-west-1b,eu-west-1c",
                engine().resolve(json("{\"Fn::GetAZs\": \"eu-west-1\"}")));
    }

    @Test
    void selectFromGetAzsResolvesZoneByIndex() {
        CloudFormationTemplateEngine e = engine();
        // CDK emits the index as a string; AWS also allows a number.
        assertEquals("us-east-1a", e.resolve(json("{\"Fn::Select\": [\"0\", {\"Fn::GetAZs\": \"\"}]}")));
        assertEquals("us-east-1b", e.resolve(json("{\"Fn::Select\": [1, {\"Fn::GetAZs\": \"\"}]}")));
    }

    @Test
    void cidrSplitsBlockIntoSubnets() {
        assertEquals("10.0.0.0/24,10.0.1.0/24,10.0.2.0/24,10.0.3.0/24",
                engine().resolve(json("{\"Fn::Cidr\": [\"10.0.0.0/16\", 4, 8]}")));
    }

    @Test
    void selectFromCidrResolvesSubnetByIndex() {
        assertEquals("10.0.2.0/24",
                engine().resolve(json("{\"Fn::Select\": [2, {\"Fn::Cidr\": [\"10.0.0.0/16\", 4, 8]}]}")));
    }
}
