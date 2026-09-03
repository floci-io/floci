package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void joinAcceptsSplitAsItsListOfValues() {
        assertEquals("x|y|z", engine().resolve(json("""
                {"Fn::Join": ["|", {"Fn::Split": [",", "x,y,z"]}]}
                """)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resolveNodePreservesConditionalTagLists(boolean useTags) {
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("UseTags", useTags), Map.of(), mapper, name -> null);
        JsonNode tags = json("""
                {"Fn::If": ["UseTags", [{"Key": "region", "Value": {"Ref": "AWS::Region"}}], []]}
                """);

        assertEquals(json(useTags ? "[{\"Key\":\"region\",\"Value\":\"us-east-1\"}]" : "[]"),
                e.resolveNode(tags));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            '{"Fn::Split": [".", "a..b."]}', '["a", "", "b", ""]'
            '{"Fn::GetAZs": ""}', '["us-east-1a", "us-east-1b", "us-east-1c"]'
            '{"Fn::Cidr": ["10.0.0.0/16", 2, 8]}', '["10.0.0.0/24", "10.0.1.0/24"]'
            """)
    void resolveNodePreservesListValuedIntrinsics(String expression, String expected) {
        assertEquals(json(expected), engine().resolveNode(json(expression)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void joinAcceptsConditionalLists(boolean useFirst) {
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("UseFirst", useFirst), Map.of(), mapper, name -> null);
        assertEquals(useFirst ? "a,b" : "x,,y,", e.resolve(json("""
                {"Fn::Join": [",", {"Fn::If": ["UseFirst", ["a", "b"],
                  {"Fn::Split": ["|", "x||y|"]}]}]}
                """)));
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

    @Test
    void resolveJsonAttributeUnwrapsAlreadySerializedStringFromFnJoin() {
        // Reproduces #2317: CDK emits RedrivePolicy / FilterPolicy / Definition as an Fn::Join
        // that resolveNode collapses to a TextNode. toString() on that node re-quotes and
        // re-escapes the JSON a second time; resolveJsonAttribute must pass the literal string
        // through instead.
        String serialized = "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:dlq\"}";
        String escaped = serialized.replace("\"", "\\\"");
        String joined = "{\"Fn::Join\":[\"\",[\"" + escaped + "\"]]}";

        assertEquals(serialized, engine().resolveJsonAttribute(json(joined)));
    }

    @Test
    void resolveJsonAttributeSerializesPlainObjectNode() {
        // The object form keeps working: a template object with a resolved intrinsic must still
        // reach the service as the JSON string it parses.
        assertEquals(
                "{\"deadLetterTargetArn\":\"Dlq.Arn\"}",
                engine().resolveJsonAttribute(json(
                        "{\"deadLetterTargetArn\":{\"Fn::GetAtt\":[\"Dlq\",\"Arn\"]}}")));
    }

    @Test
    void resolveJsonAttributeReturnsNullForMissingOrNullNode() {
        assertNull(engine().resolveJsonAttribute(json("null")));
        assertNull(engine().resolveJsonAttribute(mapper.createArrayNode().path("nope")));
    }
}
