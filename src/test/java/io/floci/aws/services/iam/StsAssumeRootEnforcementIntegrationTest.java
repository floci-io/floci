package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.OrganizationsRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * What a privileged session may do, under IAM enforcement. The session is the member account's
 * root user, which no identity policy grants or denies, so the whole of the bound is the task
 * policy the request named: "you must define TaskPolicyArn to scope the session to the privileged
 * action to be performed during the session".
 *
 * <p>{@code IAMAuditRootUserCredentials} is the one used here. It denies every action it does not
 * name and then denies the ones it does on any resource but {@code arn:aws:iam::*:root}, so one
 * policy shows both halves of the scope: the audit reads it allows on the root user, and
 * everything else refused.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class StsAssumeRootEnforcementIntegrationTest {

    private static final String MANAGEMENT = "777777777771";
    private static final String TASK_POLICY = "arn:aws:iam::aws:policy/root-task/IAMAuditRootUserCredentials";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aPrivilegedSessionMayAuditTheRootUserAndNothingElse() {
        String member = memberAccountWithRootSessions();
        ExtractableResponse<Response> session = privilegedSession(member, callerWithAssumeRoot());
        String accessKeyId = session.path("AssumeRootResponse.AssumeRootResult.Credentials.AccessKeyId");
        String sessionToken = session.path("AssumeRootResponse.AssumeRootResult.Credentials.SessionToken");

        // Allowed: the policy's first statement denies every action it does not name, and the
        // account summary is one it names, on no particular resource.
        privileged(accessKeyId, sessionToken, "GetAccountSummary")
                .when().post("/").then().statusCode(200);

        // Denied on any resource but the root user, which is the policy's second statement. The
        // audit reads it allows with no UserName name that root user here, since a root caller's
        // self-resource is its root ARN, but Floci keeps no user record for the root user, so
        // those calls answer NoSuchEntity rather than the credentials report AWS gives.
        privileged(accessKeyId, sessionToken, "GetUser")
                .formParam("UserName", "someone-else")
                .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        // And denied outright for an action the policy does not name, which is its first.
        privileged(accessKeyId, sessionToken, "CreateUser")
                .formParam("UserName", "made-by-a-privileged-session")
                .when().post("/").then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    /** An organization with one member account and {@code RootSessions} enabled. */
    private String memberAccountWithRootSessions() {
        OrganizationsRequests.request(MANAGEMENT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .when().post("/").then().statusCode(200);
        String member = OrganizationsRequests.request(MANAGEMENT, "CreateAccount",
                "{\"Email\":\"audit@example.com\",\"AccountName\":\"Audit\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("CreateAccountStatus.AccountId");
        OrganizationsRequests.request(MANAGEMENT, "EnableAWSServiceAccess",
                "{\"ServicePrincipal\":\"iam.amazonaws.com\"}")
                .when().post("/").then().statusCode(200);
        given().header("Authorization", EnforcementFixtures.auth(MANAGEMENT, "iam"))
                .formParam("Action", "EnableOrganizationsRootSessions")
                .formParam("Version", "2010-05-08")
                .when().post("/").then().statusCode(200);
        return member;
    }

    /**
     * An IAM user of the management account allowed {@code sts:AssumeRoot}, which is the caller the
     * guide's prerequisites ask for. The account root cannot be it, AssumeRoot refusing root
     * credentials, so under enforcement the permission is granted to somebody rather than
     * inherited from the account's own allow-all.
     */
    private String callerWithAssumeRoot() {
        String userName = "root-session-caller-" + UUID.randomUUID();
        String authorization = EnforcementFixtures.auth(MANAGEMENT, "iam");
        String accessKeyId = EnforcementFixtures
                .createUser(cleanup, baseUri.getPort(), authorization, userName).accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), authorization, userName, "assume-root",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"sts:AssumeRoot\",\"Resource\":\"*\"}]}");
        return accessKeyId;
    }

    private ExtractableResponse<Response> privilegedSession(String member, String callerKey) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(callerKey, "sts"))
                .formParam("Action", "AssumeRoot")
                .formParam("Version", "2011-06-15")
                .formParam("TargetPrincipal", member)
                .formParam("TaskPolicyArn.arn", TASK_POLICY)
                .when().post("/").then().statusCode(200).extract();
    }

    private static RequestSpecification privileged(String accessKeyId, String sessionToken, String action) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(accessKeyId, "iam"))
                .header("X-Amz-Security-Token", sessionToken)
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
    }
}
