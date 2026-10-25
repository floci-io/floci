package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * What credentials exchanged for a delegation token may do, under IAM enforcement.
 *
 * <p>The identity they act as is the one that approved the request, which accepting it records as
 * the identity "that gets mapped to the delegated credential", and AWS bounds them by the
 * permission policy it renders from the partner's registered template. No API registers a
 * template, so nothing renders that policy here and the approver's own permissions are the whole
 * of the bound: a delegation hands over no more than the identity that approved it holds, and that
 * identity is read off the ARN the request recorded rather than looked up as a role.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class DelegationTokenEnforcementIntegrationTest {

    private static final String CUSTOMER = "222233334444";
    private static final String PARTNER = "000000000001";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000001:partner-notifications";
    private static final String TEMPLATE = "arn:aws:iam::partner:policy/template/example";
    private static final String ALL_S3 = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}""";
    private static final String DELEGATION_ACCESS = """
            {"Effect":"Allow","Action":["iam:AssociateDelegationRequest","iam:AcceptDelegationRequest",
              "iam:UpdateDelegationRequest","iam:SendDelegationToken"],"Resource":"*"}""";

    @Inject
    DelegationRequestService delegationRequests;

    @Test
    void delegatedCredentialsCarryTheApproversPermissionsAndNothingMore() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = bucketWithObject("deleg-approver-" + suffix);
        String approverName = "reader-" + suffix;
        S3RequestSigner approver = userWithPolicy(approverName, """
                {"Version":"2012-10-17","Statement":[%s,
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""".formatted(DELEGATION_ACCESS));

        S3RequestSigner delegated = delegatedCredentials(approvedBy(approver));

        // The approver may read and not write, so the credentials it granted read and do not write.
        readObject(delegated, bucket, 200);
        writeObject(delegated, bucket, 403);
        given().filter(delegated).formParam("Action", "GetCallerIdentity").when().post("/")
                .then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        equalTo("arn:aws:iam::" + CUSTOMER + ":user/" + approverName))
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        not(containsString("assumed-role")))
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo(CUSTOMER));
    }

    @Test
    void aRoleCarryingTheApproversNameLendsTheDelegationNothing() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = bucketWithObject("deleg-twin-" + suffix);
        String approverName = "twin-" + suffix;
        S3RequestSigner approver = userWithPolicy(approverName, """
                {"Version":"2012-10-17","Statement":[%s,
                  {"Effect":"Deny","Action":"s3:*","Resource":"*"}]}""".formatted(DELEGATION_ACCESS));
        // A role in the same account that happens to carry the approver's name, allowed all of S3.
        iam("CreateRole", "RoleName", approverName, "AssumeRolePolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""");
        iam("PutRolePolicy", "RoleName", approverName, "PolicyName", "s3", "PolicyDocument", ALL_S3);

        S3RequestSigner delegated = delegatedCredentials(approvedBy(approver));

        readObject(delegated, bucket, 403);
        writeObject(delegated, bucket, 403);
    }

    @Test
    void aDelegationApprovedByTheAccountRootActsAsTheAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = bucketWithObject("deleg-root-" + suffix);
        String id = partnerCreate();
        byRoot("AssociateDelegationRequest", id);
        byRoot("AcceptDelegationRequest", id);
        byRoot("SendDelegationToken", id);

        S3RequestSigner delegated = delegatedCredentials(id);

        readObject(delegated, bucket, 200);
        given().filter(delegated).formParam("Action", "GetCallerIdentity").when().post("/")
                .then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        equalTo("arn:aws:iam::" + CUSTOMER + ":root"))
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.UserId", equalTo(CUSTOMER));
    }

    /**
     * The forwarded case the user guide describes: the owner lacks the permissions, an
     * administrator approves instead, and the provider then obtains the "approver's temporary
     * credentials". The two identities hold opposite halves of S3 here, so what the credentials
     * may do names which of them they came from.
     */
    @Test
    void aRequestForwardedForApprovalCarriesTheApproverNotTheOwner() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = bucketWithObject("deleg-forwarded-" + suffix);
        String ownerName = "forwarder-" + suffix;
        S3RequestSigner owner = userWithPolicy(ownerName, """
                {"Version":"2012-10-17","Statement":[%s,
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""".formatted(DELEGATION_ACCESS));
        String approverName = "approver-" + suffix;
        S3RequestSigner approver = userWithPolicy(approverName, """
                {"Version":"2012-10-17","Statement":[%s,
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}]}""".formatted(DELEGATION_ACCESS));

        String id = partnerCreate();
        byUser(owner, "AssociateDelegationRequest", id);
        // Forwarded for approval, accepted by the administrator, released by the owner, which is
        // the order the user guide gives for an owner who cannot approve a request itself.
        given().filter(owner).formParam("Action", "UpdateDelegationRequest")
                .formParam("Version", "2010-05-08").formParam("DelegationRequestId", id)
                .formParam("Notes", "forwarding, I cannot approve this myself")
            .when().post("/").then().statusCode(200);
        byUser(approver, "AcceptDelegationRequest", id);
        byUser(owner, "SendDelegationToken", id);

        S3RequestSigner delegated = delegatedCredentials(id);

        // The approver writes and cannot read, the owner reads and cannot write, and the
        // credentials do what the approver does.
        writeObject(delegated, bucket, 200);
        readObject(delegated, bucket, 403);
        given().filter(delegated).formParam("Action", "GetCallerIdentity").when().post("/")
                .then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        equalTo("arn:aws:iam::" + CUSTOMER + ":user/" + approverName));
    }

    /**
     * The summary is authorized against the request the ARN names, which is the whole point of
     * resolving {@code EntityArn} as the resource: an action left unresolved is evaluated against
     * {@code *}, so a policy naming one request would silently cover every other one.
     */
    @Test
    void theSummaryIsAuthorizedAgainstTheRequestItsArnNames() {
        String allowed = partnerCreate();
        String refused = partnerCreate();
        String arn = "arn:aws:iam::" + CUSTOMER + ":delegation-request/";
        S3RequestSigner reader = userWithPolicy("summary-reader-"
                + UUID.randomUUID().toString().substring(0, 8), """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":"iam:GetHumanReadableSummary","Resource":"%s"}]}"""
                .formatted(arn + allowed));

        summarize(reader, arn + allowed).statusCode(200)
                .body("GetHumanReadableSummaryResponse.GetHumanReadableSummaryResult.SummaryState",
                        equalTo("AVAILABLE"));
        summarize(reader, arn + refused).statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    /**
     * A deny names the request, so it has to hold whichever ARN the caller spells it with. The
     * lookup reads only the id out of {@code EntityArn}, deliberately, which is why the request
     * is authorized against the ARN Floci mints around that id rather than against the string as
     * it arrived: otherwise a wildcard allow plus a respelled ARN would read a denied summary.
     */
    @Test
    void aDenyOnARequestHoldsWhateverArnTheCallerSpellsItWith() {
        String id = partnerCreate();
        String canonical = "arn:aws:iam::" + CUSTOMER + ":delegation-request/" + id;
        S3RequestSigner reader = userWithPolicy("summary-deny-"
                + UUID.randomUUID().toString().substring(0, 8), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:GetHumanReadableSummary","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:GetHumanReadableSummary","Resource":"%s"}]}"""
                .formatted(canonical));

        summarize(reader, canonical).statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        summarize(reader, "arn:aws:iam::000000000009:delegation-request/" + id).statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    private static ValidatableResponse summarize(S3RequestSigner caller, String entityArn) {
        return given().filter(caller).formParam("Action", "GetHumanReadableSummary")
                .formParam("Version", "2010-05-08").formParam("EntityArn", entityArn)
            .when().post("/").then();
    }

    /** A request this identity claimed, accepted and released itself, ready to exchange. */
    private String approvedBy(S3RequestSigner approver) {
        String id = partnerCreate();
        byUser(approver, "AssociateDelegationRequest", id);
        byUser(approver, "AcceptDelegationRequest", id);
        byUser(approver, "SendDelegationToken", id);
        return id;
    }

    /** The partner exchanging the released token, which reaches it over SNS rather than the wire. */
    private S3RequestSigner delegatedCredentials(String id) {
        String token = RequestScopes.callAs(CUSTOMER,
                () -> delegationRequests.get(CUSTOMER, id).getExchangeToken());
        ExtractableResponse<Response> exchanged = given().header("Authorization", auth(PARTNER, "sts"))
                .formParam("Action", "GetDelegatedAccessToken")
                .formParam("Version", "2011-06-15")
                .formParam("TradeInToken", token)
            .when().post("/").then().statusCode(200).extract();
        return S3RequestSigner.signedAs(
                exchanged.path("GetDelegatedAccessTokenResponse.GetDelegatedAccessTokenResult"
                        + ".Credentials.AccessKeyId"),
                exchanged.path("GetDelegatedAccessTokenResponse.GetDelegatedAccessTokenResult"
                        + ".Credentials.SecretAccessKey"),
                exchanged.path("GetDelegatedAccessTokenResponse.GetDelegatedAccessTokenResult"
                        + ".Credentials.SessionToken"));
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
                .formParam("Permissions.Parameters.member.1.Name", "BucketName")
                .formParam("Permissions.Parameters.member.1.Type", "string")
                .formParam("Permissions.Parameters.member.1.Values.member.1", "example-bucket")
            .when().post("/").then().statusCode(200)
                .extract().path("CreateDelegationRequestResponse.CreateDelegationRequestResult"
                        + ".DelegationRequestId");
    }

    private static void byUser(S3RequestSigner caller, String action, String id) {
        given().filter(caller).formParam("Action", action).formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", id).when().post("/").then().statusCode(200);
    }

    private static void byRoot(String action, String id) {
        given().header("Authorization", auth(CUSTOMER, "iam"))
                .formParam("Action", action).formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", id).when().post("/").then().statusCode(200);
    }

    private static void readObject(S3RequestSigner caller, String bucket, int status) {
        given().filter(caller).when().get("/" + bucket + "/o.txt").then().statusCode(status);
    }

    private static void writeObject(S3RequestSigner caller, String bucket, int status) {
        given().filter(caller).contentType("text/plain").body("written")
                .when().put("/" + bucket + "/" + UUID.randomUUID() + ".txt").then().statusCode(status);
    }

    /** A bucket in the customer's account holding {@code o.txt}, created by an administrator there. */
    private static String bucketWithObject(String bucket) {
        S3RequestSigner admin = userWithPolicy("admin-" + bucket, ALL_S3);
        given().filter(admin).when().put("/" + bucket).then().statusCode(200);
        given().filter(admin).contentType("text/plain").body("payload")
                .when().put("/" + bucket + "/o.txt").then().statusCode(200);
        return bucket;
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

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261002/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
