package io.floci.aws.services.iam;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.testing.PartnerPermissionBoundaryEnforcementProfile;
import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * What authorization is told about the boundary a delegated role write will apply.
 *
 * <p>{@code iam:PermissionsBoundary} is the key a policy conditions on to say which boundary a
 * principal may be created with, and the enforcement filter reads it from the request before the
 * write runs. A role created under a delegation carries the cap the delegation registered rather
 * than whatever the call named, so the key has to carry that same value or a policy written about
 * it would judge a boundary the role never ends up with.
 *
 * <p>The caller here is a delegated session, which acts as the identity that approved the request,
 * so the approver's own policy is what authorizes the write and what the condition is read against.
 */
@QuarkusTest
@TestProfile(PartnerPermissionBoundaryEnforcementProfile.class)
class DelegationRoleBoundaryEnforcementIntegrationTest {

    private static final String CUSTOMER = "222233334444";
    private static final String PARTNER = "000000000001";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000001:partner-notifications";
    private static final String TEMPLATE = "arn:aws:iam::partner:policy/template/example";
    private static final String BOUNDARY =
            "arn:aws:iam::partner:policy/permission_boundary/example.com/ReadOnlyAccess_20261101";
    private static final String OTHER_BOUNDARY = "arn:aws:iam::222233334444:policy/SomethingElse";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String DELEGATION_ACCESS = """
            {"Effect":"Allow","Action":["iam:AssociateDelegationRequest","iam:AcceptDelegationRequest",
              "iam:SendDelegationToken"],"Resource":"*"}""";

    @Inject
    DelegationRequestService delegationRequests;

    @Test
    void aPolicyConditionedOnTheRegisteredCapAllowsTheRoleItCaps() {
        S3RequestSigner approver = approverAllowedToCreateRolesBoundedBy(BOUNDARY);
        String[] credentials = delegatedCredentials(approvedBy(approver));

        // No PermissionsBoundary member: the cap comes from the delegation, and the condition is
        // told that value, so the approver's policy allows the write.
        createRole(credentials).statusCode(200)
                .body("CreateRoleResponse.CreateRoleResult.Role.PermissionsBoundary"
                        + ".PermissionsBoundaryArn", equalTo(BOUNDARY));
    }

    @Test
    void aPolicyConditionedOnAnotherBoundaryRefusesItEvenWhenTheCallNamesThatOne() {
        S3RequestSigner approver = approverAllowedToCreateRolesBoundedBy(OTHER_BOUNDARY);
        String[] credentials = delegatedCredentials(approvedBy(approver));

        // The call names the boundary the approver's policy allows, and the delegation's cap is
        // what the role will carry instead, so the key carries the cap and the condition fails.
        // Naming it is what makes this a guard: were the key still read from the request member,
        // the condition would match the name and the write would be authorized for a boundary the
        // role never ends up with.
        createRole(credentials, OTHER_BOUNDARY).statusCode(403);
    }

    private static ValidatableResponse createRole(String[] credentials) {
        return createRole(credentials, null);
    }

    /** The same write, naming {@code boundaryArn} when it is not null, which the cap overrides. */
    private static ValidatableResponse createRole(String[] credentials, String boundaryArn) {
        RequestSpecification request = given().header("Authorization", auth(credentials[0], "iam"))
                .header("X-Amz-Security-Token", credentials[2])
                .formParam("Action", "CreateRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", "delegated-" + UUID.randomUUID().toString().substring(0, 8))
                .formParam("AssumeRolePolicyDocument", TRUST);
        if (boundaryArn != null) {
            request = request.formParam("PermissionsBoundary", boundaryArn);
        }
        return request.when().post("/").then();
    }

    /** A customer identity that may create a role only when its boundary is {@code boundaryArn}. */
    private static S3RequestSigner approverAllowedToCreateRolesBoundedBy(String boundaryArn) {
        String userName = "approver-" + UUID.randomUUID().toString().substring(0, 8);
        String policy = """
                {"Version":"2012-10-17","Statement":[%s,
                  {"Effect":"Allow","Action":"iam:CreateRole","Resource":"*",
                   "Condition":{"StringEquals":{"iam:PermissionsBoundary":"%s"}}}]}"""
                .formatted(DELEGATION_ACCESS, boundaryArn);
        return userWithPolicy(userName, policy);
    }

    private String approvedBy(S3RequestSigner approver) {
        String id = partnerCreate();
        byUser(approver, "AssociateDelegationRequest", id);
        byUser(approver, "AcceptDelegationRequest", id);
        byUser(approver, "SendDelegationToken", id);
        return id;
    }

    /** The access key, secret and session token the partner gets for the released token. */
    private String[] delegatedCredentials(String id) {
        String token = RequestScopes.callAs(CUSTOMER,
                () -> delegationRequests.get(CUSTOMER, id).getExchangeToken());
        ExtractableResponse<Response> exchanged = given().header("Authorization", auth(PARTNER, "sts"))
                .formParam("Action", "GetDelegatedAccessToken")
                .formParam("Version", "2011-06-15")
                .formParam("TradeInToken", token)
            .when().post("/").then().statusCode(200).extract();
        String result = "GetDelegatedAccessTokenResponse.GetDelegatedAccessTokenResult.Credentials.";
        return new String[] {
            exchanged.path(result + "AccessKeyId"),
            exchanged.path(result + "SecretAccessKey"),
            exchanged.path(result + "SessionToken"),
        };
    }

    private static String partnerCreate() {
        return given().header("Authorization", auth(PARTNER, "iam"))
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Version", "2010-05-08")
                .formParam("Description", "Set up the integration")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", "workflow-" + Long.toString(System.nanoTime(), 36))
                .formParam("SessionDuration", "3600")
                .formParam("OwnerAccountId", CUSTOMER)
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
            .when().post("/").then().statusCode(200)
                .extract().path("CreateDelegationRequestResponse.CreateDelegationRequestResult"
                        + ".DelegationRequestId");
    }

    private static S3RequestSigner userWithPolicy(String userName, String policy) {
        iam("CreateUser", "UserName", userName);
        iam("PutUserPolicy", "UserName", userName, "PolicyName", "inline", "PolicyDocument", policy);
        ExtractableResponse<Response> key = given().formParam("Action", "CreateAccessKey")
                .formParam("UserName", userName)
                .header("Authorization", auth(CUSTOMER, "iam")).when().post("/")
                .then().statusCode(200).extract();
        return S3RequestSigner.signedAs(
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"));
    }

    private static void byUser(S3RequestSigner caller, String action, String id) {
        given().filter(caller).formParam("Action", action).formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", id).when().post("/").then().statusCode(200);
    }

    private static void iam(String action, String... params) {
        RequestSpecification request = given().formParam("Action", action)
                .header("Authorization", auth(CUSTOMER, "iam"));
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261002/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
