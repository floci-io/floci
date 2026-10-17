package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
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
import static org.hamcrest.Matchers.equalTo;
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
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactNpmDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "npm-sidecar-domain";
    private static final String REPO = "npm-sidecar-repo";
    private static final String PACKAGE_NAME = "floci-verdaccio-spike";

    @Inject
    EmulatorConfig config;

    private static String bearerToken;
    private static byte[] publishFixture;
    private static byte[] expectedTarballBytes;

    @BeforeAll
    static void setUp() throws IOException {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.npm-image");
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
        long assetSizeBytesBeforePublish = describeDomainAssetSizeBytes();

        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json")
                .body(publishFixture)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(anyOf201Or200());

        // assetSizeBytes used to be hardcoded to 0 regardless of what was actually published; this
        // is the real Verdaccio round trip proving a real npm publish now counts, by exactly the
        // tarball's own decoded byte count (not the envelope's base64-and-JSON-wrapped size).
        assertEquals(assetSizeBytesBeforePublish + expectedTarballBytes.length, describeDomainAssetSizeBytes());

        String metadataBody = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200)
                .extract().asString();
        String tarballUrl = new ObjectMapper().readTree(metadataBody)
                .path("versions").path("1.0.0").path("dist").path("tarball").asText();

        // Pins VERDACCIO_PUBLIC_URL: without it this would be the container's own internal,
        // client-unreachable address instead of a URL back through this same proxy.
        String publicUrl = config.effectiveBaseUrl();
        assertTrue(tarballUrl.startsWith(publicUrl + "/codeartifact/npm/" + DOMAIN + "/" + REPO + "/"),
                "tarball URL must point back through the proxy, was: " + tarballUrl);

        byte[] fetchedTarball = given().header("Authorization", "Bearer " + bearerToken)
                .get(tarballUrl.substring(publicUrl.length()))
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(expectedTarballBytes, fetchedTarball);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package=" + PACKAGE_NAME)
                .then().statusCode(200).body("package.name", equalTo(PACKAGE_NAME))
                .body("package.originConfiguration.restrictions.publish", equalTo("ALLOW"));
    }

    /**
     * Confirmed against this exact pinned Verdaccio image that
     * republishing a just-published, byte-identical tarball comes back {@code 409 "this package
     * is already present"} on its own, never a success, unlike real AWS CodeArtifact's own
     * documented idempotent-republish contract (the same rule Maven and PyPI already implement).
     * Republishing the identical fixture a second time against the real sidecar must still
     * succeed.
     */
    @Test
    @Order(2)
    void republishingTheSameEnvelopeASecondTimeIsStillIdempotent() {
        long assetSizeBytesBeforeRepublish = describeDomainAssetSizeBytes();

        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json")
                .body(publishFixture)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(anyOf201Or200());

        // The confirmed-republish short-circuit never reaches Verdaccio, so no new bytes were
        // written; assetSizeBytes must not count this tarball a second time.
        assertEquals(assetSizeBytesBeforeRepublish, describeDomainAssetSizeBytes());
    }

    private static long describeDomainAssetSizeBytes() {
        return given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
    }

    @Test
    @Order(3)
    void missingPackageAndMissingRepositoryAreNotFound() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/does-not-exist")
                .then().statusCode(404);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/no-such-repo/does-not-exist")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package=does-not-exist")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(4)
    void repositoriesAreIsolatedFromEachOther() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=other-repo")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/other-repo/" + PACKAGE_NAME)
                .then().statusCode(404);
    }

    @Test
    @Order(5)
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
    @Order(6)
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
    @Order(7)
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
    @Order(8)
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
    @Order(9)
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
     * The real-world gap this closes: {@code GetPackageVersionAsset} used to 404 for every
     * npm-published package since Floci's generic-format package-version store never had a
     * record for it (npm publishing goes straight to Verdaccio, bypassing it entirely). Proves the
     * JSON API now bridges to the same tarball {@link #publishThenFetchRoundTripsTheRealNpmEnvelope}
     * already confirmed is really there, through the real sidecar, not a mock.
     */
    @Test
    @Order(10)
    void getPackageVersionAssetBridgesToTheRealVerdaccioSidecar() throws IOException {
        byte[] fetched = given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm"
                        + "&package=" + PACKAGE_NAME + "&version=1.0.0&asset=" + PACKAGE_NAME + "-1.0.0.tgz")
                .then().statusCode(200)
                .header("X-AssetName", equalTo(PACKAGE_NAME + "-1.0.0.tgz"))
                .extract().asByteArray();
        assertArrayEquals(expectedTarballBytes, fetched);
    }

    @Test
    @Order(10)
    void getPackageVersionAssetReturns404ForAnNpmAssetThatWasNeverPublished() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm"
                        + "&package=" + PACKAGE_NAME + "&version=1.0.0&asset=does-not-exist.tgz")
                .then().statusCode(404);
    }

    /**
     * The real-world gap this closes: {@code DescribePackageVersion} used to 404 for every
     * npm-published version the same reason {@code GetPackageVersionAsset} did. Confirms the
     * bridge checks the real Verdaccio packument's own {@code versions} map (not a mock) for the
     * published version, and 404s for one that was never published.
     */
    @Test
    @Order(10)
    void describePackageVersionBridgesToTheRealVerdaccioSidecar() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm"
                        + "&package=" + PACKAGE_NAME + "&version=1.0.0")
                .then().statusCode(200)
                .body("packageVersion.status", equalTo("Published"))
                .body("packageVersion.packageName", equalTo(PACKAGE_NAME))
                .body("packageVersion.version", equalTo("1.0.0"));

        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm"
                        + "&package=" + PACKAGE_NAME + "&version=9.9.9")
                .then().statusCode(404);
    }

    /**
     * A throwaway package, not {@link #PACKAGE_NAME}: deleting it exercises the real Verdaccio
     * unpublish-whole-package mechanism (GET the packument's own {@code _rev}, then {@code DELETE}
     * with that revision), confirmed live before this was built, and must not disturb the package
     * every other test in this class shares.
     */
    @Test
    @Order(11)
    void deletePackageRemovesTheWholeVerdaccioPackage() {
        String packageName = "delete-me-pkg";
        byte[] tarball = "throwaway tarball".getBytes(StandardCharsets.UTF_8);
        String envelope = "{\"name\":\"" + packageName + "\",\"versions\":{\"1.0.0\":{\"name\":\"" + packageName
                + "\",\"version\":\"1.0.0\",\"dist\":{\"tarball\":\"http://ignored/" + packageName + "-1.0.0.tgz\""
                + "}}},\"_attachments\":{\"" + packageName + "-1.0.0.tgz\":{\"content_type\":"
                + "\"application/octet-stream\",\"data\":\""
                + Base64.getEncoder().encodeToString(tarball) + "\",\"length\":" + tarball.length + "}}}";

        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json").body(envelope)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", AUTH)
                .delete("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package=" + packageName)
                .then().statusCode(200).body("deletedPackage.package", equalTo(packageName));

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package=" + packageName)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * The fix for a real finding (hectorvent, PR #5484): the pinned Verdaccio image is configured
     * with {@code unpublish: $all}, so a real npm client's native {@code DELETE} straight to the
     * packument ({@code npm unpublish <pkg> --force}) bypasses {@code DeletePackage} entirely and
     * goes straight through this proxy's own transparent passthrough for any non-publish request.
     * Without detecting this one wire shape, the byte count this package's publish added would
     * survive the unpublish forever, with nothing to ever clean it up ({@code DeletePackage} never
     * runs; {@code DeleteRepository} only cleans it up if the whole repository is later deleted
     * too). A throwaway package, not {@link #PACKAGE_NAME}, for the same reason as the test above.
     */
    @Test
    @Order(12)
    void nativeNpmUnpublishCleansUpTheByteCountToo() {
        String packageName = "unpublish-me-pkg";
        byte[] tarball = "unpublish throwaway tarball".getBytes(StandardCharsets.UTF_8);
        String envelope = "{\"name\":\"" + packageName + "\",\"versions\":{\"1.0.0\":{\"name\":\"" + packageName
                + "\",\"version\":\"1.0.0\",\"dist\":{\"tarball\":\"http://ignored/" + packageName + "-1.0.0.tgz\""
                + "}}},\"_attachments\":{\"" + packageName + "-1.0.0.tgz\":{\"content_type\":"
                + "\"application/octet-stream\",\"data\":\""
                + Base64.getEncoder().encodeToString(tarball) + "\",\"length\":" + tarball.length + "}}}";

        long assetSizeBytesBeforePublish = describeDomainAssetSizeBytes();
        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json").body(envelope)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(anyOf201Or200());
        assertEquals(assetSizeBytesBeforePublish + tarball.length, describeDomainAssetSizeBytes());

        String rev = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(200)
                .extract().jsonPath().getString("_rev");

        given().header("Authorization", "Bearer " + bearerToken)
                .delete("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName + "/-rev/" + rev)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(404);
        assertEquals(assetSizeBytesBeforePublish, describeDomainAssetSizeBytes());
    }

    /**
     * A second real finding found alongside the npm readme redesign itself: the native unpublish
     * path above cleans up the byte count and the deletion fence, but a first version of that same
     * fix left {@code packageVersionReadmes} untouched, so {@code GetPackageVersionReadme} would
     * keep answering for a package a real npm registry (and real CodeArtifact) now 404s on,
     * forever, since nothing else ever revisits that store once it's written. A throwaway
     * package, not {@link #PACKAGE_NAME}, for the same reason as the tests above.
     */
    @Test
    @Order(13)
    void nativeNpmUnpublishCleansUpTheCapturedReadmeToo() {
        String packageName = "unpublish-readme-pkg";
        byte[] tarball = "unpublish readme throwaway tarball".getBytes(StandardCharsets.UTF_8);
        String envelope = "{\"name\":\"" + packageName + "\",\"versions\":{\"1.0.0\":{\"name\":\"" + packageName
                + "\",\"version\":\"1.0.0\",\"readme\":\"# Soon to be unpublished\",\"dist\":{\"tarball\":"
                + "\"http://ignored/" + packageName + "-1.0.0.tgz\""
                + "}}},\"_attachments\":{\"" + packageName + "-1.0.0.tgz\":{\"content_type\":"
                + "\"application/octet-stream\",\"data\":\""
                + Base64.getEncoder().encodeToString(tarball) + "\",\"length\":" + tarball.length + "}}}";

        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json").body(envelope)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + packageName + "&version=1.0.0")
                .then().statusCode(200)
                .body("readme", equalTo("# Soon to be unpublished"));

        String rev = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(200)
                .extract().jsonPath().getString("_rev");

        given().header("Authorization", "Bearer " + bearerToken)
                .delete("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName + "/-rev/" + rev)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + packageName + "&version=1.0.0")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * Each of a multi-version npm package's versions is published in its own {@code PUT}, exactly
     * like a real {@code npm publish} (confirmed directly: an earlier draft of this test tried
     * publishing two versions in one envelope and Verdaccio itself rejected it outright, {@code 400
     * unsupported registry call}). Proves readme capture is genuinely per-version, not just
     * per-package: publishing the second version must not disturb the first's already-captured
     * readme.
     *
     * <p>Unpublishing a single version of a multi-version npm package (real wire shape confirmed
     * against npm/cli's own source: a {@code PUT} to the same {@code /-rev/<rev>} path the
     * whole-package {@code DELETE} unpublish already uses, carrying the packument with that one
     * version removed) is not detected anywhere in this proxy, deliberately: see
     * {@code CodeArtifactNpmDataPlane#nativeUnpublishTarget}'s own javadoc for why, left as a
     * documented, scoped-out limitation rather than fixed without the request-body-reading
     * plumbing a correct, narrower fix for it actually needs.
     */
    @Test
    @Order(14)
    void publishingASecondVersionDoesNotDisturbTheFirstVersionsCapturedReadme() {
        String packageName = "multi-version-readme-pkg";
        byte[] tarballV1 = "multi version readme v1".getBytes(StandardCharsets.UTF_8);
        byte[] tarballV2 = "multi version readme v2".getBytes(StandardCharsets.UTF_8);
        String envelopeV1 = "{\"name\":\"" + packageName + "\",\"versions\":{\"1.0.0\":{\"name\":\""
                + packageName + "\",\"version\":\"1.0.0\",\"readme\":\"# v1\",\"dist\":{\"tarball\":"
                + "\"http://ignored/" + packageName + "-1.0.0.tgz\"}}},\"_attachments\":{\"" + packageName
                + "-1.0.0.tgz\":{\"content_type\":\"application/octet-stream\",\"data\":\""
                + Base64.getEncoder().encodeToString(tarballV1) + "\",\"length\":" + tarballV1.length + "}}}";
        String envelopeV2 = "{\"name\":\"" + packageName + "\",\"versions\":{\"2.0.0\":{\"name\":\""
                + packageName + "\",\"version\":\"2.0.0\",\"readme\":\"# v2\",\"dist\":{\"tarball\":"
                + "\"http://ignored/" + packageName + "-2.0.0.tgz\"}}},\"_attachments\":{\"" + packageName
                + "-2.0.0.tgz\":{\"content_type\":\"application/octet-stream\",\"data\":\""
                + Base64.getEncoder().encodeToString(tarballV2) + "\",\"length\":" + tarballV2.length + "}}}";

        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json").body(envelopeV1)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(anyOf201Or200());
        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json").body(envelopeV2)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + packageName + "&version=1.0.0")
                .then().statusCode(200).body("readme", equalTo("# v1"));
        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + packageName + "&version=2.0.0")
                .then().statusCode(200).body("readme", equalTo("# v2"));
    }

    /**
     * The fix for a real finding: a first implementation answered this live from Verdaccio's own
     * packument, which turned out, checked directly against this same live container, to hoist
     * {@code readme} to the top level only (the single most-recently-published version), dropping
     * the per-version field entirely from its own GET response — even though a real npm client's
     * publish envelope (the real fixture {@link #setUp} loads, captured from an actual {@code npm
     * publish}) genuinely sends one per version. This exercises the real capture-at-publish-time
     * path end to end against that same live container: the exact text the fixture's own
     * {@code versions["1.0.0"].readme} field carries ({@code "ERROR: No README data found!"},
     * npm's own literal placeholder for a package with no real {@code README.md}, not an absent
     * field) must come back unchanged.
     */
    @Test
    @Order(15)
    void getPackageVersionReadmeReturnsWhatTheRealPublishEnvelopeSent() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + PACKAGE_NAME + "&version=1.0.0")
                .then().statusCode(200)
                .body("readme", equalTo("ERROR: No README data found!"))
                .body("package", equalTo(PACKAGE_NAME))
                .body("version", equalTo("1.0.0"));
    }

    /**
     * The follow-up hectorvent asked for on #5644: a version published with no {@code readme}
     * field at all (unlike {@link #getPackageVersionReadmeReturnsWhatTheRealPublishEnvelopeSent},
     * whose fixture carries npm's own literal placeholder text, a real string) must still read back
     * as an empty string, not 404 — the same empty-readme answer pypi's upload path already gives
     * when twine sends no {@code description} field. Before the fix, {@code checkRepublish} only
     * added a version to {@code readmeByVersion} when its {@code readme} field was non-null, so a
     * version published without one was never recorded at all, and {@code GetPackageVersionReadme}
     * answered {@code ResourceNotFoundException} for a version that genuinely exists on Verdaccio.
     */
    @Test
    @Order(16)
    void publishingWithNoReadmeFieldStillRecordsAnEmptyReadmeRatherThanNothing() {
        String packageName = "no-readme-field-pkg";
        byte[] tarball = "no readme field throwaway tarball".getBytes(StandardCharsets.UTF_8);
        String envelope = "{\"name\":\"" + packageName + "\",\"versions\":{\"1.0.0\":{\"name\":\"" + packageName
                + "\",\"version\":\"1.0.0\",\"dist\":{\"tarball\":"
                + "\"http://ignored/" + packageName + "-1.0.0.tgz\""
                + "}}},\"_attachments\":{\"" + packageName + "-1.0.0.tgz\":{\"content_type\":"
                + "\"application/octet-stream\",\"data\":\""
                + Base64.getEncoder().encodeToString(tarball) + "\",\"length\":" + tarball.length + "}}}";

        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json").body(envelope)
                .put("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + packageName)
                .then().statusCode(anyOf201Or200());

        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + packageName + "&version=1.0.0")
                .then().statusCode(200)
                .body("readme", equalTo(""))
                .body("package", equalTo(packageName))
                .body("version", equalTo("1.0.0"));
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

    private int runningVerdaccioTestContainerCount() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "-q",
                "--filter", "name=" + ContainerStorageHelper.dockerName(config, "floci-verdaccio-"))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output.isBlank() ? 0 : (int) output.lines().count();
    }

    private static Matcher<Integer> anyOf201Or200() {
        return anyOf(is(200), is(201));
    }
}
