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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * What the holder of a delegation request may do without a policy of their own.
 *
 * <p>The customer page says it in a note: "By default, IAM principals who initiate a delegation
 * request are automatically granted permissions to manage that specific request. They can associate
 * it with their account, view request details, reject a request, forward it to an administrator for
 * approval, release the exchange token to the product provider after admin approval, and list
 * delegation requests that they own." The same page lists the permissions an administrator grants,
 * and {@code iam:AcceptDelegationRequest} is one of them rather than part of the note, so approving
 * is not implicit and this suite holds that line too.
 *
 * <p>Associating is the one ability in the note this grant cannot give: a request records no owner
 * until it is claimed, so until then there is no identity to write a grant for. The claim here is
 * made by an identity allowed to make it, and everything after it is tested with no policy at all.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class DelegationOwnerGrantEnforcementIntegrationTest {

    private static final String CUSTOMER = "222233334444";
    private static final String PARTNER = "000000000001";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000001:partner-notifications";
    private static final String TEMPLATE = "arn:aws:iam::partner:policy/template/example";
    private static final String MAY_ASSOCIATE = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Action":"iam:AssociateDelegationRequest","Resource":"*"}]}""";
    private static final String MAY_NOTHING = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Action":"s3:ListAllMyBuckets","Resource":"*"}]}""";
    /** The permission the guide's table describes as "List delegation requests for your account". */
    private static final String MAY_LIST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Action":"iam:ListDelegationRequests","Resource":"*"}]}""";

    @Test
    void theHolderReadsForwardsAndReleasesItsOwnRequestWithoutAPolicyForThem() {
        String holderName = unique("holder");
        S3RequestSigner holder = userWithPolicy(holderName, MAY_ASSOCIATE);
        String id = partnerCreate();

        // The claim itself is the one step that needs a permission, and this identity has exactly
        // that one. Everything after it is the grant the note describes.
        byUser(holder, "AssociateDelegationRequest", id).statusCode(200);
        byUser(holder, "GetDelegationRequest", id).statusCode(200)
                .body("GetDelegationRequestResponse.GetDelegationRequestResult.DelegationRequest"
                        + ".DelegationRequestId", equalTo(id));
        listBy(holder, userArn(holderName)).statusCode(200);
        // Forwarding to an administrator, which UpdateDelegationRequest is on this family.
        given().filter(holder).formParam("Action", "UpdateDelegationRequest")
                .formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", id)
                .formParam("Notes", "please review")
            .when().post("/").then().statusCode(200);
        byUser(holder, "RejectDelegationRequest", id).statusCode(200);
    }

    @Test
    void theOwnerListsWhatItOwnsAndTheAccountListingStaysWithTheGrantedPermission() {
        String ownerName = unique("list-owner");
        S3RequestSigner owner = userWithPolicy(ownerName, MAY_ASSOCIATE);
        String id = partnerCreate();
        byUser(owner, "AssociateDelegationRequest", id).statusCode(200);
        String otherName = unique("list-other");
        S3RequestSigner other = userWithPolicy(otherName, MAY_ASSOCIATE);
        String otherId = partnerCreate();
        byUser(other, "AssociateDelegationRequest", otherId).statusCode(200);

        // "list delegation requests that they own" is what the note gives an owner, and the
        // condition key the reference publishes on the operation is what holds it there: the call
        // names the owner it is for, and answers with that owner's requests and no others.
        listBy(owner, userArn(ownerName)).statusCode(200)
                .body(containsString(id))
                .body(not(containsString(otherId)));
        // The account's listing is the permission the same page gives an administrator, so owning
        // a request is not asking for it, by another owner's name or by no name at all.
        listBy(owner, userArn(otherName)).statusCode(403);
        listBy(owner, null).statusCode(403);

        // Granted outright, it answers for the account, which is both of these requests.
        S3RequestSigner administrator = userWithPolicy(unique("list-admin"), MAY_LIST);
        listBy(administrator, null).statusCode(200)
                .body(containsString(id))
                .body(containsString(otherId));
    }

    @Test
    void theHolderStillCannotApproveItsOwnRequest() {
        S3RequestSigner holder = userWithPolicy(unique("holder-approve"), MAY_ASSOCIATE);
        String id = partnerCreate();
        byUser(holder, "AssociateDelegationRequest", id).statusCode(200);

        // The page lists iam:AcceptDelegationRequest among the permissions an administrator grants,
        // and the note leaves it out, so holding the request is not approving it.
        byUser(holder, "AcceptDelegationRequest", id).statusCode(403);
    }

    @Test
    void anIdentityThatOwnsNothingIsDeniedOnSomeoneElsesRequest() {
        S3RequestSigner holder = userWithPolicy(unique("holder-other"), MAY_ASSOCIATE);
        S3RequestSigner stranger = userWithPolicy(unique("stranger"), MAY_NOTHING);
        String id = partnerCreate();
        byUser(holder, "AssociateDelegationRequest", id).statusCode(200);

        // The grant is per request, so it reaches the holder and nobody else.
        byUser(stranger, "GetDelegationRequest", id).statusCode(403);
        byUser(stranger, "RejectDelegationRequest", id).statusCode(403);
    }

    @Test
    void theHolderReadsTheSummaryOfItsOwnRequest() {
        S3RequestSigner holder = userWithPolicy(unique("holder-summary"), MAY_ASSOCIATE);
        S3RequestSigner stranger = userWithPolicy(unique("stranger-summary"), MAY_NOTHING);
        String id = partnerCreate();
        byUser(holder, "AssociateDelegationRequest", id).statusCode(200);

        // The API reference ties the summary to reading the request: "a party who has permissions
        // to call GetDelegationRequest for a given delegation request will always be able to
        // retrieve the human readable summary for that request".
        summaryBy(holder, id).statusCode(200)
                .body("GetHumanReadableSummaryResponse.GetHumanReadableSummaryResult.SummaryState",
                        equalTo("AVAILABLE"));
        // And ties it just as tightly the other way, so it reaches nobody else.
        summaryBy(stranger, id).statusCode(403);
    }

    @Test
    void aRoleSessionThatClaimedTheRequestCarriesTheSameGrant() {
        String roleName = unique("holder-role");
        createRoleThatMayAssociate(roleName);
        String session = assumeRole(roleName);
        String id = partnerCreate();

        // AssociateDelegationRequest stores "the ARN of the current calling identity", which for
        // a session is its own assumed-role ARN and not the role's, so that is the identity the
        // grant has to be found for.
        byKey(session, "AssociateDelegationRequest", id).statusCode(200);
        byKey(session, "GetDelegationRequest", id).statusCode(200);
        byKey(session, "AcceptDelegationRequest", id).statusCode(403);
        byKey(session, "RejectDelegationRequest", id).statusCode(200);
    }

    @Test
    void theAccessReportForAnOwnerCountsTheIamItReachesByOwning() {
        String owner = unique("report-owner");
        S3RequestSigner holder = userWithPolicy(owner, MAY_ASSOCIATE);
        String bystander = unique("report-bystander");
        userWithPolicy(bystander, MAY_NOTHING);
        String id = partnerCreate();
        byUser(holder, "AssociateDelegationRequest", id).statusCode(200);
        // The policy existed to make the claim. With it gone, owning the request is the only
        // reason this user reaches IAM, which is what the report has to say.
        iam("DeleteUserPolicy", "UserName", owner, "PolicyName", "inline");

        reportFor(owner).body(containsString("<ServiceNamespace>iam</ServiceNamespace>"));
        // The same report for a user who owns nothing, so the line above is the grant talking.
        reportFor(bystander).body(not(containsString("<ServiceNamespace>iam</ServiceNamespace>")));
    }

    private static ValidatableResponse byUser(S3RequestSigner caller, String action, String id) {
        RequestSpecification request = given().filter(caller)
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        if (id != null) {
            request = request.formParam("DelegationRequestId", id);
        }
        return request.when().post("/").then();
    }

    private static ValidatableResponse listBy(S3RequestSigner caller, String ownerId) {
        RequestSpecification request = given().filter(caller)
                .formParam("Action", "ListDelegationRequests")
                .formParam("Version", "2010-05-08");
        if (ownerId != null) {
            request = request.formParam("OwnerId", ownerId);
        }
        return request.when().post("/").then();
    }

    private static String userArn(String userName) {
        return "arn:aws:iam::" + CUSTOMER + ":user/" + userName;
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

    private static ValidatableResponse byKey(String accessKeyId, String action, String id) {
        RequestSpecification request = given().header("Authorization", auth(accessKeyId, "iam"))
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        if (id != null) {
            request = request.formParam("DelegationRequestId", id);
        }
        return request.when().post("/").then();
    }

    private static ValidatableResponse summaryBy(S3RequestSigner caller, String id) {
        return given().filter(caller)
                .formParam("Action", "GetHumanReadableSummary")
                .formParam("Version", "2010-05-08")
                .formParam("EntityArn", DelegationRequests.entityArn(CUSTOMER, id))
            .when().post("/").then();
    }

    /** A role whose one permission is the claim, so everything after it is the grant. */
    private static void createRoleThatMayAssociate(String roleName) {
        iam("CreateRole", "RoleName", roleName, "Path", "/", "AssumeRolePolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"*"},"Action":"sts:AssumeRole"}]}""");
        iam("PutRolePolicy", "RoleName", roleName, "PolicyName", "inline",
                "PolicyDocument", MAY_ASSOCIATE);
    }

    private static String assumeRole(String roleName) {
        return given().header("Authorization", auth(CUSTOMER, "sts"))
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + CUSTOMER + ":role/" + roleName)
                .formParam("RoleSessionName", "owner-grant")
            .when().post("/").then().statusCode(200)
                .extract().path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
    }

    /** The service-level report for one user, which the grant has to reach. */
    private static ValidatableResponse reportFor(String userName) {
        String jobId = given().header("Authorization", auth(CUSTOMER, "iam"))
                .formParam("Action", "GenerateServiceLastAccessedDetails")
                .formParam("Arn", "arn:aws:iam::" + CUSTOMER + ":user/" + userName)
            .when().post("/").then().statusCode(200)
                .extract().path("GenerateServiceLastAccessedDetailsResponse"
                        + ".GenerateServiceLastAccessedDetailsResult.JobId");
        return given().header("Authorization", auth(CUSTOMER, "iam"))
                .formParam("Action", "GetServiceLastAccessedDetails")
                .formParam("JobId", jobId)
            .when().post("/").then().statusCode(200);
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
