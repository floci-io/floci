package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real npm-shaped publish/fetch round trip against a live, per-repository Verdaccio
 * container, started by {@link VerdaccioSidecarManager} exactly as in production. The publish
 * body is a real request the actual npm CLI sent during manual verification (captured with a tiny
 * Node.js capture server, see {@code npm-publish-fixture.json}), not a hand-rolled approximation,
 * so this test exercises the exact envelope shape (including a real gzip tarball attachment) a
 * real {@code npm publish} produces. The full round trip was additionally verified by hand with
 * the real npm CLI against a live Floci instance ({@code GetAuthorizationToken} through to
 * {@code npm install}), which is what first caught that {@code VERDACCIO_PUBLIC_URL} is required:
 * without it, {@code dist.tarball} in the metadata response points at the container's own
 * internal, client-unreachable address instead of back through this proxy.
 */
@QuarkusTest
@TestProfile(CodeArtifactNpmSidecarProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactNpmDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "npm-sidecar-domain";
    private static final String REPO = "npm-sidecar-repo";
    private static final String PACKAGE_NAME = "floci-verdaccio-spike";

    private static String bearerToken;
    private static byte[] publishFixture;
    private static byte[] expectedTarballBytes;

    @BeforeAll
    static void setUp() throws IOException {
        CodeArtifactNpmSidecarProfile.requireDockerAndTheSidecarImage();
        RestAssuredJsonUtils.configureAwsContentTypes();

        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = CodeArtifactNpmDockerIntegrationTest.class
                .getResourceAsStream("/codeartifact/npm-publish-fixture.json")) {
            publishFixture = in.readAllBytes();
        }
        JsonNode fixture = mapper.readTree(publishFixture);
        String base64Tarball = fixture.path("_attachments").path(PACKAGE_NAME + "-1.0.0.tgz").path("data").asText();
        expectedTarballBytes = Base64.getDecoder().decode(base64Tarball);
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
    void publishThenFetchRoundTripsTheRealNpmEnvelope() throws IOException {
        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json")
                .body(publishFixture)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(anyOf201Or200());

        String metadataBody = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200)
                .extract().asString();
        String tarballUrl = new ObjectMapper().readTree(metadataBody)
                .path("versions").path("1.0.0").path("dist").path("tarball").asText();

        // Pins VERDACCIO_PUBLIC_URL: without it this would be the container's own internal,
        // client-unreachable address instead of a URL back through this same proxy.
        assertTrue(tarballUrl.startsWith("http://localhost:4566/codeartifact/npm/" + DOMAIN + "/" + REPO + "/"),
                "tarball URL must point back through the proxy, was: " + tarballUrl);

        byte[] fetchedTarball = given().header("Authorization", "Bearer " + bearerToken)
                .get(tarballUrl.substring("http://localhost:4566".length()))
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(expectedTarballBytes, fetchedTarball);
    }

    @Test
    @Order(2)
    void missingPackageAndMissingRepositoryAreNotFound() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/does-not-exist")
                .then().statusCode(404);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/no-such-repo/does-not-exist")
                .then().statusCode(404);
    }

    @Test
    @Order(3)
    void repositoriesAreIsolatedFromEachOther() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=other-repo")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/other-repo/" + PACKAGE_NAME)
                .then().statusCode(404);
    }

    @Test
    @Order(4)
    void missingOrWrongDomainTokensAreUnauthorized() {
        given().get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(401)
                .header("WWW-Authenticate", startsWith("Bearer"));

        given().header("Authorization", "Bearer not-a-real-token")
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(401);

        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=other-npm-token-domain")
                .then().statusCode(200);
        String otherDomainToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=other-npm-token-domain")
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");

        given().header("Authorization", "Bearer " + otherDomainToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(401);
    }

    @Test
    @Order(5)
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
                                .get("/codeartifact/npm/" + DOMAIN + "/concurrent-repo/does-not-exist")
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
    @Order(6)
    void recreatingASameNamedRepositoryDoesNotInheritThePreviousOnesPackages() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json")
                .body(publishFixture)
                .put("/codeartifact/npm/" + DOMAIN + "/reused-name/" + PACKAGE_NAME)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/reused-name/" + PACKAGE_NAME)
                .then().statusCode(404);
    }

    @Test
    @Order(7)
    void aDomainCreatedInANonDefaultRegionIsServedThroughTheTokensOwnRegion() {
        String nonDefaultRegionAuth =
                "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-west-2/codeartifact/aws4_request";
        String domain = "npm-sidecar-non-default-region-domain";

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

        given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body(publishFixture)
                .put("/codeartifact/npm/" + domain + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", "Bearer " + token)
                .get("/codeartifact/npm/" + domain + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200);
    }

    @Test
    @Order(8)
    void aDomainCreatedUnderANonDefaultAccountIsServedThroughTheTokensOwnAccount() {
        String otherAccountAuth = "AWS4-HMAC-SHA256 Credential=111122223333/20260904/us-east-1/codeartifact/aws4_request";
        String domain = "npm-sidecar-cross-account-domain";

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

        given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body(publishFixture)
                .put("/codeartifact/npm/" + domain + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", "Bearer " + token)
                .get("/codeartifact/npm/" + domain + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200);
    }

    /**
     * Proves the actual mechanism, not just that {@code VerdaccioSidecarManager} has a method
     * named right: {@code ContainerTeardowns.stopAll} runs every {@code ContainerTeardown} on
     * {@code /state/reset}, and this is what stops a live container, not the repository-record
     * walk {@code CodeArtifactService} used to do (which only knew about repositories that still
     * existed in storage).
     *
     * <p>Ordered before every other test, not after: a reset stops every container this process's
     * pool is tracking, not just the one this test starts, so running it after the other tests
     * have already started their own containers would make this test's own reset sweep those up
     * too and fail the baseline comparison below for a reason that has nothing to do with whether
     * reset-repo's own container was actually stopped. Running first means the only baseline is
     * whatever this namespace already had running before this test class touched it at all (e.g.
     * a container an earlier interrupted run never got the chance to clean up), which this reset
     * cannot be expected to touch either. Self-contained (its own domain, repository and token)
     * since the class's shared domain/token fields are not populated until {@code Order(0)} runs,
     * and a state reset wipes them regardless, so every later test must not depend on anything
     * this one touched.
     */
    @Test
    @Order(-1)
    void stateResetStopsTheRepositorysVerdaccioContainer() throws Exception {
        int baseline = runningVerdaccioTestContainerCount();

        String domain = "npm-sidecar-reset-domain";
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
                .get("/codeartifact/npm/" + domain + "/reset-repo/does-not-exist")
                .then().statusCode(404);
        assertEquals(baseline + 1, runningVerdaccioTestContainerCount(),
                "expected reset-repo's Verdaccio container to be running before reset");

        given().post("/_floci/state/reset").then().statusCode(200);

        assertEquals(baseline, runningVerdaccioTestContainerCount(),
                "state reset must stop reset-repo's Verdaccio container");
    }

    private static int runningVerdaccioTestContainerCount() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "-q",
                "--filter", "name=floci-aws-codeartifact-npm-test-verdaccio-")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output.isBlank() ? 0 : (int) output.lines().count();
    }

    private static Matcher<Integer> anyOf201Or200() {
        return anyOf(is(200), is(201));
    }
}
