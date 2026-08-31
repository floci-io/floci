package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateAccessKeyResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies presigned URL SigV4 verification against URLs produced by the AWS SDK for Java,
 * an independent signer. Tests are skipped when {@code floci.services.s3.enforce-auth} is not
 * enabled on the running Floci instance.
 */
@DisplayName("S3 Presigned URL SigV4 Verification")
class S3PresignedUrlSigV4VerificationTest {

    private static final String BUCKET = TestFixtures.uniqueName("sdk-presign-sigv4");
    private static final String KEY_A = "object-a.txt";
    private static final String KEY_B = "object-b.txt";
    private static final String BODY_A = "content-of-a";
    private static final String BODY_B = "content-of-b";

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    private static S3Client s3;
    private static IamClient iam;
    private static boolean enforcementEnabled;

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        iam = TestFixtures.iamClient();

        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(KEY_A).build(),
                RequestBody.fromString(BODY_A));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(KEY_B).build(),
                RequestBody.fromString(BODY_B));

        enforcementEnabled = probeEnforcementEnabled();
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            s3.close();
        }
        if (iam != null) {
            iam.close();
        }
    }

    @Test
    @DisplayName("SDK presigned URL is accepted but not valid for another object")
    void sdkPresignedUrlIsAcceptedButNotValidForAnotherObject() throws Exception {
        assumeEnforcementEnabled();

        var urlA = presignGet(KEY_A);
        var urlB = presignGet(KEY_B);

        var a = httpGet(urlA);
        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(a.body()).isEqualTo(BODY_A);

        var b = httpGet(urlB);
        assertThat(b.statusCode()).isEqualTo(200);
        assertThat(b.body()).isEqualTo(BODY_B);

        // Transplant: object A's raw path with object B's complete raw query
        var transplanted = TestFixtures.endpoint()
                + URI.create(urlA).getRawPath() + "?" + URI.create(urlB).getRawQuery();

        var t = httpGet(transplanted);
        assertThat(t.statusCode()).isEqualTo(403);
        assertThat(t.body()).contains("SignatureDoesNotMatch");
    }

    @Test
    @DisplayName("reordered query parameters still verify")
    void reorderedQueryParametersStillVerify() throws Exception {
        assumeEnforcementEnabled();

        var url = URI.create(presignGet(KEY_A));
        var pairs = url.getRawQuery().split("&");
        var reordered = new StringBuilder();
        for (var i = pairs.length - 1; i >= 0; i--) {
            reordered.append(pairs[i]);
            if (i > 0) {
                reordered.append("&");
            }
        }

        var r = httpGet(TestFixtures.endpoint() + url.getRawPath() + "?" + reordered);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(BODY_A);
    }

    @Test
    @DisplayName("SDK presigned URL with special characters in query is accepted")
    void sdkPresignedUrlWithSpecialCharactersInQueryIsAccepted() throws Exception {
        assumeEnforcementEnabled();

        String url;
        try (var presigner = presigner()) {
            url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(BUCKET).key(KEY_A)
                            .responseContentDisposition("attachment; filename=\"a b+c.txt\"")
                            .build())
                    .build()).url().toString();
        }

        var r = httpGet(url);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(BODY_A);
    }

    @Test
    @DisplayName("expired presigned URL is rejected")
    void expiredPresignedUrlIsRejected() throws Exception {
        assumeEnforcementEnabled();

        var url = URI.create(presignGet(KEY_A));
        var expiredQuery = url.getRawQuery()
                .replaceFirst("X-Amz-Date=[^&]+", "X-Amz-Date=20200101T000000Z");

        var r = httpGet(TestFixtures.endpoint() + url.getRawPath() + "?" + expiredQuery);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("AccessDenied");
    }

    @Test
    @DisplayName("presigned URL signed with IAM access key is accepted")
    void presignedUrlSignedWithIamAccessKeyIsAccepted() throws Exception {
        assumeEnforcementEnabled();

        var userName = TestFixtures.uniqueName("presign-user");
        iam.createUser(r -> r.userName(userName).path("/"));
        CreateAccessKeyResponse keyResponse = iam.createAccessKey(r -> r.userName(userName));
        var accessKeyId = keyResponse.accessKey().accessKeyId();
        var secretKey = keyResponse.accessKey().secretAccessKey();

        String url;
        try (var presigner = presigner(accessKeyId, secretKey)) {
            url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(GetObjectRequest.builder().bucket(BUCKET).key(KEY_A).build())
                    .build()).url().toString();
        }

        var r = httpGet(url);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(BODY_A);
    }

    @Test
    @DisplayName("presigned URL with malformed credential is rejected")
    void presignedUrlWithMalformedCredentialIsRejected() throws Exception {
        assumeEnforcementEnabled();

        var url = URI.create(presignGet(KEY_A));
        var query = url.getRawQuery().replaceFirst("X-Amz-Credential=[^&]+", "X-Amz-Credential=test");

        var r = httpGet(TestFixtures.endpoint() + url.getRawPath() + "?" + query);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("InvalidAccessKeyId");
    }

    private static boolean probeEnforcementEnabled() {
        var unknownS3 = S3Client.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("bad-key", "bad-secret")))
                .forcePathStyle(true)
                .build();
        try {
            unknownS3.getObject(GetObjectRequest.builder().bucket(BUCKET).key(KEY_A).build());
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 403
                    && "InvalidAccessKeyId".equals(e.awsErrorDetails().errorCode())) {
                return true;
            }
            throw e;
        } finally {
            unknownS3.close();
        }
    }

    private static void assumeEnforcementEnabled() {
        Assumptions.assumeTrue(enforcementEnabled,
                "S3 auth enforcement is not enabled - set floci.services.s3.enforce-auth=true to run these tests");
    }

    private static S3Presigner presigner() {
        return presigner("test", "test");
    }

    private static S3Presigner presigner(String accessKeyId, String secretKey) {
        return S3Presigner.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static String presignGet(String key) {
        try (var presigner = presigner()) {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                    .build()).url().toString();
        }
    }

    private static HttpResponse<String> httpGet(String url) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
