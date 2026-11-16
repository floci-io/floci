package io.floci.aws.services.iam;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.PartnerPermissionBoundaryProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The long-term half of temporary delegation: the partner guide says long-term permissions "limit
 * the permissions of any roles that are created or managed through temporary access" and are
 * "implemented as IAM permissions boundaries", registered for the partner out of band, by email,
 * with no API at all.
 *
 * <p>So the boundaries come from configuration here, keyed by the partner account AWS registered
 * them for, and the chain is the one AWS describes: a request a registered partner files reports
 * the boundaries on itself, which is where {@code RolePermissionRestrictionArns} comes from on AWS
 * rather than from any input, the credentials exchanged for that request's token carry them, and a
 * role created with those credentials is bounded by one. Without a registration nothing carries
 * anything, which is the partner that never onboarded.
 */
@QuarkusTest
@TestProfile(PartnerPermissionBoundaryProfile.class)
class DelegationRoleBoundaryIntegrationTest {

    /** One double quote, so a template can be written without escaping every quote twice. */
    private static final String Q = "\"";

    private static final String CUSTOMER = "222233334444";
    private static final String PARTNER = "000000000001";
    private static final String UNREGISTERED_PARTNER = "000000000002";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000001:partner-notifications";
    private static final String TEMPLATE = "arn:aws:iam::partner:policy/template/example";
    private static final String BOUNDARY =
            "arn:aws:iam::partner:policy/permission_boundary/example.com/ReadOnlyAccess_20261101";
    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String ALLOW_NOTHING = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Action":"*","Resource":"*"}]}""";
    private static final String TRUST_JSON =
            "{" + Q + "Version" + Q + ":" + Q + "2012-10-17" + Q + ","
            + Q + "Statement" + Q + ":[{" + Q + "Effect" + Q + ":" + Q + "Allow" + Q + ","
            + Q + "Principal" + Q + ":{" + Q + "Service" + Q + ":" + Q + "ec2.amazonaws.com" + Q + "},"
            + Q + "Action" + Q + ":" + Q + "sts:AssumeRole" + Q + "}]}";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    @Inject
    DelegationRequestService delegationRequests;

    @Inject
    IamService iamService;

    @Test
    void aRequestFiledByARegisteredPartnerReportsTheBoundariesRegisteredForIt() {
        String id = partnerCreate(PARTNER);

        assertEquals(List.of(BOUNDARY), RequestScopes.callAs(CUSTOMER,
                () -> delegationRequests.get(CUSTOMER, id).getRolePermissionRestrictionArns()));
    }

    @Test
    void aRequestFiledByAPartnerWithNoRegistrationReportsNone() {
        String id = partnerCreate(UNREGISTERED_PARTNER);

        assertEquals(List.of(), RequestScopes.callAs(CUSTOMER,
                () -> delegationRequests.get(CUSTOMER, id).getRolePermissionRestrictionArns()));
    }

    @Test
    void aRoleCreatedWithDelegatedCredentialsCarriesTheRegisteredBoundary() {
        String id = approved(partnerCreate(PARTNER));
        String[] credentials = delegatedCredentials(PARTNER, id);

        // The session is where the cap travels: the request named it and the exchange carried it.
        assertEquals(Optional.of(BOUNDARY), RequestScopes.callAs(CUSTOMER,
                () -> iamService.delegatedRoleBoundaryArn(credentials[0])));

        given().header("Authorization", sessionAuth(credentials[0]))
                .header("X-Amz-Security-Token", credentials[2])
                .formParam("Action", "CreateRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", unique("delegated"))
                .formParam("AssumeRolePolicyDocument", TRUST)
            .when().post("/").then().statusCode(200)
                .body("CreateRoleResponse.CreateRoleResult.Role.PermissionsBoundary"
                        + ".PermissionsBoundaryArn", equalTo(BOUNDARY));
    }

    @Test
    void aRoleCreatedByAnyoneElseCarriesOnlyTheBoundaryTheCallAsksFor() {
        given().header("Authorization", auth(CUSTOMER, "iam"))
                .formParam("Action", "CreateRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", unique("undelegated"))
                .formParam("AssumeRolePolicyDocument", TRUST)
            .when().post("/").then().statusCode(200)
                .body(not(containsString("PermissionsBoundary")));
    }


    @Test
    void theCapCannotBeLiftedWithTheCredentialsThatSetIt() {
        String id = approved(partnerCreate(PARTNER));
        String[] credentials = delegatedCredentials(PARTNER, id);
        String roleName = createdRole(credentials);
        String other = managedPolicy();

        // The guide limits roles "created or managed through temporary access", and a registered
        // boundary is immutable, so these credentials cannot move the cap they were given.
        delegated(credentials, "PutRolePermissionsBoundary", "RoleName", roleName,
                "PermissionsBoundary", other).statusCode(403)
                .body(CODE, equalTo("AccessDenied"));
        delegated(credentials, "DeleteRolePermissionsBoundary", "RoleName", roleName)
                .statusCode(403).body(CODE, equalTo("AccessDenied"));

        // And the role still carries it.
        delegated(credentials, "GetRole", "RoleName", roleName).statusCode(200)
                .body("GetRoleResponse.GetRoleResult.Role.PermissionsBoundary.PermissionsBoundaryArn",
                        equalTo(BOUNDARY));
    }

