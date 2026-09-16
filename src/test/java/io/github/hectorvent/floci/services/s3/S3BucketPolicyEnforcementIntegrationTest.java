package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(S3BucketPolicyEnforcementIntegrationTest.EnforcementProfile.class)
class S3BucketPolicyEnforcementIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_A = "111122223333";
    private static final String ACCOUNT_B = "222233334444";
    private static final S3RequestSigner ROOT_SIGNER = S3RequestSigner.signedAs("test", "test");

    record Credentials(String accessKeyId, String secretAccessKey, String userArn) {
        S3RequestSigner signer() {
            return S3RequestSigner.signedAs(accessKeyId, secretAccessKey);
        }
    }

    public static final class EnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.s3.enforce-auth", "true",
                    "floci.services.iam.enforcement-enabled", "true",
                    "floci.services.s3.global-bucket-namespace", "true"
            );
        }
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260629/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static Credentials createUser(String userName, String accountId) {
        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", userName)
                .header("Authorization", auth(accountId, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        ExtractableResponse<Response> response = given()
                .formParam("Action", "CreateAccessKey")
                .formParam("UserName", userName)
                .header("Authorization", auth(accountId, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract();

        String accessKeyId = response.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        String secretAccessKey = response.path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");
        String userArn = "arn:aws:iam::" + accountId + ":user/" + userName;
        return new Credentials(accessKeyId, secretAccessKey, userArn);
    }

    private static Credentials createUserWithCredentials(String userName) {
        return createUser(userName, "000000000000");
    }

    private static Credentials createAccountAdmin(String userName, String accountId) {
        Credentials credentials = createUser(userName, accountId);
        putUserPolicy(userName, "S3Admin", """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "s3:*",
                      "Resource": "*"
                    }
                  ]
                }""", accountId);
        return credentials;
    }

    private static void putUserPolicy(String userName, String policyName, String policyDocument, String accountId) {
        given()
                .formParam("Action", "PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", policyName)
                .formParam("PolicyDocument", policyDocument)
                .header("Authorization", auth(accountId, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static String createPolicy(String policyName, String policyDocument, String accountId) {
        ExtractableResponse<Response> response = given()
                .formParam("Action", "CreatePolicy")
                .formParam("PolicyName", policyName)
                .formParam("PolicyDocument", policyDocument)
                .header("Authorization", auth(accountId, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract();
        return response.path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    private static void putUserPermissionsBoundary(String userName, String boundaryArn, String accountId) {
        given()
                .formParam("Action", "PutUserPermissionsBoundary")
                .formParam("UserName", userName)
                .formParam("PermissionsBoundary", boundaryArn)
                .header("Authorization", auth(accountId, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void createBucket(String bucketName) {
        createBucketAs(bucketName, ROOT_SIGNER);
    }

    private static void createBucketAs(String bucketName, S3RequestSigner signer) {
        given()
                .filter(signer)
        .when()
                .put("/" + bucketName)
        .then()
                .statusCode(200);
    }

    private static void putObject(String bucketName, String key, String content) {
        putObjectAs(bucketName, key, content, ROOT_SIGNER);
    }

    private static void putObjectAs(String bucketName, String key, String content, S3RequestSigner signer) {
        given()
                .filter(signer)
                .contentType("text/plain")
                .body(content)
        .when()
                .put("/" + bucketName + "/" + key)
        .then()
                .statusCode(200);
    }

    private static void putBucketPolicy(String bucketName, String policyDocument) {
        putBucketPolicyAs(bucketName, policyDocument, ROOT_SIGNER);
    }

    private static void putBucketPolicyAs(String bucketName, String policyDocument, S3RequestSigner signer) {
        given()
                .filter(signer)
                .contentType("application/json")
                .body(policyDocument)
        .when()
                .put("/" + bucketName + "?policy")
        .then()
                .statusCode(200);
    }

    @Test
    void enforcesBucketPolicyForSignedCallers() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "bp-enforce-" + suffix;
        String key = "secret.txt";
        String content = "classified data";

        createBucket(bucket);
        putObject(bucket, key, content);

        Credentials alice = createUserWithCredentials("alice-" + suffix);
        Credentials bob = createUserWithCredentials("bob-" + suffix);
        Credentials charlie = createUserWithCredentials("charlie-" + suffix);

        // Policy allows Alice on object, Bob on bucket, explicitly denies Charlie on object
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": "%s"},
                      "Action": "s3:GetObject",
                      "Resource": "arn:aws:s3:::%s/*"
                    },
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": "%s"},
                      "Action": "s3:ListBucket",
                      "Resource": "arn:aws:s3:::%s"
                    },
                    {
                      "Effect": "Deny",
                      "Principal": {"AWS": "%s"},
                      "Action": "s3:GetObject",
                      "Resource": "arn:aws:s3:::%s/*"
                    }
                  ]
                }""".formatted(alice.userArn(), bucket, bob.userArn(), bucket, charlie.userArn(), bucket);
        putBucketPolicy(bucket, policy);

        // 1. Matching caller gets 200
        given()
                .filter(S3RequestSigner.signedAs(alice.accessKeyId(), alice.secretAccessKey()))
        .when()
                .get("/" + bucket + "/" + key)
        .then()
                .statusCode(200)
                .body(equalTo(content));

        // 2. Mismatched caller gets wire-correct 403 XML body with Resource element
        given()
                .filter(S3RequestSigner.signedAs(bob.accessKeyId(), bob.secretAccessKey()))
        .when()
                .get("/" + bucket + "/" + key)
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("<Resource>/" + bucket + "/" + key + "</Resource>"))
                .body(containsString("<RequestId>"));

        // 3. Explicit deny wins
        given()
                .filter(S3RequestSigner.signedAs(charlie.accessKeyId(), charlie.secretAccessKey()))
        .when()
                .get("/" + bucket + "/" + key)
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("<Resource>/" + bucket + "/" + key + "</Resource>"));

        // 4. Bucket-level action authorization and resource formatting
        given()
                .filter(S3RequestSigner.signedAs(bob.accessKeyId(), bob.secretAccessKey()))
        .when()
                .get("/" + bucket)
        .then()
                .statusCode(200);

        given()
                .filter(S3RequestSigner.signedAs(alice.accessKeyId(), alice.secretAccessKey()))
        .when()
                .get("/" + bucket)
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("<Resource>/" + bucket + "</Resource>"));
    }

    @Test
    void crossAccountPrimaryRequestRequiresBothIdentityAndBucketPolicyAllows() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "cross-bucket-" + suffix;
        String key = "cross-test.txt";
        String content = "cross-account data";

        Credentials accountAAdmin = createAccountAdmin("admin-a-" + suffix, ACCOUNT_A);
        createBucketAs(bucket, accountAAdmin.signer());
        putObjectAs(bucket, key, content, accountAAdmin.signer());

        Credentials userB = createUser("user-b-" + suffix, ACCOUNT_B);

        // Account A bucket policy allows User B to GetObject
        String bucketPolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": "%s"},
                      "Action": "s3:GetObject",
                      "Resource": "arn:aws:s3:::%s/*"
                    }
                  ]
                }""".formatted(userB.userArn(), bucket);
        putBucketPolicyAs(bucket, bucketPolicy, accountAAdmin.signer());

        // 1. User B has bucket policy allow but NO identity policy allow -> 403 AccessDenied with Resource
        given()
                .filter(userB.signer())
        .when()
                .get("/" + bucket + "/" + key)
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("<Resource>/" + bucket + "/" + key + "</Resource>"));

        // 2. Grant identity policy to User B -> now both allow -> 200 OK
        putUserPolicy(
                "user-b-" + suffix,
                "ReadCrossBucket",
                """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "s3:GetObject",
                      "Resource": "arn:aws:s3:::%s/*"
                    }
                  ]
                }""".formatted(bucket),
                ACCOUNT_B);

        given()
                .filter(userB.signer())
        .when()
                .get("/" + bucket + "/" + key)
        .then()
                .statusCode(200)
                .body(equalTo(content));
    }

    @Test
    void sameAccountDirectUserBypassesBoundaryOnPrimaryRequest() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "boundary-bucket-" + suffix;
        String key = "data.txt";
        String content = "boundary bypass data";

        createBucket(bucket);
        putObject(bucket, key, content);

        // Create boundary policy that only allows dynamodb:* (blocks s3:GetObject)
        String boundaryPolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "dynamodb:*",
                      "Resource": "*"
                    }
                  ]
                }""";
        String boundaryArn = createPolicy("boundary-" + suffix, boundaryPolicy, "000000000000");

        String userName = "alice-bnd-" + suffix;
        Credentials alice = createUserWithCredentials(userName);
        putUserPermissionsBoundary(userName, boundaryArn, "000000000000");

        // Identity policy allows s3:GetObject (would be blocked by boundary alone)
        putUserPolicy(userName, "S3Read", """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "s3:GetObject",
                      "Resource": "arn:aws:s3:::%s/*"
                    }
                  ]
                }""".formatted(bucket), "000000000000");

        // 1. Bucket policy grants to account root: boundary is NOT bypassed -> 403
        String accountGrantPolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": "arn:aws:iam::000000000000:root"},
                      "Action": "s3:GetObject",
                      "Resource": "arn:aws:s3:::%s/*"
                    }
                  ]
                }""".formatted(bucket);
        putBucketPolicy(bucket, accountGrantPolicy);

        given()
                .filter(alice.signer())
        .when()
                .get("/" + bucket + "/" + key)
        .then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("<Resource>/" + bucket + "/" + key + "</Resource>"));

        // 2. Bucket policy directly names Alice's user ARN: boundary IS bypassed -> 200
        String directUserPolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": "%s"},
                      "Action": "s3:GetObject",
                      "Resource": "arn:aws:s3:::%s/*"
                    }
                  ]
                }""".formatted(alice.userArn(), bucket);
        putBucketPolicy(bucket, directUserPolicy);

        given()
                .filter(alice.signer())
        .when()
                .get("/" + bucket + "/" + key)
        .then()
                .statusCode(200)
                .body(equalTo(content));
    }
}

