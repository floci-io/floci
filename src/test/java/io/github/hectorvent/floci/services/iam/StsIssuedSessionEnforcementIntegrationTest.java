package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Under IAM enforcement, credentials from GetSessionToken and GetFederationToken carry the
 * permissions of the IAM user whose key minted them (STS API Reference, GetSessionToken and
 * GetFederationToken, "Permissions"): never a role's, and never more than the user's own.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class StsIssuedSessionEnforcementIntegrationTest {

    private static final String ACCOUNT = "222233334444";
    private static final String ALL_S3 = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}""";

    @Test
    void aFederatedUserNamedLikeARoleDoesNotGetTheRolesPermissions() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = bucketWithObject("sts-fed-role-" + suffix);
        String role = "Admin" + suffix;
        iam("CreateRole", "RoleName", role, "AssumeRolePolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""");
        iam("PutRolePolicy", "RoleName", role, "PolicyName", "s3", "PolicyDocument", ALL_S3);
        S3RequestSigner user = userWithPolicy("broker-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:GetFederationToken","Resource":"*"}]}""");

        // The user may not read the object, so neither may a session it mints, whatever its name.
        readObject(user, bucket, 403);
        readObject(federate(user, role, null), bucket, 403);
        readObject(federate(user, role, ALL_S3), bucket, 403);
    }

    @Test
    void aFederatedSessionGetsTheIntersectionOfItsUserAndItsSessionPolicy() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = bucketWithObject("sts-fed-scope-" + suffix);
        S3RequestSigner user = userWithPolicy("reader-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:GetObject","sts:GetFederationToken"],"Resource":"*"}]}""");

        S3RequestSigner scoped = federate(user, "Bob" + suffix, ALL_S3);
        readObject(scoped, bucket, 200);
        // The session policy allows s3:PutObject, the user does not.
        writeObject(scoped, bucket, 403);
        // Without a session policy the session has no permissions.
        readObject(federate(user, "Carol" + suffix, null), bucket, 403);

        given().filter(scoped).formParam("Action", "GetCallerIdentity").when().post("/")
                .then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        equalTo("arn:aws:sts::" + ACCOUNT + ":federated-user/Bob" + suffix))
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.UserId",
                        equalTo(ACCOUNT + ":Bob" + suffix));
    }

    @Test
    void sessionTokenCredentialsActAsTheUserThatMintedThem() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = bucketWithObject("sts-session-" + suffix);
        S3RequestSigner tokenOnly = userWithPolicy("token-only-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:GetSessionToken","Resource":"*"}]}""");
        S3RequestSigner reader = userWithPolicy("token-reader-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:GetObject","sts:GetSessionToken"],"Resource":"*"}]}""");

        // A session of a user allowed nothing else is allowed nothing else.
        S3RequestSigner tokenOnlySession = sessionToken(tokenOnly);
        readObject(tokenOnlySession, bucket, 403);
        writeObject(tokenOnlySession, bucket, 403);
        given().filter(tokenOnlySession).formParam("Action", "CreateUser").formParam("UserName", "made-" + suffix)
                .when().post("/").then().statusCode(403);
        // Nor can it assume a role the user may not assume, even one whose trust policy names the account.
        String role = "Target" + suffix;
        iam("CreateRole", "RoleName", role, "AssumeRolePolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole"}]}""".formatted(ACCOUNT));
        iam("PutRolePolicy", "RoleName", role, "PolicyName", "s3", "PolicyDocument", ALL_S3);
        given().filter(tokenOnlySession).formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT + ":role/" + role).formParam("RoleSessionName", "s")
                .when().post("/").then().statusCode(403);

        S3RequestSigner readerSession = sessionToken(reader);
        readObject(readerSession, bucket, 200);
        writeObject(readerSession, bucket, 403);
        given().filter(readerSession).formParam("Action", "GetCallerIdentity").when().post("/")
                .then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        equalTo("arn:aws:iam::" + ACCOUNT + ":user/token-reader-" + suffix));
    }

    @Test
    void bothKindsOfSessionStayWithinTheUsersPermissionsBoundary() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = bucketWithObject("sts-boundary-" + suffix);
        String userName = "bounded-" + suffix;
        S3RequestSigner user = userWithPolicy(userName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:*","sts:*"],"Resource":"*"}]}""");
        String boundaryArn = given().formParam("Action", "CreatePolicy").formParam("PolicyName", "read-only-" + suffix)
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Action":["s3:GetObject","sts:GetSessionToken","sts:GetFederationToken"],"Resource":"*"}]}""")
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/")
                .then().statusCode(200).extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        iam("PutUserPermissionsBoundary", "UserName", userName, "PermissionsBoundary", boundaryArn);

        // The boundary allows reading, not writing, so neither the user nor its sessions may write.
        writeObject(user, bucket, 403);
        S3RequestSigner session = sessionToken(user);
        readObject(session, bucket, 200);
        writeObject(session, bucket, 403);
        S3RequestSigner federated = federate(user, "Dana" + suffix, ALL_S3);
        readObject(federated, bucket, 200);
        writeObject(federated, bucket, 403);
    }

    @Test
    void aSessionTokensPrincipalArnIsItsUsersArn() {
        // A bucket-policy Deny keyed on the user's aws:PrincipalArn applies to its sessions too.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "sts-session-arn-" + suffix;
        S3RequestSigner admin = userWithPolicy("admin-" + bucket, ALL_S3);
        given().filter(admin).when().put("/" + bucket).then().statusCode(200);
        given().filter(admin).contentType("text/plain").body("payload")
                .when().put("/" + bucket + "/o.txt").then().statusCode(200);
        String userName = "denied-reader-" + suffix;
        S3RequestSigner user = userWithPolicy(userName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:GetObject","sts:GetSessionToken"],"Resource":"*"}]}""");
        S3RequestSigner session = sessionToken(user);
        readObject(session, bucket, 200);

        given().filter(admin).contentType("application/json").body("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*","Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::%s/*",
                  "Condition":{"ArnEquals":{"aws:PrincipalArn":"arn:aws:iam::%s:user/%s"}}}]}"""
                .formatted(bucket, ACCOUNT, userName))
                .when().put("/" + bucket + "?policy").then().statusCode(200);

        readObject(user, bucket, 403);
        readObject(session, bucket, 403);
    }

    @Test
    void aSessionWhoseUserWasRecreatedIsNoCredential() {
        // A session of a user deleted and recreated under its name is not that user's, and is no
        // credential at all: not even a bucket policy granting everyone lets it in as a principal.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "sts-orphan-" + suffix;
        S3RequestSigner admin = userWithPolicy("admin-" + bucket, ALL_S3);
        given().filter(admin).when().put("/" + bucket).then().statusCode(200);
        given().filter(admin).contentType("text/plain").body("payload")
                .when().put("/" + bucket + "/o.txt").then().statusCode(200);
        String userName = "recreated-" + suffix;
        iam("CreateUser", "UserName", userName);
        iam("PutUserPolicy", "UserName", userName, "PolicyName", "inline", "PolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:GetSessionToken","Resource":"*"}]}""");
        ExtractableResponse<Response> key = given().formParam("Action", "CreateAccessKey").formParam("UserName", userName)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200).extract();
        String accessKeyId = key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        S3RequestSigner session = sessionToken(S3RequestSigner.signedAs(accessKeyId,
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey")));
        iam("DeleteAccessKey", "UserName", userName, "AccessKeyId", accessKeyId);
        iam("DeleteUserPolicy", "UserName", userName, "PolicyName", "inline");
        iam("DeleteUser", "UserName", userName);
        iam("CreateUser", "UserName", userName);
        given().filter(admin).contentType("application/json").body("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
                  "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}""".formatted(bucket))
                .when().put("/" + bucket + "?policy").then().statusCode(200);

        given().when().get("/" + bucket + "/o.txt").then().statusCode(200);
        readObject(session, bucket, 403);
    }

    @Test
    void aBucketPolicyNamingAFederatedUserCannotOverrideItsUsersExplicitDeny() {
        // A grant naming the federated user reaches a session with no session policy, past implicit
        // denies, but an explicit Deny in its user's policies still wins (IAM User Guide,
        // "Resource-based policies for AWS STS federated user principal sessions").
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "sts-fed-deny-" + suffix;
        S3RequestSigner admin = userWithPolicy("admin-" + bucket, ALL_S3);
        given().filter(admin).when().put("/" + bucket).then().statusCode(200);
        given().filter(admin).contentType("text/plain").body("payload")
                .when().put("/" + bucket + "/o.txt").then().statusCode(200);
        S3RequestSigner broker = userWithPolicy("broker-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:GetFederationToken","Resource":"*"}]}""");
        S3RequestSigner denier = userWithPolicy("denier-" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:GetFederationToken","Resource":"*"},
                  {"Effect":"Deny","Action":"s3:GetObject","Resource":"*"}]}""");
        given().filter(admin).contentType("application/json").body("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":["arn:aws:sts::%1$s:federated-user/Granted%2$s",
                                      "arn:aws:sts::%1$s:federated-user/Denied%2$s"]},
                  "Action":"s3:GetObject","Resource":"arn:aws:s3:::%3$s/*"}]}""".formatted(ACCOUNT, suffix, bucket))
                .when().put("/" + bucket + "?policy").then().statusCode(200);

        readObject(federate(broker, "Granted" + suffix, null), bucket, 200);
        readObject(federate(denier, "Denied" + suffix, null), bucket, 403);
    }

    @Test
    void aFederatedSessionCannotCallIamOrStsWhateverItsPoliciesAllow() {
        // GetFederationToken credentials cannot call any IAM operation, nor any STS operation except
        // GetCallerIdentity (STS API Reference, GetFederationToken).
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String iamAndSts = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["iam:*","sts:*"],"Resource":"*"}]}""";
        S3RequestSigner admin = userWithPolicy("iam-admin-" + suffix, iamAndSts);
        String role = "Trusting" + suffix;
        iam("CreateRole", "RoleName", role, "AssumeRolePolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":["arn:aws:iam::%1$s:root","arn:aws:sts::%1$s:federated-user/Named%2$s"]},
                  "Action":"sts:AssumeRole"}]}""".formatted(ACCOUNT, suffix));

        S3RequestSigner scoped = federate(admin, "Scoped" + suffix, iamAndSts);
        createUser(scoped, "by-session-" + suffix, 403);
        assumeRole(scoped, role, 403);
        // Nor does a trust policy naming the federated user let it assume the role.
        assumeRole(federate(admin, "Named" + suffix, null), role, 403);
        given().filter(scoped).formParam("Action", "GetCallerIdentity").when().post("/").then().statusCode(200);

        // The user that minted the sessions keeps its own IAM and STS access.
        createUser(admin, "by-user-" + suffix, 200);
        assumeRole(admin, role, 200);
    }

    /** A bucket in the test account holding {@code o.txt}, created by an administrator there. */
    private static String bucketWithObject(String bucket) {
        S3RequestSigner admin = userWithPolicy("admin-" + bucket, ALL_S3);
        given().filter(admin).when().put("/" + bucket).then().statusCode(200);
        given().filter(admin).contentType("text/plain").body("payload")
                .when().put("/" + bucket + "/o.txt").then().statusCode(200);
        return bucket;
    }

    private static void readObject(S3RequestSigner caller, String bucket, int status) {
        given().filter(caller).when().get("/" + bucket + "/o.txt").then().statusCode(status);
    }

    private static void writeObject(S3RequestSigner caller, String bucket, int status) {
        given().filter(caller).contentType("text/plain").body("written")
                .when().put("/" + bucket + "/" + UUID.randomUUID() + ".txt").then().statusCode(status);
    }

    private static void createUser(S3RequestSigner caller, String userName, int status) {
        given().filter(caller).formParam("Action", "CreateUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(status);
    }

    private static void assumeRole(S3RequestSigner caller, String role, int status) {
        given().filter(caller).formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT + ":role/" + role).formParam("RoleSessionName", "s")
                .when().post("/").then().statusCode(status);
    }

    private static S3RequestSigner userWithPolicy(String userName, String policy) {
        iam("CreateUser", "UserName", userName);
        iam("PutUserPolicy", "UserName", userName, "PolicyName", "inline", "PolicyDocument", policy);
        ExtractableResponse<Response> key = given().formParam("Action", "CreateAccessKey")
                .formParam("UserName", userName)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/")
                .then().statusCode(200).extract();
        return S3RequestSigner.signedAs(
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                key.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"));
    }

    private static S3RequestSigner federate(S3RequestSigner user, String name, String sessionPolicy) {
        RequestSpecification request = given().filter(user)
                .formParam("Action", "GetFederationToken").formParam("Name", name);
        if (sessionPolicy != null) {
            request = request.formParam("Policy", sessionPolicy);
        }
        ExtractableResponse<Response> issued = request.when().post("/").then().statusCode(200).extract();
        return S3RequestSigner.signedAs(
                issued.path("GetFederationTokenResponse.GetFederationTokenResult.Credentials.AccessKeyId"),
                issued.path("GetFederationTokenResponse.GetFederationTokenResult.Credentials.SecretAccessKey"),
                issued.path("GetFederationTokenResponse.GetFederationTokenResult.Credentials.SessionToken"));
    }

    private static S3RequestSigner sessionToken(S3RequestSigner user) {
        ExtractableResponse<Response> issued = given().filter(user).formParam("Action", "GetSessionToken")
                .when().post("/").then().statusCode(200).extract();
        return S3RequestSigner.signedAs(
                issued.path("GetSessionTokenResponse.GetSessionTokenResult.Credentials.AccessKeyId"),
                issued.path("GetSessionTokenResponse.GetSessionTokenResult.Credentials.SecretAccessKey"),
                issued.path("GetSessionTokenResponse.GetSessionTokenResult.Credentials.SessionToken"));
    }

    private static void iam(String action, String... params) {
        RequestSpecification request = given().formParam("Action", action)
                .header("Authorization", auth(ACCOUNT, "iam"));
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
