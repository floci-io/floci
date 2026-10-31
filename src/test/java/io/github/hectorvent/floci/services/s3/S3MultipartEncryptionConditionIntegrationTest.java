package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A multipart upload under a bucket policy on the encryption condition keys. A part sends neither
 * header, so it is judged by what its upload recorded. AWS does not document this; it was measured
 * on S3 under the same two statements on 2026-10-30, each case here as S3 answered it but one: S3
 * has an upload that asks for no encryption record the bucket's default, where Floci records only
 * what the request asks for. So a part of such an upload, opened before the policy stood on a
 * bucket defaulting to {@code aws:kms}, is admitted on S3 and refused here.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3MultipartEncryptionConditionIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String BUCKET_KEY =
            "arn:aws:kms:us-east-1:111122223333:key/11111111-1111-1111-1111-111111111111";
    private static final String SSE = "x-amz-server-side-encryption";
    private static final String SSE_KEY = "x-amz-server-side-encryption-aws-kms-key-id";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void anUploadOpenedWithTheBucketsKeyHasItsPartsAdmittedAndStandsUnderThatKey() {
        Fixture fixture = fixture();
        putKmsOnlyPolicy(fixture);

        String uploadId = given().filter(fixture.user()).header(SSE, "aws:kms").header(SSE_KEY, BUCKET_KEY)
                .post("/" + fixture.bucket() + "/large.bin?uploads")
                .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String etag = uploadPart(fixture, "large.bin", uploadId)
                .then().statusCode(200).extract().header("ETag");
        given().filter(fixture.user()).contentType("application/xml")
                .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + etag
                        + "</ETag></Part></CompleteMultipartUpload>")
                .post("/" + fixture.bucket() + "/large.bin?uploadId=" + uploadId)
                .then().statusCode(200);

        given().filter(fixture.user()).head("/" + fixture.bucket() + "/large.bin")
                .then().statusCode(200)
                .header(SSE, equalTo("aws:kms"))
                .header(SSE_KEY, equalTo(BUCKET_KEY));
    }

    @Test
    void anUploadOpenedWithNoEncryptionUnderThePolicyIsRefusedAndNoObjectStands() {
        Fixture fixture = fixture();
        putKmsOnlyPolicy(fixture);

        given().filter(fixture.user()).post("/" + fixture.bucket() + "/plain.bin?uploads")
                .then().statusCode(403).body(containsString("AccessDenied"));

        given().filter(fixture.owner()).head("/" + fixture.bucket() + "/plain.bin").then().statusCode(404);
    }

    @Test
    void aPartOfAnUploadThatRecordedNoEncryptionIsRefusedAndNoObjectStands() {
        Fixture fixture = fixture();
        String uploadId = given().filter(fixture.user()).post("/" + fixture.bucket() + "/plain.bin?uploads")
                .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        putKmsOnlyPolicy(fixture);

        uploadPart(fixture, "plain.bin", uploadId)
                .then().statusCode(403).body(containsString("AccessDenied"));

        given().filter(fixture.owner()).head("/" + fixture.bucket() + "/plain.bin").then().statusCode(404);
        given().filter(fixture.owner()).delete("/" + fixture.bucket() + "/plain.bin?uploadId=" + uploadId)
                .then().statusCode(204);
    }

    @Test
    void aPartOfAnUploadThatRecordedAnotherEncryptionIsRefused() {
        Fixture fixture = fixture();
        String uploadId = given().filter(fixture.user()).header(SSE, "AES256")
                .post("/" + fixture.bucket() + "/aes.bin?uploads")
                .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        putKmsOnlyPolicy(fixture);

        uploadPart(fixture, "aes.bin", uploadId)
                .then().statusCode(403).body(containsString("AccessDenied"));

        given().filter(fixture.owner()).delete("/" + fixture.bucket() + "/aes.bin?uploadId=" + uploadId)
                .then().statusCode(204);
    }

    @Test
    void aPartNamingAnUploadNotHeldAnswersNoSuchUpload() {
        Fixture fixture = fixture();
        putKmsOnlyPolicy(fixture);

        uploadPart(fixture, "unknown.bin", "no-such-upload")
                .then().statusCode(404).body(containsString("NoSuchUpload"));
    }

    @Test
    void aPutNamingAnUploadButNoPartIsAnObjectWriteAndIsChecked() {
        Fixture fixture = fixture();
        S3RequestSigner unpermitted = unpermittedUser();

        given().filter(unpermitted).contentType("text/plain").body("a write")
                .put("/" + fixture.bucket() + "/written.txt?uploadId=no-such-upload")
                .then().statusCode(403).body(containsString("AccessDenied"));

        given().filter(fixture.owner()).head("/" + fixture.bucket() + "/written.txt").then().statusCode(404);
    }

    @Test
    void aPutNamingAHeldUploadButNoPartIsJudgedByItsOwnHeaders() {
        Fixture fixture = fixture();
        S3RequestSigner kmsOnly = kmsOnlyIdentityUser();
        String uploadId = given().filter(kmsOnly).header(SSE, "aws:kms").header(SSE_KEY, BUCKET_KEY)
                .post("/" + fixture.bucket() + "/held.bin?uploads")
                .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given().filter(kmsOnly).contentType("text/plain").body("a write")
                .put("/" + fixture.bucket() + "/written.txt?uploadId=" + uploadId)
                .then().statusCode(403).body(containsString("AccessDenied"));

        given().filter(fixture.owner()).delete("/" + fixture.bucket() + "/held.bin?uploadId=" + uploadId)
                .then().statusCode(204);
    }

    @Test
    void aPartCopyNamingAnUploadNotHeldIsChecked() {
        Fixture fixture = fixture();

        given().filter(unpermittedUser()).header("x-amz-copy-source", "/" + fixture.bucket() + "/source.txt")
                .put("/" + fixture.bucket() + "/copied.bin?partNumber=1&uploadId=no-such-upload")
                .then().statusCode(403).body(containsString("AccessDenied"));
    }

    private record Fixture(String bucket, S3RequestSigner owner, S3RequestSigner user) {
    }

    /** A bucket with no policy yet, an owner allowed S3, and a user allowed {@code s3:*} and {@code kms:*}. */
    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        S3RequestSigner owner = user("multipart-owner-" + suffix);
        S3RequestSigner user = user("multipart-user-" + suffix);
        String bucket = "multipart-encryption-" + suffix;
        given().filter(owner).put("/" + bucket).then().statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeBucket(endpoint.getPort(), owner, bucket));
        return new Fixture(bucket, owner, user);
    }

    private S3RequestSigner user(String userName) {
        return user(userName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:*","kms:*"],"Resource":"*"}]}""");
    }

    /** A user no policy allows anything. */
    private S3RequestSigner unpermittedUser() {
        return user("multipart-unpermitted-" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    /** A user allowed {@code s3:*} only for a request naming {@code aws:kms}. */
    private S3RequestSigner kmsOnlyIdentityUser() {
        return user("multipart-kms-only-" + UUID.randomUUID().toString().substring(0, 8), """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:*","Resource":"*",
                   "Condition":{"StringEquals":{"s3:x-amz-server-side-encryption":"aws:kms"}}}]}""");
    }

    private S3RequestSigner user(String userName, String policy) {
        String authorization = EnforcementFixtures.auth(ACCOUNT, "iam");
        EnforcementFixtures.UserCredentials credentials =
                EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, userName);
        if (policy != null) {
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, userName, "Allow", policy);
        }
        return S3RequestSigner.signedAs(credentials.accessKeyId(), credentials.secretAccessKey());
    }

    private void putKmsOnlyPolicy(Fixture fixture) {
        given().filter(fixture.owner()).contentType("application/json").body("""
                {"Version":"2012-10-17","Statement":[
                  {"Sid":"DenyUnencryptedPuts","Effect":"Deny","Principal":"*","Action":"s3:PutObject",
                   "Resource":"arn:aws:s3:::%1$s/*",
                   "Condition":{"StringNotEquals":{"s3:x-amz-server-side-encryption":"aws:kms"}}},
                  {"Sid":"DenyWrongKmsKey","Effect":"Deny","Principal":"*","Action":"s3:PutObject",
                   "Resource":"arn:aws:s3:::%1$s/*",
                   "Condition":{"StringNotEqualsIfExists":{"s3:x-amz-server-side-encryption-aws-kms-key-id":"%2$s"}}}]}
                """.formatted(fixture.bucket(), BUCKET_KEY))
                .put("/" + fixture.bucket() + "?policy")
                .then().statusCode(200);
    }

    private Response uploadPart(Fixture fixture, String key, String uploadId) {
        return given().filter(fixture.user()).contentType("application/octet-stream").body("a part".getBytes())
                .put("/" + fixture.bucket() + "/" + key + "?partNumber=1&uploadId=" + uploadId);
    }
}
