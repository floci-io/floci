package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code floci.auth.validate-signatures} authenticates every signed S3 request, whichever
 * placement carries the signature, without authorizing it: a forged, unknown or expired credential is
 * refused, while bucket policies are not evaluated and unsigned requests pass, as they do with
 * {@code enforce-auth} off. Shares {@link ValidateSignaturesProfile} with
 * {@link PreSignedUrlIntegrationTest}, which covers the presigned query-string placement.
 */
@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
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

    private static final String SIGNIN_CLIENT_ID = "arn:aws:signin:::devtools/same-device";
    private static final String SIGNIN_REDIRECT_URI = "http://127.0.0.1:4567/oauth/callback";
    private static final String SIGNIN_VERIFIER = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";

    private static String userAccessKeyId;
    private static String userSecretKey;

    @Inject
    MutableClock clock;

    @Test
    void layerContentLocationCanBeFetchedWithSignatureValidationEnabled() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("nodejs/index.js"));
            zip.write("module.exports = {};".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String location = given()
            .contentType("application/json")
            .body("""
                    {"Content":{"ZipFile":"%s"}}
                    """.formatted(Base64.getEncoder().encodeToString(archive.toByteArray())))
        .when()
            .post("/2018-10-31/layers/signed-location/versions")
        .then()
            .statusCode(201)
            .body("Content.Location", containsString("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
            .extract().path("Content.Location");

        given().urlEncodingEnabled(false).when().get(location).then().statusCode(200);
    }

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

    @Test
    @Order(30)
    void headerWithMalformedSigV4IsRejectedWithoutCreatingBucket() {
        given()
            .header("Authorization", "X Credential=111122223333/20261001/us-east-1/s3/aws4_request")
        .when()
            .put("/routing-check-malformed")
        .then()
            .statusCode(400)
            .body("Error.Code", equalTo("AuthorizationHeaderMalformed"))
            .body("Error.Message", equalTo("The authorization header you provided is invalid."));

        given().filter(LOCAL_SIGNER).when().get("/routing-check-malformed").then().statusCode(404);
    }

    @Test
    @Order(31)
    void presignedQueryMissingAlgorithmIsRejectedWithoutCreatingBucket() {
        given()
        .when()
            .put("/routing-check-missing-algo?X-Amz-Credential=111122223333%2F20261001%2Fus-east-1%2Fs3%2Faws4_request")
        .then()
            .statusCode(400)
            .body("Error.Code", equalTo("AuthorizationQueryParametersError"))
            .body("Error.Message", equalTo(S3RequestAuthorizationParser.AUTHORIZATION_QUERY_PARAMETERS_ERROR_MESSAGE));

        given().filter(LOCAL_SIGNER).when().get("/routing-check-missing-algo").then().statusCode(404);
    }

    @Test
    @Order(32)
    void headerSignedRequestWithDateOrExpiresQueryParamIsAccepted() {
        given()
            .filter(LOCAL_SIGNER)
        .when()
            .put("/" + BUCKET + "/query-date-test.txt?X-Amz-Date=" + AMZ_DATE + "&X-Amz-Expires=3600")
        .then()
            .statusCode(200);

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get("/" + BUCKET + "/query-date-test.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(33)
    void sigV4AHeaderIsRejectedWhenSignatureValidationEnabled() {
        String authSigV4A = "AWS4-ECDSA-P256-SHA256 Credential=test/" + CREDENTIAL_DATE
                + "/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=30450220abc";
        given()
            .header("Authorization", authSigV4A)
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(400)
            .body(containsString("<Code>AuthorizationHeaderMalformed</Code>"));
    }

    @Test
    @Order(34)
    void headerSignedRequestsWithSigninCredentialsAreAcceptedUntilTheyExpire() throws Exception {
        // Sign-In stamps its access token's expiry from the injected clock, so IAM measures it on the same one.
        Response token = signIn();
        S3RequestSigner signinSigner = S3RequestSigner.signedAs(token.path("accessToken.accessKeyId"),
                token.path("accessToken.secretAccessKey"), token.path("accessToken.sessionToken"));
        given()
            .filter(signinSigner)
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200);

        clock.advance(Duration.ofSeconds(token.<Integer>path("expiresIn") + 1L));
        // A known gap from AWS, not the intended answer: S3 lists 400 ExpiredToken for a session past
        // its expiry, which only IAM enforcement gives today. Signature validation still answers it
        // as a key it does not know.
        given()
            .filter(signinSigner)
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("InvalidAccessKeyId"));
    }

    /** Runs the AWS Sign-In authorization-code flow through its consent page and returns the token response. */
    private static Response signIn() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(SIGNIN_VERIFIER.getBytes(StandardCharsets.US_ASCII));
        String consent = given()
            .redirects().follow(false)
            .queryParam("client_id", SIGNIN_CLIENT_ID)
            .queryParam("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
            .queryParam("code_challenge_method", "SHA-256")
            .queryParam("redirect_uri", SIGNIN_REDIRECT_URI)
            .queryParam("response_type", "code")
            .queryParam("scope", "openid")
            .queryParam("state", "validate-signatures")
        .when()
            .get("/v1/authorize")
        .then()
            .statusCode(302)
            .extract().header("Location");
        String callback = given()
            .redirects().follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("request_id", queryParams(URI.create(consent)).get("request_id"))
            .formParam("action", "continue")
        .when()
            .post("/_floci/signin/consent")
        .then()
            .statusCode(302)
            .extract().header("Location");
        return given()
            .contentType("application/json")
            .body(Map.of(
                    "clientId", SIGNIN_CLIENT_ID,
                    "grantType", "authorization_code",
                    "code", queryParams(URI.create(callback)).get("code"),
                    "redirectUri", SIGNIN_REDIRECT_URI,
                    "codeVerifier", SIGNIN_VERIFIER))
        .when()
            .post("/v1/token")
        .then()
            .statusCode(200)
            .extract().response();
    }

    private static Map<String, String> queryParams(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }

    private static void createIamUser(String userName) {
        // validate-signatures verifies IAM's signatures too, so these calls carry real ones.
        AwsRequestSigner iam = AwsRequestSigner.signedAs("test", "test", "iam");
        given()
            .filter(iam)
            .contentType("application/x-www-form-urlencoded")
            .body("Action=CreateUser&UserName=" + userName + "&Version=2010-05-08")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        XmlPath key = given()
            .filter(iam)
            .contentType("application/x-www-form-urlencoded")
            .body("Action=CreateAccessKey&UserName=" + userName + "&Version=2010-05-08")
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
