package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p>The containers are namespaced by {@link SidecarContainersProfile}, which this class shares
 * with the other sidecar classes, so its last test deletes the repositories and domains it created.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactPypiDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String NON_DEFAULT_REGION_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-west-2/codeartifact/aws4_request";
    private static final String OTHER_ACCOUNT_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "pypi-sidecar-domain";
    private static final String REPO = "pypi-sidecar-repo";
    private static final String PACKAGE_NAME = "floci-pypiserver-spike";
    // Real sdist filenames use underscores where the package name uses hyphens; exercising this
    // exact mismatch is what proves the PEP 503 normalization in the overwrite-check actually runs,
    // not just a name that happens to already match its own normalized form.
    private static final String FILENAME = "floci_pypiserver_spike-1.0.0.tar.gz";

    private static String bearerToken;

    @Inject
    EmulatorConfig config;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.pypi-image");
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
        long assetSizeBytesBeforeUpload = describeDomainAssetSizeBytes();

        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", PACKAGE_NAME, FILENAME, content))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        // assetSizeBytes used to be hardcoded to 0 regardless of what was actually published; this
        // is the real pypiserver round trip proving a real twine upload now counts.
        assertEquals(assetSizeBytesBeforeUpload + content.length, describeDomainAssetSizeBytes());

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

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi&package=" + PACKAGE_NAME)
                .then().statusCode(200).body("package.name", equalTo(PACKAGE_NAME))
                .body("package.originConfiguration.restrictions.publish", equalTo("ALLOW"));
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
        long assetSizeBytesBeforeReupload = describeDomainAssetSizeBytes();

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
        // The idempotent-match short-circuit never forwards to pypiserver, so no new bytes were
        // written; assetSizeBytes must not count this file a second time.
        assertEquals(assetSizeBytesBeforeReupload, describeDomainAssetSizeBytes());
    }

    private static long describeDomainAssetSizeBytes() {
        return given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
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

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi&package=does-not-exist")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
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
        String domain = "pypi-sidecar-non-default-region-domain";

        given().contentType("application/json").header("Authorization", NON_DEFAULT_REGION_AUTH).body("{}")
                .post("/v1/domain?domain=" + domain)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", NON_DEFAULT_REGION_AUTH).body("{}")
                .post("/v1/repository?domain=" + domain + "&repository=" + REPO)
                .then().statusCode(200);
        String token = given().header("Authorization", NON_DEFAULT_REGION_AUTH)
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
        String domain = "pypi-sidecar-cross-account-domain";

        given().contentType("application/json").header("Authorization", OTHER_ACCOUNT_AUTH).body("{}")
                .post("/v1/domain?domain=" + domain)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", OTHER_ACCOUNT_AUTH).body("{}")
                .post("/v1/repository?domain=" + domain + "&repository=" + REPO)
                .then().statusCode(200);
        String token = given().header("Authorization", OTHER_ACCOUNT_AUTH)
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
     * The real-world gap this closes: {@code DescribePackageVersion} used to 404 for every
     * pypi-uploaded version the same reason {@code GetPackageVersionAsset} did. Confirms the
     * bridge reads the real pypiserver container's own per-project JSON listing (not a mock) to
     * confirm the version exists, and 404s for one that was never uploaded.
     */
    @Test
    @Order(12)
    void describePackageVersionBridgesToTheRealPypiserverSidecar() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=" + PACKAGE_NAME + "&version=1.0.0")
                .then().statusCode(200)
                .body("packageVersion.status", equalTo("Published"))
                .body("packageVersion.packageName", equalTo(PACKAGE_NAME))
                .body("packageVersion.version", equalTo("1.0.0"));

        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=" + PACKAGE_NAME + "&version=9.9.9")
                .then().statusCode(404);
    }

    /**
     * pypiserver has no single whole-package delete route the way Verdaccio and Reposilite do, but
     * its own management endpoint ({@code POST /} with {@code :action=remove_pkg}) does delete a
     * named version for real, confirmed live: {@code DeletePackage} lists every version through
     * that same container and removes each one, so this asserts the real removal rather than a
     * refusal, and that the file this class's earlier tests uploaded is genuinely gone afterward.
     */
    @Test
    @Order(13)
    void deletePackageRemovesTheFileFromTheRealPypiserverSidecar() {
        given().header("Authorization", AUTH)
                .delete("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi&package="
                        + PACKAGE_NAME)
                .then().statusCode(200).body("deletedPackage.package", equalTo(PACKAGE_NAME));

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=" + PACKAGE_NAME + "&version=1.0.0&asset=" + FILENAME)
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi&package="
                        + PACKAGE_NAME)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * The real-world gap this closes: the readme-capture call site inside {@code
     * CodeArtifactPypiController#upload} had never once been exercised by a real multipart body
     * against a live pypiserver container (every other readme test either called {@code
     * CodeArtifactService} directly or never sent a {@code description} field at all). A
     * throwaway package, not {@link #PACKAGE_NAME}, so it doesn't disturb the ordered sequence
     * above.
     */
    @Test
    @Order(14)
    void getPackageVersionReadmeReturnsWhatARealTwineUploadSent() {
        String packageName = "floci-pypiserver-readme-spike";
        String filename = "floci_pypiserver_readme_spike-1.0.0.tar.gz";
        String description = "# Real Readme\n\nSent as the real twine upload's own description field.";

        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, filename,
                        "readme-spike-bytes".getBytes(StandardCharsets.UTF_8), description))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi&package="
                        + packageName + "&version=1.0.0")
                .then().statusCode(200)
                .body("readme", equalTo(description))
                .body("package", equalTo(packageName))
                .body("version", equalTo("1.0.0"));
    }

    /**
     * The real-world gap this closes: {@code CopyPackageVersions} used to support only generic
     * and maven; pypi keeps its uploaded files straight on pypiserver's own flat storage rather
     * than a {@code packageVersions} record. Confirms the bridge reads the file from the real
     * sidecar (not a mock) and republishes it to the destination repository's own pypiserver
     * instance, byte for byte. {@code other-repo} is the empty repository {@link
     * #repositoriesAreIsolatedFromEachOther} already created.
     */
    @Test
    @Order(15)
    void copyPackageVersionsBridgesToTheRealPypiserverSidecar() {
        String packageName = "copyme-pypi-pkg";
        String filename = "copyme_pypi_pkg-1.0.0.tar.gz";
        byte[] content = "copy-me-pypi-sdist-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, filename, content, "Copy me description"))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"1.0.0\"]}")
                .post("/v1/package/versions/copy?domain=" + DOMAIN + "&source-repository=" + REPO
                        + "&destination-repository=other-repo&format=pypi&package=" + packageName)
                .then().statusCode(200)
                .body("successfulVersions.'1.0.0'.status", equalTo("Published"))
                .body("failedVersions.size()", equalTo(0));

        byte[] copied = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/other-repo/packages/" + filename)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("copy-me-pypi-sdist-bytes", new String(copied, StandardCharsets.UTF_8));

        // The real-world gap this proves closed: pypiserver parses only a distribution's
        // filename, never its embedded description (see GetPackageVersionReadme's own javadoc),
        // so this bridge has no way to read it from the destination sidecar after copying the
        // file; it must be propagated from this service's own captured-at-upload-time record.
        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=other-repo&format=pypi&package="
                        + packageName + "&version=1.0.0")
                .then().statusCode(200).body("readme", equalTo("Copy me description"));
    }

    /**
     * The real-world bug this would otherwise hit: pypiserver rejects a plain republish of an
     * already-uploaded filename with a real {@code 409} (confirmed live, this container is
     * started without pypiserver's own {@code --overwrite} flag, same as production), even though
     * {@code reuploadingTheSameFileIsRejectedAndTheOriginalContentSurvives} already proves that
     * for the native upload path; this confirms the bridge's own delete-then-republish ({@code
     * remove_pkg}, then re-upload, both confirmed live) actually clears the way.
     */
    @Test
    @Order(16)
    void copyPackageVersionsOverwritesAnExistingPypiFileWithDifferentContentAtTheRealSidecar() {
        String packageName = "copyover-pypi-pkg";
        String filename = "copyover_pypi_pkg-1.0.0.tar.gz";
        byte[] oldContent = "old-overwrite-pypi-sdist".getBytes(StandardCharsets.UTF_8);
        byte[] newContent = "new-overwrite-pypi-sdist".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, filename, oldContent))
                .post("/codeartifact/pypi/" + DOMAIN + "/other-repo/")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, filename, newContent))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"1.0.0\"],\"allowOverwrite\":true}")
                .post("/v1/package/versions/copy?domain=" + DOMAIN + "&source-repository=" + REPO
                        + "&destination-repository=other-repo&format=pypi&package=" + packageName)
                .then().statusCode(200)
                .body("successfulVersions.'1.0.0'.status", equalTo("Published"))
                .body("failedVersions.size()", equalTo(0));

        byte[] copied = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/other-repo/packages/" + filename)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("new-overwrite-pypi-sdist", new String(copied, StandardCharsets.UTF_8));
    }

    /**
     * The real-world bug this would otherwise hit: pypiserver's own {@code remove_pkg} management
     * call (confirmed live) deletes every file sharing a release's exact name and version, not just
     * the one filename it was asked to delete, so a copy that deletes-then-rewrites one asset at a
     * time mid-loop would wipe a sibling asset of the same release already rewritten earlier in
     * that same loop — losing it even though the copy as a whole reported success. A real pypi
     * release commonly carries both an sdist and a wheel for the exact same version, so this is not
     * a contrived edge case.
     */
    @Test
    @Order(17)
    void copyPackageVersionsOverwriteKeepsEveryAssetWhenAPypiReleaseHasBothAnSdistAndAWheel() {
        String packageName = "copyover-multi-pypi-pkg";
        String sdistFilename = "copyover_multi_pypi_pkg-1.0.0.tar.gz";
        String wheelFilename = "copyover_multi_pypi_pkg-1.0.0-py3-none-any.whl";
        byte[] oldSdist = "old-multi-pypi-sdist".getBytes(StandardCharsets.UTF_8);
        byte[] newSdist = "new-multi-pypi-sdist".getBytes(StandardCharsets.UTF_8);
        byte[] newWheel = "new-multi-pypi-wheel".getBytes(StandardCharsets.UTF_8);

        // The destination already holds this version (just the sdist, enough to make this a real
        // overwrite rather than a fresh publish) so the copy below has to delete-and-replace it.
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, sdistFilename, oldSdist))
                .post("/codeartifact/pypi/" + DOMAIN + "/other-repo/")
                .then().statusCode(200);

        // The source carries both files for this same release, exactly as a real `twine upload
        // dist/*` would have left it.
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, sdistFilename, newSdist))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, wheelFilename, newWheel))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"1.0.0\"],\"allowOverwrite\":true}")
                .post("/v1/package/versions/copy?domain=" + DOMAIN + "&source-repository=" + REPO
                        + "&destination-repository=other-repo&format=pypi&package=" + packageName)
                .then().statusCode(200)
                .body("successfulVersions.'1.0.0'.status", equalTo("Published"))
                .body("failedVersions.size()", equalTo(0));

        byte[] copiedSdist = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/other-repo/packages/" + sdistFilename)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("new-multi-pypi-sdist", new String(copiedSdist, StandardCharsets.UTF_8));
        byte[] copiedWheel = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/other-repo/packages/" + wheelFilename)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("new-multi-pypi-wheel", new String(copiedWheel, StandardCharsets.UTF_8));
    }

    /**
     * The real-world gap this closes: {@code ListPackageVersionAssets} used to not exist at all.
     * Confirms the bridge enumerates a single-asset pypi version against the real pypiserver
     * sidecar (not a mock) and reports the exact byte size and hash of a freshly uploaded file. A
     * throwaway package, not {@link #PACKAGE_NAME}: {@link
     * #deletePackageRemovesTheFileFromTheRealPypiserverSidecar} deletes that fixture for good with
     * no republish afterward, unlike this format's sibling test classes. {@link
     * #listPackageVersionAssetsListsBothTheSdistAndTheWheelOfOneVersion} covers the multi-asset
     * case this one doesn't.
     */
    @Test
    @Order(18)
    void listPackageVersionAssetsBridgesToTheRealPypiserverSidecar() {
        String packageName = "list-assets-pypi-pkg";
        String filename = "list_assets_pypi_pkg-1.0.0.tar.gz";
        byte[] content = "list-assets-sdist-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, filename, content))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/package/version/assets?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=" + packageName + "&version=1.0.0")
                .then().statusCode(200)
                .body("package", equalTo(packageName))
                .body("assets.size()", equalTo(1))
                .body("assets[0].name", equalTo(filename))
                .body("assets[0].size", equalTo(content.length))
                .extract().jsonPath();
        Map<String, String> hashes = body.getMap("assets[0].hashes");
        assertEquals(Digests.sha256Hex(content), hashes.get("SHA-256"));
    }

    /**
     * Bridges to the same real pypiserver sidecar {@link #listPackageVersionAssetsBridgesToTheRealPypiserverSidecar}
     * already uploaded {@code list-assets-pypi-pkg}'s {@code 1.0.0} to, reading the per-project
     * JSON listing's {@code releases} keys plus a {@code HEAD} on the file's own URL for its
     * publish time, instead of one release's file entries.
     */
    @Test
    @Order(19)
    void listPackageVersionsBridgesToTheRealPypiserverSidecar() {
        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=list-assets-pypi-pkg")
                .then().statusCode(200)
                .body("package", equalTo("list-assets-pypi-pkg"))
                .body("defaultDisplayVersion", equalTo("1.0.0"))
                .body("versions.size()", equalTo(1))
                .body("versions[0].version", equalTo("1.0.0"))
                .body("versions[0].status", equalTo("Published"))
                .body("versions[0].origin.originType", equalTo("INTERNAL"));
    }

    /**
     * The bug this fixes: {@code CodeArtifactService#listPackageVersionAssets}'s own javadoc used
     * to claim {@code pypi} genuinely has exactly one asset per version, the same wrong assumption
     * {@link #listPackageVersionAssetsBridgesToTheRealPypiserverSidecar}'s javadoc made. A real
     * pypi release commonly carries both a source distribution and a wheel for the same version
     * (the exact shape {@link #copyPackageVersionsOverwriteKeepsEveryAssetWhenAPypiReleaseHasBothAnSdistAndAWheel}
     * already uploads for its own purpose), so this confirms the real pypiserver sidecar lists
     * both, each with its own correct size and hash, not just one of them.
     */
    @Test
    @Order(20)
    @SuppressWarnings("unchecked")
    void listPackageVersionAssetsListsBothTheSdistAndTheWheelOfOneVersion() {
        String packageName = "list-assets-multi-pypi-pkg";
        String sdistFilename = "list_assets_multi_pypi_pkg-1.0.0.tar.gz";
        String wheelFilename = "list_assets_multi_pypi_pkg-1.0.0-py3-none-any.whl";
        byte[] sdistContent = "list-assets-multi-sdist-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] wheelContent = "list-assets-multi-wheel-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, sdistFilename, sdistContent))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, wheelFilename, wheelContent))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/package/version/assets?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=" + packageName + "&version=1.0.0")
                .then().statusCode(200)
                .body("package", equalTo(packageName))
                .body("assets.size()", equalTo(2))
                .extract().jsonPath();
        List<Map<String, Object>> assets = (List<Map<String, Object>>) (List<?>) body.getList("assets");
        Map<String, Object> sdistAsset = findAssetByName(assets, sdistFilename);
        assertEquals(sdistContent.length, sdistAsset.get("size"));
        assertEquals(Digests.sha256Hex(sdistContent), ((Map<?, ?>) sdistAsset.get("hashes")).get("SHA-256"));
        Map<String, Object> wheelAsset = findAssetByName(assets, wheelFilename);
        assertEquals(wheelContent.length, wheelAsset.get("size"));
        assertEquals(Digests.sha256Hex(wheelContent), ((Map<?, ?>) wheelAsset.get("hashes")).get("SHA-256"));
    }

    private static Map<String, Object> findAssetByName(List<Map<String, Object>> assets, String name) {
        for (Map<String, Object> asset : assets) {
            if (name.equals(asset.get("name"))) {
                return asset;
            }
        }
        throw new AssertionError("expected an asset named " + name + " among " + assets);
    }

    /**
     * The real-world gap this guards against: {@code PypiserverSidecarClient#listPackageVersions}
     * used to send one {@code HEAD} per version, one at a time, so a package with many versions
     * answered no faster than its slowest version's own round trip, times the version count. This
     * uploads enough versions that a broken fan-out (dropped, duplicated, or silently empty results
     * from the bounded executor) would show up as something other than exactly this set of
     * versions, against the real pypiserver sidecar, not a mock.
     */
    @Test
    @Order(21)
    void listPackageVersionsFetchesManyVersionsPublishTimesCorrectly() {
        String packageName = "many-versions-pypi-pkg";
        int versionCount = 12;
        Set<String> expectedVersions = new HashSet<>();
        for (int i = 1; i <= versionCount; i++) {
            String version = "1.0." + i;
            expectedVersions.add(version);
            String filename = "many_versions_pypi_pkg-" + version + ".tar.gz";
            byte[] content = ("many-versions-sdist-bytes-" + i).getBytes(StandardCharsets.UTF_8);
            given().header("Authorization", "Bearer " + bearerToken)
                    .contentType("multipart/form-data; boundary=boundary123")
                    .body(twineUploadBody("boundary123", packageName, version, filename, content, null))
                    .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                    .then().statusCode(200);
        }

        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi"
                        + "&package=" + packageName)
                .then().statusCode(200)
                .body("package", equalTo(packageName))
                .body("versions.size()", equalTo(versionCount))
                .extract().jsonPath();
        List<String> actualVersions = body.getList("versions.version", String.class);
        assertEquals(expectedVersions, new HashSet<>(actualVersions));
        String defaultDisplayVersion = body.getString("defaultDisplayVersion");
        assertTrue(expectedVersions.contains(defaultDisplayVersion),
                "expected defaultDisplayVersion to be one of the uploaded versions, got "
                        + defaultDisplayVersion);
    }

    /**
     * Bridges to pypiserver's own root {@code /simple/} index, confirmed live: uploads a package
     * with a mixed-case, dotted name to confirm {@code PypiserverSidecarClient#listPackages}'s own
     * href parsing really gets back the PEP 503-normalized project name a live pypiserver root
     * index reports, not the originally-uploaded spelling, matching what {@code
     * CodeArtifactService}'s own {@code accountingPackageName} already normalizes pypi names to.
     */
    @Test
    @Order(22)
    void listPackagesBridgesToTheRealPypiserverRootSimpleIndex() {
        String packageName = "List.Packages_Probe";
        String filename = "list_packages_probe-1.0.0.tar.gz";
        byte[] content = "list-packages-probe-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(twineUploadBody("boundary123", packageName, filename, content))
                .post("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/")
                .then().statusCode(200);

        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/packages?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi")
                .then().statusCode(200)
                .extract().jsonPath();
        List<String> names = body.getList("packages.package", String.class);
        assertTrue(names.contains("list-packages-probe"), "expected list-packages-probe among " + names);
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

    /**
     * Last, since the class shares {@link SidecarContainersProfile}'s application with the other
     * sidecar classes: deletes the repositories and domains the tests above created, which also stops
     * their pypiserver containers.
     */
    @Test
    @Order(99)
    void cleanupDeletesTheRepositoriesAndDomainsItCreated() {
        deleteDomain(AUTH, DOMAIN, REPO, "other-repo", "concurrent-repo", "reused-name");
        deleteDomain(AUTH, "other-pypi-token-domain");
        deleteDomain(NON_DEFAULT_REGION_AUTH, "pypi-sidecar-non-default-region-domain", REPO);
        deleteDomain(OTHER_ACCOUNT_AUTH, "pypi-sidecar-cross-account-domain", REPO);
    }

    private static void deleteDomain(String auth, String domain, String... repositories) {
        for (String repository : repositories) {
            given().header("Authorization", auth)
                    .delete("/v1/repository?domain=" + domain + "&repository=" + repository);
        }
        given().header("Authorization", auth).delete("/v1/domain?domain=" + domain);
        List<String> domains = given().contentType("application/json").header("Authorization", auth).body("{}")
                .post("/v1/domains").then().statusCode(200).extract().jsonPath().getList("domains.name");
        assertFalse(domains.contains(domain), domain + " is still there");
    }

    private int runningPypiserverTestContainerCount() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "-q",
                "--filter", "name=" + ContainerStorageHelper.dockerName(config, "floci-pypiserver-"))
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
        return twineUploadBody(boundary, packageName, "1.0.0", filename, content, null);
    }

    /**
     * Same real twine shape as the 4-argument overload above, with an optional {@code description}
     * field (the real PyPI legacy upload API's field for a distribution's long description) a real
     * {@code twine upload} also sends whenever the project's metadata carries one.
     */
    private static byte[] twineUploadBody(String boundary, String packageName, String filename, byte[] content,
            String description) {
        return twineUploadBody(boundary, packageName, "1.0.0", filename, content, description);
    }

    /**
     * Full shape, with an explicit {@code version} field: every other overload above hardcodes
     * {@code 1.0.0}, which happens to match every other test's own fixed filename in this class,
     * but {@code CodeArtifactPypiController#upload} now checks the version field against the
     * real filename (Greptile's review of this PR), so a caller whose filename embeds a different
     * version (see {@link #listPackageVersionsFetchesManyVersionsPublishTimesCorrectly}'s own
     * loop) must pass the matching one here instead.
     */
    private static byte[] twineUploadBody(String boundary, String packageName, String version, String filename,
            byte[] content, String description) {
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
                + version + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"filetype\"\r\n\r\n"
                + "sdist\r\n"
                + (description == null ? "" : "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"description\"\r\n\r\n"
                        + description + "\r\n")
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
