package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        given().when().put("/" + bucket).then().statusCode(200);
        uploadTemplate(bucket, "grandchild.json", grandchildTemplate);
        uploadTemplate(bucket, "child.json", childTemplate);

        try {
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
                        "TemplateURL":"http://localhost/%1$s/child.json","Parameters":{"Revision":"failed"}}},
                      "ZFail":{"Type":"AWS::CloudFormation::Stack","Properties":{}}
                    }}
                    """.formatted(bucket);
            String previousChildTemplate = deployedTemplate(childStack);
            String previousGrandchildTemplate = deployedTemplate(grandchildStack);
            uploadTemplate(bucket, "child.json", childTemplate("changed in s3", bucket));
            uploadTemplate(bucket, "grandchild.json", grandchildTemplate.replace("grandchild", "changed grandchild"));
            withTags(cfn("UpdateStack", stackName).formParam("TemplateBody", failingParentTemplate),
                    "orange", "owner-c").when().post("/").then().statusCode(200);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(stackName));
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(childStack));
            assertEquals(Map.of("team", "green", "owner", "owner-b"), tags(grandchildStack));
            // The failed update had re-provisioned the child with its own new template before ZFail
            // broke the parent: a parent rollback owes its nested stacks their prior deployment, not
            // just their tags.
            assertEquals(previousChildTemplate, deployedTemplate(childStack),
                    "the rolled-back child must restore its previous template");
            assertEquals(previousGrandchildTemplate, deployedTemplate(grandchildStack),
                    "the rolled-back grandchild must restore its previous template");
            String childXml = cfn("DescribeStacks", childStack).when().post("/")
                    .then().statusCode(200).extract().asString();
            assertEquals("previous", XmlParser.extractPairs(childXml, "member", "ParameterKey", "ParameterValue")
                    .get("Revision"));

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

            String priorChildTemplate = deployedTemplate(childStack);
            String priorGrandchildTemplate = deployedTemplate(grandchildStack);
            uploadTemplate(bucket, "child.json", "{\"Resources\":{}}");
            String removalThenFailure = explicitChildTags.replace("\"Resources\":{",
                    "\"Resources\":{\"ZFail\":{\"Type\":\"AWS::CloudFormation::Stack\","
                            + "\"DependsOn\":\"ChildStack\",\"Properties\":{}},");
            withTags(cfn("UpdateStack", stackName).formParam("TemplateBody", removalThenFailure), "failed", "owner-e")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            String recreatedGrandchild = nestedStackId(childStack, "GrandchildStack");
            assertNotEquals(grandchildStack, recreatedGrandchild,
                    "the removed grandchild must be recreated from its saved deployment");
            assertEquals(priorChildTemplate, deployedTemplate(childStack));
            assertEquals(priorGrandchildTemplate, deployedTemplate(recreatedGrandchild));
            assertEquals(Map.of(), tags(stackName));
            assertEquals(Map.of("team", "child"), tags(childStack));
            assertEquals(Map.of("team", "child"), tags(recreatedGrandchild));
        } finally {
            cfn("DeleteStack", stackName).when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stackName);
            given().when().delete("/" + bucket + "/child.json");
            given().when().delete("/" + bucket + "/grandchild.json");
            given().when().delete("/" + bucket);
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

    private static Map<String, String> tags(String stackName) {
        String xml = cfn("DescribeStacks", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "Key", "Value");
    }

    private static String deployedTemplate(String stackName) {
        String xml = cfn("GetTemplate", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractFirst(xml, "TemplateBody", null);
    }
}
