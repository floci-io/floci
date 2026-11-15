package io.floci.aws.services.codeartifact;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.floci.aws.testing.CodeArtifactRequests.cargoCrateBytes;
import static io.floci.aws.testing.CodeArtifactRequests.cargoPublishBody;
import static io.floci.aws.testing.CodeArtifactRequests.putPackageOriginConfiguration;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code 17d-CARGO-UPSTREAM-REPO}: retains a real crate version from another, real-Kellnr-backed
 * repository in the same domain through the native per-format route, the way a real {@code cargo
 * build}/{@code cargo add} would, against two genuinely separate Kellnr containers (the source
 * repository's own, and the destination's), not a mock standing in for either. See {@link
 * CodeArtifactCargoUpstreamRepositoryImporterTest} for the mocked, deterministic coverage of the
 * claim/fence/walk/archived-propagation logic this test does not re-exercise, mirroring {@link
 * CodeArtifactCargoExternalConnectionDockerIntegrationTest}'s own shape and reasoning for why a
 * live test earns its place alongside a thorough mocked one: the one thing mocks cannot prove is
 * that the real synthesized wire body this importer builds from one Kellnr's own real sparse-index
 * line is actually accepted by a second, genuinely different Kellnr container.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactCargoUpstreamRepositoryDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "cargo-upstream-repo-domain";
    private static final String SOURCE_REPO = "cargo-upstream-repo-source";
    private static final String DEST_REPO = "cargo-upstream-repo-dest";
    private static final String BLOCKED_REPO = "cargo-upstream-repo-blocked-dest";
    private static final String CRATE_NAME = "democrate";
    private static final String VERSION = "1.0.0";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.cargo-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void createDomainRepositoriesUpstreamsAndTokens() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + SOURCE_REPO)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + DEST_REPO)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"upstreams\":[{\"repositoryName\":\"" + SOURCE_REPO + "\"}]}")
                .put("/v1/repository?domain=" + DOMAIN + "&repository=" + DEST_REPO)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"upstreams\":[{\"repositoryName\":\"" + SOURCE_REPO + "\"}]}")
                .put("/v1/repository?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO)
                .then().statusCode(200);

        bearerToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");
        

        byte[] crate = cargoCrateBytes(CRATE_NAME, VERSION);
        given().header("Authorization", bearerToken)
                .contentType("application/octet-stream")
                .body(cargoPublishBody(CRATE_NAME, VERSION, crate))
                .put("/codeartifact/cargo/" + DOMAIN + "/" + SOURCE_REPO + "/api/v1/crates/new")
                .then().statusCode(200);
    }

    /**
     * Proves {@link CodeArtifactCargoUpstreamRepositoryImporter#mergeUpstreamRepositoryVersions}'s
     * own reason for existing, the same reason the external-connection importer's own merge
     * exists: the source's own version line is visible here even though nothing has ever been
     * downloaded from it yet (so nothing is retained), which a real {@code cargo} client's
     * dependency resolver needs in order to ever choose this version at all. Run before the
     * download test below, so this genuinely proves the merge, not a locally-cached line.
     */
    @Test
    @Order(1)
    void fetchingTheSparseIndexMergesInTheSourceRepositorysVersionBeforeAnythingIsRetained() {
        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + DEST_REPO + "/" + sparseIndexPath(CRATE_NAME))
                .then().statusCode(200)
                .body(containsString("\"name\":\"" + CRATE_NAME + "\""))
                .body(containsString("\"vers\":\"" + VERSION + "\""));
    }

    @Test
    @Order(2)
    void fetchingAMissingCrateRetainsItFromTheSourceRepositoryAndCountsItsBytes() {
        long assetSizeBytesBefore = describeDomainAssetSizeBytes();

        byte[] fetched = given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + DEST_REPO + "/api/v1/crates/" + CRATE_NAME + "/"
                        + VERSION + "/download")
                .then().statusCode(200)
                .extract().asByteArray();

        // A real .crate is a real gzip file: 0x1f 0x8b magic bytes, not asserting exact content
        // since the wire-body-synthesizing path rebuilds the metadata, only the crate bytes
        // themselves survive untouched.
        assertEquals((byte) 0x1f, fetched[0]);
        assertEquals((byte) 0x8b, fetched[1]);
        assertThat(fetched.length, greaterThan(50));

        assertEquals(assetSizeBytesBefore + fetched.length, describeDomainAssetSizeBytes());
    }

    @Test
    @Order(3)
    void refetchingTheSameVersionServesItWithoutRetainingItAgain() {
        long assetSizeBytesBefore = describeDomainAssetSizeBytes();

        byte[] fetched = given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + DEST_REPO + "/api/v1/crates/" + CRATE_NAME + "/"
                        + VERSION + "/download")
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals((byte) 0x1f, fetched[0]);
        assertEquals((byte) 0x8b, fetched[1]);

        assertEquals(assetSizeBytesBefore, describeDomainAssetSizeBytes());
    }

    /**
     * A {@code BLOCK}ed package shows no upstream-only version either, matching every other
     * retention path's own "behave exactly as if the upstream repository did not have it" rule,
     * not just refusing the download: a real {@code cargo} client's own resolver must not even
     * see a version this repository is configured never to retain from its own upstream chain.
     */
    @Test
    @Order(4)
    void internalUpstreamBlockPreventsBothRetentionAndTheIndexMerge() {
        putPackageOriginConfiguration(AUTH, DOMAIN, BLOCKED_REPO, "cargo", null, CRATE_NAME, "ALLOW", "BLOCK");

        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + BLOCKED_REPO + "/api/v1/crates/" + CRATE_NAME + "/"
                        + VERSION + "/download")
                .then().statusCode(404);

        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + BLOCKED_REPO + "/" + sparseIndexPath(CRATE_NAME))
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO + "&format=cargo&package="
                        + CRATE_NAME)
                .then().statusCode(200)
                .body("package.originConfiguration.restrictions.upstream", equalTo("BLOCK"));
    }

    @Test
    @Order(5)
    void aVersionTheSourceRepositoryDoesNotHaveIsNeverRetained() {
        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + DEST_REPO + "/api/v1/crates/" + CRATE_NAME
                        + "/999.999.999/download")
                .then().statusCode(404);
    }

    /**
     * Confirms the fix every other format's own importer already needed (a successfully retained
     * version reporting its real origin) holds for cargo too, against the real sidecar's own
     * retained version from {@link #fetchingAMissingCrateRetainsItFromTheSourceRepositoryAndCountsItsBytes},
     * not a mock: {@code INTERNAL}, not {@code EXTERNAL} (a same-domain upstream, confirmed from
     * {@code CodeArtifactService}'s own real {@code PackageVersionOrigin.originType} mapping), with
     * {@code domainEntryPoint.repositoryName} naming the real source repository.
     */
    @Test
    @Order(6)
    void aRetainedVersionReportsAnInternalOriginNamingTheSourceRepository() {
        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + DEST_REPO + "&format=cargo&package="
                        + CRATE_NAME)
                .then().statusCode(200)
                .body("versions[0].version", equalTo(VERSION))
                .body("versions[0].origin.originType", equalTo("INTERNAL"))
                .body("versions[0].origin.domainEntryPoint.repositoryName", equalTo(SOURCE_REPO));
    }

    /**
     * Releases every Kellnr container this class started: see {@code
     * CodeArtifactCargoDockerIntegrationTest}'s own javadoc note on why this is a real ordered
     * {@code @Test} and not {@code @AfterAll}.
     */
    @Test
    @Order(7)
    void releasesEveryKellnrContainerThisClassStarted() {
        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + SOURCE_REPO)
                .then().statusCode(200);
        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + DEST_REPO)
                .then().statusCode(200);
        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO)
                .then().statusCode(200);
    }

    private static long describeDomainAssetSizeBytes() {
        return given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
    }

    /** The real crates.io sparse-index bucketing rule for {@link #CRATE_NAME} (a 4+-character
     * name): first two, then next two, characters. */
    private static String sparseIndexPath(String crateName) {
        return crateName.substring(0, 2) + "/" + crateName.substring(2, 4) + "/" + crateName;
    }
}
