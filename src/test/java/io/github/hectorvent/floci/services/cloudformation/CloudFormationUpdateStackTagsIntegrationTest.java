package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationUpdateStackTagsIntegrationTest {

    @Test
    void tagsOnlyUpdatesWorkForDirectRequestsAndChangeSets() {
        String stackName = "cfn-tags-only-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {"Resources":{"Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s"}}}}
                """.formatted(stackName);
        try {
            withTags(request("CreateStack", stackName).formParam("TemplateBody", template),
                    "before", "original")
                    .when().post("/").then().statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            withTags(request("UpdateStack", stackName).formParam("TemplateBody", template),
                    "after", "new")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("Env", "after", "Team", "new"), tags(stackName));

            withTags(request("CreateChangeSet", stackName)
                    .formParam("TemplateBody", template)
                    .formParam("ChangeSetName", "tags-only")
                    .formParam("ChangeSetType", "UPDATE"), "final", "latest")
                    .when().post("/").then().statusCode(200);
            assertEquals(Map.of("Env", "after", "Team", "new"), tags(stackName));
            executeChangeSet(stackName, "tags-only");
            assertEquals(Map.of("Env", "final", "Team", "latest"), tags(stackName));

            String failingTemplate = """
                    {"Resources":{
                      "Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s"}},
                      "ZFail":{"Type":"AWS::CloudFormation::Stack","Properties":{}}
                    }}
                    """.formatted(stackName);
            withTags(request("UpdateStack", stackName).formParam("TemplateBody", failingTemplate),
                    "failed", "attempt")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("Env", "final", "Team", "latest"), tags(stackName));
        } finally {
            request("DeleteStack", stackName).when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void suppliedTagsReplaceStackTagsOnlyWhenTheUpdateExecutes() {
        String stackName = "cfn-update-tags-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {"Parameters":{"Timeout":{"Type":"Number","Default":"30"}},
                 "Resources":{"Queue":{"Type":"AWS::SQS::Queue","Properties":{
                   "QueueName":"%s","VisibilityTimeout":{"Ref":"Timeout"}}}}}
                """.formatted(stackName);
        try {
            withTags(request("CreateStack", stackName).formParam("TemplateBody", template),
                    "before", "original")
                    .when().post("/").then().statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("Env", "before", "Owner", "original"), tags(stackName));

            withTags(updateRequest("UpdateStack", stackName, template, "60"), "after", "new")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("Env", "after", "Team", "new"), tags(stackName));

            updateRequest("UpdateStack", stackName, template, "70")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("Env", "after", "Team", "new"), tags(stackName));

            withTags(updateRequest("CreateChangeSet", stackName, template, "80")
                    .formParam("ChangeSetName", "tag-update")
                    .formParam("ChangeSetType", "UPDATE"), "final", "latest")
                    .when().post("/").then().statusCode(200);
            assertEquals(Map.of("Env", "after", "Team", "new"), tags(stackName));
            executeChangeSet(stackName, "tag-update");
            assertEquals(Map.of("Env", "final", "Team", "latest"), tags(stackName));

            updateRequest("CreateChangeSet", stackName, template, "90")
                    .formParam("ChangeSetName", "untagged-update")
                    .formParam("ChangeSetType", "UPDATE")
                    .when().post("/").then().statusCode(200);
            executeChangeSet(stackName, "untagged-update");
            assertEquals(Map.of("Env", "final", "Team", "latest"), tags(stackName));

            updateRequest("UpdateStack", stackName, template, "100")
                    .formParam("Tags", "")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of(), tags(stackName));

            withTags(updateRequest("UpdateStack", stackName, template, "110"), "reset", "again")
                    .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(Map.of("Env", "reset", "Team", "again"), tags(stackName));

            updateRequest("CreateChangeSet", stackName, template, "120")
                    .formParam("ChangeSetName", "clear-tags")
                    .formParam("ChangeSetType", "UPDATE")
                    .formParam("Tags.member", "")
                    .when().post("/").then().statusCode(200);
            assertEquals(Map.of("Env", "reset", "Team", "again"), tags(stackName));
            executeChangeSet(stackName, "clear-tags");
            assertEquals(Map.of(), tags(stackName));
        } finally {
            request("DeleteStack", stackName).when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    private static RequestSpecification request(String action, String stackName) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("StackName", stackName);
    }

    private static RequestSpecification updateRequest(String action, String stackName,
                                                      String template, String timeout) {
        return request(action, stackName).formParam("TemplateBody", template)
                .formParam("Parameters.member.1.ParameterKey", "Timeout")
                .formParam("Parameters.member.1.ParameterValue", timeout);
    }

    private static RequestSpecification withTags(RequestSpecification request,
                                                 String env, String secondValue) {
        String secondKey = "before".equals(env) ? "Owner" : "Team";
        return request.formParam("Tags.member.1.Key", "Env")
                .formParam("Tags.member.1.Value", env)
                .formParam("Tags.member.2.Key", secondKey)
                .formParam("Tags.member.2.Value", secondValue);
    }

    private static Map<String, String> tags(String stackName) {
        String xml = request("DescribeStacks", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "Key", "Value");
    }

    private static void executeChangeSet(String stackName, String changeSetName) {
        request("ExecuteChangeSet", stackName).formParam("ChangeSetName", changeSetName)
                .when().post("/").then().statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }
}
