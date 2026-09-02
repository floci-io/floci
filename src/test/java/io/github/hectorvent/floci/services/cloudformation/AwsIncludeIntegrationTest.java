package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The deployed outcome of {@code Fn::Transform}/{@code AWS::Include}: a {@code
 * DefinitionSubstitutions} mapping written as an include reaches the state machine's actual
 * definition, closing the symptom in "Request": 23 of 67 state machines in one repository
 * deploying with a literal {@code ${expandItems}}. The state machine is declared as
 * {@code AWS::StepFunctions::StateMachine}, not the SAM {@code AWS::Serverless::StateMachine} form
 * ({@code fix/sam-serverless-statemachine}'s subject); this test does not depend on that transform.
 */
@QuarkusTest
class AwsIncludeIntegrationTest {

    private static final Logger LOG = Logger.getLogger(AwsIncludeIntegrationTest.class);
    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";

    @Inject
    S3Service s3Service;

    private final ObjectMapper mapper = new ObjectMapper();

    private final List<String> stacksToDelete = new ArrayList<>();
    private final Map<String, List<String>> objectsToDelete = new LinkedHashMap<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        for (String stackName : stacksToDelete) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
            .when()
                .post("/");
        }
        stacksToDelete.clear();

        objectsToDelete.forEach((bucket, keys) -> {
            try {
                for (String key : keys) {
                    s3Service.deleteObject(bucket, key);
                }
                s3Service.deleteBucket(bucket);
            } catch (RuntimeException e) {
                // A bucket that a test (or a resource it provisioned) left with an object this
                // test never tracked throws BucketNotEmpty here. Left unguarded, that throw used
                // to abort the forEach before objectsToDelete.clear() ran, so the next @AfterEach
                // retried the same stale bucket/key pairs on top of its own, cascading one failed
                // delete into every test that ran afterward. Catching per-bucket keeps the loop
                // going for the buckets that follow and lets clear() below always run.
                LOG.warnv(e, "AwsIncludeIntegrationTest cleanup could not remove bucket {0}", bucket);
            }
        });
        objectsToDelete.clear();
    }

    private String putSnippet(String bucket, String key, String content) {
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, key, content.getBytes(StandardCharsets.UTF_8), "text/yaml", Map.of());
        objectsToDelete.computeIfAbsent(bucket, b -> new ArrayList<>()).add(key);
        return "s3://" + bucket + "/" + key;
    }

    @Test
    void includedSubstitutionsReachTheStateMachineDefinition() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "aws-include-it-" + suffix;
        String key = "definition-substitutions.yaml";
        String stackName = "aws-include-stack-" + suffix;
        String stateMachineName = "aws-include-sm-" + suffix;
        String stateMachineArn = "arn:aws:states:us-east-1:000000000000:stateMachine:" + stateMachineName;
        stacksToDelete.add(stackName);

        String location = putSnippet(bucket, key, "expandItems: substituted-value\n");

        String template = stateMachineTemplate(stateMachineName, location);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        String definition = given()
            .header("X-Amz-Target", "AWSStepFunctions.DescribeStateMachine")
            .contentType(SFN_CONTENT_TYPE)
            .body("{\"stateMachineArn\":\"" + stateMachineArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("definition", containsString("substituted-value"))
            .body("definition", not(containsString("${expandItems}")))
            .extract().path("definition");

        assertDoesNotThrow(() -> mapper.readTree(definition),
                "the deployed definition does not parse as JSON: " + definition);
    }

    @Test
    void stackWithUnreadableSnippetFailsCreation() {
        // No bucket is created for this location: AWS::Include fails the same way any other
        // unreadable AWS resource reference does, before any resource in the stack is attempted:
        // the same class of pre-provisioning failure CloudFormationSsmParameterIntegrationTest pins
        // for a missing SSM parameter, and CREATE_FAILED (not a rollback) is what that class of
        // failure produces today: CloudFormationService's create-path exception handler sets
        // CREATE_FAILED directly, and only a resource that reached the provisioning loop drives the
        // ROLLBACK_IN_PROGRESS/ROLLBACK_COMPLETE transition.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "aws-include-missing-stack-" + suffix;
        String stateMachineName = "aws-include-missing-sm-" + suffix;
        String location = "s3://aws-include-it-missing-bucket-" + suffix + "/definition-substitutions.yaml";
        stacksToDelete.add(stackName);

        String template = stateMachineTemplate(stateMachineName, location);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_FAILED</StackStatus>"))
            .body(containsString(location))
            .body(not(containsString("CREATE_COMPLETE")));

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        // The merge fails before the resource loop starts, so the state machine was never attempted:
        // it never even reaches CREATE_FAILED as a tracked resource, unlike a resource that fails
        // during provisioning itself.
        assertThat(resourcesXml, not(containsString("AWS::StepFunctions::StateMachine")));
    }

    @Test
    void getTemplateSummaryDoesNotExpandTheInclude() {
        // Same unreachable location as the unhappy path above, but through GetTemplateSummary:
        // AWS answers this with the transform declared and the snippet never fetched (measured
        // against us-east-1). Reusing the merge seam for GetTemplateSummary would instead fail the
        // summary on an unreachable location, which is the receipt this test exists to catch on a
        // future refactor.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stateMachineName = "aws-include-summary-sm-" + suffix;
        String location = "s3://aws-include-it-missing-bucket-" + suffix + "/definition-substitutions.yaml";

        String template = stateMachineTemplate(stateMachineName, location);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplateSummary")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<member>AWS::StepFunctions::StateMachine</member>"))
            // Anchored to the enclosing element, not just to a bare <member>: TemplateSummary
            // also renders ResourceTypes and Capabilities as <member> lists, so an unanchored
            // containsString cannot tell DeclaredTransforms apart from either of them and would
            // stay green even if AWS::Include regressed into the wrong list.
            // Measured against us-east-1: AWS reports AWS::Include in DeclaredTransforms for the
            // embedded Fn::Transform form too, with no top-level Transform section and no fetch of
            // the (here unreachable) snippet.
            .body(containsString("<DeclaredTransforms><member>AWS::Include</member></DeclaredTransforms>"));
    }

    @Test
    void getTemplateReturnsTheSubmittedYamlWhileTheChangeSetBaselineStaysMerged() {
        // Superseded assertions, and why they were wrong: this test used to assert that GetTemplate
        // returned the MERGED body ("merged-value" present, "Fn::Transform" absent). That pinned a
        // real fix (a live Fn::Transform node was leaking into the deployed template because the
        // re-serialize sat inside the SAM-only branch) but overshot it: real CloudFormation's
        // GetTemplate defaults to TemplateStage=Original and returns the template exactly as the
        // caller submitted it, not the SAM/Include-expanded form (GetTemplateSummary already reads
        // stack.getOriginalTemplateBody() for the same reason). So GetTemplate must return the
        // caller's raw YAML, Fn::Transform node and all, while CreateChangeSet's baseline diff
        // keeps comparing against the merged body (stack.getTemplateBody()) so a resubmit of the
        // identical template still reports zero changes instead of a spurious Modify.
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "aws-include-it-persist-" + suffix;
        String key = "definition-substitutions.yaml";
        String stackName = "aws-include-persist-stack-" + suffix;
        String parameterName = "/aws-include-it/persist-" + suffix;
        stacksToDelete.add(stackName);

        String location = putSnippet(bucket, key, "mergedFlag: merged-value\n");

        String template = """
            Resources:
              IncludeParam:
                Type: AWS::SSM::Parameter
                Metadata:
                  Fn::Transform:
                    Name: AWS::Include
                    Parameters:
                      Location: %s
                Properties:
                  Name: %s
                  Type: String
                  Value: unmerged-value
            """.formatted(location, parameterName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // The submitted YAML, verbatim: the Fn::Transform node survives (Original stage, not
            // merged) and the YAML key syntax ("Value: unmerged-value", no quotes around either
            // side) rules out a reserialized JSON pretty-print of the merged tree. "mergedFlag" is
            // the snippet's own key, so its presence would mean the merge ran despite Original.
            .body(containsString("Fn::Transform"))
            .body(containsString("Value: unmerged-value"))
            .body(not(containsString("mergedFlag")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "identical-resubmit-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "identical-resubmit-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // The baseline diff still uses the merged body (stack.getTemplateBody(), untouched by
            // this fix): resubmitting the identical template must not report a spurious Modify.
            .body(not(containsString("<Action>Modify</Action>")));
    }

    @Test
    void getTemplateStageSelectsBetweenTheMergedBodyAndTheSubmittedBody() {
        // One stack carrying both a SAM resource and an embedded AWS::Include, so TemplateStage
        // exercises both processors at once: Processed is the merged/SAM-expanded body
        // (stack.getTemplateBody()) with AWS::Serverless::Function expanded away and the include
        // merged in, Original is the caller's raw YAML (stack.getOriginalTemplateBody()), and no
        // TemplateStage at all still defaults to Original, byte for byte.
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "aws-include-it-stage-" + suffix;
        String key = "stage-definition.yaml";
        String stackName = "aws-include-stage-stack-" + suffix;
        String functionName = "aws-include-stage-func-" + suffix;
        String parameterName = "/aws-include-it/stage-" + suffix;
        stacksToDelete.add(stackName);

        String location = putSnippet(bucket, key, "mergedFlag: merged-value\n");

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              StageFunction:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: %s
                  Handler: index.handler
                  Runtime: nodejs22.x
                  InlineCode: |
                    exports.handler = async () => ({ statusCode: 200, body: 'ok' });
              IncludeParam:
                Type: AWS::SSM::Parameter
                Metadata:
                  Fn::Transform:
                    Name: AWS::Include
                    Parameters:
                      Location: %s
                Properties:
                  Name: %s
                  Type: String
                  Value: unmerged-value
            """.formatted(functionName, location, parameterName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
            .formParam("TemplateStage", "Processed")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("AWS::Lambda::Function"))
            .body(containsString("mergedFlag"))
            .body(not(containsString("AWS::Serverless::Function")))
            .body(not(containsString("Fn::Transform")));

        String originalBody = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
            .formParam("TemplateStage", "Original")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("AWS::Serverless::Function"))
            .body(containsString("Fn::Transform"))
            .body(not(containsString("mergedFlag")))
            .extract().path("GetTemplateResponse.GetTemplateResult.TemplateBody");

        String defaultStageBody = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("GetTemplateResponse.GetTemplateResult.TemplateBody");

        assertThat(defaultStageBody, equalTo(originalBody));
    }

    @Test
    void getTemplateWithInvalidStageRejectsWithTheAwsEnumValidationError() {
        // Verbatim against real AWS (measured on us-east-1): an invalid TemplateStage value is
        // rejected with ValidationError naming the enum, on any existing stack.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "aws-include-stage-bogus-stack-" + suffix;
        String parameterName = "/aws-include-it/stage-bogus-" + suffix;
        stacksToDelete.add(stackName);

        String template = """
            Resources:
              BogusStageParam:
                Type: AWS::SSM::Parameter
                Properties:
                  Name: %s
                  Type: String
                  Value: plain-value
            """.formatted(parameterName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
            .formParam("TemplateStage", "Bogus")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Value &apos;Bogus&apos; at &apos;templateStage&apos; failed to satisfy "
                    + "constraint: Member must satisfy enum value set: [Processed, Original]"));
    }

    @Test
    void getTemplateWithInvalidStageValidatesBeforeCheckingWhetherTheStackExists() {
        // Verbatim against real AWS (measured on us-east-1): `--template-stage Bogus` against a
        // stack name that was never created still answers with the enum ValidationError, not
        // "Stack with id ... does not exist", proving AWS validates the request shape before it
        // looks the stack up. This stack name is never created here, on purpose: a test that
        // creates the stack first (like the one above) cannot tell this ordering apart from
        // floci validating after a successful lookup.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "aws-include-stage-bogus-no-stack-" + suffix;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
            .formParam("TemplateStage", "Bogus")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Value &apos;Bogus&apos; at &apos;templateStage&apos; failed to satisfy "
                    + "constraint: Member must satisfy enum value set: [Processed, Original]"))
            .body(not(containsString("does not exist")));
    }

    @Test
    void getTemplateWithEmptyStringStageRejectsWithTheAwsEnumValidationError() {
        // Verbatim against real AWS (measured on us-east-1): `--template-stage ""` is rejected the
        // same as any other value outside the enum. params.getFirst("TemplateStage") returns "" for
        // this request, distinct from the omitted case (null), which defaults to Original.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "aws-include-stage-empty-stack-" + suffix;
        String parameterName = "/aws-include-it/stage-empty-" + suffix;
        stacksToDelete.add(stackName);

        String template = """
            Resources:
              EmptyStageParam:
                Type: AWS::SSM::Parameter
                Properties:
                  Name: %s
                  Type: String
                  Value: plain-value
            """.formatted(parameterName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
            .formParam("TemplateStage", "")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Value &apos;&apos; at &apos;templateStage&apos; failed to satisfy "
                    + "constraint: Member must satisfy enum value set: [Processed, Original]"));
    }

    @Test
    void getTemplateOnAPlainStackReturnsIdenticalBodiesAcrossStagesAndAdvertisesBothInStagesAvailable() {
        // No transform at all: real AWS still accepts TemplateStage on a plain stack and returns
        // the identical body for both stages (measured md5-identical on us-east-1), and
        // StagesAvailable lists both stages on every GetTemplate call, including one with no
        // stage requested, which floci never returned before this change.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "aws-include-stage-plain-stack-" + suffix;
        String parameterName = "/aws-include-it/stage-plain-" + suffix;
        stacksToDelete.add(stackName);

        String template = """
            Resources:
              PlainStageParam:
                Type: AWS::SSM::Parameter
                Properties:
                  Name: %s
                  Type: String
                  Value: plain-value
            """.formatted(parameterName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String processedBody = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
            .formParam("TemplateStage", "Processed")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("GetTemplateResponse.GetTemplateResult.TemplateBody");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetTemplateResponse.GetTemplateResult.TemplateBody", equalTo(processedBody))
            .body(containsString(
                    "<StagesAvailable><member>Original</member><member>Processed</member></StagesAvailable>"));
    }

    @Test
    void conditionsFromTheIncludedSnippetReachProvisioningInsteadOfBeingRejected() {
        // validateConditionDependencies used to preflight the RAW, unmerged body: a Conditions
        // section spliced in from a snippet was invisible there, so a resource conditioned on it
        // defaulted to excluded and a dependent resource failed CreateStack synchronously with a
        // spurious "Unresolved resource dependencies" error, even though the merge that runs later
        // in executeTemplate would have resolved the condition to true.
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "aws-include-it-cond-" + suffix;
        String key = "conditions.yaml";
        String stackName = "aws-include-cond-stack-" + suffix;
        String conditionedParamName = "/aws-include-it/conditioned-" + suffix;
        String dependentParamName = "/aws-include-it/dependent-" + suffix;
        stacksToDelete.add(stackName);

        String location = putSnippet(bucket, key, "Conditions:\n  FeatureEnabled: true\n");

        String template = """
            Fn::Transform:
              Name: AWS::Include
              Parameters:
                Location: %s
            Resources:
              ConditionedParam:
                Type: AWS::SSM::Parameter
                Condition: FeatureEnabled
                Properties:
                  Name: %s
                  Type: String
                  Value: conditioned-value
              DependentParam:
                Type: AWS::SSM::Parameter
                DependsOn: ConditionedParam
                Properties:
                  Name: %s
                  Type: String
                  Value: dependent-value
            """.formatted(location, conditionedParamName, dependentParamName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(not(containsString("Unresolved resource dependencies")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>ConditionedParam</LogicalResourceId>"))
            .body(containsString("<LogicalResourceId>DependentParam</LogicalResourceId>"));
    }

    private static String stateMachineTemplate(String stateMachineName, String location) {
        return """
            Resources:
              QueryPool:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  StateMachineName: %s
                  RoleArn: arn:aws:iam::000000000000:role/aws-include-it-role
                  DefinitionString: |-
                    {"StartAt":"Done","States":{"Done":{"Type":"Pass","Result":"${expandItems}","End":true}}}
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: %s
            """.formatted(stateMachineName, location);
    }
}
