package io.floci.aws.core.common;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class AwsQueryControllerIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void missingActionParameterReturns400MissingAction() {
        given()
            .contentType("application/x-www-form-urlencoded")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .contentType("application/xml")
            .body("ErrorResponse.Error.Code", equalTo("MissingAction"))
            .body("ErrorResponse.Error.Message", equalTo("The request must contain the parameter Action"));
    }

    @Test
    void globalClusterActionFallbackWithoutAuthorizationHeader() {
        // Without a credential scope the service is inferred from the action. An action the RDS
        // handler serves but the inference list does not name is dispatched to SQS, which answers
        // UnsupportedOperation for it.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeGlobalClusters")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeGlobalClustersResponse.DescribeGlobalClustersResult.GlobalClusters",
                    equalTo(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DescribeDBClusterSnapshots", "DescribeDBSnapshots",
            "DescribeDBClusterParameterGroups", "DescribeDBClusterParameters", "DescribeOptionGroups",
            "CreateDBClusterParameterGroup", "ModifyDBClusterParameterGroup",
            "DeleteDBClusterParameterGroup", "CreateOptionGroup", "ModifyOptionGroup",
            "DeleteOptionGroup"})
    void everyRdsActionIsInferredWithoutAnAuthorizationHeader(String action) {
        // Whatever each answers — a result or a validation error for the parameters left out here —
        // RDS has to be the one answering. An action the handler serves but the inference list does
        // not name is dispatched to SQS instead.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
        .when()
            .post("/")
        .then()
            .body(not(containsString("sqs.amazonaws.com")))
            .body(not(containsString("is not supported by SQS")));
    }

    @Test
    void ec2ActionFallbackWithoutAuthorizationHeader() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeRegions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeRegionsResponse.regionInfo.item.size()", greaterThan(0));
    }

    @Test
    void sharedActionNameReachesTheServiceItsVersionDeclares() {
        // IAM and ElastiCache both define CreateUser and IAM takes it by name. The Version an
        // ElastiCache client sends is what brings the request to ElastiCache instead.
        String userId = "version-routed-" + UUID.randomUUID().toString().substring(0, 8);
        // Registered first, so the user goes even when an assertion below fails. It asserts
        // nothing, so it stays quiet once the test has deleted the user itself.
        cleanup.register(() -> deleteElastiCacheUser(userId));
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateUser")
            .formParam("Version", "2015-02-02")
            .formParam("UserId", userId)
            .formParam("UserName", userId)
            .formParam("Engine", "redis")
            .formParam("AccessString", "on ~* +@all")
            .formParam("AuthenticationMode.Type", "no-password-required")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateUserResponse.CreateUserResult.UserId", equalTo(userId));

        // DeleteUser is shared the same way.
        deleteElastiCacheUser(userId)
            .statusCode(200)
            .body("DeleteUserResponse.DeleteUserResult.UserId", equalTo(userId));
    }

    @Test
    void sqsPermissionActionReachesSqsUnderItsVersion() {
        // SNS also defines AddPermission and takes it by name, so it is SQS's Version that brings
        // this request to the queue rather than to SNS.
        String queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("Version", "2012-11-05")
            .formParam("QueueName", "version-routed-" + UUID.randomUUID().toString().substring(0, 8))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateQueueResponse.CreateQueueResult.QueueUrl");
        cleanup.register(() -> given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("Version", "2012-11-05")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AddPermission")
            .formParam("Version", "2012-11-05")
            .formParam("QueueUrl", queueUrl)
            .formParam("Label", "granted-under-sqs-version")
            .formParam("AWSAccountId.1", "111122223333")
            .formParam("ActionName.1", "SendMessage")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("Version", "2012-11-05")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "Policy")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("granted-under-sqs-version"));
    }

    private static ValidatableResponse deleteElastiCacheUser(String userId) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteUser")
            .formParam("Version", "2015-02-02")
            .formParam("UserId", userId)
        .when()
            .post("/")
        .then();
    }
}
