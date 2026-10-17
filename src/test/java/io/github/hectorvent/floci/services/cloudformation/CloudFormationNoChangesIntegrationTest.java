package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An update that changes none of the stack's resources and outputs. Measured against
 * CloudFormation (eu-west-1, 2026-10-03 and 2026-10-16): UpdateStack answers ValidationError "No
 * updates are to be performed." and changes nothing; a CreateChangeSet for it is created FAILED,
 * UNAVAILABLE, with "The submitted information didn't contain changes. Submit different information
 * to create a change set.". What is compared is each active resource's Type, Properties, Metadata,
 * DeletionPolicy, UpdateReplacePolicy and UpdatePolicy, and each active output, with whatever does
 * not depend on a resource resolved: a new Description, template Metadata, an unused parameter,
 * mapping or condition, a DependsOn, a literal written as Fn::Join, a parameter written as Fn::Sub
 * or an output whose condition is false is no change; a change to an active output, a resource's
 * Metadata, a used mapping value, a Ref of a resource rewritten as Fn::Sub, or a dynamic reference
 * whose stored value moved is an update. The CDK deletes the FAILED change set and deletes it again
 * by name before its next deploy; DeleteChangeSet answers success for a change set the stack does
 * not have, by name or by ARN, and ValidationError for a stack that does not exist (measured
 * 2026-10-04). Tags are part of what an update submits (measured 2026-10-16): the stack's own tags
 * in another order, or no Tags, change nothing; different tags, or an empty set on a tagged stack,
 * are an update that replaces them.
 */
