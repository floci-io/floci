package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CloudFormationChangeSetArnIntegrationTest {

    private static final String TEMPLATE = "Resources:\n  Queue:\n    Type: AWS::SQS::Queue\n";

    private final List<String> stacksToDelete = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteStacks() {
        for (String stackName : stacksToDelete) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteStack")
                    .formParam("StackName", stackName)
            .when().post("/");
        }
    }

    @Test
    void changeSetArnWorksWithoutStackNameForDescribeExecuteAndDelete() {
        String firstStack = newStackName("first");
        String secondStack = newStackName("second");
        String firstArn = createChangeSet(firstStack, "shared-name");
        String secondArn = createChangeSet(secondStack, "shared-name");

        request("DescribeChangeSet", secondArn).then().statusCode(200)
                .body(containsString("<StackName>" + secondStack + "</StackName>"))
                .body(containsString("<ChangeSetId>" + secondArn + "</ChangeSetId>"));

        // An ARN's UUID must be checked too; a matching short name on another stack is not enough.
        request("DescribeChangeSet", firstArn + "-wrong").then().statusCode(400)
                .body(containsString("<Code>ChangeSetNotFoundException</Code>"));
        request("DescribeChangeSet", firstArn.replace(":000000000000:", ":000000000001:"))
                .then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"));
        request("DescribeChangeSet", firstArn.replace(":us-east-1:", ":us-west-2:"))
                .then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"));

        request("ExecuteChangeSet", secondArn).then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(secondStack).status());
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", secondStack)
        .when().post("/")
        .then().statusCode(200)
                .body(containsString("<LogicalResourceId>Queue</LogicalResourceId>"))
                .body(containsString("<ResourceStatus>CREATE_COMPLETE</ResourceStatus>"));
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", firstStack)
        .when().post("/")
        .then().statusCode(200)
                .body(containsString("<StackStatus>REVIEW_IN_PROGRESS</StackStatus>"));
        request("DeleteChangeSet", firstArn).then().statusCode(200);

        request("DescribeChangeSet", firstArn).then().statusCode(400)
                .body(containsString("<Code>ChangeSetNotFoundException</Code>"));
        request("DescribeChangeSet", secondArn).then().statusCode(200)
                .body(containsString("<StackName>" + secondStack + "</StackName>"));
    }

    @Test
    void shortChangeSetNameStillRequiresStackNameAndExplicitStackNameStillWorks() {
        String stackName = newStackName("named");
        String arn = createChangeSet(stackName, "named-change-set");

        request("DescribeChangeSet", "named-change-set").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeChangeSet")
                .formParam("StackName", stackName)
                .formParam("ChangeSetName", arn)
        .when().post("/")
        .then().statusCode(200)
                .body(containsString("<ChangeSetId>" + arn + "</ChangeSetId>"));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeChangeSet")
                .formParam("StackName", stackName)
                .formParam("ChangeSetName", arn + "-wrong")
        .when().post("/")
        .then().statusCode(400)
                .body(containsString("<Code>ChangeSetNotFoundException</Code>"));
    }

    private String newStackName(String suffix) {
        String name = "cfn-cs-arn-" + suffix + "-" + Long.toString(System.nanoTime(), 36);
        stacksToDelete.add(name);
        return name;
    }

    private static String createChangeSet(String stackName, String changeSetName) {
        String xml = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateChangeSet")
                .formParam("StackName", stackName)
                .formParam("ChangeSetName", changeSetName)
                .formParam("ChangeSetType", "CREATE")
                .formParam("TemplateBody", TEMPLATE)
        .when().post("/")
        .then().statusCode(200)
        .extract().asString();
        String changeSetArn = XmlParser.extractFirst(xml, "Id", null);
        assertNotNull(changeSetArn, xml);
        return changeSetArn;
    }

    private static Response request(String action, String changeSetArn) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("ChangeSetName", changeSetArn)
        .when().post("/");
    }
}
