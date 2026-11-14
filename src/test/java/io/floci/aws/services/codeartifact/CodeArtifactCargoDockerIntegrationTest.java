package io.floci.aws.services.codeartifact;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static io.floci.aws.testing.CodeArtifactRequests.putPackageOriginConfiguration;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs a real Cargo publish/install round trip against a live, per-repository Kellnr container,
 * started by {@link KellnrSidecarManager} exactly as in production. The published {@code .crate}
 * files are hand-built with the JDK's own {@code java.util.zip} plus the already-present {@code
 * commons-compress} (a real {@code Cargo.toml} inside a real gzipped tarball), not a captured
 * fixture: what this class needs to prove is the real container's behavior, not a client
 * library's fidelity to the wire format, the same reasoning {@link
 * CodeArtifactNuGetDockerIntegrationTest}'s own class-level documentation gives for NuGet.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactCargoDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "cargo-sidecar-domain";
    private static final String REPO = "cargo-sidecar-repo";
    private static final String CRATE_NAME = "democrate";
    private static final String VERSION = "1.0.0";

    private static String bearerToken;
    private static byte[] originalPublishBody;
    private static byte[] originalCrateBytes;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.cargo-image");
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

    /**
     * {@code config.json} must be rooted at the exact endpoint {@code GetRepositoryEndpoint}
     * itself returns, the same requirement {@link CodeArtifactNuGetDockerIntegrationTest}'s own
     * service-index test proves for NuGet: a real {@code cargo} client's own sparse-registry
     * resolver only trusts an absolute {@code dl}/{@code api} rooted at the address it already
     * reached this response through.
     */
    @Test
    @Order(1)
    void configJsonIsRootedAtTheRealRepositoryEndpoint() {
        String repositoryEndpoint = given().header("Authorization", AUTH)
                .get("/v1/repository/endpoint?domain=" + DOMAIN + "&repository=" + REPO + "&format=cargo")
                .then().statusCode(200)
                .extract().jsonPath().getString("repositoryEndpoint");
        String endpointNoTrailingSlash = repositoryEndpoint.substring(0, repositoryEndpoint.length() - 1);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/config.json")
                .then().statusCode(200)
                .body("dl", equalTo(endpointNoTrailingSlash + "/api/v1/crates"))
                .body("api", equalTo(endpointNoTrailingSlash))
                .body("auth-required", equalTo(true));
    }

    /**
     * The full round trip: publish with a real, non-empty dependency list, then confirm the
     * sparse index and the download both genuinely reflect what was published, dependency list
     * included, not a reconstructed or truncated version of it.
     */
    @Test
    @Order(2)
    void publishThenInstallRoundTripsTheExactBytesAndRealDependencies() throws IOException {
        originalCrateBytes = buildCrate(CRATE_NAME, VERSION);
        originalPublishBody = publishBody(CRATE_NAME, VERSION, originalCrateBytes);
        long assetSizeBytesBeforePublish = describeDomainAssetSizeBytes();

        given().header("Authorization", bearerToken)
                .contentType("application/octet-stream")
                .body(originalPublishBody)
                .put("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/new")
                .then().statusCode(200)
                .body("warnings", equalTo(null));

        assertEquals(assetSizeBytesBeforePublish + originalCrateBytes.length, describeDomainAssetSizeBytes());

        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/de/mo/" + CRATE_NAME)
                .then().statusCode(200)
                .body(containsString("\"name\":\"" + CRATE_NAME + "\""))
                .body(containsString("\"vers\":\"" + VERSION + "\""))
                .body(containsString("\"name\":\"real-dependency\""));

        byte[] fetchedCrate = given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/" + CRATE_NAME + "/" + VERSION
                        + "/download")
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(originalCrateBytes, fetchedCrate);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=cargo&package=" + CRATE_NAME)
                .then().statusCode(200).body("package.name", equalTo(CRATE_NAME))
                .body("package.originConfiguration.restrictions.publish", equalTo("ALLOW"));
    }

    @Test
    @Order(3)
    void getPackageVersionAssetBridgesToTheRealKellnrSidecar() throws IOException {
        String assetName = CRATE_NAME + "-" + VERSION + ".crate";
        byte[] fetched = given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=cargo"
                        + "&package=" + CRATE_NAME + "&version=" + VERSION + "&asset=" + assetName)
                .then().statusCode(200)
                .header("X-AssetName", equalTo(assetName))
                .extract().asByteArray();
        assertArrayEquals(originalCrateBytes, fetched);
    }

    @Test
    @Order(4)
    void describePackageVersionBridgesToTheRealKellnrSidecar() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=cargo&package="
                        + CRATE_NAME + "&version=" + VERSION)
                .then().statusCode(200)
                .body("packageVersion.version", equalTo(VERSION))
                .body("packageVersion.status", equalTo("Published"));
    }

    /**
     * A republish of an already-existing name/version is rejected, matching the Cargo Book's own
     * documented rule that "a publish is generally permanent" and a version "can never be
     * overwritten", confirmed live against Kellnr: it answers 400 with a body already shaped
     * exactly like the Cargo Book's own documented error response, which this test confirms is
     * forwarded to the client verbatim rather than swallowed into an opaque 500.
     */
    @Test
    @Order(5)
    void repeatingTheExactSamePublishIsRejectedAsAnAlreadyExistingVersion() {
        given().header("Authorization", bearerToken)
                .contentType("application/octet-stream")
                .body(originalPublishBody)
                .put("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/new")
                .then().statusCode(400)
                .body("errors[0].detail", containsString("already exists"));
    }

    @Test
    @Order(6)
    void repositoriesAreIsolatedFromEachOther() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=other-cargo-repo")
                .then().statusCode(200);

        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/other-cargo-repo/de/mo/" + CRATE_NAME)
                .then().statusCode(404);
    }

    @Test
    @Order(7)
    void missingOrWrongDomainTokensAreUnauthorized() {
        given().get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/config.json")
                .then().statusCode(401);

        given().header("Authorization", "not-a-real-token")
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/config.json")
                .then().statusCode(401);

        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=other-cargo-token-domain")
                .then().statusCode(200);
        String otherDomainToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=other-cargo-token-domain")
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");

        given().header("Authorization", otherDomainToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/config.json")
                .then().statusCode(401);
    }

    /**
     * Closes 10-NATIVE-STATUS for cargo: a plain {@code cargo add}/{@code cargo build} download of
     * an Archived version's real {@code .crate} must 404 against the live Kellnr sidecar, even
     * though the file itself is still sitting there untouched. Its own crate, not {@link
     * #CRATE_NAME}, so archiving it can't disturb any other ordered test in this class.
     */
    @Test
    @Order(8)
    void archivingAVersionBlocksItsRealKellnrContentDownloadWhileLeavingTheBytesInPlace() throws IOException {
        String crateName = "archivecrate";
        byte[] crateBytes = buildCrate(crateName, VERSION);
        given().header("Authorization", bearerToken)
                .contentType("application/octet-stream")
                .body(publishBody(crateName, VERSION, crateBytes))
                .put("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/new")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"" + VERSION + "\"],\"targetStatus\":\"Archived\"}")
                .post("/v1/package/versions/update_status?domain=" + DOMAIN + "&repository=" + REPO
                        + "&format=cargo&package=" + crateName)
                .then().statusCode(200)
                .body("successfulVersions['" + VERSION + "'].status", equalTo("Archived"));

        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/" + crateName + "/" + VERSION
                        + "/download")
                .then().statusCode(404);

        // Proves the block above is this check's own override, not Kellnr having genuinely lost
        // the file: un-archiving must make the exact same bytes fetchable again, with nothing
        // re-published in between.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"" + VERSION + "\"],\"targetStatus\":\"Published\"}")
                .post("/v1/package/versions/update_status?domain=" + DOMAIN + "&repository=" + REPO
                        + "&format=cargo&package=" + crateName)
                .then().statusCode(200)
                .body("successfulVersions['" + VERSION + "'].status", equalTo("Published"));
        byte[] fetchedAfterUnarchive = given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/" + crateName + "/" + VERSION
                        + "/download")
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(crateBytes, fetchedAfterUnarchive);
    }

    /**
     * {@code publish:BLOCK} set through the real {@code PutPackageOriginConfiguration} route must
     * refuse a real {@code cargo publish}, the same real-world gap this already closed for every
     * other native format. Its own crate, so no other ordered test is disturbed.
     */
    @Test
    @Order(9)
    void publishBlockedByOriginConfigurationRefusesARealPublishUntilItIsLifted() throws IOException {
        String crateName = "originblockedcrate";
        byte[] published = buildCrate(crateName, VERSION);
        given().header("Authorization", bearerToken)
                .contentType("application/octet-stream")
                .body(publishBody(crateName, VERSION, published))
                .put("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/new")
                .then().statusCode(200);

        putPackageOriginConfiguration(AUTH, DOMAIN, REPO, "cargo", null, crateName, "BLOCK", "BLOCK");

        byte[] nextVersionCrate = buildCrate(crateName, "2.0.0");
        given().header("Authorization", bearerToken)
                .contentType("application/octet-stream")
                .body(publishBody(crateName, "2.0.0", nextVersionCrate))
                .put("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/new")
                .then().statusCode(409);
        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/" + sparseIndexPath(crateName))
                .then().statusCode(200)
                .body(containsString("\"vers\":\"" + VERSION + "\""));

        putPackageOriginConfiguration(AUTH, DOMAIN, REPO, "cargo", null, crateName, "ALLOW", "BLOCK");

        given().header("Authorization", bearerToken)
                .contentType("application/octet-stream")
                .body(publishBody(crateName, "2.0.0", nextVersionCrate))
                .put("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/new")
                .then().statusCode(200);
    }

    /**
     * {@code ListPackages} against a repository that has actually published through cargo, against
     * the real Kellnr container this whole class already shares: {@code KellnrSidecarClient} had
     * no {@code listPackages} override at all until this was added, so adding {@code cargo} to
     * {@code CONTAINER_BACKED_FORMATS} alone made every unfiltered or cargo-filtered {@code
     * ListPackages} request on any repository 500 before this fix, confirmed only by calling the
     * real action here rather than the sidecar client directly.
     */
    @Test
    @Order(10)
    void listPackagesListsEveryRealCrateThisRepositoryHasPublished() {
        given().header("Authorization", AUTH)
                .post("/v1/packages?domain=" + DOMAIN + "&repository=" + REPO + "&format=cargo")
                .then().statusCode(200)
                .body("packages.package", hasItem(CRATE_NAME));
    }

    /**
     * Releases this class's own Kellnr container (via a real ordered {@code @Test}, not {@code
     * @AfterAll}: Quarkus's own test extension can already be tearing down the HTTP listener by
     * the time a {@code @AfterAll} static method runs if this happens to be the last class in the
     * fork, so the request itself would fail with a connection reset) rather than leaving it for a
     * later, unrelated {@code /state/reset} call to stop as part of {@code ContainerTeardowns
     * #stopAll}'s own sequential sweep over every registered {@code ContainerTeardown}, cargo's
     * included: that sweep blocks whichever single HTTP request happens to trigger it on every
     * one of them, so a container left running here only extends some later, unrelated test's own
     * reset call rather than this class's own.
     */
    @Test
    @Order(11)
    void releasesItsOwnKellnrContainerSoALaterUnrelatedResetDoesNotHaveToStopIt() {
        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then().statusCode(200);
    }

    private static long describeDomainAssetSizeBytes() {
        return given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
    }

    /** The real crates.io sparse-index bucketing rule for a name 9 characters long (4+ case). */
    private static String sparseIndexPath(String crateName) {
        return crateName.substring(0, 2) + "/" + crateName.substring(2, 4) + "/" + crateName;
    }

    /**
     * The real binary wire body confirmed directly from the Cargo Book's own spec text, and live
     * against a real Kellnr container in part 1: a 4-byte little-endian metadata length, the JSON
     * metadata itself (here with one real, non-empty dependency, to prove a native publish's own
     * dependency list survives the round trip untouched, unlike {@code CopyPackageVersions}'s own
     * always-empty-deps path), then a 4-byte little-endian crate length and the crate itself.
     */
    private static byte[] publishBody(String name, String version, byte[] crateBytes) {
        String metadataJson = "{\"name\":\"" + name + "\",\"vers\":\"" + version + "\",\"deps\":["
                + "{\"name\":\"real-dependency\",\"version_req\":\"^1.0\",\"features\":[],\"optional\":false,"
                + "\"default_features\":true,\"target\":null,\"kind\":\"normal\",\"registry\":null}],"
                + "\"features\":{},\"authors\":[],\"description\":\"a test crate\",\"documentation\":null,"
                + "\"homepage\":null,\"readme\":null,\"readme_file\":null,\"keywords\":[],\"categories\":[],"
                + "\"license\":\"MIT\",\"license_file\":null,\"repository\":null,\"badges\":{},\"links\":null}";
        byte[] metadata = metadataJson.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + metadata.length + 4 + crateBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(metadata.length);
        buffer.put(metadata);
        buffer.putInt(crateBytes.length);
        buffer.put(crateBytes);
        return buffer.array();
    }

    /** A real, minimal, valid {@code .crate}: a gzipped tarball with one real {@code Cargo.toml}. */
    private static byte[] buildCrate(String name, String version) throws IOException {
        String cargoToml = "[package]\nname = \"" + name + "\"\nversion = \"" + version + "\"\nedition = \"2021\"\n";
        byte[] cargoTomlBytes = cargoToml.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry(name + "-" + version + "/Cargo.toml");
            entry.setSize(cargoTomlBytes.length);
            tar.putArchiveEntry(entry);
            tar.write(cargoTomlBytes);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }
}
