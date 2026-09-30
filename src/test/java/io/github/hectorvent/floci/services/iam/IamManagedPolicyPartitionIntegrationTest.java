package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The managed policy catalog under a China signing scope: GetPolicy resolves the China ARN and
 * ListPolicies lists the China catalog, both read-only so the shared emulator needs no cleanup.
 */
@QuarkusTest
class IamManagedPolicyPartitionIntegrationTest {

    private static final String CHINA_IAM = PartitionMatrix.sigV4Auth("cn-north-1", "iam");

    @Test
    void getPolicyResolvesTheChinaManagedPolicyArn() {
        given()
            .header("Authorization", CHINA_IAM)
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", "arn:aws-cn:iam::aws:policy/AdministratorAccess")
        .when().post("/").then().statusCode(200)
            .body(containsString("<Arn>arn:aws-cn:iam::aws:policy/AdministratorAccess</Arn>"))
            .body(containsString("<PolicyName>AdministratorAccess</PolicyName>"));

        given()
            .header("Authorization", CHINA_IAM)
            .formParam("Action", "GetPolicyVersion")
            .formParam("PolicyArn", "arn:aws-cn:iam::aws:policy/AWSLambdaExecute")
            .formParam("VersionId", "v1")
        .when().post("/").then().statusCode(200)
            .body(containsString("arn:aws-cn:logs:"))
            .body(not(containsString("arn:aws:")));
    }

    @Test
    void listPoliciesUnderChinaScopeListsTheChinaCatalog() {
        given()
            .header("Authorization", CHINA_IAM)
            .formParam("Action", "ListPolicies")
            .formParam("Scope", "AWS")
            .formParam("MaxItems", "5")
        .when().post("/").then().statusCode(200)
            .body(containsString("<Arn>arn:aws-cn:iam::aws:policy/"))
            .body(not(containsString("<Arn>arn:aws:iam::aws:policy/")));
    }
}
