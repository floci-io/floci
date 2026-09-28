package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateAccessKeyResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("checksumCases")
    @DisplayName("unsigned checksum header on presigned multipart PUT is rejected")
    void unsignedChecksumHeaderOnPresignedUploadPartIsRejected(ChecksumCase checksum) throws Exception {
        assumeEnforcementEnabled();

        String key = "unsigned-checksum-" + checksum.algorithm().toString().toLowerCase(Locale.ROOT) + ".bin";
        CreateMultipartUploadResponse upload = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(BUCKET)
                .key(key)
                .checksumAlgorithm(checksum.algorithm())
                .build());
        try {
            PresignedUploadPartRequest unsignedChecksum;
            PresignedUploadPartRequest signedChecksum;
            try (S3Presigner presigner = presigner()) {
                unsignedChecksum = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(5))
                        .uploadPartRequest(UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key(key)
                                .uploadId(upload.uploadId())
                                .partNumber(1)
                                .build())
                        .build());
                UploadPartRequest.Builder signedRequest = UploadPartRequest.builder()
                        .bucket(BUCKET)
                        .key(key)
                        .uploadId(upload.uploadId())
                        .partNumber(1);
                signedChecksum = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(5))
                        .uploadPartRequest(withChecksum(signedRequest, checksum))
                        .build());
            }

            HttpResult rejected = httpPut(unsignedChecksum,
                    Map.of(checksum.headerName(), checksum.value()), "123456789");
            assertThat(rejected.status()).isEqualTo(403);
            assertThat(rejected.body()).contains("<Code>AccessDenied</Code>");
            assertThat(rejected.body()).contains(
                    "<Message>There were headers present in the request which were not signed</Message>");
            assertThat(rejected.body()).contains(
                    "<HeadersNotSigned>" + checksum.headerName() + "</HeadersNotSigned>");

            assertThat(signedChecksum.httpRequest().headers()).containsKey(checksum.headerName());
            HttpResult accepted = httpPut(signedChecksum, Map.of(), "123456789");
            assertThat(accepted.status()).isEqualTo(200);
        } finally {
            s3.abortMultipartUpload(r -> r.bucket(BUCKET).key(key).uploadId(upload.uploadId()));
        }
    }

    private static Stream<ChecksumCase> checksumCases() {
        return Stream.of(
                new ChecksumCase(ChecksumAlgorithm.CRC32,
                        "x-amz-checksum-crc32", "y/Q5Jg=="),
                new ChecksumCase(ChecksumAlgorithm.CRC32_C,
                        "x-amz-checksum-crc32c", "4waSgw=="),
                new ChecksumCase(ChecksumAlgorithm.CRC64_NVME,
                        "x-amz-checksum-crc64nvme", "rosUhgp5mIg="),
                new ChecksumCase(ChecksumAlgorithm.SHA1,
                        "x-amz-checksum-sha1", "98O8HYCOBHMq32eZZczDTKeuNEE="),
                new ChecksumCase(ChecksumAlgorithm.SHA256,
                        "x-amz-checksum-sha256", "FeKw08M4keuw8e9gnsQZQgwg4yDOlMZfvIwzEkSOsiU="));
    }

    private static UploadPartRequest withChecksum(
            UploadPartRequest.Builder request, ChecksumCase checksum) {
        return switch (checksum.algorithm()) {
            case CRC32 -> request.checksumCRC32(checksum.value()).build();
            case CRC32_C -> request.checksumCRC32C(checksum.value()).build();
            case CRC64_NVME -> request.checksumCRC64NVME(checksum.value()).build();
            case SHA1 -> request.checksumSHA1(checksum.value()).build();
            case SHA256 -> request.checksumSHA256(checksum.value()).build();
            default -> throw new IllegalArgumentException(
                    "Unsupported checksum algorithm: " + checksum.algorithm());
        };
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

    private static HttpResult httpPut(PresignedUploadPartRequest presigned,
                                      Map<String, String> additionalHeaders,
                                      String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) presigned.url().openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        for (Map.Entry<String, List<String>> entry : presigned.httpRequest().headers().entrySet()) {
            for (String value : entry.getValue()) {
                connection.addRequestProperty(entry.getKey(), value);
            }
        }
        additionalHeaders.forEach(connection::addRequestProperty);

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }

        int status = connection.getResponseCode();
        InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (responseStream == null) {
            return new HttpResult(status, "");
        }
        try (InputStream input = responseStream) {
            return new HttpResult(status, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private record HttpResult(int status, String body) {
    }

    private record ChecksumCase(ChecksumAlgorithm algorithm, String headerName, String value) {
        @Override
        public String toString() {
            return algorithm.toString();
        }
    }
}
