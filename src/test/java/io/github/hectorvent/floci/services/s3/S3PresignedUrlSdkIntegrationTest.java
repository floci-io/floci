package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies presigned URL SigV4 verification against URLs produced by the AWS SDK for Java,
 * an independent signer. Running under {@code enforce-auth=true} exercises the real
 * verification path, and using {@link S3Presigner} proves Floci accepts the SDK's canonical
 * construction rather than only the test-local signing helper.
 */
@QuarkusTest
@TestProfile(S3AuthEnforcementIntegrationTest.S3AuthProfile.class)
class S3PresignedUrlSdkIntegrationTest {

    private static final String BUCKET = "sdk-presign-bucket";
    private static final String KEY_A = "object-a.txt";
    private static final String KEY_B = "object-b.txt";
    private static final String BODY_A = "content-of-a";
    private static final String BODY_B = "content-of-b";

    private static boolean created;

    @Inject
    IamService iamService;

    @Test
    void sdkPresignedUrlIsAcceptedButNotValidForAnotherObject() throws Exception {
        ensureBucketAndObjects();

        String urlA = presignGet(KEY_A);
        String urlB = presignGet(KEY_B);

        // Both SDK-generated URLs must succeed — independently proves Floci accepts the SDK's SigV4.
        HttpResponse<String> a = httpGet(urlA);
        assertEquals(200, a.statusCode(), a.body());
        assertEquals(BODY_A, a.body());

        HttpResponse<String> b = httpGet(urlB);
        assertEquals(200, b.statusCode(), b.body());
        assertEquals(BODY_B, b.body());

        // Transplant: object A's raw path with object B's complete raw query, kept exactly as signed.
        String transplanted = endpoint()
                + URI.create(urlA).getRawPath() + "?" + URI.create(urlB).getRawQuery();

        HttpResponse<String> t = httpGet(transplanted);
        assertEquals(403, t.statusCode(), t.body());
        assertTrue(t.body().contains("SignatureDoesNotMatch"), "expected SignatureDoesNotMatch, got: " + t.body());
    }

    @Test
    void reorderedQueryParametersStillVerify() throws Exception {
        ensureBucketAndObjects();

        URI url = URI.create(presignGet(KEY_A));

        // Reverse the query parameter order on the wire; the signature must still verify because
        // the canonical form sorts parameters before hashing.
        String[] pairs = url.getRawQuery().split("&");
        StringBuilder reordered = new StringBuilder();
        for (int i = pairs.length - 1; i >= 0; i--) {
            reordered.append(pairs[i]);
            if (i > 0) {
                reordered.append("&");
            }
        }

        HttpResponse<String> r = httpGet(endpoint() + url.getRawPath() + "?" + reordered);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(BODY_A, r.body());
    }

    @Test
    void sdkPresignedUrlWithSpecialCharactersInQueryIsAccepted() throws Exception {
        ensureBucketAndObjects();

        // response-content-disposition is signed into the query and carries spaces, quotes,
        // ';' and '+', so accepting the URL proves Floci encodes these the same way the SDK does.
        String url;
        try (S3Presigner presigner = presigner()) {
            url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(BUCKET).key(KEY_A)
                            .responseContentDisposition("attachment; filename=\"a b+c.txt\"")
                            .build())
                    .build()).url().toString();
        }

        HttpResponse<String> r = httpGet(url);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(BODY_A, r.body());
    }

    @Test
    void expiredPresignedUrlIsRejected() throws Exception {
        ensureBucketAndObjects();

        // Rewrite X-Amz-Date to the past so the URL is already expired. Expiration is checked
        // before signature verification, so this is deterministic and needs no wait.
        URI url = URI.create(presignGet(KEY_A));
        String expiredQuery = url.getRawQuery()
                .replaceFirst("X-Amz-Date=[^&]+", "X-Amz-Date=20200101T000000Z");

        HttpResponse<String> r = httpGet(endpoint() + url.getRawPath() + "?" + expiredQuery);
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("AccessDenied"), "expected AccessDenied, got: " + r.body());
    }

    @Test
    void presignedUrlSignedWithIamAccessKeyIsAccepted() throws Exception {
        ensureBucketAndObjects();
        iamService.createUser("presign-user", "/");
        AccessKey key = iamService.createAccessKey("presign-user");

        String url;
        try (S3Presigner presigner = presigner(key.getAccessKeyId(), key.getSecretAccessKey())) {
            url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(GetObjectRequest.builder().bucket(BUCKET).key(KEY_A).build())
                    .build()).url().toString();
        }

        HttpResponse<String> r = httpGet(url);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(BODY_A, r.body());
    }

    @Test
    void presignedUrlWithMalformedCredentialIsRejected() throws Exception {
        ensureBucketAndObjects();

        // A credential that does not split into the 5 scope parts must be rejected before
        // signature verification.
        URI url = URI.create(presignGet(KEY_A));
        String query = url.getRawQuery().replaceFirst("X-Amz-Credential=[^&]+", "X-Amz-Credential=test");

        HttpResponse<String> r = httpGet(endpoint() + url.getRawPath() + "?" + query);
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("InvalidAccessKeyId"), "expected InvalidAccessKeyId, got: " + r.body());
    }

    private static void ensureBucketAndObjects() {
        if (created) {
            return;
        }
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().body(BODY_A).when().put("/" + BUCKET + "/" + KEY_A).then().statusCode(200);
        given().body(BODY_B).when().put("/" + BUCKET + "/" + KEY_B).then().statusCode(200);
        created = true;
    }

    private static URI endpoint() {
        return URI.create("http://localhost:" + RestAssured.port);
    }

    private static S3Presigner presigner() {
        return presigner("test", "test");
    }

    private static S3Presigner presigner(String accessKeyId, String secretKey) {
        return S3Presigner.builder()
                .endpointOverride(endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static String presignGet(String key) {
        try (S3Presigner presigner = presigner()) {
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
