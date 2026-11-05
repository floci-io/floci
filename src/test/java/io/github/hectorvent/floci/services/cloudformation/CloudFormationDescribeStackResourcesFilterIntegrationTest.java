package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The two filters {@code DescribeStackResources} accepts.
 *
 * <p>Ignoring them is quiet rather than loud: a caller that filters and reads the first member
 * gets whichever resource the stack happens to list first, and nothing in the response says the
 * filter was dropped. Serverless Framework resolves an AppSync API id exactly that way, which is
 * how this surfaced (issue #5451).
 */
@QuarkusTest
class CloudFormationDescribeStackResourcesFilterIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    private String createStack(String suffix) {
        return createStack("cfn-dsr-" + suffix, suffix);
    }

    /** Stack name and resource names chosen separately, so two stacks can own the same physical id. */
    private String createStack(String stackName, String suffix) {
        String template = """
                {
                  "Resources": {
                    "MyQueue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "cfn-dsr-q-%s"}},
                    "MyTopic": {"Type": "AWS::SNS::Topic", "Properties": {"TopicName": "cfn-dsr-t-%s"}}
                  }
                }
                """.formatted(suffix, suffix);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        return stackName;
    }

    private RequestSpecification describe() {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResources");
    }

    @Test
    void logicalResourceIdNarrowsToThatOneResource() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);

        describe()
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", "MyTopic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyTopic</LogicalResourceId>"))
            .body(not(containsString("<LogicalResourceId>MyQueue</LogicalResourceId>")))
            // AWS stamps the stack's own name on every member.
            .body(containsString("<StackName>" + stackName + "</StackName>"));
    }

    @Test
    void aLogicalResourceIdTheStackDoesNotHaveMatchesNothing() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);

        // A filter that matches nothing, not an error: DescribeStackResource, the singular action
        // where the resource is required rather than optional, is the one that raises for a miss.
        describe()
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", "NoSuchResource")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<LogicalResourceId>")));
    }

    @Test
    void physicalResourceIdNamesTheStackAndReturnsAllOfIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);
        String topicName = "cfn-dsr-t-" + suffix;

        // "the associated resources of the stack that the resource belongs to are returned": the
        // physical id selects the stack, it does not narrow it to the one resource.
        describe()
            .formParam("PhysicalResourceId", "arn:aws:sns:us-east-1:000000000000:" + topicName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyTopic</LogicalResourceId>"))
            .body(containsString("<LogicalResourceId>MyQueue</LogicalResourceId>"))
            .body(containsString("<StackName>" + stackName + "</StackName>"));
    }

    @Test
    void bothStackNameAndPhysicalResourceIdIsAValidationError() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);

        describe()
            .formParam("StackName", stackName)
            .formParam("PhysicalResourceId", "arn:aws:sns:us-east-1:000000000000:cfn-dsr-t-" + suffix)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("Cannot specify both StackName and PhysicalResourceId."));
    }

    @Test
    void aRedeployedPhysicalIdResolvesToTheLiveStackNotTheDeletedOne() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String topicArn = "arn:aws:sns:us-east-1:000000000000:cfn-dsr-t-" + suffix;
        String dead = createStack("cfn-dsr-old-" + suffix, suffix);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", dead)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // DeleteStack answers before the deletion runs. Without this the old stack can still be
        // DELETE_IN_PROGRESS when the new one is created, which sorts it with the live stacks and
        // lets the test pass on creation time alone without ever exercising the deleted branch.
        CfnStackWaits.awaitStackDeleted(dead);

        // The same resource names under a second, live stack. A deleted stack stays listed for its
        // retention window and listStacks answers oldest first, so the dead one would win on a
        // straight walk. The two stacks are named differently so the answer is tellable apart.
        String live = createStack("cfn-dsr-new-" + suffix, suffix);

        describe()
            .formParam("PhysicalResourceId", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackName>" + live + "</StackName>"))
            .body(not(containsString("<StackName>" + dead + "</StackName>")));
    }

    @Test
    void neitherStackNameNorPhysicalResourceIdIsAValidationError() {
        describe()
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("Either StackName or PhysicalResourceId must be specified."));
    }

    @Test
    void withoutAFilterEveryResourceStillComesBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);

        describe()
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyQueue</LogicalResourceId>"))
            .body(containsString("<LogicalResourceId>MyTopic</LogicalResourceId>"));
    }
}
