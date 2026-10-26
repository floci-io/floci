package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.provisioners.Ec2NetworkCfnProvisioner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CloudFormationTemplateEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private CloudFormationTemplateEngine engineWithAttributes(Map<String, Map<String, String>> attributes) {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), attributes, Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    @Test
    void getAttOnAMissingAttributeOfAStubbedDisabledServiceIsRefused() {
        CloudFormationTemplateEngine e = engineWithAttributes(Map.of("Repo", Map.of(
                "Arn", "arn:aws:stub:::Repo", CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR, "true")));

        AwsException failure = assertThrows(AwsException.class,
                () -> e.resolve(json("{\"Fn::GetAtt\": [\"Repo\", \"RepositoryUri\"]}")));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("arn:aws:stub:::Repo", e.resolve(json("{\"Fn::GetAtt\": [\"Repo\", \"Arn\"]}")));
    }

    @Test
    void getAttOnAMissingAttributeOfAnOrdinaryStubStillResolvesToThePlaceholder() {
        CloudFormationTemplateEngine e = engineWithAttributes(Map.of("Thing", Map.of("Arn", "arn:aws:stub:::Thing")));

        assertEquals("Thing.Missing", e.resolve(json("{\"Fn::GetAtt\": [\"Thing\", \"Missing\"]}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"Fn::Sub\":\"${Bucket.Missing}/*\"}",
        "{\"Fn::Join\":[\"\",[{\"Fn::GetAtt\":[\"Bucket\",\"Missing\"]},\"/*\"]]}",
        "{\"Fn::Sub\":[\"${Value}/*\",{\"Value\":{\"Fn::GetAtt\":\"Bucket.Missing\"}}]}",
        "{\"Fn::Base64\":{\"Fn::GetAtt\":\"Bucket.Missing\"}}",
        "{\"Fn::Split\":[\",\",{\"Fn::GetAtt\":\"Bucket.Missing\"}]}",
        "{\"Fn::Join\":[\"\",[{\"Fn::If\":[\"Absent\",\"unused\",{\"Fn::GetAtt\":\"Bucket.Missing\"}]}]]}",
        "{\"Fn::Join\":[\"\",[{\"Fn::Select\":[0,[{\"Fn::GetAtt\":\"Bucket.Missing\"}]]}]]}"
    })
    void stringTypeChecksRecordMissingAttributesOnce(String intrinsic) {
        CloudFormationTemplateEngine e = engineWithAttributes(Map.of("Bucket", Map.of("Arn", "bucket-arn")));
        JsonNode policy = mapper.createObjectNode().set("Resource", json(intrinsic));

        AwsException failure = assertThrows(AwsException.class, () -> e.resolveJsonAttributeStrict(policy));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("Policy document contains unresolved CloudFormation intrinsics: Fn::GetAtt Bucket.Missing. "
                + "Storing them would silently void the statements that use them.", failure.getMessage());
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CloudFormationTemplateEngine engineWithNumericAttribute() {
        Ec2NetworkCfnProvisioner provisioner = new Ec2NetworkCfnProvisioner(null);
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack", "stack/id",
                Map.of(), Map.of(), Map.of("Prefix", Map.of("Version", "1", "PrefixListId", "pl-example")),
                Map.of("Include", true), Map.of(), mapper, name -> null, null, null, Map.of(),
                (id, attribute, value) -> provisioner.attributeValue("AWS::EC2::PrefixList", attribute, value));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]}",
        "{\"Fn::GetAtt\":\"Prefix.Version\"}",
        "{\"Fn::If\":[\"Include\",{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]},0]}",
        "{\"Fn::Select\":[0,[{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]}]]}"
    })
    void getAttPreservesItsNumericType(String intrinsic) {
        JsonNode result = engineWithNumericAttribute().resolveNode(json(intrinsic));
        assertTrue(result.isIntegralNumber());
        assertEquals(1, result.intValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"Fn::Join\":[\"\",[{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]}]]}",
        "{\"Fn::Join\":[\"\",[{\"Fn::If\":[\"Include\",{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]},\"ok\"]}]]}",
        "{\"Fn::Sub\":\"${Prefix.Version}\"}",
        "{\"Fn::Sub\":[\"${Version}\",{\"Version\":{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]}}]}",
        "{\"Fn::Join\":[\"\",[{\"Fn::Select\":[0,[{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]}]]}]]}",
        "{\"Fn::Select\":[0,{\"Fn::Split\":[\",\",{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]}]}]}",
        "{\"Fn::Base64\":{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]}}"
    })
    void numericAttributesCannotBeUsedAsStringFragments(String intrinsic) {
        AwsException error = assertThrows(AwsException.class,
                () -> engineWithNumericAttribute().resolve(json(intrinsic)));
        assertEquals("ValidationError", error.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"Fn::Join\":[\"\",[{\"Fn::GetAtt\":[\"Prefix\",\"PrefixListId\"]}]]}",
        "{\"Fn::Sub\":\"${Prefix.PrefixListId}\"}",
        "{\"Fn::Sub\":[\"${Id}\",{\"Id\":{\"Fn::GetAtt\":[\"Prefix\",\"PrefixListId\"]}}]}",
        "{\"Fn::Select\":[0,{\"Fn::Split\":[\",\",{\"Fn::GetAtt\":[\"Prefix\",\"PrefixListId\"]}]}]}",
        "{\"Fn::If\":[\"Missing\",{\"Fn::GetAtt\":[\"Prefix\",\"Version\"]},\"pl-example\"]}"
    })
    void stringAttributesAndUnselectedNumericBranchesRemainValid(String intrinsic) {
        assertEquals("pl-example", engineWithNumericAttribute().resolve(json(intrinsic)));
    }

    @Test
    void literalsNumberParametersAndUnselectedNumericAttributesKeepStringCoercion() {
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "stack", "stack/id", Map.of("Number", "1"), Map.of(), Map.of(),
                Map.of(), Map.of(), mapper, name -> null, null, null, Map.of("Number", "Number"));
        assertEquals("1", e.resolveString(json("1"), "must be a string"));
        assertEquals("1", e.resolveString(json("{\"Ref\":\"Number\"}"), "must be a string"));
        assertEquals("1", e.resolve(json("{\"Fn::Join\":[\"\",[1]]}")));
        assertEquals("1", e.resolve(json("{\"Fn::Join\":[\"\",[{\"Ref\":\"Number\"}]]}")));
        assertEquals("1", e.resolve(json("{\"Fn::Select\":[0,{\"Fn::Split\":[\",\",1]}]}")));
        assertEquals("1", e.resolve(json("{\"Fn::Select\":[0,{\"Fn::Split\":[\",\",{\"Ref\":\"Number\"}]}]}")));
        assertEquals("1", e.resolve(json("{\"Fn::Sub\":[\"${Value}\",{\"Value\":1}]}")));
        assertEquals("pl-example", engineWithNumericAttribute().resolve(json("""
                {"Fn::Join":["",[{"Fn::Select":[1,[{"Fn::GetAtt":["Prefix","Version"]},"pl-example"]]}]]}
                """)));
    }

    @Test
    void selectedNoValueOmitsTheOptionalPropertyWhileKeepingLiteralEmptyText() {
        JsonNode source = json("""
                {"Scopes":{"Fn::If":["Include",[],{"Ref":"AWS::NoValue"}]},"Name":""}
                """);
        assertEquals(json("{\"Name\":\"\"}"),
                engineWithCondition("Include", false).resolveNodeOmittingNoValue(source));
    }

    @Test
    void selectedNoValueOmitsTheOptionalArrayEntryWhileKeepingLiteralEmptyText() {
        JsonNode source = json("""
                ["first",{"Fn::If":["Include","read",{"Ref":"AWS::NoValue"}]},"","last"]
                """);
        assertEquals(json("[\"first\",\"\",\"last\"]"),
                engineWithCondition("Include", false).resolveNodeOmittingNoValue(source));
    }

    @Test
    void optionalNodeResolutionPreservesTheLegacyNoValueContract() {
        JsonNode noValue = json("{\"Ref\":\"AWS::NoValue\"}");
        assertEquals("", engine().resolveNode(noValue).asText());
        assertTrue(engine().resolveNodeOmittingNoValue(noValue).isMissingNode());
        assertEquals(json("\"\""), engine().resolveNodeOmittingNoValue(json("\"\"")));
        assertEquals(json("\"AWS::NoValue\""), engine().resolveNodeOmittingNoValue(json("\"AWS::NoValue\"")));
    }

    @Test
    void optionalNodeResolutionDoesNotRewriteOtherIntrinsicArguments() {
        JsonNode joined = json("""
                {"Fn::Join": ["-", ["read", {"Ref": "AWS::NoValue"}, "all"]]}
                """);
        assertEquals("read--all", engine().resolveNodeOmittingNoValue(joined).asText());
        JsonNode malformedConditional = json("{\"Fn::If\":[\"IncludeScopes\"]}");
        assertEquals(engine().resolveNode(malformedConditional),
                engine().resolveNodeOmittingNoValue(malformedConditional));
        assertEquals(json("{\"keep\":\"\"}"), engine().resolveNodeOmittingNoValue(
                json("{\"keep\":{\"Ref\":\"AWS::NoValue\",\"extra\":true}}")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void optionalNodeResolutionOmitsSelectedNestedValuesWithoutMutatingInput(boolean include) {
        CloudFormationTemplateEngine e = engineWithCondition("Include", include);
        JsonNode source = json("""
                {"nested":{"optional":{"Fn::If":["Include",{"list":[0,false,""]},{"Ref":"AWS::NoValue"}]},
                  "empty":"","literal":"AWS::NoValue","nil":null},
                 "items":["first",{"Fn::If":["Include",{"Ref":"AWS::Region"},{"Ref":"AWS::NoValue"}]},"last"]}
                """);
        JsonNode before = source.deepCopy();
        JsonNode expected = json(include ? """
                {"nested":{"optional":{"list":[0,false,""]},"empty":"","literal":"AWS::NoValue","nil":null},
                 "items":["first","us-east-1","last"]}
                """ : """
                {"nested":{"empty":"","literal":"AWS::NoValue","nil":null},"items":["first","last"]}
                """);
        assertEquals(expected, e.resolveNodeOmittingNoValue(source));
        assertEquals(before, source);
    }

    @Test
    void optionalNodeResolutionOnlyResolvesTheSelectedDynamicReferenceOnce() {
        List<String> calls = new ArrayList<>();
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("Include", true), Map.of(), mapper, name -> null, value -> {
                    calls.add(value);
                    return "{{resolve:ssm:/literal-result}}";
                });
        assertEquals("{{resolve:ssm:/literal-result}}", e.resolveNodeOmittingNoValue(json("""
                {"Fn::If":["Include","{{resolve:ssm:/selected}}","{{resolve:ssm:/unselected}}"]}
                """)).asText());
        assertEquals(List.of("{{resolve:ssm:/selected}}"), calls);
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
    void getAttDoesNotExposeFlociInternalAttributes() {
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(),
                Map.of("Res", Map.of("Arn", "arn:res", "__FlociSecret", "secret-value")),
                Map.of(), Map.of(), mapper, (Function<String, String>) name -> null);

        assertEquals("arn:res", e.resolve(json("{\"Fn::GetAtt\": [\"Res\", \"Arn\"]}")));
        assertEquals("Res.__FlociSecret",
                e.resolve(json("{\"Fn::GetAtt\": [\"Res\", \"__FlociSecret\"]}")));
        assertNotEquals("secret-value", e.resolve(json("{\"Fn::Sub\": \"${Res.__FlociSecret}\"}")));
    }

    @Test
    void resolveJsonAttributeReturnsNullForMissingOrNullNode() {
        assertNull(engine().resolveJsonAttribute(json("null")));
        assertNull(engine().resolveJsonAttribute(mapper.createArrayNode().path("nope")));
    }

    /**
     * Measured on CloudFormation (eu-west-1, 2026-10-18), for a Lambda function's Architectures and
     * Layers: the first forms are refused as a string where a list is expected, the others are
     * lists. Fn::GetAtt and Fn::ImportValue are only known when the resource is provisioned.
     */
    @Test
    void yieldsStringTellsTheFormsCloudFormationRefusesWhereAListIsExpected() {
        Map<String, String> types = Map.of("Str", "String", "Num", "Number", "Csv", "CommaDelimitedList",
                "Subnets", "List<AWS::EC2::Subnet::Id>", "SsmList", "AWS::SSM::Parameter::Value<List<String>>",
                "SsmCsv", "AWS::SSM::Parameter::Value<CommaDelimitedList>");
        Map<String, String> values = Map.of("Str", "x86_64", "Num", "1", "Csv", "x86_64", "Subnets", "subnet-a",
                "SsmList", "/p", "SsmCsv", "/p");
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000", "us-east-1",
                "my-stack", "stack/id", values, Map.of(), Map.of(), Map.of("Yes", true), Map.of(), mapper,
                (Function<String, String>) name -> null, null, null, types);

        for (String string : List.of("\"x86_64\"", "5", "true", "{\"Ref\": \"Str\"}", "{\"Ref\": \"Num\"}",
                "{\"Ref\": \"AWS::Region\"}", "{\"Fn::Select\": [0, {\"Ref\": \"Csv\"}]}",
                "{\"Fn::Join\": [\",\", {\"Ref\": \"Csv\"}]}", "{\"Fn::Sub\": \"x86_64\"}", "{\"Fn::Base64\": \"x86_64\"}",
                "{\"Fn::If\": [\"Yes\", \"x86_64\", {\"Ref\": \"AWS::NoValue\"}]}")) {
            assertTrue(e.yieldsString(json(string)), string);
        }
        for (String list : List.of("[\"x86_64\"]", "{\"Ref\": \"Csv\"}", "{\"Ref\": \"Subnets\"}",
                "{\"Ref\": \"SsmList\"}", "{\"Ref\": \"SsmCsv\"}", "{\"Ref\": \"AWS::NotificationARNs\"}",
                "{\"Fn::Split\": [\",\", {\"Ref\": \"Str\"}]}", "{\"Fn::If\": [\"Yes\", {\"Ref\": \"Csv\"}, [\"arm64\"]]}",
                "{\"Fn::GetAtt\": [\"Queue\", \"Arn\"]}", "{\"Fn::ImportValue\": \"Archs\"}")) {
            assertFalse(e.yieldsString(json(list)), list);
        }
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
    // begin with. resolveStringList already handled Fn::Split and comma-delimited scalars
    // correctly (and drops blank entries, unlike the private resolveList it delegates to); it
    // only needed Fn::If added, which is what these exercise.

    @Test
    void resolveStringListSplitsFnSplitWithItsActualDelimiter() {
        assertEquals(List.of("a", "b", "c"),
                engine().resolveStringList(json("{\"Fn::Split\": [\"|\", \"a|b|c\"]}")));
    }

    @Test
    void resolveStringListSplitsACommaDelimitedListParameterRef() {
        assertEquals(List.of("a", "b", "c"),
                engineWithParameter("Csv", "a,b,c").resolveStringList(json("{\"Ref\": \"Csv\"}")));
    }

    @Test
    void resolveStringListResolvesFnIfChoosingBetweenTwoFnSplitLists() {
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
                engineWithCondition("UseReportBatch", true).resolveStringList(node));
        assertEquals(List.of("x", "y"),
                engineWithCondition("UseReportBatch", false).resolveStringList(node));
    }

    @Test
    void resolveStringListResolvesFnIfChoosingBetweenACommaDelimitedRefAndALiteralArray() {
        JsonNode node = json("{\"Fn::If\": [\"UseParam\", {\"Ref\": \"Csv\"}, [\"fallback\"]]}");

        CloudFormationTemplateEngine trueEngine = new CloudFormationTemplateEngine(
                "000000000000", "us-east-1", "my-stack", "stack/id",
                Map.of("Csv", "x,y"), Map.of(), Map.of(), Map.of("UseParam", true), Map.of(), mapper,
                (Function<String, String>) n -> null);
        assertEquals(List.of("x", "y"), trueEngine.resolveStringList(node));
    }

    @Test
    void resolveStringListDropsAConditionalElementThatResolvesToNoValue() {
        // github.com/floci-io/floci/pull/2996 (Greptile review): a list element that resolves to
        // AWS::NoValue must be omitted, not forwarded as an invalid empty-string list entry. This
        // is exactly why FunctionResponseTypes must go through resolveStringList (which drops
        // blanks) rather than the private resolveList it delegates to (which keeps them).
        JsonNode node = json("""
                ["ReportBatchItemFailures", {"Fn::If": ["UseExtra", "ExtraType", {"Ref": "AWS::NoValue"}]}]
                """);

        assertEquals(List.of("ReportBatchItemFailures"),
                engineWithCondition("UseExtra", false).resolveStringList(node));
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

    /**
     * Reproduces #2213: a Lambda {@code Environment.Variables} entry (or any other plain string
     * template value) carrying {@code {{resolve:...}}} syntax reached the deployed resource as the
     * literal text because {@code resolveNode}, the general-purpose path every non-RDS property goes
     * through, had no dynamic-reference stage.
     */
    @Test
    void resolveNodeResolvesDynamicReferenceInPlainStringValue() {
        UnaryOperator<String> resolver = value -> {
            assertEquals("{{resolve:ssm:/demo/url}}", value);
            return "https://real.example.com";
        };
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), mapper, (Function<String, String>) name -> null, resolver);

        assertEquals("https://real.example.com",
                e.resolveNode(json("\"{{resolve:ssm:/demo/url}}\"")).asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"Fn::GetAtt\":[\"Custom\",\"Url\"]}",
        "{\"Fn::GetAtt\":\"Custom.Url\"}",
        "{\"Fn::If\":[\"Include\",{\"Fn::GetAtt\":[\"Custom\",\"Url\"]},\"unused\"]}",
        "{\"Fn::Select\":[0,[{\"Fn::GetAtt\":[\"Custom\",\"Url\"]}]]}"
    })
    void getAttResolvesTextualDynamicReferencesOnce(String intrinsic) {
        List<String> calls = new ArrayList<>();
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(),
                Map.of("Custom", Map.of("Url", "{{resolve:ssm:/demo/url}}")),
                Map.of("Include", true), Map.of(), mapper, name -> null, value -> {
                    calls.add(value);
                    return "{{resolve:ssm:/literal-result}}";
                });

        assertEquals("{{resolve:ssm:/literal-result}}", e.resolveNode(json(intrinsic)).textValue());
        assertEquals(List.of("{{resolve:ssm:/demo/url}}"), calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"Fn::GetAtt\":[\"Custom\",\"Url\"]}", "{\"Fn::GetAtt\":\"Custom.Url\"}"})
    void getAttWithoutDynamicReferencesLeavesTextUnchanged(String intrinsic) {
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(),
                Map.of("Custom", Map.of("Url", "{{resolve:ssm:/demo/url}}")), Map.of(), Map.of(), mapper,
                name -> null, value -> fail("dynamic reference must not be resolved here: " + value));

        assertEquals("{{resolve:ssm:/demo/url}}", e.resolveWithoutDynamicReferences(json(intrinsic)));
    }

    /**
     * CDK emits an RDS master credential as an {@code Fn::Join} whose fragments split one dynamic
     * reference: {@code ["{{resolve:secretsmanager:", {"Ref": "Secret"}, ":SecretString:password::}}"]}.
     * Resolving each fragment on its own rejects the opening fragment as an unclosed reference, so
     * the reference must be resolved once, on the concatenated string.
     */
    @Test
    void resolveNodeResolvesDynamicReferenceSplicedAcrossJoinFragments() {
        List<String> resolverInputs = new ArrayList<>();
        UnaryOperator<String> resolver = value -> {
            resolverInputs.add(value);
            return "resolved-password";
        };
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(),
                Map.of("Secret", "arn:aws:secretsmanager:us-east-1:000000000000:secret:creds-AbC123"),
                Map.of(), Map.of(), Map.of(), mapper, (Function<String, String>) name -> null, resolver);

        String resolved = e.resolveNode(json("{\"Fn::Join\":[\"\",[\"{{resolve:secretsmanager:\","
                + "{\"Ref\":\"Secret\"},\":SecretString:password::}}\"]]}")).asText();

        assertEquals("resolved-password", resolved);
        assertEquals(List.of("{{resolve:secretsmanager:arn:aws:secretsmanager:us-east-1:000000000000:"
                + "secret:creds-AbC123:SecretString:password::}}"), resolverInputs);
    }

    @Test
    void nestedOptOutPreservesReferencesAndRestoresResolution() {
        List<String> inputs = new ArrayList<>();
        CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "stack", "stack/id", Map.of(), Map.of(), Map.of(),
                Map.of("Chosen", true), Map.of(), mapper, name -> null, value -> {
                    inputs.add(value);
                    return "resolved";
                });
        String reference = "{{resolve:ssm-secure:/password}}";
        for (String expression : List.of(
                "\"%s\"",
                "{\"Fn::If\":[\"Chosen\",\"%s\",\"unused\"]}",
                "{\"Fn::Select\":[0,[\"%s\",\"unused\"]]}",
                "{\"Fn::Sub\":[\"${Value}\",{\"Value\":\"%s\"}]}",
                "{\"Fn::Join\":[\"\",[{\"Fn::If\":[\"Chosen\",\"%s\",\"unused\"]}]]}")) {
            assertEquals(reference, engine.resolveWithoutDynamicReferences(json(expression.formatted(reference))), expression);
            assertTrue(inputs.isEmpty(), expression);
        }
        assertEquals("resolved", engine.resolve(json("\"" + reference + "\"")));
        assertEquals(List.of(reference), inputs);
    }

    /**
     * The RDS credential path opts out of the general dynamic-reference stage and resolves the
     * reference itself, so the joined string must survive intact with the reference untouched.
     */
    @Test
    void resolveWithoutDynamicReferencesKeepsJoinSplicedReferenceIntact() {
        UnaryOperator<String> resolver = value -> fail("dynamic reference must not be resolved here: " + value);
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(),
                Map.of("Secret", "arn:aws:secretsmanager:us-east-1:000000000000:secret:creds-AbC123"),
                Map.of(), Map.of(), Map.of(), mapper, (Function<String, String>) name -> null, resolver);

        assertEquals("{{resolve:secretsmanager:arn:aws:secretsmanager:us-east-1:000000000000:"
                        + "secret:creds-AbC123:SecretString:password::}}",
                e.resolveWithoutDynamicReferences(json("{\"Fn::Join\":[\"\",[\"{{resolve:secretsmanager:\","
                        + "{\"Ref\":\"Secret\"},\":SecretString:password::}}\"]]}")));
    }

    /**
     * The same dynamic-reference stage applies to the text an intrinsic function produces, since a
     * literal {@code {{resolve:...}}} embedded in an {@code Fn::Sub} template survives substitution
     * untouched and reaches resolveNode's final string.
     */
    @Test
    void resolveNodeResolvesDynamicReferenceProducedByIntrinsic() {
        UnaryOperator<String> resolver = value -> {
            assertEquals("prefix-{{resolve:ssm:/demo/url}}", value);
            return "prefix-https://real.example.com";
        };
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), mapper, (Function<String, String>) name -> null, resolver);

        assertEquals("prefix-https://real.example.com", e.resolveNode(
                json("{\"Fn::Sub\": \"prefix-{{resolve:ssm:/demo/url}}\"}")).asText());
    }

    /**
     * A plain literal with no dynamic reference syntax must never reach the resolver: it is left
     * exactly as written, and a resolver that throws proves it was not invoked.
     */
    @Test
    void resolveNodeLeavesPlainLiteralStringUntouched() {
        UnaryOperator<String> resolver = value -> fail("dynamic reference resolver must not be "
                + "invoked for a value with no {{resolve:...}} syntax: " + value);
        CloudFormationTemplateEngine e = new CloudFormationTemplateEngine("000000000000",
                "us-east-1", "my-stack", "stack/id", Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), mapper, (Function<String, String>) name -> null, resolver);

        assertEquals("just-a-normal-value", e.resolveNode(json("\"just-a-normal-value\"")).asText());
    }

    /**
     * With no dynamic-reference resolver configured (the 11-argument constructor every other test
     * in this class uses), resolveNode leaves {@code {{resolve:...}}} syntax exactly as written
     * instead of failing: callers that never need dynamic references stay decoupled from them.
     */
    @Test
    void resolveNodeWithNoResolverLeavesDynamicReferenceSyntaxVerbatim() {
        assertEquals("{{resolve:ssm:/demo/url}}",
                engine().resolveNode(json("\"{{resolve:ssm:/demo/url}}\"")).asText());
    }
}
