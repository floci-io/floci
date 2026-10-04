package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code floci.auth.validate-signatures} authenticates every signed S3 request, whichever
 * placement carries the signature, without authorizing it: a forged or unknown credential is
 * refused, while bucket policies are not evaluated and unsigned requests pass, as they do with
 * {@code enforce-auth} off. Shares {@link PreSignedUrlIntegrationTest}'s profile, which covers the
 * presigned query-string placement.
 */
@QuarkusTest
@TestProfile(PreSignedUrlIntegrationTest.PresignValidationProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ValidateSignaturesIntegrationTest {

    private static final String BUCKET = "validate-signatures-bucket";
    private static final String KEY = "object.txt";
    private static final String CREDENTIAL_DATE = "20260101";
    private static final String AMZ_DATE = CREDENTIAL_DATE + "T000000Z";
    private static final S3RequestSigner LOCAL_SIGNER = S3RequestSigner.signedAs("test", "test");
    private static final String DENY_ALL_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
            "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
            """.formatted(BUCKET);

    private static String userAccessKeyId;
    private static String userSecretKey;

    @Test
    @Order(1)
    void headerSignedRequestsWithAKnownKeyAreAccepted() {
        given().filter(LOCAL_SIGNER).when().put("/" + BUCKET).then().statusCode(200);
        given()
            .filter(LOCAL_SIGNER)
            .body("signed body")
        .when()
            .put("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200);
        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo("signed body"));
    }

    @Test
    @Order(2)
    void headerSignatureThatDoesNotVerifyIsRejected() {
        given()
            .filter(LOCAL_SIGNER.withSignature("0".repeat(64)))
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    @Order(3)
    void headerSignedRequestWithAnUnknownKeyIsRejected() {
        given()
            .filter(S3RequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret"))
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("InvalidAccessKeyId"));
    }

    @Test
    @Order(4)
    void headerSignedBodyThatDoesNotMatchItsHashIsRejected() {
        given()
            .filter(LOCAL_SIGNER.withContentSha256("0".repeat(64)))
            .body("tampered body")
        .when()
            .put("/" + BUCKET + "/tampered.txt")
        .then()
            .statusCode(400)
            .body("Error.Code", equalTo("XAmzContentSHA256Mismatch"));
    }

    @Test
    @Order(5)
    void unsignedRequestsAreLeftToEnforceAuth() {
        given().body("anonymous body").when().put("/" + BUCKET + "/anonymous.txt").then().statusCode(200);
        given().when().get("/" + BUCKET + "/anonymous.txt").then().statusCode(200).body(equalTo("anonymous body"));
    }

    @Test
    @Order(10)
    void verifiedCallerIsNotAuthorizedAgainstTheBucketPolicy() {
        createIamUser("validate-signatures-user");
        given()
            .filter(LOCAL_SIGNER)
            .body(DENY_ALL_POLICY)
        .when()
            .put("/" + BUCKET + "?policy")
        .then()
            .statusCode(200);
        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get("/" + BUCKET + "?policy")
        .then()
            .statusCode(200)
            .body(containsString("\"Deny\""));

        given()
            .filter(S3RequestSigner.signedAs(userAccessKeyId, userSecretKey))
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo("signed body"));
    }

    @Test
    @Order(20)
    void presignedPostWithAGenuineSignatureIsAccepted() {
        String key = "post-genuine.txt";
        String policy = postPolicy(key);
        String credential = "test/" + CREDENTIAL_DATE + "/us-east-1/s3/aws4_request";

        given()
            .multiPart("key", key)
            .multiPart("policy", policy)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", credential)
            .multiPart("x-amz-date", AMZ_DATE)
            .multiPart("x-amz-signature", signPolicy(policy, "test"))
            .multiPart("file", key, "posted".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(21)
    void presignedPostWithAForgedSignatureIsRejected() {
        String key = "post-forged.txt";
        String policy = postPolicy(key);

        given()
            .multiPart("key", key)
            .multiPart("policy", policy)
            .multiPart("x-amz-algorithm", "AWS4-HMAC-SHA256")
            .multiPart("x-amz-credential", "test/" + CREDENTIAL_DATE + "/us-east-1/s3/aws4_request")
            .multiPart("x-amz-date", AMZ_DATE)
            .multiPart("x-amz-signature", "0".repeat(64))
            .multiPart("file", key, "forged".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("SignatureDoesNotMatch"));

        given().filter(LOCAL_SIGNER).when().get("/" + BUCKET + "/" + key).then().statusCode(404);
    }

    @Test
    @Order(22)
    void presignedPostWithASignatureButNoPolicyIsRejected() {
        given()
            .multiPart("key", "post-partial.txt")
            .multiPart("x-amz-signature", "0".repeat(64))
            .multiPart("file", "post-partial.txt", "partial".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));
    }

    @Test
    @Order(23)
    void unsignedPostIsLeftToEnforceAuth() {
        given()
            .multiPart("key", "post-anonymous.txt")
            .multiPart("file", "post-anonymous.txt", "anonymous".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
            .post("/" + BUCKET)
        .then()
            .statusCode(204);
    }

    private static void createIamUser(String userName) {
        String authorization = "AWS4-HMAC-SHA256 Credential=test/" + CREDENTIAL_DATE
                + "/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=unused";
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserName", userName)
            .header("Authorization", authorization)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        XmlPath key = given()
            .formParam("Action", "CreateAccessKey")
            .formParam("UserName", userName)
            .header("Authorization", authorization)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .xmlPath();
        userAccessKeyId = key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        userSecretKey = key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");
    }

    private static String postPolicy(String key) {
        String expiration = DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().plusSeconds(3600).atZone(ZoneOffset.UTC));
        String policy = """
                {"expiration": "%s", "conditions": [{"bucket": "%s"}, {"key": "%s"}]}
                """.formatted(expiration, BUCKET, key);
        return Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));
    }

    private static String signPolicy(String policy, String secretKey) {
        try {
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(secretKey, CREDENTIAL_DATE, "us-east-1", "s3");
            return SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(signingKey, policy));
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the POST policy", e);
        }
    }
}
