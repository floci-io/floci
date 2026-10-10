package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * With enforcement on, a session past its expiration is answered with AWS's
 * {@code ExpiredTokenException}, not as a key that exists nowhere, on every call and every time.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ExpiredSessionEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    @Inject
    IamService iamService;

    /**
     * The teardown runs after Quarkus has reset the port RestAssured points at, so it has to say
     * where the application is. The test port is random, which leaves this as the only place to
     * read it from.
     */
    @TestHTTPResource("/")
    static URI baseUri;

    /**
     * A QuarkusTest shares one application with every other test class, so the bucket and the
     * sessions here would otherwise outlive it. The sessions go too: what this class pins is that
     * a refusal leaves them stored, so nothing takes them out until Floci's own sweep runs. Each
     * step is registered before it happens and runs independently of the others, so a bucket that
     * will not delete cannot strand a session.
     */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anExpiredSessionIsRefusedAsExpired() {
        String accessKeyId = expiredSession();

        getCallerIdentity(accessKeyId)
                .then().statusCode(403).body(containsString("<Code>ExpiredTokenException</Code>"));
        // Asked again, it is still expired rather than gone.
        getCallerIdentity(accessKeyId)
                .then().statusCode(403).body(containsString("<Code>ExpiredTokenException</Code>"));
        given()
                .header("Authorization", authorization(accessKeyId, "lambda"))
                .contentType("application/json")
        .when()
                .get("/2015-03-31/functions")
        .then()
                .statusCode(403)
                .header("X-Amzn-Errortype", "ExpiredTokenException")
                .body("__type", containsString("ExpiredTokenException"));
        given()
                .header("Authorization", authorization(accessKeyId, "s3"))
        .when()
                .get("/")
        .then()
                .statusCode(400)
                .body(containsString("<Code>ExpiredToken</Code>"));
    }

    @Test
    void aPresignedPostWithAnExpiredSessionIsRefusedAndTheSessionStaysExpired() {
        // A presigned POST carries its credential in the form body, which only S3 reads.
        String accessKeyId = expiredSession();
        String bucket = "expired-session-post-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(),
                authorization("test", "s3"), bucket));
        given()
                .header("Authorization", authorization("test", "s3"))
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);

        given()
                .multiPart("key", "expired.txt")
                .multiPart("x-amz-credential", accessKeyId + "/20260101/us-east-1/s3/aws4_request")
                .multiPart("file", "expired.txt", "x".getBytes(StandardCharsets.UTF_8), "text/plain")
        .when()
                .post("/" + bucket)
        .then()
                .statusCode(400)
                .body(containsString("<Code>ExpiredToken</Code>"));
        // Refusing it did not delete the session, which would leave the key unknown.
        getCallerIdentity(accessKeyId)
                .then().statusCode(403).body(containsString("<Code>ExpiredTokenException</Code>"));
    }

    private String expiredSession() {
        String accessKeyId = "ASIAEXPIRED"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase(Locale.ROOT);
        cleanup.register(() -> iamService.unregisterSession(ACCOUNT_ID, accessKeyId));
        iamService.registerSessionForAccount(ACCOUNT_ID, accessKeyId, "secret",
                "arn:aws:iam::" + ACCOUNT_ID + ":role/expired-session-role", LONG_AGO, null);
        return accessKeyId;
    }

    private static Response getCallerIdentity(String accessKeyId) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization(accessKeyId, "sts"))
                .formParam("Action", "GetCallerIdentity")
                .formParam("Version", "2011-06-15")
        .when()
                .post("/");
    }

    private static String authorization(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260101/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=not-verified";
    }
}
