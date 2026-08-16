package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(S3AuthEnforcementIntegrationTest.S3AuthProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AuthEnforcementIntegrationTest {

    private static final String PUBLIC_BUCKET = "auth-public-bucket";
    private static final String PRIVATE_BUCKET = "auth-private-bucket";
    private static final String WEBSITE_BUCKET = "auth-website-bucket";
    private static final String WEBSITE_ERROR_BUCKET = "auth-website-error-bucket";
    private static final String BUCKET_ACL_BUCKET = "auth-bucket-acl-bucket";
    private static final String DENY_BUCKET = "auth-deny-bucket";
    private static final String GET_ONLY_BUCKET = "auth-get-only-bucket";
    private static final String VERSION_BUCKET = "auth-version-bucket";
    private static final String WRITE_BUCKET = "auth-write-bucket";
    private static final String PUBLIC_WRITE_BUCKET = "auth-public-write-bucket";
    private static final String PUBLIC_WRITE_ACL_BUCKET = "auth-public-write-acl-bucket";
    private static final String DENY_WRITE_ACL_BUCKET = "auth-deny-write-acl-bucket";
    private static final String DELETE_VERSION_BUCKET = "auth-delete-version-bucket";
    private static final String BYPASS_BUCKET = "auth-bypass-governance-bucket";
    private static final String BYPASS_KEY = "bypass-target.txt";
    private static final String CONDITIONAL_DENY_ACL_BUCKET = "auth-conditional-deny-acl-bucket";
    private static final String PUBLIC_KEY = "public.txt";
    private static final String PRIVATE_KEY = "private.txt";
    private static final String ERROR_KEY = "error.html";
    private static final String ACL_KEY = "acl-list.txt";
    private static final String DENY_KEY = "deny.txt";
    private static final String VERSION_KEY = "versioned.txt";
    private static final String WRITE_KEY = "signed.txt";
    private static final String ANON_WRITE_KEY = "anon.txt";
    private static final String SIGNING_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    private static final String SIGNING_DATE = SIGNING_TIMESTAMP.substring(0, 8);
    private static final String LOCAL_AUTH_HEADER = authorizationHeader("test");
    private static final String BAD_AUTH_HEADER = authorizationHeader("bad-key");
    private static final String ACCOUNT_SHAPED_AUTH_HEADER = authorizationHeader("123456789012");

    @Test
    @Order(1)
    void createBucketsAndObjects() {
        given().when().put("/" + PUBLIC_BUCKET).then().statusCode(200);
        given().when().put("/" + PRIVATE_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("public body")
        .when()
            .put("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY)
        .then()
            .statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("private body")
        .when()
            .put("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body(publicReadPolicy(PUBLIC_BUCKET))
        .when()
            .put("/" + PUBLIC_BUCKET + "?policy")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void unsignedRequestCanReadPublicObject() {
        given()
        .when()
            .get("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY)
        .then()
            .statusCode(200)
            .body(equalTo("public body"));

        given()
        .when()
            .head("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void unsignedRequestCanListPublicBucket() {
        given()
        .when()
            .get("/" + PUBLIC_BUCKET + "?list-type=2")
        .then()
            .statusCode(200)
            .body(containsString("<Key>" + PUBLIC_KEY + "</Key>"));
    }

    @Test
    @Order(4)
    void unsignedRequestCannotReadPrivateObject() {
        given()
        .when()
            .get("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
        .when()
            .head("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(403);
    }

    @Test
    @Order(5)
    void unsignedRequestCannotListPrivateBucket() {
        given()
        .when()
            .get("/" + PRIVATE_BUCKET + "?list-type=2")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(6)
    void signedRequestWithBadAccessKeyCannotUsePublicAccess() {
        given()
            .header("Authorization", BAD_AUTH_HEADER)
        .when()
            .get("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY)
        .then()
            .statusCode(403)
            .body(containsString("InvalidAccessKeyId"));
    }

    @Test
    @Order(7)
    void signedRequestWithAccountShapedAccessKeyCannotReadPrivateObject() {
        given()
            .header("Authorization", ACCOUNT_SHAPED_AUTH_HEADER)
        .when()
            .get("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("InvalidAccessKeyId"));
    }

    @Test
    @Order(8)
    void presignedRequestWithBadAccessKeyCannotUsePublicAccess() {
        String path = "/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY;
        String sig = presignedSignature("GET", path, "bad-key", "bad-key", "3600");
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("bad-key"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", sig)
        .when()
            .get(path)
        .then()
            .statusCode(403)
            .body(containsString("InvalidAccessKeyId"));
    }

    @Test
    @Order(9)
    void malformedPresignedRequestCannotUsePublicAccess() {
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
        .when()
            .get("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY)
        .then()
            .statusCode(400)
            .body("Error.Code", equalTo("AuthorizationQueryParametersError"))
            .body(containsString("Query-string authentication version 4 requires the X-Amz-Algorithm"));
    }

    @Test
    @Order(10)
    void signedRequestWithLocalAccessKeyCanReadPrivateObject() {
        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .get("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(200)
            .body(equalTo("private body"));
    }

    @Test
    @Order(11)
    void presignedRequestWithLocalAccessKeyCanReadPrivateObject() {
        String path = "/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY;
        String sig = presignedSignature("GET", path, "test", "test", "3600");
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("test"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", sig)
        .when()
            .get(path)
        .then()
            .statusCode(200)
            .body(equalTo("private body"));
    }

    @Test
    @Order(12)
    void websiteRootAuthorizesIndexObjectReadNotBucketList() {
        given().when().put("/" + WEBSITE_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .contentType("text/html")
            .body("<html>index</html>")
        .when()
            .put("/" + WEBSITE_BUCKET + "/index.html")
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .body(websiteConfiguration())
        .when()
            .put("/" + WEBSITE_BUCKET + "?website")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body(publicGetObjectPolicy(WEBSITE_BUCKET))
        .when()
            .put("/" + WEBSITE_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
            .header("Host", WEBSITE_BUCKET + ".s3-website-us-east-1.localhost:"
                    + io.restassured.RestAssured.port)
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(equalTo("<html>index</html>"));

        given()
        .when()
            .get("/" + WEBSITE_BUCKET + "?list-type=2")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(13)
    void websiteRootUsesErrorDocumentForDeniedIndexObject() {
        given().when().put("/" + WEBSITE_ERROR_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .contentType("text/html")
            .body("<html>private index</html>")
        .when()
            .put("/" + WEBSITE_ERROR_BUCKET + "/index.html")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .contentType("text/html")
            .body("<html>denied</html>")
        .when()
            .put("/" + WEBSITE_ERROR_BUCKET + "/" + ERROR_KEY)
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .body(websiteConfiguration(ERROR_KEY))
        .when()
            .put("/" + WEBSITE_ERROR_BUCKET + "?website")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body(publicGetObjectPolicy(WEBSITE_ERROR_BUCKET, ERROR_KEY))
        .when()
            .put("/" + WEBSITE_ERROR_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
            .header("Host", WEBSITE_ERROR_BUCKET + ".s3-website-us-east-1.localhost:"
                    + io.restassured.RestAssured.port)
        .when()
            .get("/")
        .then()
            .statusCode(403)
            .header("x-amz-error-code", "AccessDenied")
            .body(equalTo("<html>denied</html>"));
    }

    @Test
    @Order(14)
    void bucketAclPublicReadAllowsUnsignedList() {
        given().when().put("/" + BUCKET_ACL_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("listed")
        .when()
            .put("/" + BUCKET_ACL_BUCKET + "/" + ACL_KEY)
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .body(publicReadAcl())
        .when()
            .put("/" + BUCKET_ACL_BUCKET + "?acl")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET_ACL_BUCKET + "?list-type=2")
        .then()
            .statusCode(200)
            .body(containsString("<Key>" + ACL_KEY + "</Key>"));
    }

    @Test
    @Order(15)
    void explicitBucketPolicyDenyOverridesPublicObjectAcl() {
        given().when().put("/" + DENY_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .header("x-amz-acl", "public-read")
            .body("denied")
        .when()
            .put("/" + DENY_BUCKET + "/" + DENY_KEY)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body(denyGetObjectPolicy(DENY_BUCKET))
        .when()
            .put("/" + DENY_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + DENY_BUCKET + "/" + DENY_KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(16)
    void unsignedRequestCannotReadPrivateBucketSubresources() {
        given()
        .when()
            .get("/" + PRIVATE_BUCKET + "?acl")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));

        given()
        .when()
            .get("/" + PRIVATE_BUCKET + "?versions")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));
    }

    @Test
    @Order(17)
    void publicGetObjectPolicyDoesNotAuthorizeObjectSubresources() {
        given()
        .when()
            .get("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY + "?acl")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));

        given()
        .when()
            .get("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY + "?tagging")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));
    }

    @Test
    @Order(18)
    void headBucketHonorsAuthEnforcement() {
        given()
        .when()
            .head("/" + PRIVATE_BUCKET)
        .then()
            .statusCode(403);

        given()
            .header("Authorization", BAD_AUTH_HEADER)
        .when()
            .head("/" + PUBLIC_BUCKET)
        .then()
            .statusCode(403);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .head("/" + PRIVATE_BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(19)
    void selectObjectContentHonorsReadAuthorization() {
        given()
            .contentType("application/xml")
            .body(selectRequest())
        .when()
            .post("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY + "?select&select-type=2")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));

        given()
            .header("Authorization", BAD_AUTH_HEADER)
            .contentType("application/xml")
            .body(selectRequest())
        .when()
            .post("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY + "?select&select-type=2")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("InvalidAccessKeyId"));
    }

    @Test
    @Order(20)
    void missingObjectRequiresListBucketToReturnNoSuchKey() {
        given().when().put("/" + GET_ONLY_BUCKET).then().statusCode(200);

        given()
            .contentType("application/json")
            .body(publicGetObjectPolicy(GET_ONLY_BUCKET))
        .when()
            .put("/" + GET_ONLY_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + GET_ONLY_BUCKET + "/missing.txt")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));

        given()
        .when()
            .get("/" + PUBLIC_BUCKET + "/missing.txt")
        .then()
            .statusCode(404)
            .body("Error.Code", equalTo("NoSuchKey"));
    }

    @Test
    @Order(21)
    void versionedObjectReadRequiresGetObjectVersion() {
        given().when().put("/" + VERSION_BUCKET).then().statusCode(200);

        given()
            .contentType("application/xml")
            .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
            .put("/" + VERSION_BUCKET + "?versioning")
        .then()
            .statusCode(200);

        String versionId = given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("versioned body")
        .when()
            .put("/" + VERSION_BUCKET + "/" + VERSION_KEY)
        .then()
            .statusCode(200)
            .extract().header("x-amz-version-id");

        given()
            .contentType("application/json")
            .body(publicObjectActionPolicy(VERSION_BUCKET, "s3:GetObject"))
        .when()
            .put("/" + VERSION_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + VERSION_BUCKET + "/" + VERSION_KEY + "?versionId=" + versionId)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));

        given()
            .contentType("application/json")
            .body(publicObjectActionPolicy(VERSION_BUCKET, "s3:GetObjectVersion"))
        .when()
            .put("/" + VERSION_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + VERSION_BUCKET + "/" + VERSION_KEY + "?versionId=" + versionId)
        .then()
            .statusCode(200)
            .body(equalTo("versioned body"));
    }

    @Test
    @Order(22)
    void presignedRequestWithExplicitContentSha256IsAccepted() throws Exception {
        String path = "/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY;
        String contentHash = sha256Hex("private body");
        String sig = presignedSignature("GET", path, "test", "test", "3600", contentHash);
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("test"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Content-Sha256", contentHash)
            .queryParam("X-Amz-Signature", sig)
        .when()
            .get(path)
        .then()
            .statusCode(200)
            .body(equalTo("private body"));
    }

    @Test
    @Order(23)
    void presignedRequestWithMismatchedContentSha256IsRejected() throws Exception {
        String path = "/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY;
        String contentHash = sha256Hex("private body");
        String wrongHash = sha256Hex("wrong body");
        String sig = presignedSignature("GET", path, "test", "test", "3600", contentHash);
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("test"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Content-Sha256", wrongHash)
            .queryParam("X-Amz-Signature", sig)
        .when()
            .get(path)
        .then()
            .statusCode(403)
            .body(containsString("SignatureDoesNotMatch"));
    }

    @Test
    @Order(24)
    void presignedRequestWithTamperedSignatureIsRejected() {
        String path = "/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY;
        String signature = presignedSignature("GET", path, "test", "test", "3600");
        String tampered = signature.substring(0, signature.length() - 1)
                + (signature.endsWith("0") ? "1" : "0");
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("test"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", tampered)
        .when()
            .get(path)
        .then()
            .statusCode(403)
            .body(containsString("SignatureDoesNotMatch"));
    }

    @Test
    @Order(25)
    void presignedRequestWithExpiresExceedingMaxIsRejected() {
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("test"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "604801")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", "dummy")
        .when()
            .get("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(400)
            .body(containsString("AuthorizationQueryParametersError"));
    }

    @Test
    @Order(26)
    void presignedRequestWithExpiresZeroIsRejected() {
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("test"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "0")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", "dummy")
        .when()
            .get("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(400)
            .body(containsString("AuthorizationQueryParametersError"));
    }

    @Test
    @Order(27)
    void presignedRequestWithUnsupportedAlgorithmIsRejected() {
        given()
            .queryParam("X-Amz-Algorithm", "BOGUS-ALGORITHM")
            .queryParam("X-Amz-Credential", credential("test"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", "dummy")
        .when()
            .get("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(400)
            .body(containsString("AuthorizationQueryParametersError"));
    }

    @Test
    @Order(28)
    void unsignedRequestCannotPutObject() {
        given().when().put("/" + WRITE_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("signed body")
        .when()
            .put("/" + WRITE_BUCKET + "/" + WRITE_KEY)
        .then()
            .statusCode(200);

        given()
            .body("injected-by-anonymous")
        .when()
            .put("/" + WRITE_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .get("/" + WRITE_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(29)
    void unsignedRequestCannotDeleteObject() {
        given()
        .when()
            .delete("/" + WRITE_BUCKET + "/" + WRITE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .get("/" + WRITE_BUCKET + "/" + WRITE_KEY)
        .then()
            .statusCode(200)
            .body(equalTo("signed body"));

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .delete("/" + WRITE_BUCKET + "/" + WRITE_KEY)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(30)
    void signedRequestWithBadAccessKeyCannotWriteObject() {
        given()
            .header("Authorization", BAD_AUTH_HEADER)
            .body("bad key body")
        .when()
            .put("/" + WRITE_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("InvalidAccessKeyId"));

        given()
            .header("Authorization", BAD_AUTH_HEADER)
        .when()
            .delete("/" + WRITE_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("InvalidAccessKeyId"));
    }

    @Test
    @Order(31)
    void bucketPolicyCanExplicitlyAllowAnonymousWrite() {
        given().when().put("/" + PUBLIC_WRITE_BUCKET).then().statusCode(200);

        given()
            .contentType("application/json")
            .body(publicObjectActionPolicy(PUBLIC_WRITE_BUCKET, "s3:PutObject"))
        .when()
            .put("/" + PUBLIC_WRITE_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
            .body("public write body")
        .when()
            .put("/" + PUBLIC_WRITE_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body(publicObjectActionPolicy(PUBLIC_WRITE_BUCKET, "s3:DeleteObject"))
        .when()
            .put("/" + PUBLIC_WRITE_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .delete("/" + PUBLIC_WRITE_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(32)
    void unsignedRequestCannotWriteObjectSubresources() {
        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("subresource body")
        .when()
            .put("/" + WRITE_BUCKET + "/subresource.txt")
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .body("<Tagging><TagSet><Tag><Key>a</Key><Value>b</Value></Tag></TagSet></Tagging>")
        .when()
            .put("/" + WRITE_BUCKET + "/subresource.txt?tagging")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .contentType("application/xml")
            .body("<Retention><Mode>GOVERNANCE</Mode><RetainUntilDate>2099-01-01T00:00:00Z</RetainUntilDate></Retention>")
        .when()
            .put("/" + WRITE_BUCKET + "/subresource.txt?retention")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .contentType("application/xml")
            .body("<LegalHold><Status>ON</Status></LegalHold>")
        .when()
            .put("/" + WRITE_BUCKET + "/subresource.txt?legal-hold")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .contentType("application/xml")
            .body(publicReadAcl())
        .when()
            .put("/" + WRITE_BUCKET + "/subresource.txt?acl")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
        .when()
            .delete("/" + WRITE_BUCKET + "/subresource.txt?tagging")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(33)
    void unsignedRequestCannotWriteViaMultipartOrRestore() {
        given()
        .when()
            .post("/" + WRITE_BUCKET + "/multipart.txt?uploads")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        String uploadId = given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .post("/" + WRITE_BUCKET + "/multipart.txt?uploads")
        .then()
            .statusCode(200)
            .extract().body().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body("part data")
        .when()
            .put("/" + WRITE_BUCKET + "/multipart.txt?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        String eTag = given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("part data")
        .when()
            .put("/" + WRITE_BUCKET + "/multipart.txt?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .extract().header("ETag");

        given()
            .contentType("application/xml")
            .body(completeMultipartBody(eTag))
        .when()
            .post("/" + WRITE_BUCKET + "/multipart.txt?uploadId=" + uploadId)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
        .when()
            .delete("/" + WRITE_BUCKET + "/multipart.txt?uploadId=" + uploadId)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .contentType("application/xml")
            .body(completeMultipartBody(eTag))
        .when()
            .post("/" + WRITE_BUCKET + "/multipart.txt?uploadId=" + uploadId)
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .body("<RestoreRequest><Days>1</Days></RestoreRequest>")
        .when()
            .post("/" + WRITE_BUCKET + "/multipart.txt?restore")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(34)
    void unsignedRequestCannotCopyObject() {
        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("copy source body")
        .when()
            .put("/" + WRITE_BUCKET + "/copy-source.txt")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-copy-source", "/" + WRITE_BUCKET + "/copy-source.txt")
        .when()
            .put("/" + WRITE_BUCKET + "/copy-dest.txt")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .header("x-amz-copy-source", "/" + WRITE_BUCKET + "/copy-source.txt")
        .when()
            .put("/" + WRITE_BUCKET + "/copy-dest.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(35)
    void batchDeleteObjectsDeniesUnauthorizedKeysButAllowsAuthorized() {
        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("batch a")
        .when()
            .put("/" + WRITE_BUCKET + "/batch-a.txt")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("batch b")
        .when()
            .put("/" + WRITE_BUCKET + "/batch-b.txt")
        .then()
            .statusCode(200);

        String deleteXml = """
                <Delete>
                  <Object><Key>batch-a.txt</Key></Object>
                  <Object><Key>batch-b.txt</Key></Object>
                </Delete>
                """;

        given()
            .contentType("application/xml")
            .body(deleteXml)
        .when()
            .post("/" + WRITE_BUCKET + "?delete")
        .then()
            .statusCode(200)
            .body(containsString("<Code>AccessDenied</Code>"))
            .body(containsString("<Key>batch-a.txt</Key>"))
            .body(containsString("<Key>batch-b.txt</Key>"))
            .body(not(containsString("<Deleted>")));

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .get("/" + WRITE_BUCKET + "/batch-a.txt")
        .then()
            .statusCode(200)
            .body(equalTo("batch a"));

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .contentType("application/xml")
            .body(deleteXml)
        .when()
            .post("/" + WRITE_BUCKET + "?delete")
        .then()
            .statusCode(200)
            .body(containsString("<Deleted>"))
            .body(not(containsString("AccessDenied")));
    }

    @Test
    @Order(36)
    void batchDeleteObjectsWithUnknownAccessKeyFailsWholeRequest() {
        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("bad-key batch a")
        .when()
            .put("/" + WRITE_BUCKET + "/bad-key-batch-a.txt")
        .then()
            .statusCode(200);

        String deleteXml = """
                <Delete>
                  <Object><Key>bad-key-batch-a.txt</Key></Object>
                </Delete>
                """;

        given()
            .header("Authorization", BAD_AUTH_HEADER)
            .contentType("application/xml")
            .body(deleteXml)
        .when()
            .post("/" + WRITE_BUCKET + "?delete")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("InvalidAccessKeyId"));

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .get("/" + WRITE_BUCKET + "/bad-key-batch-a.txt")
        .then()
            .statusCode(200)
            .body(equalTo("bad-key batch a"));
    }

    @Test
    @Order(37)
    void bucketAclPublicReadWriteAllowsAnonymousCreateButNotOverwriteOrDelete() {
        // Per AWS's ACL docs, a bucket-ACL WRITE grant to a non-owner (like AllUsers) "denies
        // non-owners the ability to overwrite or delete existing objects" -- it only lets them
        // create new ones.
        given().when().put("/" + PUBLIC_WRITE_ACL_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .header("x-amz-acl", "public-read-write")
        .when()
            .put("/" + PUBLIC_WRITE_ACL_BUCKET + "?acl")
        .then()
            .statusCode(200);

        given()
            .body("anonymous via public-read-write ACL")
        .when()
            .put("/" + PUBLIC_WRITE_ACL_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
        .when()
            .get("/" + PUBLIC_WRITE_ACL_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(200)
            .body(equalTo("anonymous via public-read-write ACL"));

        given()
            .body("overwrite attempt")
        .when()
            .put("/" + PUBLIC_WRITE_ACL_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
        .when()
            .delete("/" + PUBLIC_WRITE_ACL_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(38)
    void explicitPolicyDenyOverridesPublicWriteAcl() {
        given().when().put("/" + DENY_WRITE_ACL_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .header("x-amz-acl", "public-read-write")
        .when()
            .put("/" + DENY_WRITE_ACL_BUCKET + "?acl")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body(denyObjectActionPolicy(DENY_WRITE_ACL_BUCKET, "s3:PutObject"))
        .when()
            .put("/" + DENY_WRITE_ACL_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
            .body("should stay denied despite public-read-write ACL")
        .when()
            .put("/" + DENY_WRITE_ACL_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    private static String denyObjectActionPolicy(String bucket, String action) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Deny",
                    "Principal": "*",
                    "Action": "%s",
                    "Resource": "arn:aws:s3:::%s/*"
                  }
                }
                """.formatted(action, bucket);
    }

    @Test
    @Order(39)
    void publicWriteAclDoesNotAuthorizeObjectSubresources() {
        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("subresource body")
        .when()
            .put("/" + PUBLIC_WRITE_ACL_BUCKET + "/acl-subresource.txt")
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .body("<Tagging><TagSet><Tag><Key>a</Key><Value>b</Value></Tag></TagSet></Tagging>")
        .when()
            .put("/" + PUBLIC_WRITE_ACL_BUCKET + "/acl-subresource.txt?tagging")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        given()
            .contentType("application/xml")
            .body(publicReadAcl())
        .when()
            .put("/" + PUBLIC_WRITE_ACL_BUCKET + "/acl-subresource.txt?acl")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(40)
    void deleteObjectPolicyGrantDoesNotAuthorizeVersionedDelete() {
        given().when().put("/" + DELETE_VERSION_BUCKET).then().statusCode(200);

        given()
            .contentType("application/xml")
            .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
            .put("/" + DELETE_VERSION_BUCKET + "?versioning")
        .then()
            .statusCode(200);

        String versionId = given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("versioned delete target")
        .when()
            .put("/" + DELETE_VERSION_BUCKET + "/" + VERSION_KEY)
        .then()
            .statusCode(200)
            .extract().header("x-amz-version-id");

        given()
            .contentType("application/json")
            .body(publicObjectActionPolicy(DELETE_VERSION_BUCKET, "s3:DeleteObject"))
        .when()
            .put("/" + DELETE_VERSION_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .delete("/" + DELETE_VERSION_BUCKET + "/" + VERSION_KEY)
        .then()
            .statusCode(204);

        given()
        .when()
            .delete("/" + DELETE_VERSION_BUCKET + "/" + VERSION_KEY + "?versionId=" + versionId)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));
    }

    @Test
    @Order(41)
    void deleteObjectDoesNotBypassGovernanceRetentionWithoutDistinctPermission() {
        given().when().put("/" + BYPASS_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .body("locked candidate")
        .when()
            .put("/" + BYPASS_BUCKET + "/" + BYPASS_KEY)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body(publicObjectActionPolicy(BYPASS_BUCKET, "s3:DeleteObject"))
        .when()
            .put("/" + BYPASS_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-bypass-governance-retention", "true")
        .when()
            .delete("/" + BYPASS_BUCKET + "/" + BYPASS_KEY)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("AccessDenied"));

        given()
            .contentType("application/json")
            .body(deleteAndBypassGovernancePolicy(BYPASS_BUCKET))
        .when()
            .put("/" + BYPASS_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-bypass-governance-retention", "true")
        .when()
            .delete("/" + BYPASS_BUCKET + "/" + BYPASS_KEY)
        .then()
            .statusCode(204);
    }

    private static String deleteAndBypassGovernancePolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": ["s3:DeleteObject", "s3:BypassGovernanceRetention"],
                    "Resource": "arn:aws:s3:::%s/*"
                  }
                }
                """.formatted(bucket);
    }

    @Test
    @Order(42)
    void conditionalPolicyDenyFailsClosedOverridingPublicWriteAcl() {
        // Floci has no request context to evaluate a Condition against, so a conditional Deny
        // is treated as applying (fail closed) -- consistent with S3PublicAccessEvaluatorTest's
        // conditionalDenyFailsClosedAndOverridesAllow, this must also override the ACL fallback.
        given().when().put("/" + CONDITIONAL_DENY_ACL_BUCKET).then().statusCode(200);

        given()
            .header("Authorization", LOCAL_AUTH_HEADER)
            .header("x-amz-acl", "public-read-write")
        .when()
            .put("/" + CONDITIONAL_DENY_ACL_BUCKET + "?acl")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body(conditionalDenyObjectActionPolicy(CONDITIONAL_DENY_ACL_BUCKET, "s3:PutObject"))
        .when()
            .put("/" + CONDITIONAL_DENY_ACL_BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
            .body("should stay denied despite the public-read-write ACL")
        .when()
            .put("/" + CONDITIONAL_DENY_ACL_BUCKET + "/" + ANON_WRITE_KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    private static String conditionalDenyObjectActionPolicy(String bucket, String action) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Deny",
                    "Principal": "*",
                    "Action": "%s",
                    "Resource": "arn:aws:s3:::%s/*",
                    "Condition": {
                      "StringEquals": {
                        "aws:SourceIp": "203.0.113.0/24"
                      }
                    }
                  }
                }
                """.formatted(action, bucket);
    }

    private static String completeMultipartBody(String eTag) {
        return """
                <CompleteMultipartUpload>
                  <Part>
                    <PartNumber>1</PartNumber>
                    <ETag>%s</ETag>
                  </Part>
                </CompleteMultipartUpload>
                """.formatted(eTag);
    }

    private static String publicReadPolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": "*",
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    },
                    {
                      "Effect": "Allow",
                      "Principal": "*",
                      "Action": ["s3:ListBucket"],
                      "Resource": ["arn:aws:s3:::%s"]
                    }
                  ]
                }
                """.formatted(bucket, bucket);
    }

    private static String authorizationHeader(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + credential(accessKeyId)
                + ", SignedHeaders=host;x-amz-date, Signature=test";
    }

    private static String credential(String accessKeyId) {
        return accessKeyId + "/" + SIGNING_DATE + "/us-east-1/s3/aws4_request";
    }

    private static String presignedSignature(String method, String path,
                                              String accessKeyId, String secretKey, String expires) {
        return presignedSignature(method, path, accessKeyId, secretKey, expires, "UNSIGNED-PAYLOAD");
    }

    private static String presignedSignature(String method, String path,
                                              String accessKeyId, String secretKey,
                                              String expires, String payloadHash) {
        try {
            String credentialScope = SIGNING_DATE + "/us-east-1/s3/aws4_request";
            String encodedCredential = URLEncoder.encode(
                    accessKeyId + "/" + credentialScope, StandardCharsets.UTF_8);

            // Build query string in sorted order (excluding Signature)
            String contentSha256Param = "UNSIGNED-PAYLOAD".equals(payloadHash)
                    ? "" : "&X-Amz-Content-Sha256=" + payloadHash;
            String canonicalQueryString = "X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + contentSha256Param
                    + "&X-Amz-Credential=" + encodedCredential
                    + "&X-Amz-Date=" + SIGNING_TIMESTAMP
                    + "&X-Amz-Expires=" + expires
                    + "&X-Amz-SignedHeaders=host";

            String canonicalRequest = method + "\n"
                    + path + "\n"
                    + canonicalQueryString + "\n"
                    + "host:localhost:" + io.restassured.RestAssured.port + "\n\n"
                    + "host\n"
                    + payloadHash;

            String stringToSign = "AWS4-HMAC-SHA256\n"
                    + SIGNING_TIMESTAMP + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);

            byte[] signingKey = deriveSigningKey(secretKey, SIGNING_DATE, "us-east-1", "s3");
            return hexEncode(hmacSha256(signingKey, stringToSign));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region,
                                           String service) throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String websiteConfiguration() {
        return websiteConfiguration(null);
    }

    private static String websiteConfiguration(String errorDocument) {
        if (errorDocument == null) {
            return """
                    <WebsiteConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <IndexDocument>
                        <Suffix>index.html</Suffix>
                      </IndexDocument>
                    </WebsiteConfiguration>
                    """;
        }
        return """
                <WebsiteConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <IndexDocument>
                    <Suffix>index.html</Suffix>
                  </IndexDocument>
                  <ErrorDocument>
                    <Key>%s</Key>
                  </ErrorDocument>
                </WebsiteConfiguration>
                """.formatted(errorDocument);
    }

    private static String publicGetObjectPolicy(String bucket) {
        return publicGetObjectPolicy(bucket, "*");
    }

    private static String publicGetObjectPolicy(String bucket, String key) {
        return publicObjectActionPolicy(bucket, key, "s3:GetObject");
    }

    private static String publicObjectActionPolicy(String bucket, String action) {
        return publicObjectActionPolicy(bucket, "*", action);
    }

    private static String publicObjectActionPolicy(String bucket, String key, String action) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": "%s",
                    "Resource": "arn:aws:s3:::%s/%s"
                  }
                }
                """.formatted(action, bucket, key);
    }

    private static String denyGetObjectPolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Deny",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::%s/*"
                  }
                }
                """.formatted(bucket);
    }

    private static String publicReadAcl() {
        return """
                <AccessControlPolicy xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Owner>
                    <ID>owner</ID>
                  </Owner>
                  <AccessControlList>
                    <Grant>
                      <Grantee xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="Group">
                        <URI>http://acs.amazonaws.com/groups/global/AllUsers</URI>
                      </Grantee>
                      <Permission>READ</Permission>
                    </Grant>
                  </AccessControlList>
                </AccessControlPolicy>
                """;
    }

    private static String selectRequest() {
        return """
                <SelectObjectContentRequest>
                  <Expression>SELECT * FROM S3Object</Expression>
                  <InputSerialization>
                    <CSV>
                      <FileHeaderInfo>NONE</FileHeaderInfo>
                    </CSV>
                  </InputSerialization>
                  <OutputSerialization>
                    <CSV />
                  </OutputSerialization>
                </SelectObjectContentRequest>
                """;
    }

    public static final class S3AuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.enforce-auth", "true");
        }
    }
}
