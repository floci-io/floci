package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * UpdateStack and CreateChangeSet with {@code UsePreviousTemplate=true} reuse the template the
 * stack currently holds instead of falling back to an empty one.
 */
@QuarkusTest
class CloudFormationUsePreviousTemplateIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String TEMPLATE = """
            {"Parameters":{"Timeout":{"Type":"Number","Default":"30"}},
             "Resources":{"Queue":{"Type":"AWS::SQS::Queue",
               "Properties":{"QueueName":"%s","VisibilityTimeout":{"Ref":"Timeout"}}}}}
            """;

    private void createStack(String stackName, String queueName) {
        given().contentType(FORM)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", TEMPLATE.formatted(queueName))
        .when().post("/")
        .then().statusCode(200);
    }

    private String visibilityTimeout(String queueName) {
        Response url = given().contentType(FORM)
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
        .when().post("/");
        String queueUrl = url.xmlPath().getString("GetQueueUrlResponse.GetQueueUrlResult.QueueUrl");
        return given().contentType(FORM)
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "VisibilityTimeout")
        .when().post("/")
        .xmlPath().getString("GetQueueAttributesResponse.GetQueueAttributesResult.Attribute.Value");
    }

    @Test
    void updateStack_usePreviousTemplate_appliesNewParametersAndKeepsTemplate() {
        createStack("use-prev-update", "use-prev-update-q");

        given().contentType(FORM)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", "use-prev-update")
            .formParam("UsePreviousTemplate", "true")
            .formParam("Parameters.member.1.ParameterKey", "Timeout")
            .formParam("Parameters.member.1.ParameterValue", "60")
        .when().post("/")
        .then().statusCode(200);

        assertEquals("60", visibilityTimeout("use-prev-update-q"));

        given().contentType(FORM)
            .formParam("Action", "GetTemplate")
            .formParam("StackName", "use-prev-update")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("use-prev-update-q"))
            .body(containsString("VisibilityTimeout"));
    }

    @Test
    void updateStack_usePreviousTemplateOnSamStack_keepsSubmittedTemplateAsOriginal() {
        String samTemplate = """
                {"Transform":"AWS::Serverless-2016-10-31",
                 "Parameters":{"Memory":{"Type":"Number","Default":"128"}},
                 "Resources":{"Fn":{"Type":"AWS::Serverless::Function",
                   "Properties":{"Handler":"index.handler","Runtime":"nodejs22.x",
                     "InlineCode":"exports.handler = async () => {};",
                     "MemorySize":{"Ref":"Memory"}}}}}
                """;
        given().contentType(FORM)
            .formParam("Action", "CreateStack")
            .formParam("StackName", "use-prev-sam")
            .formParam("TemplateBody", samTemplate)
        .when().post("/")
        .then().statusCode(200);

        given().contentType(FORM)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", "use-prev-sam")
            .formParam("UsePreviousTemplate", "true")
            .formParam("Parameters.member.1.ParameterKey", "Memory")
            .formParam("Parameters.member.1.ParameterValue", "256")
        .when().post("/")
        .then().statusCode(200);

        given().contentType(FORM)
            .formParam("Action", "GetTemplate")
            .formParam("StackName", "use-prev-sam")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("AWS::Serverless::Function"))
            .body(not(containsString("AWS::Lambda::Function")));
    }

    @Test
    void createChangeSet_usePreviousTemplate_previewsModifyNotRemove() {
        createStack("use-prev-cs", "use-prev-cs-q");

        given().contentType(FORM)
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", "use-prev-cs")
            .formParam("ChangeSetName", "parameter-only")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("UsePreviousTemplate", "true")
            .formParam("Parameters.member.1.ParameterKey", "Timeout")
            .formParam("Parameters.member.1.ParameterValue", "60")
        .when().post("/")
        .then().statusCode(200);

        given().contentType(FORM)
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", "use-prev-cs")
            .formParam("ChangeSetName", "parameter-only")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Action>Modify</Action>"))
            .body(not(containsString("<Action>Remove</Action>")));
    }

    @Test
    void updateStack_usePreviousTemplateWithTemplateBody_returnsValidationError() {
        createStack("use-prev-both", "use-prev-both-q");

        given().contentType(FORM)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", "use-prev-both")
            .formParam("UsePreviousTemplate", "true")
            .formParam("TemplateBody", TEMPLATE.formatted("use-prev-both-q"))
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"));
    }

    @Test
    void updateStack_withoutAnyTemplateSource_returnsValidationError() {
        createStack("use-prev-none", "use-prev-none-q");

        given().contentType(FORM)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", "use-prev-none")
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Either Template URL or Template Body must be specified."));
    }

    @Test
    void createChangeSet_usePreviousTemplateOnCreateType_returnsValidationError() {
        given().contentType(FORM)
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", "use-prev-create-type")
            .formParam("ChangeSetName", "cs")
            .formParam("ChangeSetType", "CREATE")
            .formParam("UsePreviousTemplate", "true")
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"));
    }

    @Test
    void updateStack_usePreviousTemplateOnMissingStack_returnsValidationError() {
        given().contentType(FORM)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", "use-prev-missing")
            .formParam("UsePreviousTemplate", "true")
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("does not exist"));
    }
}
