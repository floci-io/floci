package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class GeneratedPreSignedUrlAuthEnforcementIntegrationTest {

    private static final S3RequestSigner OWNER = S3RequestSigner.signedAs("test", "test");

    @Inject
    PreSignedUrlGenerator presignGenerator;

    @Inject
    IamService iamService;

    @Test
    void generatedUrlUsesRegisteredSigV4SessionUnderEnforcedAuth() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "generated-presign-auth-" + suffix;
        String key = "exports/report with spaces.csv";
        String content = "recipient,status\nalice@example.com,DELIVERY\n";

        given()
            .filter(OWNER)
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);

        given()
            .filter(OWNER)
            .body(content)
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);

        String url = presignGenerator.generatePresignedUrl(
                "http://localhost:" + io.restassured.RestAssured.port, bucket, key, "GET", 3600);
        URI uri = URI.create(url);
        String credential = queryParam(uri, "X-Amz-Credential");
        String sessionToken = queryParam(uri, "X-Amz-Security-Token");
        String accessKeyId = credential.substring(0, credential.indexOf('/'));

        assertTrue(accessKeyId.matches("ASIA[A-Z0-9]{16}"));
        assertNotNull(sessionToken);
        assertEquals("000000000000", iamService.resolveAccountId(accessKeyId).orElseThrow());
        assertTrue(iamService.findSecretKey(accessKeyId, sessionToken).isPresent());

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(uri.getRawPath() + "?" + uri.getRawQuery())
        .then()
            .statusCode(200)
            .body(equalTo(content));

        String tamperedQuery = uri.getRawQuery().replaceFirst(
                "X-Amz-Signature=[0-9a-f]+", "X-Amz-Signature=" + "0".repeat(64));
        given()
            .urlEncodingEnabled(false)
        .when()
            .get(uri.getRawPath() + "?" + tamperedQuery)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("SignatureDoesNotMatch"));

        given().filter(OWNER).when().delete("/" + bucket + "/" + key).then().statusCode(204);
        given().filter(OWNER).when().delete("/" + bucket).then().statusCode(204);
    }

    private static String queryParam(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
