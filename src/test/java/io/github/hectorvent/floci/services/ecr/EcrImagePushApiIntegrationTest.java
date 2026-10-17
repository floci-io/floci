package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code PutImage} and the layer API's checks that come before the registry is reached: unknown
 * repositories, unknown uploads and malformed input. Needs no Docker; the push itself is covered
 * by {@code EcrImagePushApiDockerIntegrationTest}.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EcrImagePushApiIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AmazonEC2ContainerRegistry_V20150921.";
    private static final String REPO = "push-api-it/checks";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String UPLOAD_ID = "6a1f2b3c-0000-4000-8000-000000000000";

    private boolean created;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Once, but per test: RestAssured has its port only once a test starts. */
    @BeforeEach
    void createRepository() {
        if (!created) {
            call("CreateRepository", "{\"repositoryName\":\"" + REPO + "\"}").statusCode(200);
            created = true;
        }
    }

    @Test
    void everyActionNeedsAnExistingRepository() {
        String missing = "\"repositoryName\":\"push-api-it/missing\"";
        String[][] calls = {
            {"PutImage", "{" + missing + ",\"imageManifest\":\"{}\",\"imageTag\":\"v1\"}"},
            {"BatchCheckLayerAvailability", "{" + missing + ",\"layerDigests\":[\"" + DIGEST + "\"]}"},
            {"GetDownloadUrlForLayer", "{" + missing + ",\"layerDigest\":\"" + DIGEST + "\"}"},
            {"InitiateLayerUpload", "{" + missing + "}"},
            {"UploadLayerPart", "{" + missing + ",\"uploadId\":\"" + UPLOAD_ID
                    + "\",\"partFirstByte\":0,\"partLastByte\":0,\"layerPartBlob\":\"YQ==\"}"},
            {"CompleteLayerUpload", "{" + missing + ",\"uploadId\":\"" + UPLOAD_ID
                    + "\",\"layerDigests\":[\"" + DIGEST + "\"]}"},
        };
        for (String[] c : calls) {
            call(c[0], c[1]).statusCode(400).body("__type", equalTo("RepositoryNotFoundException"));
        }
    }

    @Test
    void putImageChecksTheManifestAndItsDigest() {
        call("PutImage", "{\"repositoryName\":\"" + REPO + "\",\"imageManifest\":\"{}\",\"imageTag\":\"v1\","
                + "\"imageDigest\":\"" + DIGEST + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ImageDigestDoesNotMatchException"))
                .body("message", containsString("sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"));
        call("PutImage", "{\"repositoryName\":\"" + REPO + "\",\"imageManifest\":\"not json\",\"imageTag\":\"v1\"}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("PutImage", "{\"repositoryName\":\"" + REPO + "\",\"imageManifest\":\"\",\"imageTag\":\"v1\"}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("PutImage", "{\"repositoryName\":\"" + REPO + "\",\"imageManifest\":\"{}\",\"imageTag\":\""
                + "t".repeat(301) + "\"}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void layerDigestsAreChecked() {
        call("GetDownloadUrlForLayer", "{\"repositoryName\":\"" + REPO + "\",\"layerDigest\":\"not-a-digest\"}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("BatchCheckLayerAvailability", "{\"repositoryName\":\"" + REPO + "\",\"layerDigests\":[]}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("CompleteLayerUpload", "{\"repositoryName\":\"" + REPO + "\",\"uploadId\":\"" + UPLOAD_ID
                + "\",\"layerDigests\":[]}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("CompleteLayerUpload", "{\"repositoryName\":\"" + REPO + "\",\"uploadId\":\"" + UPLOAD_ID
                + "\",\"layerDigests\":[\"bad\"]}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        // An upload holds one layer: a second, different digest is refused, not ignored.
        call("CompleteLayerUpload", "{\"repositoryName\":\"" + REPO + "\",\"uploadId\":\"" + UPLOAD_ID
                + "\",\"layerDigests\":[\"sha256:" + "a".repeat(64) + "\",\"sha256:" + "b".repeat(64) + "\"]}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void anUnknownUploadIsNotFound() {
        call("UploadLayerPart", "{\"repositoryName\":\"" + REPO + "\",\"uploadId\":\"" + UPLOAD_ID
                + "\",\"partFirstByte\":0,\"partLastByte\":0,\"layerPartBlob\":\"YQ==\"}")
                .statusCode(400).body("__type", equalTo("UploadNotFoundException"));
        call("CompleteLayerUpload", "{\"repositoryName\":\"" + REPO + "\",\"uploadId\":\"" + UPLOAD_ID
                + "\",\"layerDigests\":[\"" + DIGEST + "\"]}")
                .statusCode(400).body("__type", equalTo("UploadNotFoundException"));
    }

    @Test
    void uploadPartInputIsChecked() {
        call("UploadLayerPart", "{\"repositoryName\":\"" + REPO + "\",\"uploadId\":\"" + UPLOAD_ID
                + "\",\"partFirstByte\":-1,\"partLastByte\":0,\"layerPartBlob\":\"YQ==\"}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("UploadLayerPart", "{\"repositoryName\":\"" + REPO + "\",\"uploadId\":\"" + UPLOAD_ID
                + "\",\"partFirstByte\":0,\"partLastByte\":0,\"layerPartBlob\":\"%%%\"}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
        call("UploadLayerPart", "{\"repositoryName\":\"" + REPO + "\",\"uploadId\":\"" + UPLOAD_ID
                + "\",\"partFirstByte\":\"zero\",\"partLastByte\":0,\"layerPartBlob\":\"YQ==\"}")
                .statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .header("X-Amz-Target", PREFIX + action)
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then();
    }
}
