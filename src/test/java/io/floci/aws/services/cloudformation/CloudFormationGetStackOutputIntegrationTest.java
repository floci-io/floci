package io.floci.aws.services.cloudformation;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.ssm.SsmService;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Fn::GetStackOutput} reads another stack's output, without an export, in the same
 * account and Region, another Region, or another account through a role. A stack or an output
 * that does not exist fails the deploy; it never resolves to an empty string.
 */
@QuarkusTest
class CloudFormationGetStackOutputIntegrationTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "000000000042";
    private static final String US_EAST_1 = "us-east-1";
    private static final String EU_WEST_1 = "eu-west-1";
    private static final String DESCRIBE_STACKS = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"cloudformation:DescribeStacks","Resource":"*"}]}""";

    @Inject
    S3Service s3Service;

    @Inject
    SsmService ssmService;

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final String suffix = Long.toString(System.nanoTime(), 36);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sameAccountSameRegion_resourcePropertyGetsTheOutput() {
        String producer = name("producer");
        String consumer = name("consumer");
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, producer, "vpc-0123456789");

        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, consumer(parameter("same"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + producer + "\", \"OutputName\": \"VpcId\"}}"));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(consumer).status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("same")).body("Parameter.Value", equalTo("vpc-0123456789"));
    }

    @Test
    void yamlShortFormInsideJoin_resolves() {
        String producer = name("yaml-producer");
        String consumer = name("yaml-consumer");
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, producer, "subnet-42");
        String template = """
                Resources:
                  Copy:
                    Type: AWS::SSM::Parameter
                    Properties:
                      Name: %s
                      Type: String
                      Value: !Join
                        - "-"
                        - - !GetStackOutput
                            StackName: %s
                            OutputName: VpcId
                          - copy
                """.formatted(parameter("yaml"), producer);

        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, template);

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(consumer).status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("yaml")).body("Parameter.Value", equalTo("subnet-42-copy"));
    }

    @Test
    void missingOutput_failsTheDeploy() {
        String producer = name("missing-output-producer");
        String consumer = name("missing-output");
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, producer, "vpc-1");

        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, consumer(parameter("missing-output"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + producer + "\", \"OutputName\": \"Nope\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(consumer);
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(CfnStackWaits.failedResourceReason(consumer), containsString("TemplateError: Fn::GetStackOutput references output Nope from stack "
                + producer + ", but this output was not found. The output may have been deleted."));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("missing-output")).statusCode(400);
    }

    @Test
    void missingStack_failsTheDeploy() {
        String missing = name("never-created");
        String consumer = name("missing-stack");
        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, consumer(parameter("missing-stack"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + missing + "\", \"OutputName\": \"VpcId\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(consumer);
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(CfnStackWaits.failedResourceReason(consumer), containsString("Stack with id " + missing + " does not exist (Service:"
                + " AmazonCloudFormation; Status Code: 400; Error Code: ValidationError; Request ID: "));
        assertThat(CfnStackWaits.failedResourceReason(consumer), endsWith("; Proxy: null)"));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("missing-stack")).statusCode(400);
    }

    @Test
    void crossRegion_readsTheNamedRegionOnly() {
        String producer = name("region-producer");
        String consumer = name("region-consumer");
        String regionDefault = name("region-default");
        createProducer(DEFAULT_ACCOUNT, EU_WEST_1, producer, "vpc-eu");

        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, consumer(parameter("region"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + producer + "\", \"OutputName\": \"VpcId\","
                        + " \"Region\": \"eu-west-1\"}}"));
        createStack(DEFAULT_ACCOUNT, US_EAST_1, regionDefault, consumer(parameter("region-default"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + producer + "\", \"OutputName\": \"VpcId\"}}"));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(consumer).status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("region")).body("Parameter.Value", equalTo("vpc-eu"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(regionDefault).status());
    }

    @Test
    void crossAccount_readsThroughTheRoleAndFailsWithoutIt() {
        String reader = name("reader");
        String producer = name("account-producer");
        String consumer = name("account-consumer");
        String noRole = name("account-no-role");
        String missingRole = name("account-missing-role");
        createRole(OTHER_ACCOUNT, reader);
        createProducer(OTHER_ACCOUNT, US_EAST_1, producer, "vpc-other-account");
        String roleArn = "arn:aws:iam::" + OTHER_ACCOUNT + ":role/" + reader;
        String nobodyArn = "arn:aws:iam::" + OTHER_ACCOUNT + ":role/" + name("nobody");

        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, consumer(parameter("account"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + producer + "\", \"OutputName\": \"VpcId\","
                        + " \"RoleArn\": \"" + roleArn + "\"}}"));
        createStack(DEFAULT_ACCOUNT, US_EAST_1, noRole, consumer(parameter("account-no-role"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + producer + "\", \"OutputName\": \"VpcId\"}}"));
        createStack(DEFAULT_ACCOUNT, US_EAST_1, missingRole, consumer(parameter("account-missing-role"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + producer + "\", \"OutputName\": \"VpcId\","
                        + " \"RoleArn\": \"" + nobodyArn + "\"}}"));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(consumer).status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("account")).body("Parameter.Value", equalTo("vpc-other-account"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(noRole).status());
        assertThat(CfnStackWaits.failedResourceReason(noRole),
                containsString("Stack with id " + producer + " does not exist"));
        CfnStackWaits.StackState missingRoleState = CfnStackWaits.awaitTerminal(missingRole);
        assertEquals("ROLLBACK_COMPLETE", missingRoleState.status());
        assertThat(CfnStackWaits.failedResourceReason(missingRole), containsString("is not authorized to perform: sts:AssumeRole on resource: "
                + nobodyArn + " (Service: AWSSecurityTokenService; Status Code: 403;"
                + " Error Code: AccessDenied; Request ID: "));
    }

    /**
     * With IAM enforcement off, as AssumeRole, Floci requires the role to exist and evaluates no
     * trust policy: the CDK's shape resolves, as one trusting only a service does.
     * {@code CloudFormationGetStackOutputEnforcementIntegrationTest} covers enforcement on.
     */
    @Test
    void crossAccount_theRolesTrustPolicyIsNotEvaluated() {
        String cdkShape = name("cdk-shape");
        String lambdaOnly = name("lambda-only");
        String producer = name("trust-producer");
        createRole(OTHER_ACCOUNT, cdkShape, "{\"AWS\":\"arn:aws:iam::" + DEFAULT_ACCOUNT
                + ":role/cdk-hnb659fds-cfn-exec-role-" + DEFAULT_ACCOUNT + "-us-east-1\"}");
        createRole(OTHER_ACCOUNT, lambdaOnly, "{\"Service\":\"lambda.amazonaws.com\"}");
        createProducer(OTHER_ACCOUNT, US_EAST_1, producer, "vpc-trust");

        for (String role : List.of(cdkShape, lambdaOnly)) {
            createStack(DEFAULT_ACCOUNT, US_EAST_1, role + "-consumer", consumer(parameter(role),
                    "{\"Fn::GetStackOutput\": {\"StackName\": \"" + producer + "\", \"OutputName\": \"VpcId\","
                            + " \"RoleArn\": \"arn:aws:iam::" + OTHER_ACCOUNT + ":role/" + role + "\"}}"));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(role + "-consumer").status());
            parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter(role)).body("Parameter.Value", equalTo("vpc-trust"));
        }
    }

    /**
     * Measured (eu-west-1, 2026-10-04): an output that cannot be read fails the create after the
     * resources exist, and the stack rolls back, deleting them.
     */
    @Test
    void anOutputThatCannotBeReadRollsTheStackBack() {
        String producer = name("output-producer");
        String consumer = name("output-consumer");
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, producer, "vpc-output");
        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, """
                {"Resources": {"Copy": {"Type": "AWS::SSM::Parameter",
                  "Properties": {"Name": "%s", "Type": "String", "Value": "created"}}},
                 "Outputs": {"Missing": {"Value": {"Fn::GetStackOutput": {
                   "StackName": "%s", "OutputName": "Missing"}}}}}
                """.formatted(parameter("output-rollback"), producer));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(consumer);
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(state.reason(), containsString("TemplateError: Fn::GetStackOutput references output Missing"));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("output-rollback"))
                .statusCode(400)
                .body(containsString("ParameterNotFound"));
    }

    /**
     * Measured (eu-west-1, 2026-10-04): CloudFormation does not resolve a dynamic reference inside
     * Fn::GetStackOutput; the stack name reaches DescribeStacks as written, which refuses it.
     */
    @Test
    void aDynamicReferenceInTheStackNameIsNotResolved() {
        String consumer = name("dynamic-consumer");
        String reference = "{{resolve:ssm:" + parameter("producer-name") + "}}";
        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, consumer(parameter("dynamic"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + reference + "\", \"OutputName\": \"VpcId\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(consumer);
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(CfnStackWaits.failedResourceReason(consumer), containsString("1 validation error detected: Value '" + reference + "'"
                + " at 'stackName' failed to satisfy constraint: Member must satisfy regular expression pattern:"
                + " [a-zA-Z][-a-zA-Z0-9]*|arn:[-a-zA-Z0-9:/._+]* (Service: AmazonCloudFormation; Status Code: 400;"
                + " Error Code: ValidationError; Request ID: "));
    }

    /**
     * Measured (eu-west-1, 2026-10-17): nor is one inside an Fn::Sub variable map, although the
     * parameter it names holds the producer's name.
     */
    @Test
    void aDynamicReferenceInAnFnSubVariableMapIsNotResolved() {
        String producer = name("sub-producer");
        String consumer = name("sub-dynamic");
        String producerName = parameter("sub-producer-name");
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, producer, "vpc-sub");
        ssmService.putParameter(producerName, producer, "String", null, true, US_EAST_1);
        String reference = "{{resolve:ssm:" + producerName + "}}";
        try {
            createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, consumer(parameter("sub-dynamic"),
                    "{\"Fn::GetStackOutput\": {\"StackName\": {\"Fn::Sub\": [\"${N}\", {\"N\": \"" + reference
                            + "\"}]}, \"OutputName\": \"VpcId\"}}"));

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(consumer);
            assertEquals("ROLLBACK_COMPLETE", state.status());
            assertThat(CfnStackWaits.failedResourceReason(consumer), containsString("1 validation error detected: Value '" + reference
                    + "' at 'stackName' failed to satisfy constraint"));
        } finally {
            ssmService.deleteParameter(producerName, US_EAST_1);
        }
    }

    @Test
    void crossPartitionRegion_failsTheDeploy() {
        String consumer = name("partition");
        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, consumer(parameter("partition"),
                "{\"Fn::GetStackOutput\": {\"StackName\": \"" + name("producer") + "\", \"OutputName\": \"VpcId\","
                        + " \"Region\": \"cn-north-1\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(consumer);
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(CfnStackWaits.failedResourceReason(consumer), containsString("The security token included in the request is invalid (Service:"
                + " AmazonCloudFormation; Status Code: 403; Error Code: InvalidClientTokenId; Request ID: "));
    }

    @Test
    void directOutputsValue_resolves() {
        String producer = name("outputs-producer");
        String consumer = name("outputs-consumer");
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, producer, "vpc-out");
        String template = """
                {"Outputs": {"Copied": {"Value":
                  {"Fn::GetStackOutput": {"StackName": "%s", "OutputName": "VpcId"}}}}}
                """.formatted(producer);

        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, template);

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(consumer).status());
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", consumer)
                .when().post("/").then()
                .statusCode(200)
                .body(containsString("<OutputValue>vpc-out</OutputValue>"));
    }

    @Test
    void anywhereInConditions_isRefusedOnCreate() {
        String template = """
                {"Conditions": {"IsProd": {"Fn::Not": [{"Fn::Equals": [
                  {"Fn::GetStackOutput": {"StackName": "%s", "OutputName": "VpcId"}}, "prod"]}]}},
                 "Resources": {"Topic": {"Type": "AWS::SNS::Topic", "Condition": "IsProd"}}}
                """.formatted(name("producer"));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", name("conditions"))
                .formParam("TemplateBody", template)
                .when().post("/").then()
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("Template error: Cannot use Fn::GetStackOutput in Conditions."));
    }

    /**
     * Measured (eu-west-1, 2026-10-16): arguments that are not a map, or that lack StackName or
     * OutputName, are refused when the stack operation is requested.
     */
    @Test
    void malformedArguments_areRefusedOnCreate() {
        String producer = name("producer");
        String[][] cases = {
                {"{\"StackName\": \"" + producer + "\"}", "GetStackOutput: OutputName is required"},
                {"{\"OutputName\": \"VpcId\"}", "GetStackOutput: StackName is required"},
                {"[\"" + producer + "\", \"VpcId\"]", "TemplateError: Fn::GetStackOutput requires a map of named parameters"},
        };
        for (String[] c : cases) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateStack")
                    .formParam("StackName", name("malformed"))
                    .formParam("TemplateBody", consumer(parameter("malformed"), "{\"Fn::GetStackOutput\": " + c[0] + "}"))
                    .when().post("/").then()
                    .statusCode(400)
                    .body(containsString("<Code>ValidationError</Code>"))
                    .body(containsString(c[1]));
        }
    }

    /**
     * Measured (eu-west-1, 2026-10-16): an output whose Condition is false is never read, so one
     * naming a stack that does not exist leaves the deploy complete, and it is not listed.
     */
    @Test
    void anOutputWhoseConditionIsFalse_isNotRead() {
        String producer = name("gated-producer");
        String consumer = name("gated");
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, producer, "vpc-gated");
        String template = """
                {"Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                 "Resources": {"Topic": {"Type": "AWS::SNS::Topic"}},
                 "Outputs": {
                   "Read": {"Value": {"Fn::GetStackOutput": {"StackName": "%s", "OutputName": "VpcId"}}},
                   "Gated": {"Condition": "Never",
                             "Value": {"Fn::GetStackOutput": {"StackName": "%s", "OutputName": "VpcId"}}}}}
                """.formatted(producer, name("missing"));
        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, template);

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(consumer).status());
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", consumer)
                .when().post("/").then()
                .statusCode(200)
                .body(containsString("<OutputValue>vpc-gated</OutputValue>"))
                .body(not(containsString("<OutputKey>Gated</OutputKey>")));
    }

    /** The placement rules apply to the template as processed, AWS::Include fragments merged. */
    @Test
    void inConditionsThroughAnInclude_isRefusedOnCreate() {
        String bucket = name("include");
        s3Service.createBucket(bucket, US_EAST_1);
        s3Service.putObject(bucket, "conditions.json", """
                {"IsProd": {"Fn::Equals": [
                  {"Fn::GetStackOutput": {"StackName": "%s", "OutputName": "VpcId"}}, "prod"]}}
                """.formatted(name("producer")).getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
        String template = """
                {"Conditions": {"Fn::Transform": {"Name": "AWS::Include",
                   "Parameters": {"Location": "s3://%s/conditions.json"}}},
                 "Resources": {"Topic": {"Type": "AWS::SNS::Topic", "Condition": "IsProd"}}}
                """.formatted(bucket);
        try {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateStack")
                    .formParam("StackName", name("include-conditions"))
                    .formParam("TemplateBody", template)
                    .when().post("/").then()
                    .statusCode(400)
                    .body(containsString("Template error: Cannot use Fn::GetStackOutput in Conditions."));
        } finally {
            s3Service.deleteObject(bucket, "conditions.json");
            s3Service.deleteBucket(bucket);
        }
    }

    /** Measured (eu-west-1, 2026-10-17): an output's export name may not depend on another stack. */
    @Test
    void inAnExportName_isRefusedOnCreate() {
        String template = """
                {"Resources": {"Topic": {"Type": "AWS::SNS::Topic"}},
                 "Outputs": {"Out": {"Value": "x", "Export": {"Name":
                   {"Fn::GetStackOutput": {"StackName": "%s", "OutputName": "VpcId"}}}}}}
                """.formatted(name("producer"));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", name("export-name"))
                .formParam("TemplateBody", template)
                .when().post("/").then()
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("Template format error: Output Out is malformed.  The Name field of Export"
                        + " must not depend on any resources, imported values, or Fn::GetAZs."));
    }

    /**
     * Measured (eu-west-1, 2026-10-17): a nested stack's template is checked when the child is
     * created. The Child resource fails with the template error, and the parent rolls back.
     */
    @Test
    void inANestedStacksConditions_failsTheChildResource() {
        String bucket = name("nested");
        String parent = name("nested-parent");
        s3Service.createBucket(bucket, US_EAST_1);
        s3Service.putObject(bucket, "child.json", """
                {"Conditions": {"C": {"Fn::Equals": [
                  {"Fn::GetStackOutput": {"StackName": "%s", "OutputName": "VpcId"}}, "x"]}},
                 "Resources": {"Topic": {"Type": "AWS::SNS::Topic", "Condition": "C"}}}
                """.formatted(name("producer")).getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
        try {
            createStack(DEFAULT_ACCOUNT, US_EAST_1, parent, """
                    {"Resources": {"Child": {"Type": "AWS::CloudFormation::Stack",
                      "Properties": {"TemplateURL": "http://localhost/%s/child.json"}}}}
                    """.formatted(bucket));

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(parent);
            assertEquals("ROLLBACK_COMPLETE", state.status());
            assertThat(CfnStackWaits.failedResourceReason(parent), containsString("Template error: Cannot use Fn::GetStackOutput in Conditions."));
        } finally {
            s3Service.deleteObject(bucket, "child.json");
            s3Service.deleteBucket(bucket);
        }
    }

    @Test
    void insideImportValue_isRefusedOnCreate() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", name("import"))
                .formParam("TemplateBody", consumer(parameter("import"), "{\"Fn::ImportValue\": {\"Fn::GetStackOutput\":"
                        + " {\"StackName\": \"" + name("producer") + "\", \"OutputName\": \"VpcId\"}}}"))
                .when().post("/").then()
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("Template error: the attribute in Fn::ImportValue must not depend on any"
                        + " resources, imported values, or Fn::GetAZs"));
    }

    /**
     * What AWS resolves beyond its documentation: a resource's Ref in a parameter, an Fn::Sub
     * variable map, Fn::Base64, and a key the function does not define, which is ignored.
     */
    @Test
    void parametersMayNameAResource_andEveryPositionResolves() {
        String producer = name("ref-producer");
        String consumer = name("ref-consumer");
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, producer, "vpc-ref");
        String template = """
                {"Resources": {
                  "Name": {"Type": "AWS::SSM::Parameter",
                    "Properties": {"Name": "%1$s", "Type": "String", "Value": "unused"}},
                  "ByRef": {"Type": "AWS::SSM::Parameter", "Properties": {"Name": "%2$s", "Type": "String",
                    "Value": {"Fn::GetStackOutput": {"StackName": {"Ref": "Name"}, "OutputName": "VpcId", "Extra": 1}}}},
                  "BySub": {"Type": "AWS::SSM::Parameter", "Properties": {"Name": "%3$s", "Type": "String",
                    "Value": {"Fn::Sub": ["x-${V}", {"V": {"Fn::GetStackOutput":
                      {"StackName": "%1$s", "OutputName": "VpcId"}}}]}}},
                  "ByBase64": {"Type": "AWS::SSM::Parameter", "Properties": {"Name": "%4$s", "Type": "String",
                    "Value": {"Fn::Base64": {"Fn::GetStackOutput":
                      {"StackName": "%1$s", "OutputName": "VpcId"}}}}}}}
                """.formatted(producer, parameter("by-ref"), parameter("by-sub"), parameter("by-base64"));

        createStack(DEFAULT_ACCOUNT, US_EAST_1, consumer, template);

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(consumer).status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("by-ref")).body("Parameter.Value", equalTo("vpc-ref"));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("by-sub")).body("Parameter.Value", equalTo("x-vpc-ref"));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, parameter("by-base64")).body("Parameter.Value", equalTo("dnBjLXJlZg=="));
    }

    /** A name unique to this run, so a rerun in the same JVM never meets a stack of an earlier one. */
    private String name(String base) {
        return "gso-" + base + "-" + suffix;
    }

    private String parameter(String base) {
        return "/gso/" + suffix + "/" + base;
    }

    private static String consumer(String parameterName, String value) {
        return """
                {"Resources": {"Copy": {"Type": "AWS::SSM::Parameter",
                  "Properties": {"Name": "%s", "Type": "String", "Value": %s}}}}
                """.formatted(parameterName, value);
    }

    private void createProducer(String account, String region, String stackName, String vpcId) {
        createStack(account, region, stackName,
                "{\"Outputs\": {\"VpcId\": {\"Value\": \"" + vpcId + "\"}}}");
        assertEquals("CREATE_COMPLETE",
                CfnStackWaits.awaitTerminal(stackName, auth(account, region, "cloudformation")).status());
    }

    private void createStack(String account, String region, String stackName, String template) {
        GetStackOutputFixtures.createStack(cleanup, account, region, stackName, template);
    }

    private void createRole(String account, String roleName) {
        createRole(account, roleName, "{\"AWS\":\"arn:aws:iam::" + DEFAULT_ACCOUNT + ":root\"}");
    }

    /** A role in {@code account} whose trust policy allows {@code principal}, a Principal element's value. */
    private void createRole(String account, String roleName, String principal) {
        EnforcementFixtures.createRole(cleanup, baseUri.getPort(), account, "/", roleName,
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":" + principal
                        + ",\"Action\":\"sts:AssumeRole\"}]}", DESCRIBE_STACKS);
    }

    private static ValidatableResponse parameterValue(String account, String region, String name) {
        return GetStackOutputFixtures.parameterValue(account, region, name);
    }
}
