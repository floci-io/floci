package io.github.hectorvent.floci.services.iam;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Verifies the AWS-managed {@code SecurityAudit} policy is resolvable and
 * attachable. {@code SecurityAudit} is a standard AWS managed policy commonly
 * attached to read-only auditing roles; {@code AttachRolePolicy} fails if the
 * managed policy is not present in the emulator's seed list.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityAuditManagedPolicyIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static final String SECURITY_AUDIT_ARN =
            "arn:aws:iam::aws:policy/SecurityAudit";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @Test
    @Order(1)
    void getSecurityAuditManagedPolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", SECURITY_AUDIT_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName", equalTo("SecurityAudit"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(SECURITY_AUDIT_ARN));
    }

    @Test
    @Order(2)
    void attachSecurityAuditToRole() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "SecurityAuditTestRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "SecurityAuditTestRole")
            .formParam("PolicyArn", SECURITY_AUDIT_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
