package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for cross-account CloudFormation provisioning:
 * <ul>
 *   <li>single-stack resources land in the caller's account namespace; and</li>
 *   <li>StackSet instances materialize resources in each target account's namespace.</li>
 * </ul>
 */
@QuarkusTest
class CloudFormationStackSetsIntegrationTest {

    private static final String ADMIN = "111111111111";
    private static final String ACCOUNT_B = "222222222222";
    private static final String ACCOUNT_C = "333333333333";
    private static final String REGION = "us-east-1";

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String queueTemplate(String queueName) {
        return """
            {"Resources":{"Q":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s"}}}}
            """.formatted(queueName);
    }

    private void assertQueueVisible(String account, String queueName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
            .header("Authorization", auth(account, "sqs"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("/" + account + "/" + queueName));
    }

    private void assertQueueAbsent(String account, String queueName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
            .header("Authorization", auth(account, "sqs"))
        .when().post("/")
        .then().body(containsString("QueueDoesNotExist"));
    }

    @Test
    void singleStackProvisioningLandsInCallerAccount() {
        String queue = "single-" + UUID.randomUUID().toString().substring(0, 8);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "stack-" + queue)
            .formParam("TemplateBody", queueTemplate(queue))
            .header("Authorization", auth(ACCOUNT_B, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        // Resource lands in the caller's account, not the default account.
        assertQueueVisible(ACCOUNT_B, queue);
        assertQueueAbsent("000000000000", queue);
    }

    @Test
    void stackSetProvisionsInstancesIntoTargetAccounts() {
        String setName = "set-" + UUID.randomUUID().toString().substring(0, 8);
        String queue = "ss-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Admin creates the StackSet.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackSet")
            .formParam("StackSetName", setName)
            .formParam("TemplateBody", queueTemplate(queue))
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<StackSetId>"));

        // 2. Create instances into accounts B and C.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackInstances")
            .formParam("StackSetName", setName)
            .formParam("Accounts.member.1", ACCOUNT_B)
            .formParam("Accounts.member.2", ACCOUNT_C)
            .formParam("Regions.member.1", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<OperationId>"));

        // 3. The queue materializes in B and C, but not in the admin or default account.
        assertQueueVisible(ACCOUNT_B, queue);
        assertQueueVisible(ACCOUNT_C, queue);
        assertQueueAbsent(ADMIN, queue);
        assertQueueAbsent("000000000000", queue);

        // 4. DescribeStackSet returns the definition.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackSet")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<StackSetName>" + setName + "</StackSetName>"))
            .body(containsString("<Status>ACTIVE</Status>"));

        // 5. ListStackInstances shows both target accounts.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListStackInstances")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Account>" + ACCOUNT_B + "</Account>"))
            .body(containsString("<Account>" + ACCOUNT_C + "</Account>"));

        // 6. ListStackSetOperations records the CREATE operation.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListStackSetOperations")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Action>CREATE</Action>"));

        // 7. Delete the instances; resources and instance records go away.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStackInstances")
            .formParam("StackSetName", setName)
            .formParam("Accounts.member.1", ACCOUNT_B)
            .formParam("Accounts.member.2", ACCOUNT_C)
            .formParam("Regions.member.1", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<OperationId>"));

        assertQueueAbsent(ACCOUNT_B, queue);
        assertQueueAbsent(ACCOUNT_C, queue);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListStackInstances")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(not(containsString("<Account>" + ACCOUNT_B + "</Account>")));

        // 8. With no instances left, the StackSet can be deleted.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStackSet")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    void stackSetErrorPaths() {
        String setName = "errset-" + UUID.randomUUID().toString().substring(0, 8);
        String queue = "errq-" + UUID.randomUUID().toString().substring(0, 8);

        // Describe a non-existent stack set → StackSetNotFoundException.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackSet")
            .formParam("StackSetName", "does-not-exist-" + setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(404)
            .body(containsString("StackSetNotFoundException"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackSet")
            .formParam("StackSetName", setName)
            .formParam("TemplateBody", queueTemplate(queue))
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        // Duplicate create → NameAlreadyExistsException.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackSet")
            .formParam("StackSetName", setName)
            .formParam("TemplateBody", queueTemplate(queue))
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(409)
            .body(containsString("NameAlreadyExistsException"));

        // Add an instance, then attempt to delete the non-empty set → StackSetNotEmptyException.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackInstances")
            .formParam("StackSetName", setName)
            .formParam("Accounts.member.1", ACCOUNT_B)
            .formParam("Regions.member.1", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStackSet")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(409)
            .body(containsString("StackSetNotEmptyException"));
    }
}
