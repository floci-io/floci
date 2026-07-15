package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

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
    private static final String PUBLIC_KEY = "public.txt";
    private static final String PRIVATE_KEY = "private.txt";
    private static final String ERROR_KEY = "error.html";
    private static final String ACL_KEY = "acl-list.txt";
    private static final String DENY_KEY = "deny.txt";
    private static final String VERSION_KEY = "versioned.txt";
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
            .body("public body")
        .when()
            .put("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY)
        .then()
            .statusCode(200);

        given()
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
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("bad-key"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", "test")
        .when()
            .get("/" + PUBLIC_BUCKET + "/" + PUBLIC_KEY)
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
        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential("test"))
            .queryParam("X-Amz-Date", SIGNING_TIMESTAMP)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", "test")
        .when()
            .get("/" + PRIVATE_BUCKET + "/" + PRIVATE_KEY)
        .then()
            .statusCode(200)
            .body(equalTo("private body"));
    }

    @Test
    @Order(12)
    void websiteRootAuthorizesIndexObjectReadNotBucketList() {
        given().when().put("/" + WEBSITE_BUCKET).then().statusCode(200);

        given()
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
            .contentType("text/html")
            .body("<html>private index</html>")
        .when()
            .put("/" + WEBSITE_ERROR_BUCKET + "/index.html")
        .then()
            .statusCode(200);

        given()
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
