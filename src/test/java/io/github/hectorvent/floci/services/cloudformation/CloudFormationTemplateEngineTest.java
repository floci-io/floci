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

    /**
     * Fn::Select is positional: a blank entry ahead of the selected index is still a real list
     * element and must not shift the elements after it, unlike resolveStringList's provisioner-
     * facing contract of dropping blanks (issue found in PR #2946 review).
     */
    @Test
    void selectFromLiteralArrayKeepsBlankEntriesAtTheirPosition() {
        CloudFormationTemplateEngine e = engine();
        assertEquals("", e.resolve(json("{\"Fn::Select\": [0, [\"\", \"subnet-b\", \"subnet-c\"]]}")));
        assertEquals("subnet-b", e.resolve(json("{\"Fn::Select\": [1, [\"\", \"subnet-b\", \"subnet-c\"]]}")));
        assertEquals("subnet-c", e.resolve(json("{\"Fn::Select\": [2, [\"\", \"subnet-b\", \"subnet-c\"]]}")));
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

    @Test
    void resolveStringListSplitsListValuedIntrinsicNode() {
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), mapper, (Function<String, String>) name ->
                        "Subnets".equals(name) ? "subnet-a,subnet-b" : null);

        assertEquals(java.util.List.of("subnet-a", "subnet-b"),
                e.resolveStringList(json("{\"Fn::Split\":[\",\",{\"Fn::ImportValue\":\"Subnets\"}]}")));
    }

    @Test
    void resolveStringListFlattensListValuedArrayElements() {
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), mapper, (Function<String, String>) name ->
                        "Subnets".equals(name) ? "subnet-a,subnet-b" : null);

        assertEquals(java.util.List.of("subnet-a", "subnet-b"),
                e.resolveStringList(json(
                        "[{\"Fn::Split\":[\",\",{\"Fn::ImportValue\":\"Subnets\"}]}]")));
    }

    @Test
    void resolveStringListKeepsLiteralArrayOfScalars() {
        assertEquals(java.util.List.of("subnet-a", "subnet-b"),
                engine().resolveStringList(json("[\"subnet-a\",\"subnet-b\"]")));
    }

    @Test
    void resolveStringListDropsBlankEntries() {
        assertEquals(java.util.List.of("subnet-a"),
                engine().resolveStringList(json("[\"subnet-a\",\"\",\"  \"]")));
    }

    @Test
    void resolveStringListReturnsEmptyForNullOrMissing() {
        assertEquals(java.util.List.of(), engine().resolveStringList(json("null")));
        assertEquals(java.util.List.of(),
                engine().resolveStringList(mapper.createArrayNode().path("nope")));
    }

    @Test
    void resolveStringListExpandsFnIfWithListValuedBranch() {
        CloudFormationTemplateEngine eTrue = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("UseCustom", true), Map.of(), mapper, (Function<String, String>) name ->
                        "Subnets".equals(name) ? "subnet-a,subnet-b" : null);

        assertEquals(java.util.List.of("subnet-a", "subnet-b"),
                eTrue.resolveStringList(json("{\"Fn::If\":[\"UseCustom\",{\"Fn::Split\":[\",\",{\"Fn::ImportValue\":\"Subnets\"}]},\"subnet-default\"]}")));

        CloudFormationTemplateEngine eFalse = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("UseCustom", false), Map.of(), mapper, (Function<String, String>) name ->
                        "Subnets".equals(name) ? "subnet-a,subnet-b" : null);

        assertEquals(java.util.List.of("subnet-default"),
                eFalse.resolveStringList(json("{\"Fn::If\":[\"UseCustom\",{\"Fn::Split\":[\",\",{\"Fn::ImportValue\":\"Subnets\"}]},\"subnet-default\"]}")));
    }

    @Test
    void resolveStringListFlattensFnIfInsideArrayElements() {
        CloudFormationTemplateEngine eTrue = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("UseCustom", true), Map.of(), mapper, (Function<String, String>) name ->
                        "Subnets".equals(name) ? "subnet-a,subnet-b" : null);

        assertEquals(java.util.List.of("subnet-a", "subnet-b"),
                eTrue.resolveStringList(json("[{\"Fn::If\":[\"UseCustom\",{\"Fn::Split\":[\",\",{\"Fn::ImportValue\":\"Subnets\"}]},\"subnet-default\"]}]")));

        CloudFormationTemplateEngine eFalse = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("UseCustom", false), Map.of(), mapper, (Function<String, String>) name ->
                        "Subnets".equals(name) ? "subnet-a,subnet-b" : null);

        assertEquals(java.util.List.of("subnet-default"),
                eFalse.resolveStringList(json("[{\"Fn::If\":[\"UseCustom\",{\"Fn::Split\":[\",\",{\"Fn::ImportValue\":\"Subnets\"}]},\"subnet-default\"]}]")));
    }

    @Test
    void resolveStringListExpandsFnIfSelectingArrayWithNestedSplit() {
        CloudFormationTemplateEngine eTrue = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("UseCustom", true), Map.of(), mapper, (Function<String, String>) name ->
                        "Subnets".equals(name) ? "subnet-a,subnet-b" : null);

        assertEquals(java.util.List.of("subnet-prefix", "subnet-a", "subnet-b"),
                eTrue.resolveStringList(json("{\"Fn::If\":[\"UseCustom\",[\"subnet-prefix\",{\"Fn::Split\":[\",\",{\"Fn::ImportValue\":\"Subnets\"}]}],[\"subnet-default\"]]}")));

        CloudFormationTemplateEngine eFalse = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("UseCustom", false), Map.of(), mapper, (Function<String, String>) name ->
                        "Subnets".equals(name) ? "subnet-a,subnet-b" : null);

        assertEquals(java.util.List.of("subnet-default"),
                eFalse.resolveStringList(json("{\"Fn::If\":[\"UseCustom\",[\"subnet-prefix\",{\"Fn::Split\":[\",\",{\"Fn::ImportValue\":\"Subnets\"}]}],[\"subnet-default\"]]}")));
    }
}
