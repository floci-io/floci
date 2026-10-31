package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Under IAM enforcement the code bucket must be versioned and let Lambda read the object version. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaWebCodeBucketIntegrationTest {

    private static final String BASE = "/2025-03-07";
    private static final String ROOT = auth("000000000000", "lambda");
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final String VERSIONING = "You must enable S3 versioning for your S3 bucket when"
            + " S3ObjectStorageMode is set to REFERENCE.";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    private String functionName;
    private String functionArn;
    private String bucket;

    @BeforeEach
    void createFunction() {
        functionName = "code-bucket-" + Long.toString(System.nanoTime(), 36);
        functionArn = "arn:aws:lambda:us-east-1:000000000000:web-function/" + functionName;
        bucket = functionName;
        LambdaWebRoles.trustLambda(iamService, ROLE);
        LambdaWebCodeBuckets.create(s3Service, "web-code-bucket");
        put("web-code-bucket");
        int port = baseUri.getPort();
        String name = functionName;
        cleanup.register(() -> given().port(port).header("Authorization", ROOT)
                .delete(BASE + "/web-functions/" + name));
        given().header("Authorization", ROOT).contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s"}},
                         "endpointConfig": {"endpointName": "dev", "endpointType": "HomeRegion",
                                            "authType": "ApplicationManaged"}}
                        """.formatted(functionName, build("web-code-bucket", null), ROLE))
                .when().put(BASE + "/web-functions")
                .then().statusCode(202);
        s3Service.createBucket(bucket, "us-east-1");
    }

    @Test
    void theLatestVersionOfTheObjectMustNotBeTheNullVersion() {
        LambdaWebCodeBuckets.allowLambda(s3Service, bucket);
        put(bucket);
        refused(null, VERSIONING);
        s3Service.putBucketVersioning(bucket, "Enabled");
        refused(null, VERSIONING);
        accepted("null");

        put(bucket);
        accepted(null);
        s3Service.putBucketVersioning(bucket, "Suspended");
        accepted(null);
        put(bucket);
        refused(null, VERSIONING);
    }

    @Test
    void lambdaNeedsGetObjectVersionUnderTheConditionsItSends() {
        s3Service.putBucketVersioning(bucket, "Enabled");
        put(bucket);
        String refusal = lambdaRefusal();
        refused(null, refusal);

        policy(allow("\"s3:GetObject\"", null));
        refused(null, refusal);
        policy(allow("\"s3:GetObjectVersion\"", null));
        accepted(null);
        policy(allow("\"s3:GetObjectVersion\"", "{\"ArnLike\":{\"aws:SourceArn\":\"" + functionArn + "/revision/*\"}}"));
        accepted(null);
        policy(allow("\"s3:GetObjectVersion\"", "{\"ArnLike\":{\"aws:SourceArn\":\"" + functionArn + "\"}}"));
        refused(null, refusal);
        policy(allow("\"s3:GetObjectVersion\"", "{\"StringEquals\":{\"aws:SourceAccount\":\"000000000000\"}}"));
        accepted(null);
        policy(allow("\"s3:GetObjectVersion\"", "{\"StringEquals\":{\"aws:SourceAccount\":\"111111111111\"}}"));
        refused(null, refusal);
        policy(allow("[\"s3:GetObject\",\"s3:GetObjectVersion\"]", null) + "," + deny("s3:GetObject"));
        accepted(null);
        policy(allow("[\"s3:GetObject\",\"s3:GetObjectVersion\"]", null) + "," + deny("s3:GetObjectVersion"));
        refused(null, refusal);
    }

    @Test
    void aGrantForOneVersionAdmitsOnlyThatVersion() {
        s3Service.putBucketVersioning(bucket, "Enabled");
        String older = put(bucket);
        String latest = put(bucket);
        policy(allow("\"s3:GetObjectVersion\"", "{\"StringEquals\":{\"s3:VersionId\":\"" + latest + "\"}}"));
        accepted(null);
        accepted(latest);
        refused(older, lambdaRefusal());
        policy(allow("\"s3:GetObjectVersion\"", "{\"StringEquals\":{\"s3:VersionId\":\"" + older + "\"}}"));
        refused(null, lambdaRefusal());
        accepted(older);
    }

    @Test
    void aRevisionFailsForGoodOnceLambdaCanNoLongerReadItsCode() {
        s3Service.putBucketVersioning(bucket, "Enabled");
        put(bucket);
        LambdaWebCodeBuckets.allowLambda(s3Service, bucket);
        String revisionId = createRevision(null).statusCode(202).extract().path("revisionId");
        String revision = BASE + "/web-functions/" + functionName + "/revisions/" + revisionId;
        given().header("Authorization", ROOT).when().get(revision)
                .then().statusCode(200).body("state", equalTo("Active"));

        s3Service.deleteBucketPolicy(bucket);
        given().header("Authorization", ROOT).when().get(revision)
                .then().statusCode(200)
                .body("state", equalTo("Failed"))
                .body("stateReason", equalTo(lambdaRefusal()));
        LambdaWebCodeBuckets.allowLambda(s3Service, bucket);
        given().header("Authorization", ROOT).when().get(revision)
                .then().statusCode(200).body("state", equalTo("Failed"));
    }

    private String lambdaRefusal() {
        return "Lambda service principal lambda.amazonaws.com does not have s3:GetObject and s3:GetObjectVersion"
                + " permission on s3://" + bucket + "/app.zip. Update the bucket policy to grant the Lambda service"
                + " principal access and retry.";
    }

    private String put(String in) {
        return s3Service.putObject(in, "app.zip", LambdaWebZips.indexJs(""), "application/zip", Map.of())
                .getVersionId();
    }

    private void policy(String statements) {
        s3Service.putBucketPolicy(bucket, "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private String allow(String actions, String condition) {
        return "{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":" + actions
                + ",\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"" + (condition == null ? "" : ",\"Condition\":"
                + condition) + "}";
    }

    private String deny(String action) {
        return "{\"Effect\":\"Deny\",\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"" + action
                + "\",\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"}";
    }

    private static String build(String in, String versionId) {
        return """
                {"codeConfig": {"s3Object": {"bucket": "%s", "key": "app.zip"%s}}, "runtimeConfig": {"runtime": "nodejs24.x"}}
                """.formatted(in, versionId == null ? "" : ", \"versionId\": \"" + versionId + "\"");
    }

    private ValidatableResponse createRevision(String versionId) {
        return given().header("Authorization", ROOT).contentType("application/json")
                .body("""
                        {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s"}}
                        """.formatted(build(bucket, versionId), ROLE))
                .when().post(BASE + "/web-functions/" + functionName + "/revisions")
                .then();
    }

    private void accepted(String versionId) {
        createRevision(versionId).statusCode(202);
    }

    private void refused(String versionId, String message) {
        createRevision(versionId).statusCode(400)
                .header("X-Amzn-Errortype", "ValidationException")
                .body("message", equalTo(message));
    }
}
