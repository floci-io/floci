package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.github.hectorvent.floci.testing.CfnRequests.tags;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationNestedStackTagsIntegrationTest {

    @Test
    void nestedStacksInheritRootTagsOnCreateAndUpdateAndRestoreThemOnRollback() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackName = "cfn-nested-tags-" + suffix;
        String bucket = "cfn-nested-tags-templates-" + suffix;
        String grandchildTemplate = """
                {"Description":"grandchild","Resources":{}}
                """;
        String childTemplate = childTemplate("child", bucket);
        String parentTemplate = parentTemplate(bucket);
        Throwable testFailure = null;
        try {
            given().when().put("/" + bucket).then().statusCode(200);
            uploadTemplate(bucket, "grandchild.json", grandchildTemplate);
            uploadTemplate(bucket, "child.json", childTemplate);

            withTags(cfn("CreateStack", stackName).formParam("TemplateBody", parentTemplate), "blue", "owner-a")
                    .when().post("/").then().statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            String childStack = nestedStackId(stackName, "ChildStack");
            String grandchildStack = nestedStackId(childStack, "GrandchildStack");
            assertEquals(Map.of("team", "blue", "owner", "owner-a"), tags(childStack));
            assertEquals(Map.of("team", "blue", "owner", "owner-a"), tags(grandchildStack));

            withTags(cfn("UpdateStack", stackName).formParam("TemplateBody", parentTemplate), "green", "owner-b")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(childStack));
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(grandchildStack));

            uploadTemplate(bucket, "child.json", childTemplate("updated child", bucket));
            cfn("UpdateStack", stackName).formParam("TemplateBody", parentTemplate)
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(childStack));

            String failingParentTemplate = """
                    {"Resources":{
                      "ChildStack":{"Type":"AWS::CloudFormation::Stack","Properties":{
                        "TemplateURL":"http://localhost/%1$s/child.json"}},
                      "ZFail":{"Type":"AWS::CloudFormation::Stack","Properties":{}}
                    }}
                    """.formatted(bucket);
            String previousChildTemplate = deployedTemplate(childStack);
            String previousGrandchildTemplate = deployedTemplate(grandchildStack);
            withTags(cfn("UpdateStack", stackName).formParam("TemplateBody", failingParentTemplate),
                    "orange", "owner-c").when().post("/").then().statusCode(200);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(stackName));
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(childStack));
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(grandchildStack));
            // The failed update re-provisioned the child with the template and the parameter values
            // it already ran, so only its tags moved: the rollback puts the prior tags back on the
            // child and on the grandchild that inherited them, and every deployment stays exactly
            // where it was. A nested stack the update re-deployed with a changed template or
            // changed parameters is owed a deployment rollback this build defers; that one ends
            // UPDATE_ROLLBACK_FAILED instead, covered in CloudFormationServiceRollbackTest.
            assertEquals(previousChildTemplate, deployedTemplate(childStack));
            assertEquals(previousGrandchildTemplate, deployedTemplate(grandchildStack));

            cfn("UpdateStack", stackName).formParam("TemplateBody", parentTemplate).formParam("Tags", "")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of(), tags(stackName));
            assertEquals(Map.of(), tags(childStack));
            assertEquals(Map.of(), tags(grandchildStack));

            String explicitChildTags = parentTemplate.replace("\"TemplateURL\":",
                    "\"Tags\":[{\"Key\":\"team\",\"Value\":\"child\"}],\"TemplateURL\":");
            withTags(cfn("UpdateStack", stackName).formParam("TemplateBody", explicitChildTags), "root", "owner-d")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("team", "child", "owner", "owner-d"), tags(childStack));
            assertEquals(Map.of("team", "child", "owner", "owner-d"), tags(grandchildStack));

            cfn("UpdateStack", stackName).formParam("TemplateBody", explicitChildTags).formParam("Tags", "")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("team", "child"), tags(childStack));
            assertEquals(Map.of("team", "child"), tags(grandchildStack));
        } catch (RuntimeException | Error failure) {
            testFailure = failure;
            throw failure;
        } finally {
            try {
                assertAll("nested stack cleanup",
                        () -> cfn("DeleteStack", stackName).when().post("/").then().statusCode(200),
                        () -> CfnStackWaits.awaitStackDeleted(stackName),
                        () -> given().when().delete("/" + bucket + "/child.json").then().statusCode(204),
                        () -> given().when().delete("/" + bucket + "/grandchild.json").then().statusCode(204),
                        () -> given().when().delete("/" + bucket).then().statusCode(204));
            } catch (RuntimeException | Error cleanupFailure) {
                if (testFailure == null) {
                    throw cleanupFailure;
                }
                testFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static String childTemplate(String description, String bucket) {
        return """
                {"Description":"%1$s","Parameters":{"Revision":{"Type":"String","Default":"previous"}},
                 "Resources":{"GrandchildStack":{"Type":"AWS::CloudFormation::Stack",
                  "Properties":{"TemplateURL":"http://localhost/%2$s/grandchild.json"}}}}
                """.formatted(description, bucket);
    }

    private static String parentTemplate(String bucket) {
        return """
                {"Resources":{"ChildStack":{"Type":"AWS::CloudFormation::Stack",
                  "Properties":{"TemplateURL":"http://localhost/%1$s/child.json"}}}}
                """.formatted(bucket);
    }

    private static void uploadTemplate(String bucket, String key, String body) {
        given().contentType("application/json").body(body)
                .when().put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static RequestSpecification withTags(RequestSpecification request, String team, String owner) {
        return request.formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", team)
                .formParam("Tags.member.2.Key", "owner")
                .formParam("Tags.member.2.Value", owner);
    }

    private static String nestedStackId(String stackName, String logicalId) {
        String xml = cfn("DescribeStackResources", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "LogicalResourceId", "PhysicalResourceId").get(logicalId);
    }

    private static String deployedTemplate(String stackName) {
        String xml = cfn("GetTemplate", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractFirst(xml, "TemplateBody", null);
    }
}
