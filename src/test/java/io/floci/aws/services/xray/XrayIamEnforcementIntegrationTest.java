package io.floci.aws.services.xray;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * X-Ray routes are rewritten onto an internal prefix before the IAM filter runs, so this proves the
 * rewrite does not take a request out of IAM's reach: a key with no policy is refused on the same
 * paths a key with {@code xray:*} is allowed through.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class XrayIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "741686000030";
    private static final String TRACE_ID = "1-5f3b8a0e-3c9d4d8e9b7a2c1d4e5f6a7b";

    @TestHTTPResource("/")
    URI baseUri;

    private final PartitionCleanup cleanup = new PartitionCleanup();

    @RegisterExtension
    final AfterAllCallback classCleanup = cleanup::afterEach;

    private String deniedKey;
    private String allowedKey;

    @BeforeAll
    void createKeys() {
        deniedKey = userWithPolicy(null);
        allowedKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"xray:*","Resource":"*"}]}
                """);
    }

    @Test
    void aKeyWithoutAPolicyCannotWriteSegments() {
        given().header("Authorization", auth(deniedKey, "xray"))
                .contentType("application/json").body(segmentBody())
                .when().post("/TraceSegments")
                .then().statusCode(403);
    }

    @Test
    void aKeyWithoutAPolicyCannotReadSamplingRules() {
        given().header("Authorization", auth(deniedKey, "xray"))
                .contentType("application/json").body("{}")
                .when().post("/GetSamplingRules")
                .then().statusCode(403);
    }

    @Test
    void aKeyWithXrayAllowWritesAndReads() {
        given().header("Authorization", auth(allowedKey, "xray"))
                .contentType("application/json").body(segmentBody())
                .when().post("/TraceSegments")
                .then().statusCode(200).body("UnprocessedTraceSegments.size()", equalTo(0));

        given().header("Authorization", auth(allowedKey, "xray"))
                .contentType("application/json").body("{}")
                .when().post("/GetSamplingRules")
                .then().statusCode(200);
    }

    private static String segmentBody() {
        double now = Instant.now().getEpochSecond();
        String document = "{\"trace_id\":\"" + TRACE_ID + "\",\"id\":\"0f1e2d3c4b5a6978\","
                + "\"name\":\"iam-probe\",\"start_time\":" + now + ",\"end_time\":" + (now + 1) + "}";
        return "{\"TraceSegmentDocuments\":[\"" + document.replace("\"", "\\\"") + "\"]}";
    }

    private String userWithPolicy(String policy) {
        String user = "xray-iam-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        if (policy != null) {
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "xray", policy);
        }
        return key;
    }
}