@QuarkusTest
class CloudFormationNoChangesIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources": {"Topic": {"Type": "AWS::SNS::Topic", "Properties": {"TopicName": "%s"}}},
             "Outputs": {"Out": {"Value": "1"}}}""";

    @Inject
    S3Service s3Service;

    @Inject
    SsmService ssmService;

    private final String suffix = Long.toString(System.nanoTime(), 36);
    private final List<String> stacksToDelete = new ArrayList<>();
    private final List<String> parametersToDelete = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        for (String stackName : stacksToDelete) {
            request("DeleteStack", stackName).when().post("/");
        }
        stacksToDelete.clear();
        for (String parameter : parametersToDelete) {
            ssmService.deleteParameter(parameter, "us-east-1");
        }
        parametersToDelete.clear();
    }

    @Test
    void anIdenticalUpdateIsRefusedAndChangesNothing() {
        String stackName = name("no-change");
        String template = TEMPLATE.formatted(name("no-change-topic"));
        create(stackName, template);
        String changeSetsBefore = listChangeSets(stackName).extract().asString();

        update(stackName, template)
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>No updates are to be performed.</Message>"));
        update(stackName, template.replace("\n", "\n        ").replace(": ", ":    "))
                .statusCode(400)
                .body(containsString("No updates are to be performed."));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        listChangeSets(stackName).body(not(containsString("<ChangeSetName>update-")));
        assertEquals(changeSetsBefore.replaceAll("<RequestId>[^<]*</RequestId>", ""),
                listChangeSets(stackName).extract().asString().replaceAll("<RequestId>[^<]*</RequestId>", ""));
    }

    @Test
    void aChangeToTheOutputsAloneIsAnUpdate() {
        String stackName = name("outputs-only");
        String template = TEMPLATE.formatted(name("outputs-only-topic"));
        create(stackName, template);

        update(stackName, template.replace("\"Value\": \"1\"", "\"Value\": \"2\"")).statusCode(200);

        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
                .when().post("/").then().statusCode(200)
                .body(containsString("<OutputKey>Out</OutputKey>"))
                .body(containsString("<OutputValue>2</OutputValue>"));
    }

    /**
     * What does not change a resource or an output is no update: a new Description, template
     * Metadata, an unused parameter, mapping or condition, a DependsOn, a literal written as
     * Fn::Join, a parameter written as Fn::Sub, an Fn::If whose other branch changed, and an output
     * whose condition is false.
     */
    @Test
    void whatChangesNoResourceOrOutputIsNoUpdate() {
        String template = """
                {"Parameters": {"P": {"Type": "String", "Default": "x"}},
                 "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                 "Resources": {
                   "A": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": "v"}},
                   "B": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": {"Ref": "P"}}}},
                 "Outputs": {"Out": {"Value": "o"}, "Gated": {"Condition": "Never", "Value": "g"}}}""";
        String stackName = name("no-change-forms");
        create(stackName, template);

        for (String same : new String[] {
                template.replace("{\"Parameters\"", "{\"Description\": \"new\", \"Metadata\": {\"k\": \"v\"}, \"Parameters\""),
                template.replace("\"Default\": \"x\"}}", "\"Default\": \"x\"}, \"Unused\": {\"Type\": \"String\", \"Default\": \"u\"}}, "
                        + "\"Mappings\": {\"M\": {\"k\": {\"v\": \"m\"}}}"),
                template.replace("\"Type\": \"String\", \"Value\": \"v\"}}", "\"Type\": \"String\", \"Value\": \"v\"}, \"DependsOn\": \"B\"}"),
                template.replace("\"Value\": \"v\"", "\"Value\": {\"Fn::Join\": [\"\", [\"v\"]]}"),
                template.replace("{\"Ref\": \"P\"}", "{\"Fn::Sub\": \"${P}\"}"),
                template.replace("\"Value\": \"v\"", "\"Value\": {\"Fn::If\": [\"Never\", \"other\", \"v\"]}"),
                template.replace("\"Value\": \"g\"", "\"Value\": \"g2\""),
        }) {
            update(stackName, same)
                    .statusCode(400)
                    .body(containsString("No updates are to be performed."));
        }
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /**
     * A Ref of a resource is compared as written: rewritten as Fn::Sub, it is an update, as a new
     * Metadata on a resource is.
     */
    @Test
    void whatReadsAResourceIsComparedAsWritten() {
        String template = """
                {"Resources": {
                   "A": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": "a"}},
                   "B": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": {"Ref": "A"}}}}}""";
        String stackName = name("as-written");
        create(stackName, template);

        String rewritten = template.replace("{\"Ref\": \"A\"}", "{\"Fn::Sub\": \"${A}\"}");
        update(stackName, rewritten).statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

        update(stackName, rewritten.replace("\"Value\": \"a\"}}", "\"Value\": \"a\"}, \"Metadata\": {\"k\": \"v\"}}"))
                .statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /** A mapping value a resource uses is resolved: changed, the resource is modified. */
    @Test
    void aUsedMappingValueIsAChange() {
        String template = """
                {"Mappings": {"M": {"k": {"v": "%s"}}},
                 "Resources": {"B": {"Type": "AWS::SSM::Parameter",
                                     "Properties": {"Type": "String", "Value": {"Fn::FindInMap": ["M", "k", "v"]}}}}}""";
        String stackName = name("used-mapping");
        create(stackName, template.formatted("first"));

        changeSet(stackName, "mapping", template.formatted("second")).when().post("/").then().statusCode(200);
        describeChangeSet(stackName, "mapping")
                .body(containsString("<Status>CREATE_COMPLETE</Status>"))
                .body(containsString("<Action>Modify</Action>"))
                .body(containsString("<LogicalResourceId>B</LogicalResourceId>"));
    }

    /**
     * A dynamic reference compares as the value it resolves to: identical, the template is an
     * update once the stored value moved, and the resource takes the new value.
     */
    @Test
    void aDynamicReferenceWhoseValueMovedIsAChange() {
        String source = "/no-change/source-" + suffix;
        ssmService.putParameter(source, "first", "String", null, true, "us-east-1");
        parametersToDelete.add(source);
        String template = """
                {"Resources": {"Copy": {"Type": "AWS::SSM::Parameter",
                   "Properties": {"Name": "%s-copy", "Type": "String", "Value": "{{resolve:ssm:%s}}"}}}}"""
                .formatted(source, source);
        String stackName = name("moved-reference");
        create(stackName, template);

        update(stackName, template)
                .statusCode(400)
                .body(containsString("No updates are to be performed."));

        ssmService.putParameter(source, "second", "String", null, true, "us-east-1");
        update(stackName, template).statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        assertEquals("second", ssmService.getParameter(source + "-copy", "us-east-1").getValue());
    }

    /**
     * A dynamic reference inside an Fn::Sub that reads a resource compares as the value it resolves
     * to, the resource read left as written: unchanged, the template is no update; once the stored
     * value moved, it is one.
     */
    @Test
    void aDynamicReferenceBesideAResourceReadComparesByItsValue() {
        String source = "/no-change/beside-" + suffix;
        ssmService.putParameter(source, "first", "String", null, true, "us-east-1");
        parametersToDelete.add(source);
        String template = """
                {"Resources": {
                   "A": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": "a"}},
                   "B": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String",
                     "Value": {"Fn::Sub": "${A}-{{resolve:ssm:%s}}"}}}}}""".formatted(source);
        String stackName = name("beside-resource");
        create(stackName, template);

        update(stackName, template)
                .statusCode(400)
                .body(containsString("No updates are to be performed."));

        ssmService.putParameter(source, "second", "String", null, true, "us-east-1");
        update(stackName, template).statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /**
     * An update that fails rolls back to what the stack had, the dynamic reference values it was
     * deployed with included: retried after a stored value moved, it is still an update.
     */
    @Test
    void aFailedUpdateLeavesTheDeployedDynamicReferenceValues() {
        String source = "/no-change/rolled-back-" + suffix;
        ssmService.putParameter(source, "first", "String", null, true, "us-east-1");
        parametersToDelete.add(source);
        String copy = """
                "Copy": {"Type": "AWS::SSM::Parameter",
                   "Properties": {"Type": "String", "Value": "{{resolve:ssm:%s}}"}}""".formatted(source);
        String template = "{\"Resources\": {" + copy + "}}";
        // A nested stack without a TemplateURL fails once Copy took the new value.
        String failing = "{\"Resources\": {" + copy + ", \"Nested\": {\"Type\": \"AWS::CloudFormation::Stack\","
                + " \"DependsOn\": \"Copy\", \"Properties\": {}}}}";
        String stackName = name("rolled-back");
        create(stackName, template);

        ssmService.putParameter(source, "second", "String", null, true, "us-east-1");
        update(stackName, failing).statusCode(200);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

        update(stackName, template).statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /**
     * What reads a resource is compared as written, an AWS::NoValue in it included: an Fn::Join that
     * drops one is an update.
     */
    @Test
    void aNoValueInsideAResourceReadIsPartOfWhatIsWritten() {
        String template = """
                {"Resources": {
                   "A": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": "a"}},
                   "B": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String",
                     "Value": {"Fn::Join": ["-", [{"Ref": "A"}, "x", {"Ref": "AWS::NoValue"}]]}}}}}""";
        String stackName = name("novalue-written");
        create(stackName, template);

        update(stackName, template.replace(", {\"Ref\": \"AWS::NoValue\"}", "")).statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /**
     * The array form of an Fn::Sub that reads a resource has its other variables replaced by their
     * values too, those its own map defines left as written: an AWS::Region written as its value is
     * no update.
     */
    @Test
    void anFnSubArrayReadingAResourceComparesItsOtherVariablesByValue() {
        String template = """
                {"Resources": {
                   "A": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": "a"}},
                   "B": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String",
                     "Value": {"Fn::Sub": ["${A}-${AWS::Region}-${X}", {"X": "x"}]}}}}}""";
        String stackName = name("sub-array");
        create(stackName, template);

        update(stackName, template.replace("${AWS::Region}", "us-east-1"))
                .statusCode(400)
                .body(containsString("No updates are to be performed."));
        update(stackName, template.replace("{\"X\": \"x\"}", "{\"X\": \"y\"}")).statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /**
     * A transformed template is compared as CloudFormation processes it: the SAM transform applied,
     * so resubmitting an unchanged SAM template is not an update either.
     */
    @Test
    void anUnchangedSamTemplateIsRefused() {
        String template = """
                {"Transform": "AWS::Serverless-2016-10-31",
                 "Resources": {"Table": {"Type": "AWS::Serverless::SimpleTable",
                                         "Properties": {"TableName": "%s"}}}}""".formatted(name("sam-table"));
        String stackName = name("no-change-sam");
        create(stackName, template);

        update(stackName, template)
                .statusCode(400)
                .body(containsString("No updates are to be performed."));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /**
     * The SAM transform is applied with the stack's parameter values, as execution applies it: a
     * version SAM names after a hash of its code, which a parameter given a value other than its
     * default changes, compares equal, and resubmitting the template with the same values is not an
     * update.
     */
    @Test
    void anUnchangedSamTemplateIsComparedWithItsParameterValues() {
        String template = """
                {"Transform": "AWS::Serverless-2016-10-31",
                 "Parameters": {"Release": {"Type": "String", "Default": "first"}},
                 "Resources": {"Function": {"Type": "AWS::Serverless::Function",
                   "Properties": {"FunctionName": "%s", "Handler": "index.handler",
                                  "Runtime": "nodejs20.x", "AutoPublishAlias": "live",
                                  "InlineCode": {"Fn::Sub": "exports.handler = async () => '${Release}';"}}}}}"""
                .formatted(name("sam-params-func"));
        String stackName = name("no-change-sam-params");
        request("CreateStack", stackName).formParam("TemplateBody", template)
                .formParam("Capabilities.member.1", "CAPABILITY_IAM")
                .formParam("Parameters.member.1.ParameterKey", "Release")
                .formParam("Parameters.member.1.ParameterValue", "second")
                .when().post("/").then().statusCode(200);
        stacksToDelete.add(stackName);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

        request("UpdateStack", stackName).formParam("TemplateBody", template)
                .formParam("Capabilities.member.1", "CAPABILITY_IAM")
                .formParam("Parameters.member.1.ParameterKey", "Release")
                .formParam("Parameters.member.1.ParameterValue", "second")
                .when().post("/").then()
                .statusCode(400)
                .body(containsString("No updates are to be performed."));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /**
     * AWS::Include fragments are merged before the comparison, and read again: the same template
     * with the same fragment is not an update, and with the fragment changed in S3 it is.
     */
    @Test
    void anIncludeIsComparedWithItsFragmentReadAgain() {
        String bucket = name("include");
        s3Service.createBucket(bucket, "us-east-1");
        putFragment(bucket, "Description: first\n");
        String template = """
                Resources:
                  Topic:
                    Type: AWS::SNS::Topic
                    Metadata:
                      Fn::Transform:
                        Name: AWS::Include
                        Parameters:
                          Location: s3://%s/fragment.yaml
                    Properties:
                      TopicName: %s
                """.formatted(bucket, name("include-topic"));
        String stackName = name("no-change-include");
        try {
            create(stackName, template);

            update(stackName, template)
                    .statusCode(400)
                    .body(containsString("No updates are to be performed."));

            putFragment(bucket, "Description: second\n");
            update(stackName, template).statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        } finally {
            s3Service.deleteObject(bucket, "fragment.yaml");
            s3Service.deleteBucket(bucket);
        }
    }

    private void putFragment(String bucket, String content) {
        s3Service.putObject(bucket, "fragment.yaml", content.getBytes(StandardCharsets.UTF_8), "text/yaml", Map.of());
    }

    @Test
    void aChangeSetWithNothingToChangeIsCreatedFailed() {
        String stackName = name("change-set-same");
        String template = TEMPLATE.formatted(name("change-set-same-topic"));
        create(stackName, template);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateChangeSet")
                .formParam("StackName", stackName)
                .formParam("ChangeSetName", "same")
                .formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeChangeSet")
                .formParam("StackName", stackName)
                .formParam("ChangeSetName", "same")
                .when().post("/").then()
                .statusCode(200)
                .body(containsString("<Status>FAILED</Status>"))
                .body(containsString("<ExecutionStatus>UNAVAILABLE</ExecutionStatus>"))
                .body(containsString("<StatusReason>The submitted information didn"))
                .body(containsString("t contain changes. Submit different information to create a change set.</StatusReason>"));
    }

    @Test
    void tagsArePartOfWhatAnUpdateSubmits() {
        String stackName = name("tags");
        String template = TEMPLATE.formatted(name("tags-topic"));
        withTags(request("CreateStack", stackName).formParam("TemplateBody", template), "Env", "a", "Team", "x")
                .when().post("/").then().statusCode(200);
        stacksToDelete.add(stackName);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

        withTags(request("UpdateStack", stackName).formParam("TemplateBody", template), "Team", "x", "Env", "a")
                .when().post("/").then()
                .statusCode(400)
                .body(containsString("<Message>No updates are to be performed.</Message>"));
        update(stackName, template)
                .statusCode(400)
                .body(containsString("<Message>No updates are to be performed.</Message>"));
        assertEquals(Map.of("Env", "a", "Team", "x"), tags(stackName));

        withTags(changeSet(stackName, "same-tags", template), "Env", "a", "Team", "x")
                .when().post("/").then().statusCode(200);
        describeChangeSet(stackName, "same-tags").body(containsString("<Status>FAILED</Status>"));
        withTags(changeSet(stackName, "other-tags", template), "Env", "b")
                .when().post("/").then().statusCode(200);
        describeChangeSet(stackName, "other-tags")
                .body(containsString("<Status>CREATE_COMPLETE</Status>"))
                .body(containsString("<ExecutionStatus>AVAILABLE</ExecutionStatus>"));

        withTags(request("UpdateStack", stackName).formParam("TemplateBody", template), "Env", "b")
                .when().post("/").then().statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        assertEquals(Map.of("Env", "b"), tags(stackName));

        request("UpdateStack", stackName).formParam("TemplateBody", template).formParam("Tags", "")
                .when().post("/").then().statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        assertEquals(Map.of(), tags(stackName));
        request("UpdateStack", stackName).formParam("TemplateBody", template).formParam("Tags", "")
                .when().post("/").then()
                .statusCode(400)
                .body(containsString("<Message>No updates are to be performed.</Message>"));
    }

    @Test
    void theChangeSetTheCdkDeletedCanBeDeletedAgain() {
        String stackName = name("delete-again");
        String template = TEMPLATE.formatted(name("delete-again-topic"));
        create(stackName, template);
        String id = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateChangeSet")
                .formParam("StackName", stackName)
                .formParam("ChangeSetName", "cdk-deploy-change-set")
                .formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateChangeSetResponse.CreateChangeSetResult.Id");

        deleteChangeSet(stackName, "cdk-deploy-change-set").statusCode(200);
        deleteChangeSet(stackName, "cdk-deploy-change-set")
                .statusCode(200)
                .body(containsString("<DeleteChangeSetResult/>"));
        deleteChangeSet(stackName, id).statusCode(200);
        // By its ARN alone too, with no StackName (measured 2026-10-17).
        deleteChangeSet(null, id)
                .statusCode(200)
                .body(containsString("<DeleteChangeSetResult/>"));
        deleteChangeSet(name("delete-again-missing"), "cdk-deploy-change-set")
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    /** {@code stackName} null sends none: the change set is named by its ARN alone. */
    private static ValidatableResponse deleteChangeSet(String stackName, String changeSetName) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteChangeSet")
                .formParam("ChangeSetName", changeSetName);
        if (stackName != null) {
            request.formParam("StackName", stackName);
        }
        return request.when().post("/").then();
    }

    /** A name unique to this run, so a rerun in the same JVM never meets a stack of an earlier one. */
    private String name(String base) {
        return "no-change-" + base + "-" + suffix;
    }

    private void create(String stackName, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
        stacksToDelete.add(stackName);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    private static ValidatableResponse update(String stackName, String template) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "UpdateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
                .when().post("/").then();
    }

    private static RequestSpecification request(String action, String stackName) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stackName);
    }

    private static RequestSpecification changeSet(String stackName, String changeSetName, String template) {
        return request("CreateChangeSet", stackName)
                .formParam("ChangeSetName", changeSetName)
                .formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template);
    }

    private static ValidatableResponse describeChangeSet(String stackName, String changeSetName) {
        return request("DescribeChangeSet", stackName)
                .formParam("ChangeSetName", changeSetName)
                .when().post("/").then().statusCode(200);
    }

    private static RequestSpecification withTags(RequestSpecification request, String... keysAndValues) {
        for (int i = 0; i < keysAndValues.length; i += 2) {
            int member = i / 2 + 1;
            request.formParam("Tags.member." + member + ".Key", keysAndValues[i])
                    .formParam("Tags.member." + member + ".Value", keysAndValues[i + 1]);
        }
        return request;
    }

    private static Map<String, String> tags(String stackName) {
        String xml = request("DescribeStacks", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "Key", "Value");
    }

    private static ValidatableResponse listChangeSets(String stackName) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListChangeSets")
                .formParam("StackName", stackName)
                .when().post("/").then().statusCode(200);
    }
}