    @Test
    void theRegisteredCapWinsOverABoundaryTheCallNames() {
        String id = approved(partnerCreate(PARTNER));
        String[] credentials = delegatedCredentials(PARTNER, id);
        String other = managedPolicy();

        delegated(credentials, "CreateRole",
                "RoleName", unique("delegated-other"),
                "AssumeRolePolicyDocument", TRUST,
                "PermissionsBoundary", other).statusCode(200)
                .body("CreateRoleResponse.CreateRoleResult.Role.PermissionsBoundary"
                        + ".PermissionsBoundaryArn", equalTo(BOUNDARY));
    }

    @Test
    void aRegisteredBoundaryIsAPolicyNowhere() {
        // It caps and does nothing else: no read, no attach and no version of it exists, so no
        // document stored under its ARN can shadow the registration either.
        String userName = unique("boundary-reader");
        byCustomer2("CreateUser", "UserName", userName);

        byCustomer2Expecting("GetPolicy", 404, "PolicyArn", BOUNDARY);
        byCustomer2Expecting("AttachUserPolicy", 404, "UserName", userName, "PolicyArn", BOUNDARY);
        // An identity document, so the write's own reading of the document cannot answer first:
        // what is under test is that the ARN resolves to no policy of this account.
        byCustomer2Expecting("CreatePolicyVersion", 404, "PolicyArn", BOUNDARY,
                "PolicyDocument", ALLOW_NOTHING, "SetAsDefault", "true");

        byCustomer2("DeleteUser", "UserName", userName);
    }

    /** A managed policy of the customer's own, for the cases that need a second boundary ARN. */
    private String managedPolicy() {
        return given().header("Authorization", auth(CUSTOMER, "iam"))
                .formParam("Action", "CreatePolicy")
                .formParam("Version", "2010-05-08")
                .formParam("PolicyName", unique("other-boundary"))
                .formParam("PolicyDocument", ALLOW_NOTHING)
            .when().post("/").then().statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    /** A role created with the delegated credentials, which the cap is applied to. */
    private String createdRole(String[] credentials) {
        String roleName = unique("delegated");
        delegated(credentials, "CreateRole", "RoleName", roleName,
                "AssumeRolePolicyDocument", TRUST).statusCode(200);
        return roleName;
    }

    private static ValidatableResponse delegated(String[] credentials,
                                                                        String action,
                                                                        String... params) {
        RequestSpecification request = given().header("Authorization", sessionAuth(credentials[0]))
                .header("X-Amz-Security-Token", credentials[2])
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static void byCustomer2(String action, String... params) {
        byCustomer2Expecting(action, 200, params);
    }

    private static void byCustomer2Expecting(String action, int status, String... params) {
        RequestSpecification request = given().header("Authorization", auth(CUSTOMER, "iam"))
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        request.when().post("/").then().statusCode(status);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aRoleAStackProvisionsForADelegatedCallerCarriesTheCapToo() {
        String id = approved(partnerCreate(PARTNER));
        String[] credentials = delegatedCredentials(PARTNER, id);
        String stackName = unique("delegated-stack");
        String roleName = unique("stack-role");
        String template = "{\"Resources\":{\"R\":{\"Type\":\"AWS::IAM::Role\",\"Properties\":{"
                + "\"RoleName\":\"" + roleName + "\",\"AssumeRolePolicyDocument\":" + TRUST_JSON + "}}}}";

        // The stack is created with the delegated credentials, so the roles it provisions are
        // created through temporary access and the guide caps them.
        // The router dispatches on the service the credential scope names, so a stack call signs
        // for cloudformation while the same session's IAM calls sign for iam.
        given().header("Authorization", auth(credentials[0], "cloudformation"))
                .header("X-Amz-Security-Token", credentials[2])
                .formParam("Action", "CreateStack")
                .formParam("Version", "2010-05-15")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
            .when().post("/").then().statusCode(200);

        // The session acts in the customer's account, so that is where the stack and its role live.
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", auth(CUSTOMER, "cloudformation"));
        try {
            given().header("Authorization", auth(CUSTOMER, "iam"))
                    .formParam("Action", "GetRole")
                    .formParam("Version", "2010-05-08")
                    .formParam("RoleName", roleName)
                .when().post("/").then().statusCode(200)
                    .body("GetRoleResponse.GetRoleResult.Role.PermissionsBoundary.PermissionsBoundaryArn",
                            equalTo(BOUNDARY));
        } finally {
            CfnStackWaits.deleteStack(stackName, auth(CUSTOMER, "cloudformation"));
        }
    }

    private String approved(String id) {
        byCustomer("AssociateDelegationRequest", id);
        byCustomer("AcceptDelegationRequest", id);
        byCustomer("SendDelegationToken", id);
        return id;
    }

    /** The access key, secret and session token the partner gets for the released token. */
    private String[] delegatedCredentials(String partnerAccountId, String id) {
        String token = RequestScopes.callAs(CUSTOMER,
                () -> delegationRequests.get(CUSTOMER, id).getExchangeToken());
        ExtractableResponse<Response> exchanged = given()
                .header("Authorization", auth(partnerAccountId, "sts"))
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

    private static String partnerCreate(String partnerAccountId) {
        return given().header("Authorization", auth(partnerAccountId, "iam"))
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

    private static void byCustomer(String action, String id) {
        given().header("Authorization", auth(CUSTOMER, "iam"))
                .formParam("Action", action)
                .formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", id)
            .when().post("/").then().statusCode(200);
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261002/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String sessionAuth(String accessKeyId) {
        return auth(accessKeyId, "iam");
    }
}
