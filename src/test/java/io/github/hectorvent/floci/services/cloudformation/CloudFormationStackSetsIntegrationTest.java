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
    void stackSetFromTemplateUrlProvisionsAndReportsDetailedStatus() {
        String setName = "url-set-" + UUID.randomUUID().toString().substring(0, 8);
        String queue = "url-q-" + UUID.randomUUID().toString().substring(0, 8);
        String bucket = "tpl-bucket-" + UUID.randomUUID().toString().substring(0, 8);
        String tkey = "template.json";

        // Stage the template in S3, the way DPS does, then reference it by TemplateURL.
        given().header("Authorization", auth(ADMIN, "s3")).when().put("/" + bucket)
            .then().statusCode(200);
        given().header("Authorization", auth(ADMIN, "s3")).body(queueTemplate(queue))
            .when().put("/" + bucket + "/" + tkey).then().statusCode(200);
        String templateUrl = "http://localhost:4566/" + bucket + "/" + tkey;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackSet")
            .formParam("StackSetName", setName)
            .formParam("TemplateURL", templateUrl)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200).body(containsString("<StackSetId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackInstances")
            .formParam("StackSetName", setName)
            .formParam("Accounts.member.1", ACCOUNT_B)
            .formParam("Regions.member.1", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        // The template fetched from S3 was provisioned into account B.
        assertQueueVisible(ACCOUNT_B, queue);

        // DescribeStackInstance reports the nested StackInstanceStatus.DetailedStatus DPS polls.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackInstance")
            .formParam("StackSetName", setName)
            .formParam("StackInstanceAccount", ACCOUNT_B)
            .formParam("StackInstanceRegion", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<DetailedStatus>SUCCEEDED</DetailedStatus>"));
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
    void createStackSetWithoutTemplateIsRejected() {
        // AWS rejects CreateStackSet when neither TemplateBody nor TemplateURL is supplied; otherwise
        // a later CreateStackInstances would deploy empty stacks into every target account.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackSet")
            .formParam("StackSetName", "set-" + UUID.randomUUID().toString().substring(0, 8))
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("ValidationError"));
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

        // 5b. DescribeStackInstance (singular) returns one target's instance.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackInstance")
            .formParam("StackSetName", setName)
            .formParam("StackInstanceAccount", ACCOUNT_B)
            .formParam("StackInstanceRegion", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Account>" + ACCOUNT_B + "</Account>"))
            .body(containsString("<Region>" + REGION + "</Region>"));

        // 6. ListStackSetOperations records the CREATE operation.
        String operationId = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListStackSetOperations")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Action>CREATE</Action>"))
            .extract().path("ListStackSetOperationsResponse.ListStackSetOperationsResult.Summaries.member.OperationId");

        // 6b. DescribeStackSetOperation returns that operation.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackSetOperation")
            .formParam("StackSetName", setName)
            .formParam("OperationId", operationId)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<OperationId>" + operationId + "</OperationId>"))
            .body(containsString("<Status>SUCCEEDED</Status>"));

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
    void updateStackSetReappliesToExistingInstances() {
        String setName = "upd-" + UUID.randomUUID().toString().substring(0, 8);
        String q1 = "u1-" + UUID.randomUUID().toString().substring(0, 8);
        String q2 = "u2-" + UUID.randomUUID().toString().substring(0, 8);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackSet")
            .formParam("StackSetName", setName)
            .formParam("TemplateBody", queueTemplate(q1))
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackInstances")
            .formParam("StackSetName", setName)
            .formParam("Accounts.member.1", ACCOUNT_B)
            .formParam("Regions.member.1", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        assertQueueVisible(ACCOUNT_B, q1);
        assertQueueAbsent(ACCOUNT_B, q2);

        // Update the template to add a second queue; existing instances are re-applied.
        String twoQueues = """
            {"Resources":{
              "Q1":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s"}},
              "Q2":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s"}}}}
            """.formatted(q1, q2);
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStackSet")
            .formParam("StackSetName", setName)
            .formParam("TemplateBody", twoQueues)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<OperationId>"));

        // The newly added resource now exists in the target account.
        assertQueueVisible(ACCOUNT_B, q2);

        // The operation history records the update.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListStackSetOperations")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Action>UPDATE</Action>"));
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

    @Test
    void deleteStackInstancesWithRetainStacksKeepsUnderlyingResources() {
        String setName = "retain-" + UUID.randomUUID().toString().substring(0, 8);
        String queue = "retain-q-" + UUID.randomUUID().toString().substring(0, 8);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackSet")
            .formParam("StackSetName", setName)
            .formParam("TemplateBody", queueTemplate(queue))
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackInstances")
            .formParam("StackSetName", setName)
            .formParam("Accounts.member.1", ACCOUNT_B)
            .formParam("Regions.member.1", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);
        assertQueueVisible(ACCOUNT_B, queue);

        // RetainStacks=true (the SDK always sends it) detaches the instance but keeps the stack.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStackInstances")
            .formParam("StackSetName", setName)
            .formParam("Accounts.member.1", ACCOUNT_B)
            .formParam("Regions.member.1", REGION)
            .formParam("RetainStacks", "true")
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<OperationId>"));

        // The instance is no longer part of the StackSet...
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListStackInstances")
            .formParam("StackSetName", setName)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(not(containsString("<Account>" + ACCOUNT_B + "</Account>")));

        // ...but its underlying resources were retained in the target account.
        assertQueueVisible(ACCOUNT_B, queue);
    }

    @Test
    void operationReportsFailedWhenAnInstanceRollsBack() {
        String setName = "failset-" + UUID.randomUUID().toString().substring(0, 8);
        // A nested stack with no TemplateURL fails to provision, rolling the instance stack back.
        String badTemplate =
            "{\"Resources\":{\"Nested\":{\"Type\":\"AWS::CloudFormation::Stack\",\"Properties\":{}}}}";

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackSet")
            .formParam("StackSetName", setName)
            .formParam("TemplateBody", badTemplate)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        String operationId = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStackInstances")
            .formParam("StackSetName", setName)
            .formParam("Accounts.member.1", ACCOUNT_B)
            .formParam("Regions.member.1", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateStackInstancesResponse.CreateStackInstancesResult.OperationId");

        // The rolled-back instance is INOPERABLE / FAILED...
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackInstance")
            .formParam("StackSetName", setName)
            .formParam("StackInstanceAccount", ACCOUNT_B)
            .formParam("StackInstanceRegion", REGION)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<DetailedStatus>FAILED</DetailedStatus>"));

        // ...so the operation reports FAILED rather than a false SUCCEEDED.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackSetOperation")
            .formParam("StackSetName", setName)
            .formParam("OperationId", operationId)
            .header("Authorization", auth(ADMIN, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Status>FAILED</Status>"));
    }
}
