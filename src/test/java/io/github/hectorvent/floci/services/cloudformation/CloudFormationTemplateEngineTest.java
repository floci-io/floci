package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private CloudFormationTemplateEngine engineWithCondition(String name, boolean value) {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(name, value), Map.of(), mapper,
                (Function<String, String>) n -> null);
    }

    @Test
    void resolveFnIfPicksTheTrueBranchScalar() {
        assertEquals("prod", engineWithCondition("UseProd", true)
                .resolve(json("{\"Fn::If\": [\"UseProd\", \"prod\", \"dev\"]}")));
    }

    @Test
    void resolveFnIfPicksTheFalseBranchScalar() {
        assertEquals("dev", engineWithCondition("UseProd", false)
                .resolve(json("{\"Fn::If\": [\"UseProd\", \"prod\", \"dev\"]}")));
    }

    @Test
    void resolveNodePreservesArrayShapeThroughFnIf() {
        // github.com/floci-io/floci/issues/2396 (PR #2796 review): resolveNode treated Fn::If the
        // same as every other intrinsic and collapsed it to a stringified scalar, so a conditional
        // array (e.g. a Tags property choosing between two tag lists) resolved to text instead of
        // the chosen branch's actual array - every caller checking isArray() on the result then
        // read a resolvable conditional list as unresolvable.
        JsonNode ifNode = json("""
                {"Fn::If": ["UseProdTags",
                    [{"Key": "Env", "Value": "prod"}],
                    [{"Key": "Env", "Value": "dev"}]]}
                """);

        JsonNode trueResolved = engineWithCondition("UseProdTags", true).resolveNode(ifNode);
        assertTrue(trueResolved.isArray(), "true branch must resolve to a real array: " + trueResolved);
        assertEquals("prod", trueResolved.get(0).get("Value").asText());

        JsonNode falseResolved = engineWithCondition("UseProdTags", false).resolveNode(ifNode);
        assertTrue(falseResolved.isArray(), "false branch must resolve to a real array: " + falseResolved);
        assertEquals("dev", falseResolved.get(0).get("Value").asText());
    }

    @Test
    void resolveNodeResolvesIntrinsicsInsideTheChosenFnIfBranch() {
        // The chosen branch is passed back through resolveNode, not returned verbatim, so a nested
        // intrinsic inside it (e.g. a Ref-valued tag) still resolves.
        JsonNode ifNode = json("""
                {"Fn::If": ["UseProdTags", [{"Key": "Env", "Value": {"Ref": "AWS::Region"}}], []]}
                """);

        JsonNode resolved = engineWithCondition("UseProdTags", true).resolveNode(ifNode);

        assertEquals("us-east-1", resolved.get(0).get("Value").asText());
    }

    private CloudFormationTemplateEngine engineWithParameter(String name, String value) {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(name, value), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) n -> null);
    }

    // github.com/floci-io/floci/issues/2848 (Greptile review on the follow-up fix): "List
    // intrinsics remain scalar" - resolveNode's Fn::If fix alone does not make Fn::Split or a Ref
    // to a CommaDelimitedList parameter list-shaped, since resolveNode never split anything to
    // begin with. resolveList is the engine's dedicated list resolver and already handled
    // Fn::Split and comma-delimited scalars correctly; it only needed Fn::If added.

    @Test
    void resolveListSplitsFnSplitWithItsActualDelimiter() {
        assertEquals(List.of("a", "b", "c"),
                engine().resolveList(json("{\"Fn::Split\": [\"|\", \"a|b|c\"]}")));
    }

    @Test
    void resolveListSplitsACommaDelimitedListParameterRef() {
        assertEquals(List.of("a", "b", "c"),
                engineWithParameter("Csv", "a,b,c").resolveList(json("{\"Ref\": \"Csv\"}")));
    }

    @Test
    void resolveListResolvesFnIfChoosingBetweenTwoFnSplitLists() {
        // The delimiter is deliberately not a comma: falling through to the pre-existing
        // scalar-then-comma-split fallback (rather than actually recursing into the Fn::Split
        // branch) would return the whole unsplit "a|b|c" source as a single element instead of
        // the three pipe-split ones, so this only passes if resolveList's own Fn::If handling
        // is what runs.
        JsonNode node = json("""
                {"Fn::If": ["UseReportBatch",
                    {"Fn::Split": ["|", "a|b|c"]},
                    {"Fn::Split": ["|", "x|y"]}]}
                """);

        assertEquals(List.of("a", "b", "c"),
                engineWithCondition("UseReportBatch", true).resolveList(node));
        assertEquals(List.of("x", "y"),
                engineWithCondition("UseReportBatch", false).resolveList(node));
    }

    @Test
    void resolveListResolvesFnIfChoosingBetweenACommaDelimitedRefAndALiteralArray() {
        JsonNode node = json("{\"Fn::If\": [\"UseParam\", {\"Ref\": \"Csv\"}, [\"fallback\"]]}");

        CloudFormationTemplateEngine trueEngine = new CloudFormationTemplateEngine(
                "000000000000", "us-east-1", "my-stack", "stack/id",
                Map.of("Csv", "x,y"), Map.of(), Map.of(), Map.of("UseParam", true), Map.of(), mapper,
                (Function<String, String>) n -> null);
        assertEquals(List.of("x", "y"), trueEngine.resolveList(node));
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
