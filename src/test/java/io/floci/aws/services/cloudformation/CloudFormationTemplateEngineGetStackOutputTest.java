package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFormationTemplateEngineGetStackOutputTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<List<String>> lookups = new ArrayList<>();

    private CloudFormationTemplateEngine engine() {
        return engine(Map.of("Env", "prod"), Map.of("Enabled", true));
    }

    private CloudFormationTemplateEngine engine(Map<String, String> parameters, Map<String, Boolean> conditions) {
        return new CloudFormationTemplateEngine("111111111111", "us-east-1", "consumer", "stack/id",
                parameters, Map.of("Bucket", "bucket-1"), Map.of(), conditions, Map.of(), mapper,
                name -> "exported-" + name, null, this::lookup);
    }

    private String lookup(String stackName, String outputName, String region, String roleArn) {
        lookups.add(Arrays.asList(stackName, outputName, region, roleArn));
        if ("Missing".equals(stackName)) {
            throw new AwsException("ValidationError", "Stack with id Missing does not exist", 400);
        }
        return stackName + "/" + outputName + "@" + region;
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AwsException failure(String template) {
        return assertThrows(AwsException.class, () -> engine().resolve(json(template)));
    }

    @Test
    void sameAccountSameRegion_readsTheOutputWithTheStacksRegion() {
        assertEquals("Producer/VpcId@us-east-1", engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId"}}
                """)));
        assertEquals(List.of(Arrays.asList("Producer", "VpcId", "us-east-1", null)), lookups);
    }

    /**
     * CloudFormation hands the parameters to the lookup as resolved, whitespace included: a stack
     * name with spaces then fails DescribeStacks' name constraint, it is not read as another name.
     */
    @Test
    void parameterValues_reachTheLookupUntrimmed() {
        engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": " Producer ", "OutputName": " VpcId"}}
                """));
        assertEquals(List.of(Arrays.asList(" Producer ", " VpcId", "us-east-1", null)), lookups);
    }

    @Test
    void resolveNode_returnsTheOutputAsText() {
        assertEquals(TextNode.valueOf("Producer/VpcId@us-east-1"), engine().resolveNode(json("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId"}}
                """)));
    }

    @Test
    void crossRegion_readsTheNamedRegion() {
        assertEquals("Producer/VpcId@us-west-2", engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId", "Region": "us-west-2"}}
                """)));
    }

    @Test
    void crossAccount_passesTheRole() {
        engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId",
                  "RoleArn": "arn:aws:iam::222222222222:role/GetStackOutputRole", "Region": "us-west-2"}}
                """));
        assertEquals(List.of(List.of("Producer", "VpcId", "us-west-2",
                "arn:aws:iam::222222222222:role/GetStackOutputRole")), lookups);
    }

    @Test
    void supportedPositions_joinIfAndSelect() {
        assertEquals("Producer/A@us-east-1-suffix", engine().resolve(json("""
                {"Fn::Join": ["-", [{"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "A"}}, "suffix"]]}
                """)));
        assertEquals("Producer/B@us-east-1", engine().resolve(json("""
                {"Fn::If": ["Enabled", {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "B"}}, "off"]}
                """)));
        assertEquals("Producer/C@us-east-1", engine().resolve(json("""
                {"Fn::Select": [1, ["x", {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "C"}}]]}
                """)));
    }

    @Test
    void parameterValues_acceptTheDocumentedFunctions() {
        assertEquals("prod-net/Vpc@us-east-1", engine().resolve(json("""
                {"Fn::GetStackOutput": {
                  "StackName": {"Fn::Join": ["-", [{"Ref": "Env"}, "net"]]},
                  "OutputName": {"Fn::Sub": "Vpc"},
                  "Region": {"Fn::If": ["Enabled", {"Ref": "AWS::Region"}, "us-west-2"]}}}
                """)));
        assertEquals("prod-us-east-1/Vpc@us-east-1", engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": {"Fn::Sub": "${Env}-${AWS::Region}"}, "OutputName": "Vpc"}}
                """)));
    }

    @Test
    void missingStack_failsInsteadOfResolvingToEmpty() {
        AwsException e = failure("""
                {"Fn::GetStackOutput": {"StackName": "Missing", "OutputName": "VpcId"}}
                """);
        assertEquals("ValidationError", e.getErrorCode());
        assertThat(e.getMessage(), containsString("does not exist"));
    }

    @Test
    void requiredParameters_areValidated() {
        assertEquals("GetStackOutput: StackName is required", failure("""
                {"Fn::GetStackOutput": {"OutputName": "VpcId"}}
                """).getMessage());
        assertEquals("GetStackOutput: OutputName is required", failure("""
                {"Fn::GetStackOutput": {"StackName": "Producer"}}
                """).getMessage());
        assertEquals("TemplateError: Fn::GetStackOutput requires a map of named parameters", failure("""
                {"Fn::GetStackOutput": ["Producer", "VpcId"]}
                """).getMessage());
        assertTrue(lookups.isEmpty());
    }

    @Test
    void aKeyTheFunctionDoesNotDefine_isIgnored() {
        assertEquals("Producer/VpcId@us-east-1", engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId", "Account": "2"}}
                """)));
    }

    /** AWS resolves any function in a parameter, a resource's Ref and an import included. */
    @Test
    void parameterValues_resolveWhateverTheyName() {
        assertEquals("bucket-1/VpcId@us-east-1", engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": {"Ref": "Bucket"}, "OutputName": "VpcId"}}
                """)));
        assertEquals("bucket-1/VpcId@us-east-1", engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": {"Fn::Sub": "${Bucket}"}, "OutputName": "VpcId"}}
                """)));
        assertEquals("exported-Shared/VpcId@us-east-1", engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": {"Fn::ImportValue": "Shared"}, "OutputName": "VpcId"}}
                """)));
        assertEquals("Other/Name@us-east-1/VpcId@us-east-1", engine().resolve(json("""
                {"Fn::GetStackOutput": {"StackName": {"Fn::GetStackOutput": {"StackName": "Other", "OutputName": "Name"}},
                  "OutputName": "VpcId"}}
                """)));
    }

    @Test
    void invalidRegion_isATemplateError() {
        AwsException e = failure("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId", "Region": "us-nowhere-9"}}
                """);
        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("TemplateError: Region us-nowhere-9 in Fn::GetStackOutput is not a valid AWS region.",
                e.getMessage());
        assertTrue(lookups.isEmpty());
    }

    /** A Region of another partition fails the way AWS's cross-partition call does. */
    @Test
    void anotherPartitionsRegion_failsWithAnInvalidToken() {
        AwsException e = failure("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId", "Region": "cn-north-1"}}
                """);
        assertEquals("InvalidClientTokenId", e.getErrorCode());
        assertThat(e.getMessage(), containsString("The security token included in the request is invalid (Service:"
                + " AmazonCloudFormation; Status Code: 403; Error Code: InvalidClientTokenId; Request ID: "));
        assertTrue(lookups.isEmpty());
    }

    @Test
    void aRoleArnThatIsNoArn_isATemplateError() {
        AwsException e = failure("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId", "RoleArn": "not-an-arn"}}
                """);
        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("TemplateError: Invalid RoleArn parameter not-an-arn in Fn::GetStackOutput", e.getMessage());
    }

    /** Any ARN goes to the role assumption, which is what refuses a user, an S3 or an aws-cn ARN. */
    @ParameterizedTest
    @ValueSource(strings = {"arn:aws:iam::222222222222:user/Reader", "arn:aws:s3:::bucket",
        "arn:aws-cn:iam::222222222222:role/Reader"})
    void anyArn_reachesTheRoleAssumption(String roleArn) {
        engine().resolve(json("{\"Fn::GetStackOutput\": {\"StackName\": \"Producer\","
                + " \"OutputName\": \"VpcId\", \"RoleArn\": \"" + roleArn + "\"}}"));
        assertEquals(List.of(Arrays.asList("Producer", "VpcId", "us-east-1", roleArn)), lookups);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"Fn::Base64\": {\"Fn::GetStackOutput\": {\"StackName\": \"P\", \"OutputName\": \"O\"}}}",
        "{\"Fn::Sub\": [\"${V}\", {\"V\": {\"Fn::GetStackOutput\": {\"StackName\": \"P\", \"OutputName\": \"O\"}}}]}"
    })
    void positionsTheDocumentationListsAsUnsupported_resolve(String template) {
        engine().resolve(json(template));
        assertEquals(List.of(Arrays.asList("P", "O", "us-east-1", null)), lookups);
    }

    /**
     * Measured (eu-west-1, 2026-10-17): a dynamic reference in an argument is handed on as written,
     * one inside an Fn::Sub variable map included, while the same Fn::Sub elsewhere resolves it.
     */
    @Test
    void dynamicReferencesInTheArguments_areLeftAsWritten() {
        CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine("111111111111", "us-east-1",
                "consumer", "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                name -> null, value -> value.replace("{{resolve:ssm:/name}}", "Producer"), this::lookup);
        String sub = "{\"Fn::Sub\": [\"${N}\", {\"N\": \"{{resolve:ssm:/name}}\"}]}";

        engine.resolve(json("{\"Fn::GetStackOutput\": {\"StackName\": " + sub + ", \"OutputName\": \"VpcId\"}}"));

        assertEquals(List.of(Arrays.asList("{{resolve:ssm:/name}}", "VpcId", "us-east-1", null)), lookups);
        assertEquals("Producer", engine.resolve(json(sub)));
    }

    @Test
    void withoutAResolver_theReferenceFails() {
        CloudFormationTemplateEngine standalone = CloudFormationTemplateEngine.standalone("111111111111",
                "us-east-1", "cloudcontrol", mapper, null);
        assertThrows(AwsException.class, () -> standalone.resolve(json("""
                {"Fn::GetStackOutput": {"StackName": "Producer", "OutputName": "VpcId"}}
                """)));
    }

    @Test
    void containsGetStackOutput_findsNestedReferences() {
        assertTrue(CloudFormationTemplateEngine.containsGetStackOutput(json("""
                [{"Fn::Join": ["", [{"Fn::GetStackOutput": {"StackName": "P", "OutputName": "O"}}]]}, "x"]
                """)));
        assertFalse(CloudFormationTemplateEngine.containsGetStackOutput(json("""
                [{"Ref": "Env"}, "x"]
                """)));
    }
}
