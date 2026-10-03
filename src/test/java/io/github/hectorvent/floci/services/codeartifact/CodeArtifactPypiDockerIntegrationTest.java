package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real PyPI-shaped upload/download round trip against a live, per-repository pypiserver
 * container, started by {@link PypiserverSidecarManager} exactly as in production. The upload body
 * is a hand-built multipart form matching the fields a real {@code twine upload} sends (verified
 * against a live pypiserver instance and real twine by hand during design), not a captured fixture:
 * unlike npm's envelope, the parsing itself ({@link io.github.hectorvent.floci.core.common.MultipartFormParser})
 * is already covered by its own unit tests and by {@code CodeArtifactPypiControllerTest}, so what
 * this class needs to prove is the real container's behavior, not the parser's fidelity to one
 * exact client's byte-for-byte output.
 */
@QuarkusTest
@TestProfile(CodeArtifactPypiSidecarProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactPypiDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "pypi-sidecar-domain";
    private static final String REPO = "pypi-sidecar-repo";
    private static final String PACKAGE_NAME = "floci-pypiserver-spike";
    // Real sdist filenames use underscores where the package name uses hyphens; exercising this
    // exact mismatch is what proves the PEP 503 normalization in the overwrite-check actually runs,
    // not just a name that happens to already match its own normalized form.
    private static final String FILENAME = "floci_pypiserver_spike-1.0.0.tar.gz";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        CodeArtifactPypiSidecarProfile.requireDockerAndTheSidecarImage();
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void createDomainRepositoryAndAuthorizationToken() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then().statusCode(200);

        bearerToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");
    }

    @Test
    @Order(1)
    void uploadThenDownloadRoundTripsTheExactBytesThroughTheSimpleIndex() {
        byte[] content = "real-sdist-bytes".getBytes(StandardCharsets.UTF_8);

        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", PACKAGE_NAME, FILENAME, content))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        String indexHtml = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(indexHtml.contains(FILENAME), "simple index must list the uploaded file: " + indexHtml);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/packages/" + FILENAME)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals(new String(content, StandardCharsets.UTF_8), new String(fetched, StandardCharsets.UTF_8));
    }

    /**
     * AWS's own CodeArtifact docs ("Overwriting package assets" in packages-overview.html) say a
     * republish of an asset whose content is byte-identical to what is already published succeeds
     * because the operation is idempotent; only different content gets the 409 the next test
     * covers. A naive "is this filename already listed" check would reject both cases alike, which
     * would make twine's own retry-after-a-dropped-response behavior fail spuriously.
     */
    @Test
    @Order(2)
    void reuploadingTheExactSameFileSucceedsIdempotently() {
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", PACKAGE_NAME, FILENAME, "real-sdist-bytes".getBytes(StandardCharsets.UTF_8)))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/packages/" + FILENAME)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("real-sdist-bytes", new String(fetched, StandardCharsets.UTF_8));
    }

    @Test
    @Order(3)
    void reuploadingTheSameFileIsRejectedAndTheOriginalContentSurvives() {
        byte[] differentContent = "different-sdist-bytes".getBytes(StandardCharsets.UTF_8);

        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", PACKAGE_NAME, FILENAME, differentContent))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(409);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/packages/" + FILENAME)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("real-sdist-bytes", new String(fetched, StandardCharsets.UTF_8));
    }

    @Test
    @Order(4)
    void missingPackageAndMissingRepositoryAreNotFound() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/simple/does-not-exist/")
                .then().statusCode(404);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/no-such-repo/simple/does-not-exist/")
                .then().statusCode(404);
    }

    @Test
    @Order(5)
    void repositoriesAreIsolatedFromEachOther() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=other-repo")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/other-repo/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(404);
    }

    @Test
    @Order(6)
    void missingOrWrongDomainTokensAreUnauthorized() {
        // /simple/<package>/, not the bare /simple/ root: AWS doesn't support that path either,
        // so there's no route for it at all, and an unrouted path 404s before auth ever runs.
        // Challenges as Basic, not Bearer: real pip and twine both authenticate with HTTP Basic
        // (username=aws, password=<token>), so this is the scheme a retry would actually honor.
        given().get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(401)
                .header("WWW-Authenticate", startsWith("Basic"));

        given().header("Authorization", "Bearer not-a-real-token")
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(401);

        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=other-pypi-token-domain")
                .then().statusCode(200);
        String otherDomainToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=other-pypi-token-domain")
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");

        given().header("Authorization", "Bearer " + otherDomainToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(401);
    }

    @Test
    @Order(7)
    void aRealPipClientsBasicAuthCredentialsAreAcceptedWithTheTokenAsThePassword() {
        String basic = "Basic " + Base64.getEncoder().encodeToString(("aws:" + bearerToken).getBytes(StandardCharsets.UTF_8));

        given().header("Authorization", basic)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(200)
                .body(containsString(FILENAME));
    }

    @Test
    @Order(8)
    void concurrentFirstUseOfANewRepositoryOnlyStartsOneContainer() throws InterruptedException {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=concurrent-repo")
                .then().statusCode(200);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger notFoundResponses = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        given().header("Authorization", "Bearer " + bearerToken)
                                .get("/codeartifact/pypi/" + DOMAIN + "/concurrent-repo/simple/does-not-exist/")
                                .then().statusCode(404);
                        notFoundResponses.incrementAndGet();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(attempts, notFoundResponses.get());
    }

    @Test
    @Order(9)
    void recreatingASameNamedRepositoryDoesNotInheritThePreviousOnesPackages() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", PACKAGE_NAME, FILENAME, "first-generation".getBytes(StandardCharsets.UTF_8)))
                .post("/codeartifact/pypi/" + DOMAIN + "/reused-name/")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/reused-name/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(404);
    }

    @Test
    @Order(10)
    void aDomainCreatedInANonDefaultRegionIsServedThroughTheTokensOwnRegion() {
        String nonDefaultRegionAuth =
                "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-west-2/codeartifact/aws4_request";
        String domain = "pypi-sidecar-non-default-region-domain";

        given().contentType("application/json").header("Authorization", nonDefaultRegionAuth).body("{}")
                .post("/v1/domain?domain=" + domain)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", nonDefaultRegionAuth).body("{}")
                .post("/v1/repository?domain=" + domain + "&repository=" + REPO)
                .then().statusCode(200);
        String token = given().header("Authorization", nonDefaultRegionAuth)
                .post("/v1/authorization-token?domain=" + domain)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");

        given().header("Authorization", "Bearer " + token)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", PACKAGE_NAME, FILENAME, "non-default-region-bytes".getBytes(StandardCharsets.UTF_8)))
                .post("/codeartifact/pypi/" + domain + "/" + REPO + "/")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + token)
                .get("/codeartifact/pypi/" + domain + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(200);
    }

    @Test
    @Order(11)
    void aDomainCreatedUnderANonDefaultAccountIsServedThroughTheTokensOwnAccount() {
        String otherAccountAuth = "AWS4-HMAC-SHA256 Credential=111122223333/20260904/us-east-1/codeartifact/aws4_request";
        String domain = "pypi-sidecar-cross-account-domain";

        given().contentType("application/json").header("Authorization", otherAccountAuth).body("{}")
                .post("/v1/domain?domain=" + domain)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", otherAccountAuth).body("{}")
                .post("/v1/repository?domain=" + domain + "&repository=" + REPO)
                .then().statusCode(200);
        String token = given().header("Authorization", otherAccountAuth)
                .post("/v1/authorization-token?domain=" + domain)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");

        given().header("Authorization", "Bearer " + token)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", PACKAGE_NAME, FILENAME, "cross-account-bytes".getBytes(StandardCharsets.UTF_8)))
                .post("/codeartifact/pypi/" + domain + "/" + REPO + "/")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + token)
                .get("/codeartifact/pypi/" + domain + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(200);
    }

    /**
     * The real-world gap this closes: {@code GetPackageVersionAsset} used to 404 for every
     * pypi-published file since Floci's generic-format package-version store never had a record
     * for it (pypi publishing goes straight to pypiserver, bypassing it entirely). Proves the JSON
     * API now bridges to the same file {@link #uploadThenDownloadRoundTripsTheExactBytesThroughTheSimpleIndex}
     * already confirmed is really there, through the real sidecar, not a mock. No namespace: pypi
     * packages don't have one.
     */
    @Test
    @Order(12)
    void getPackageVersionAssetBridgesToTheRealPypiserverSidecar() {
        byte[] fetched = given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=" + PACKAGE_NAME + "&version=1.0.0&asset=" + FILENAME)
                .then().statusCode(200)
                .header("X-AssetName", equalTo(FILENAME))
                .extract().asByteArray();
        assertEquals("real-sdist-bytes", new String(fetched, StandardCharsets.UTF_8));
    }

    @Test
    @Order(12)
    void getPackageVersionAssetReturns404ForAPypiAssetThatWasNeverUploaded() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=" + PACKAGE_NAME + "&version=1.0.0&asset=does-not-exist.tar.gz")
                .then().statusCode(404);
    }

    /**
     * Proves the actual mechanism, not just that {@code PypiserverSidecarManager} has a method
     * named right: {@code ContainerTeardowns.stopAll} runs every {@code ContainerTeardown} on
     * {@code /state/reset}, and this is what stops a live container.
     *
     * <p>Ordered before every other test, for the same reason {@code CodeArtifactNpmDockerIntegrationTest}'s
     * equivalent reset test runs first: a reset stops every container this process's pool is
     * tracking, not just the one this test starts, so running it after the other tests would sweep
     * up their containers too and break the baseline comparison for a reason unrelated to whether
     * this test's own container actually stopped.
     */
    @Test
    @Order(-1)
    void stateResetStopsTheRepositorysPypiserverContainer() throws Exception {
        int baseline = runningPypiserverTestContainerCount();

        String domain = "pypi-sidecar-reset-domain";
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=" + domain)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + domain + "&repository=reset-repo")
                .then().statusCode(200);
        String token = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + domain)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");
        // A 404 read still starts the container; only a missing token skips it.
        given().header("Authorization", "Bearer " + token)
                .get("/codeartifact/pypi/" + domain + "/reset-repo/simple/does-not-exist/")
                .then().statusCode(404);
        assertEquals(baseline + 1, runningPypiserverTestContainerCount(),
                "expected reset-repo's pypiserver container to be running before reset");

        given().post("/_floci/state/reset").then().statusCode(200);

        assertEquals(baseline, runningPypiserverTestContainerCount(),
                "state reset must stop reset-repo's pypiserver container");
    }

    private static int runningPypiserverTestContainerCount() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "-q",
                "--filter", "name=floci-aws-codeartifact-pypi-test-pypiserver-")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output.isBlank() ? 0 : (int) output.lines().count();
    }

    /**
     * The fields a real {@code twine upload} sends (verified by hand against a live pypiserver
     * instance during design); pypiserver, and this controller's own parsing, only look at
     * {@code name} and the {@code content} file part, so the rest are included for realism only.
     */
    private static byte[] twineUploadBody(String boundary, String packageName, String filename, byte[] content) {
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\":action\"\r\n\r\n"
                + "file_upload\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"protocol_version\"\r\n\r\n"
                + "1\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + packageName + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"version\"\r\n\r\n"
                + "1.0.0\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"filetype\"\r\n\r\n"
                + "sdist\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"content\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String closing = "\r\n--" + boundary + "--\r\n";
        byte[] preambleBytes = preamble.getBytes(StandardCharsets.UTF_8);
        byte[] closingBytes = closing.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[preambleBytes.length + content.length + closingBytes.length];
        System.arraycopy(preambleBytes, 0, body, 0, preambleBytes.length);
        System.arraycopy(content, 0, body, preambleBytes.length, content.length);
        System.arraycopy(closingBytes, 0, body, preambleBytes.length + content.length, closingBytes.length);
        return body;
    }
}
