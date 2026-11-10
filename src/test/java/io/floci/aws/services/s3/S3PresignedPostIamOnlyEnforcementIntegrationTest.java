package io.floci.aws.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.cloudtrail.CloudTrailLogWriter;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the presigned POST gap in #3195/#3504: {@code S3Controller} only ran
 * {@code IamEnforcementFilter#authorizeAdditionalResource} inside {@code validatePresignedPostAuth},
 * which itself only runs when {@code floci.services.s3.enforce-auth} is enabled. With that flag
 * left off (its default) and only {@code floci.services.iam.enforcement-enabled} turned on, a
 * presigned POST never reached the signing principal's identity policy at all, so an explicit
 * {@code Deny} had no effect even though the equivalent header-signed and presigned-URL requests
 * were already correctly denied. This mirrors {@code S3PresignedUrlIamEnforcementIntegrationTest}'s
 * IAM-only profile, which the combined-flags profile in
 * {@code S3PresignedPostAuthEnforcementIntegrationTest} cannot exercise.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3PresignedPostIamOnlyEnforcementIntegrationTest {

    private static final String REGION = "us-east-1";
    /** A non-default account, distinct from the configured {@code 000000000000} default. */
    private static final String NON_DEFAULT_ACCOUNT = "222233334444";
    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Inject
    CloudTrailLogWriter trailWriter;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void presignedPostIsDeniedWhenIdentityPolicyDeniesPutObject() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-iam-deny-" + suffix;
        String userName = "presigned-post-denied-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "DenyPutObject", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"s3:PutObject","Resource":"*"}
                ]}""");

        given()
                .multiPart("key", "denied.txt")
                .multiPart("x-amz-credential", presignedCredential(accessKeyId))
                .multiPart("file", "denied.txt",
                        "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
                .post("/" + bucket)
        .then()
                .statusCode(403)
                .body("Error.Code", equalTo("AccessDenied"));
    }

    @Test
    void presignedPostSucceedsWhenIdentityPolicyAllowsPutObject() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-iam-allow-" + suffix;
        String userName = "presigned-post-allowed-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "AllowPutObject", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::%1$s/*"}
                ]}""".formatted(bucket));

        given()
                .multiPart("key", "allowed.txt")
                .multiPart("x-amz-credential", presignedCredential(accessKeyId))
                .multiPart("file", "allowed.txt",
                        "uploaded via presigned POST".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
                .post("/" + bucket)
        .then()
                .statusCode(204);
    }

    /**
     * Regression for the multi-account gap found in review on #3504: {@code AccountContextFilter}
     * runs before the multipart body is parsed, so for a presigned POST it sets the ambient
     * request account to the configured default. An IAM user (and access key) created in a
     * non-default account must still be resolved from the parsed credential rather than looked
     * up in that ambient default account, or its identity policy is silently skipped via the
     * unknown-key bypass instead of being evaluated.
     */
    @Test
    void presignedPostIsDeniedForNonDefaultAccountKeyWhenIdentityPolicyDenies() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-iam-deny-xacct-" + suffix;
        String userName = "presigned-post-denied-xacct-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName, NON_DEFAULT_ACCOUNT);
        putUserPolicy(userName, "DenyPutObject", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"s3:PutObject","Resource":"*"}
                ]}""", NON_DEFAULT_ACCOUNT);

        given()
                .multiPart("key", "denied-xacct.txt")
                .multiPart("x-amz-credential", presignedCredential(accessKeyId))
                .multiPart("file", "denied-xacct.txt",
                        "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
                .post("/" + bucket)
        .then()
                .statusCode(403)
                .body("Error.Code", equalTo("AccessDenied"));
    }

    /** Allow counterpart of {@link #presignedPostIsDeniedForNonDefaultAccountKeyWhenIdentityPolicyDenies}. */
    @Test
    void presignedPostSucceedsForNonDefaultAccountKeyWhenIdentityPolicyAllows() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-iam-allow-xacct-" + suffix;
        String userName = "presigned-post-allowed-xacct-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName, NON_DEFAULT_ACCOUNT);
        putUserPolicy(userName, "AllowPutObject", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::%1$s/*"}
                ]}""".formatted(bucket), NON_DEFAULT_ACCOUNT);

        given()
                .multiPart("key", "allowed-xacct.txt")
                .multiPart("x-amz-credential", presignedCredential(accessKeyId))
                .multiPart("file", "allowed-xacct.txt",
                        "uploaded via presigned POST".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
                .post("/" + bucket)
        .then()
                .statusCode(204);
    }

    /**
     * Regression for {@code IamEnforcementFilter#authorizeAdditionalResource} resolving the
     * bucket policy without the global condition keys ({@code aws:PrincipalAccount} in
     * particular) that {@code filter()} always supplies for the header-signed and presigned-URL
     * paths. A bucket-policy {@code Deny} conditioned on the account key being absent must not
     * fire for a genuine signed principal, whose account this filter always knows. Exercised in
     * this IAM-only profile (rather than {@code S3PresignedPostAuthEnforcementIntegrationTest})
     * so only {@code authorizeAdditionalResource} gates the request: with
     * {@code s3.enforce-auth} on, {@code S3Service#authorizeSignedBucketPolicy} independently
     * re-evaluates the same bucket policy through its own, narrower condition context.
     */
    @Test
    void presignedPostSucceedsWhenBucketPolicyDeniesOnlyForMissingPrincipalAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-null-condition-" + suffix;
        String userName = "presigned-post-null-condition-user-" + suffix;

        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "AllowPutObject", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}
                ]}""");
        putBucketPolicy(bucket, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny",
                   "Principal":{"AWS":"arn:aws:iam::000000000000:user/%1$s"},
                   "Action":"s3:PutObject",
                   "Resource":"arn:aws:s3:::%2$s/*",
                   "Condition":{"Null":{"aws:PrincipalAccount":"true"}}}
                ]}""".formatted(userName, bucket));

        given()
                .multiPart("key", "principal-account-present.txt")
                .multiPart("x-amz-credential", presignedCredential(accessKeyId))
                .multiPart("file", "principal-account-present.txt",
                        "uploaded with aws:PrincipalAccount populated".getBytes(StandardCharsets.UTF_8),
                        "text/plain")
        .when()
                .post("/" + bucket)
        .then()
                .statusCode(204);
    }

    /**
     * A browser POST is refused outside the filter that records IAM denials, since its credential is
     * only in the form, so it left no CloudTrail record at all. It is now recorded as PostObject, the
     * name AWS's S3 developer guide gives it among the object-level actions CloudTrail logs, in the
     * Region its credential names: a trail that logs only that Region, not the default one, gets it.
     */
    @Test
    void aDeniedPresignedPostIsRecordedInCloudTrailAsPostObject() throws IOException {
        String region = "eu-west-1";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-trail-" + suffix;
        String logBucket = "presigned-post-trail-logs-" + suffix;
        String trail = "presigned-post-trail-" + suffix;
        String userName = "presigned-post-trail-user-" + suffix;
        createBucketAsRoot(bucket, region);
        createBucketAsRoot(logBucket, region);
        cloudTrail(region, "CreateTrail", "{\"Name\":\"" + trail + "\",\"S3BucketName\":\"" + logBucket + "\"}");
        cloudTrail(region, "PutEventSelectors", """
                {"TrailName":"%s","EventSelectors":[{"ReadWriteType":"All","IncludeManagementEvents":false,
                 "DataResources":[{"Type":"AWS::S3::Object","Values":["arn:aws:s3:::%s/"]}]}]}"""
                .formatted(trail, bucket));
        cloudTrail(region, "StartLogging", "{\"Name\":\"" + trail + "\"}");
        String accessKeyId = createUser(userName);
        putUserPolicy(userName, "DenyPutObject", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"s3:PutObject","Resource":"*"}
                ]}""");

        try {
            given()
                    .multiPart("key", "trailed.txt")
                    .multiPart("x-amz-credential", presignedCredential(accessKeyId, region))
                    .multiPart("file", "trailed.txt",
                            "should not be stored".getBytes(StandardCharsets.UTF_8), "text/plain")
            .when()
                    .post("/" + bucket)
            .then()
                    .statusCode(403)
                    .body("Error.Code", equalTo("AccessDenied"));
            trailWriter.flushNow();

            List<JsonNode> records = trailRecords(logBucket);
            assertTrue(records.stream().anyMatch(record -> "PostObject".equals(record.path("eventName").asText())
                            && "AccessDenied".equals(record.path("errorCode").asText())
                            && bucket.equals(record.path("requestParameters").path("bucketName").asText())
                            && "trailed.txt".equals(record.path("requestParameters").path("key").asText())),
                    "Expected a PostObject AccessDenied record for " + bucket + "/trailed.txt: " + records);
        } finally {
            cloudTrail(region, "DeleteTrail", "{\"Name\":\"" + trail + "\"}");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "x-amz-server-side-encryption,Allow,aws:kms",
            "x-amz-server-side-encryption,Deny,aws:kms",
            "x-amz-server-side-encryption-aws-kms-key-id,Allow,aws:kms",
            "x-amz-server-side-encryption-aws-kms-key-id,Deny,aws:kms",
            "x-amz-server-side-encryption,Allow,' aws:kms '",
            "x-amz-server-side-encryption,Deny,' aws:kms '"
    })
    void presignedPostUsesEncryptionConditionsFromForm(String field, String effect, String encryption) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-encryption-" + suffix;
        String userName = "presigned-post-encryption-user-" + suffix;
        String value = field.endsWith("key-id") ? "approved-key" : "aws:kms";
        boolean isDenied = "Deny".equals(effect);
        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        if (isDenied) {
            putUserPolicy(userName, "AllowPutObject", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}
                    ]}""");
        }
        putUserPolicy(userName, "EncryptionCondition", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"%s","Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/*",
                   "Condition":{"StringEquals":{"s3:%s":"%s"}}}
                ]}""".formatted(effect, bucket, field, value));

        given()
                .header(field, "header-only-value")
                .multiPart("key", "encrypted.txt")
                .multiPart("x-amz-credential", presignedCredential(accessKeyId))
                .multiPart("X-Amz-Server-Side-Encryption", encryption)
                .multiPart("X-Amz-Server-Side-Encryption-Aws-Kms-Key-Id", "approved-key")
                .multiPart("file", "encrypted.txt", "encrypted".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
                .post("/" + bucket)
        .then()
                .statusCode(isDenied ? 403 : 204);

        given()
                .header("Authorization", auth("test", "s3"))
        .when()
                .get("/" + bucket + "/encrypted.txt")
        .then()
                .statusCode(isDenied ? 404 : 200);
    }

    @ParameterizedTest
    @CsvSource({
            ",Allow,StringEquals",
            "AES256,Allow,StringEquals",
            ",Deny,StringNotEquals",
            "AES256,Deny,StringNotEquals"
    })
    void presignedPostRejectsKmsKeyWithoutKmsEncryption(String encryption, String effect, String operator) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "presigned-post-invalid-kms-" + suffix;
        String userName = "presigned-post-invalid-kms-user-" + suffix;
        createBucketAsRoot(bucket);
        String accessKeyId = createUser(userName);
        if ("Deny".equals(effect)) {
            putUserPolicy(userName, "AllowPutObject", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}
                    ]}""");
        }
        putUserPolicy(userName, "EncryptionCondition", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"%s","Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/*",
                   "Condition":{"%s":{"s3:x-amz-server-side-encryption-aws-kms-key-id":"approved-key"}}}
                ]}""".formatted(effect, bucket, operator));

        RequestSpecification upload = given()
                .header("x-amz-server-side-encryption", "aws:kms")
                .multiPart("key", "invalid.txt")
                .multiPart("x-amz-credential", presignedCredential(accessKeyId))
                .multiPart("X-Amz-Server-Side-Encryption-Aws-Kms-Key-Id", "approved-key")
                .multiPart("file", "invalid.txt", "invalid".getBytes(StandardCharsets.UTF_8), "text/plain");
        if (encryption != null) {
            upload.multiPart("X-Amz-Server-Side-Encryption", encryption);
        }
        upload.when()
                .post("/" + bucket)
        .then()
                .statusCode(400)
                .body("Error.Code", equalTo("InvalidArgument"));

        given()
                .header("Authorization", auth("test", "s3"))
        .when()
                .get("/" + bucket + "/invalid.txt")
        .then()
                .statusCode(404);
    }

    private static void cloudTrail(String region, String operation, String body) {
        given()
                .header("Authorization", auth("test", "cloudtrail", region))
                .header("X-Amz-Target", "CloudTrail_20131101." + operation)
                .contentType("application/x-amz-json-1.1")
                .body(body)
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    /** Every record in the log files the trail delivered to {@code logBucket}. */
    private static List<JsonNode> trailRecords(String logBucket) throws IOException {
        String listing = given()
                .header("Authorization", auth("test", "s3"))
        .when()
                .get("/" + logBucket + "?list-type=2")
        .then()
                .statusCode(200)
                .extract().asString();
        List<JsonNode> records = new ArrayList<>();
        Matcher key = Pattern.compile("<Key>([^<]+\\.json\\.gz)</Key>").matcher(listing);
        while (key.find()) {
            byte[] compressed = given()
                    .header("Authorization", auth("test", "s3"))
            .when()
                    .get("/" + logBucket + "/" + key.group(1))
            .then()
                    .statusCode(200)
                    .extract().asByteArray();
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                new ObjectMapper().readTree(in.readAllBytes()).path("Records").forEach(records::add);
            }
        }
        return records;
    }

    private static void putBucketPolicy(String bucket, String policyDocument) {
        given()
                .header("Authorization", auth("test", "s3"))
                .contentType("application/json")
                .body(policyDocument)
        .when()
                .put("/" + bucket + "?policy")
        .then()
                .statusCode(200);
    }

    private static String presignedCredential(String accessKeyId) {
        return presignedCredential(accessKeyId, REGION);
    }

    private static String presignedCredential(String accessKeyId, String region) {
        String amzDate = AMZ_DATE_FMT.format(Instant.now());
        String dateStamp = amzDate.substring(0, 8);
        return accessKeyId + "/" + dateStamp + "/" + region + "/s3/aws4_request";
    }

    private static void createBucketAsRoot(String bucket) {
        given()
                .header("Authorization", auth("test", "s3"))
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
    }

    private static void createBucketAsRoot(String bucket, String region) {
        given()
                .header("Authorization", auth("test", "s3", region))
                .body("<CreateBucketConfiguration><LocationConstraint>" + region
                        + "</LocationConstraint></CreateBucketConfiguration>")
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
    }

    private static String createUser(String userName) {
        return createUser(userName, null);
    }

    /** Creates the user (and its access key) as root in {@code accountId}, or the default account when null. */
    private static String createUser(String userName, String accountId) {
        String authHeader = auth(accountId == null ? "test" : accountId, "iam");
        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", userName)
                .header("Authorization", authHeader)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        return given()
                .formParam("Action", "CreateAccessKey")
                .formParam("UserName", userName)
                .header("Authorization", authHeader)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract()
                .path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static void putUserPolicy(String userName, String policyName, String policyDocument) {
        putUserPolicy(userName, policyName, policyDocument, null);
    }

    /** Attaches the policy as root in {@code accountId}, or the default account when null. */
    private static void putUserPolicy(String userName, String policyName, String policyDocument, String accountId) {
        given()
                .formParam("Action", "PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", policyName)
                .formParam("PolicyDocument", policyDocument)
                .header("Authorization", auth(accountId == null ? "test" : accountId, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static String auth(String accessKeyId, String service) {
        return auth(accessKeyId, service, REGION);
    }

    private static String auth(String accessKeyId, String service, String region) {
        String amzDate = AMZ_DATE_FMT.format(Instant.now()).substring(0, 8);
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + amzDate + "/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
