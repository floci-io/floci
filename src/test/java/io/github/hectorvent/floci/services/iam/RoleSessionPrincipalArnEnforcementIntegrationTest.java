package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code iam.enforcement-enabled=true}, a role session's {@code aws:PrincipalArn} is the ARN of
 * the role that was assumed, path included, not the session's ARN (IAM User Guide, global condition
 * keys, {@code aws:PrincipalArn}: "For IAM roles, the request context returns the ARN of the role").
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RoleSessionPrincipalArnEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";

    private record Session(String accessKeyId, String sessionToken) {
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRoleSessionsPrincipalArnIsItsRolesArnWithItsPath() {
        String role = "principal-arn-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/team/" + role;
        String sessionArn = "arn:aws:sts::" + ACCOUNT + ":assumed-role/" + role + "/s";
        createRole(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*",
                   "Condition":{"ArnEquals":{"aws:PrincipalArn":"%1$s"}}},
                  {"Effect":"Allow","Action":"iam:ListRoles","Resource":"*",
                   "Condition":{"ArnEquals":{"aws:PrincipalArn":"%2$s"}}},
                  {"Effect":"Allow","Action":"iam:ListGroups","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:ListGroups","Resource":"*",
                   "Condition":{"ArnEquals":{"aws:PrincipalArn":"%1$s"}}}]}""".formatted(roleArn, sessionArn));
        Session session = assumeRole(roleArn);

        // A condition on the role's own ARN matches the session.
        iamCall(session, "ListUsers").then().statusCode(200);
        // A condition on the session ARN does not: that is not what the key carries.
        iamCall(session, "ListRoles").then().statusCode(403);
        // A Deny keyed on the role's ARN fires for its sessions.
        iamCall(session, "ListGroups").then().statusCode(403);
    }

    @Test
    void theSecondResourceAnOperationChecksSeesTheRolesArnToo() {
        // GetParameter on a Secrets Manager reference also needs secretsmanager:GetSecretValue on
        // the secret, which the filter checks as a second resource of the same request.
        String secret = "principal-arn-" + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", auth(ACCOUNT, "secretsmanager"))
                .header("X-Amz-Target", "secretsmanager.CreateSecret")
                .contentType("application/x-amz-json-1.1")
                .body("{\"Name\": \"" + secret + "\", \"SecretString\": \"secret-value\"}")
                .when().post("/").then().statusCode(200);
        String role = "principal-arn-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/team/" + role;
        createRole(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:GetParameter","Resource":"*"},
                  {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"*",
                   "Condition":{"ArnEquals":{"aws:PrincipalArn":"%s"}}}]}""".formatted(roleArn));
        Session session = assumeRole(roleArn);

        given().header("Authorization", auth(session.accessKeyId(), "ssm"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .header("X-Amz-Target", "AmazonSSM.GetParameter")
                .contentType("application/x-amz-json-1.1")
                .body("{\"Name\": \"/aws/reference/secretsmanager/" + secret + "\", \"WithDecryption\": true}")
                .when().post("/")
                .then().statusCode(200)
                .body("Parameter.Value", equalTo("secret-value"));
    }

    /** A role under /team/ that its own account may assume, with {@code policy} inline. */
    private static void createRole(String role, String policy) {
        given().formParam("Action", "CreateRole").formParam("RoleName", role).formParam("Path", "/team/")
                .formParam("AssumeRolePolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole"}]}""".formatted(ACCOUNT))
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        given().formParam("Action", "PutRolePolicy").formParam("RoleName", role).formParam("PolicyName", "p")
                .formParam("PolicyDocument", policy)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200);
    }

    private static Session assumeRole(String roleArn) {
        Response response = given().formParam("Action", "AssumeRole").formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "s")
                .header("Authorization", auth(ACCOUNT, "sts")).when().post("/");
        response.then().statusCode(200);
        return new Session(response.path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId"),
                response.path("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken"));
    }

    private static Response iamCall(Session session, String action) {
        return given().formParam("Action", action)
                .header("Authorization", auth(session.accessKeyId(), "iam"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .when().post("/");
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261001/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
