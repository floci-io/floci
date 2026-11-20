package io.floci.aws.services.iam;

import io.floci.aws.testing.DelegationRequests;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Policies scoped by the owner of the delegation request a call names.
 *
 * <p>The Service Reference publishes {@code iam:DelegationRequestOwner} on the {@code delegation-request}
 * resource type rather than on the actions that act on one, so its value is not in the request: it is what the
 * stored request records. AWS describes the key as filtering "based on the AWS identity or principal that owns
 * the delegation request", the owner being "the AWS identity or principal in the customer account that
 * initiated or received the delegation request", and writes the example as
 * {@code "ArnEquals": {"iam:DelegationRequestOwner": "${aws:PrincipalArn}"}} so that "you allow users to manage
 * only delegation requests that they own".
 *
 * <p>So each case here turns on the value being the owner on the request, not merely present: an allow holds a
 * caller to its own requests, a deny reaches one request and leaves the next alone, and a call that names no
 * request carries no value at all.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class DelegationRequestOwnerConditionIntegrationTest {

    private static final String CUSTOMER = "222233334444";
    private static final String PARTNER = "000000000001";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000001:partner-notifications";
    private static final String TEMPLATE = "arn:aws:iam::partner:policy/template/example";
    private static final String STRANGER = "arn:aws:iam::222233334444:user/someone-else";

    /** Claiming, plus approving only the requests the caller owns, which is the guide's own example shape. */
    private static final String MAY_CLAIM_AND_ACCEPT_ITS_OWN = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"iam:AssociateDelegationRequest","Resource":"*"},
              {"Effect":"Allow","Action":"iam:AcceptDelegationRequest","Resource":"*",
               "Condition":{"ArnEquals":{"iam:DelegationRequestOwner":"%s"}}}]}""";
    private static final String MAY_CLAIM = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Action":"iam:AssociateDelegationRequest","Resource":"*"}]}""";
    /** Claiming, with one action the owner grant would otherwise give taken back for its own requests. */
    private static final String MAY_CLAIM_DENIED_ON_ITS_OWN = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"iam:AssociateDelegationRequest","Resource":"*"},
              {"Effect":"Deny","Action":"%s","Resource":"*",
               "Condition":{"ArnEquals":{"iam:DelegationRequestOwner":"%s"}}}]}""";
    private static final String MAY_CREATE_ITS_OWN = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Action":"iam:CreateDelegationRequest","Resource":"*",
              "Condition":{"ArnEquals":{"iam:DelegationRequestOwner":"%s"}}}]}""";
    private static final String MAY_CREATE = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Action":"iam:CreateDelegationRequest","Resource":"*"}]}""";

    @Test
    void anAllowHeldToTheOwnerReachesTheRequestThatCallerOwns() {
        String name = unique("owner-accept");
        S3RequestSigner owner = userWithPolicy(name, MAY_CLAIM_AND_ACCEPT_ITS_OWN.formatted(userArn(name)));
        String id = partnerCreate();
        byUser(owner, "AssociateDelegationRequest", id).statusCode(200);

        // Approving is not part of the grant an owner carries, so this allow is the only thing that can
        // reach it, and it only matches where the stored request names this caller as its owner.
        byUser(owner, "AcceptDelegationRequest", id).statusCode(200);
    }

    @Test
    void theSameAllowDoesNotReachARequestAnotherIdentityOwns() {
        String name = unique("owner-other");
        S3RequestSigner caller = userWithPolicy(name, MAY_CLAIM_AND_ACCEPT_ITS_OWN.formatted(userArn(name)));
        S3RequestSigner neighbour = userWithPolicy(unique("owner-neighbour"), MAY_CLAIM);
        String theirs = partnerCreate();
        byUser(neighbour, "AssociateDelegationRequest", theirs).statusCode(200);

        byUser(caller, "AcceptDelegationRequest", theirs).statusCode(403);
    }

    @Test
    void theSameAllowDoesNotReachARequestNobodyOwnsYet() {
        String name = unique("owner-unclaimed");
        S3RequestSigner caller = userWithPolicy(name, MAY_CLAIM_AND_ACCEPT_ITS_OWN.formatted(userArn(name)));

        // A request records no owner until it is claimed, so there is no value for the key to match and the
        // conditioned allow does not apply.
        byUser(caller, "AcceptDelegationRequest", partnerCreate()).statusCode(403);
    }

    @Test
    void aDenyHeldToTheOwnerTakesBackWhatOwningWouldGive() {
        String name = unique("deny-own");
        S3RequestSigner owner = userWithPolicy(name,
                MAY_CLAIM_DENIED_ON_ITS_OWN.formatted("iam:GetDelegationRequest", userArn(name)));
        String id = partnerCreate();
        byUser(owner, "AssociateDelegationRequest", id).statusCode(200);

        // Reading its own request is what an owner may do with no policy at all, so a deny that lands here
        // can only have matched on the owner the stored request records.
        byUser(owner, "GetDelegationRequest", id).statusCode(403);
    }

    @Test
    void aDenyHeldToAnotherOwnerLeavesTheCallersOwnRequestAlone() {
        String name = unique("deny-other");
        S3RequestSigner owner = userWithPolicy(name,
                MAY_CLAIM_DENIED_ON_ITS_OWN.formatted("iam:GetDelegationRequest", STRANGER));
        String id = partnerCreate();
        byUser(owner, "AssociateDelegationRequest", id).statusCode(200);

        // The value is this caller's ARN rather than any owner at all, so a deny written for someone else
        // does not match it.
        byUser(owner, "GetDelegationRequest", id).statusCode(200)
                .body("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest"
                        + ".DelegationRequestId", equalTo(id));
    }

    @Test
    void theSummaryCarriesTheOwnerOfTheRequestItsEntityArnNames() {
        String name = unique("deny-summary");
        S3RequestSigner owner = userWithPolicy(name,
                MAY_CLAIM_DENIED_ON_ITS_OWN.formatted("iam:GetHumanReadableSummary", userArn(name)));
        String id = partnerCreate();
        byUser(owner, "AssociateDelegationRequest", id).statusCode(200);

        // This is the one action of the family that names its request by an ARN rather than by an id, and
        // the owner is read for it all the same.
        summaryBy(owner, id).statusCode(403);
    }

    @Test
    void creatingNamesNoRequestSoAnAllowHeldToTheOwnerNeverMatches() {
        String conditioned = unique("create-conditioned");
        S3RequestSigner held = userWithPolicy(conditioned, MAY_CREATE_ITS_OWN.formatted(userArn(conditioned)));
        S3RequestSigner plain = userWithPolicy(unique("create-plain"), MAY_CREATE);

        // The request a create makes does not exist while it is authorized, so the key carries nothing and
        // a conditioned allow cannot match, while the same allow without the condition does.
        createBy(held).statusCode(403);
        createBy(plain).statusCode(200);
    }

    private static ValidatableResponse byUser(S3RequestSigner caller, String action, String id) {
        return given().filter(caller)
                .formParam("Action", action)
                .formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", id)
            .when().post("/").then();
    }

    private static ValidatableResponse summaryBy(S3RequestSigner caller, String id) {
        return given().filter(caller)
                .formParam("Action", "GetHumanReadableSummary")
                .formParam("Version", "2010-05-08")
                .formParam("EntityArn", DelegationRequests.entityArn(CUSTOMER, id))
            .when().post("/").then();
    }

    private static ValidatableResponse createBy(S3RequestSigner caller) {
        return creation(given().filter(caller), null).when().post("/").then();
    }

    /**
     * A well formed {@code CreateDelegationRequest}, named for the owner account where one is given, so that
     * the shape a create has to take is written once however the call is signed.
     */
    private static RequestSpecification creation(RequestSpecification request, String ownerAccountId) {
        request = request
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Version", "2010-05-08")
                .formParam("Description", "Set up the integration")
                .formParam("NotificationChannel", TOPIC)
                .formParam("RequestorWorkflowId", "workflow-" + Long.toString(System.nanoTime(), 36))
                .formParam("SessionDuration", "3600")
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE);
        return ownerAccountId == null ? request : request.formParam("OwnerAccountId", ownerAccountId);
    }

    private static String userArn(String userName) {
        return "arn:aws:iam::" + CUSTOMER + ":user/" + userName;
    }

    private static String partnerCreate() {
        return creation(given().header("Authorization", auth(PARTNER, "iam")), CUSTOMER)
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

    private static void iam(String action, String... params) {
        RequestSpecification request = given().formParam("Action", action)
                .header("Authorization", auth(CUSTOMER, "iam"));
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261002/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
