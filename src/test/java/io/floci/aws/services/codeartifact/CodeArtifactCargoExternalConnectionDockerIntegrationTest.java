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

import static io.floci.aws.testing.CodeArtifactRequests.putPackageOriginConfiguration;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retains a real crate version from the real, live {@code public:crates-io} repository (not a
 * local fake), through the native per-format route, the way a real {@code cargo build}/{@code
 * cargo add} would. {@code libc} 0.2.169 is picked because it is small, stable, and confirmed
 * live against the real {@code index.crates.io}/{@code static.crates.io} production endpoints
 * (the index's own {@code cksum} for this exact version was confirmed to match the real
 * downloaded bytes' own SHA-256 before writing this test, not assumed). A genuine network
 * dependency, unlike every other sidecar-backed test here, which only ever talks to a container
 * Floci itself started; see {@code CodeArtifactCargoExternalConnectionImporterTest} for the
 * mocked, deterministic coverage of the claim/fence/mixed-origin/checksum logic this test does
 * not re-exercise, mirroring {@code CodeArtifactNuGetExternalConnectionDockerIntegrationTest}'s
 * own shape and reasoning.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactCargoExternalConnectionDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "cargo-external-connection-domain";
    private static final String REPO = "cargo-external-connection-repo";
    private static final String BLOCKED_REPO = "cargo-external-connection-blocked-repo";
    private static final String CRATE_NAME = "libc";
    private static final String VERSION = "0.2.169";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.cargo-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void createDomainRepositoriesConnectionAndToken() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + REPO
                        + "&external-connection=public:crates-io")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO
                        + "&external-connection=public:crates-io")
                .then().statusCode(200);

        bearerToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");
    }

    /**
     * Proves {@link CodeArtifactCargoExternalConnectionImporter#mergeExternalVersions}'s own
     * reason for existing: the real upstream version line is visible here even though nothing
     * has ever been downloaded (so nothing is retained yet), which a real {@code cargo} client's
     * dependency resolver needs in order to ever choose this version at all. Run before the
     * download test below, so this genuinely proves the merge, not a locally-cached line.
     */
    @Test
    @Order(1)
    void fetchingTheSparseIndexMergesInTheRealUpstreamVersionBeforeAnythingIsRetained() {
        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/" + sparseIndexPath(CRATE_NAME))
                .then().statusCode(200)
                .body(containsString("\"name\":\"" + CRATE_NAME + "\""))
                .body(containsString("\"vers\":\"" + VERSION + "\""));
    }

    @Test
    @Order(2)
    void fetchingAMissingCrateRetainsItFromTheRealCratesIoConnectionAndCountsItsBytes() {
        long assetSizeBytesBefore = describeDomainAssetSizeBytes();
        assertEquals(0L, assetSizeBytesBefore);

        byte[] fetched = given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/" + CRATE_NAME + "/" + VERSION
                        + "/download")
                .then().statusCode(200)
                .extract().asByteArray();

        // A real .crate is a real gzip file: 0x1f 0x8b magic bytes, not asserting exact content
        // since the real artifact is out of this test's control.
        assertEquals((byte) 0x1f, fetched[0]);
        assertEquals((byte) 0x8b, fetched[1]);
        assertThat(fetched.length, greaterThan(1000));

        assertEquals(assetSizeBytesBefore + fetched.length, describeDomainAssetSizeBytes());
    }

    @Test
    @Order(3)
    void refetchingTheSameVersionServesItWithoutRetainingItAgain() {
        long assetSizeBytesBefore = describeDomainAssetSizeBytes();

        byte[] fetched = given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/" + CRATE_NAME + "/" + VERSION
                        + "/download")
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals((byte) 0x1f, fetched[0]);
        assertEquals((byte) 0x8b, fetched[1]);

        assertEquals(assetSizeBytesBefore, describeDomainAssetSizeBytes());
    }

    /**
     * A {@code BLOCK}ed package shows no upstream-only version either, matching every other
     * retention path's own "behave exactly as if the external content did not exist" rule, not
     * just refusing the download: a real {@code cargo} client's own resolver must not even see a
     * version this repository is configured never to retain.
     */
    @Test
    @Order(4)
    void upstreamBlockPreventsBothRetentionAndTheIndexMerge() {
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
    void aVersionTheRealRepositoryDoesNotHaveIsNeverRetained() {
        given().header("Authorization", bearerToken)
                .get("/codeartifact/cargo/" + DOMAIN + "/" + REPO + "/api/v1/crates/" + CRATE_NAME
                        + "/999.999.999/download")
                .then().statusCode(404);
    }

    /**
     * Confirms the fix every other format's own importer already needed (a successfully retained
     * version reporting an external, not internal, origin) holds for cargo too, against the real
     * sidecar's own retained version from {@link
     * #fetchingAMissingCrateRetainsItFromTheRealCratesIoConnectionAndCountsItsBytes}, not a mock.
     */
    @Test
    @Order(6)
    void aRetainedVersionReportsAnExternalOriginNotInternal() {
        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=cargo&package="
                        + CRATE_NAME)
                .then().statusCode(200)
                .body("versions[0].version", equalTo(VERSION))
                .body("versions[0].origin.originType", equalTo("EXTERNAL"))
                .body("versions[0].origin.domainEntryPoint.externalConnectionName", equalTo("public:crates-io"));
    }

    /**
     * A mixed-case real crate name, confirmed live against production before writing this test
     * (its real {@code cksum} matches its real downloaded bytes' own SHA-256): the Cargo Book
     * requires the sparse-index bucket path and filename to be lowercased, confirmed live too
     * (the real {@code index.crates.io} 404s at {@code In/fl/Inflector} and only resolves at
     * {@code in/fl/inflector}), while the download route needs the crate's own original case.
     * Its own repository, separate from {@link #REPO}, so this doesn't interleave with the
     * ordered sequence above.
     */
    @Test
    @Order(7)
    void aMixedCaseCrateNameResolvesAndRetainsCorrectly() {
        String domain = "cargo-external-connection-mixed-case-domain";
        String repo = "cargo-external-connection-mixed-case-repo";
        String crateName = "Inflector";
        String version = "0.11.4";
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=" + domain)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + domain + "&repository=" + repo)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + domain + "&repository=" + repo
                        + "&external-connection=public:crates-io")
                .then().statusCode(200);
        String token = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + domain)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");

        given().header("Authorization", token)
                .get("/codeartifact/cargo/" + domain + "/" + repo + "/" + sparseIndexPath(crateName))
                .then().statusCode(200)
                .body(containsString("\"name\":\"" + crateName + "\""))
                .body(containsString("\"vers\":\"" + version + "\""));

        byte[] fetched = given().header("Authorization", token)
                .get("/codeartifact/cargo/" + domain + "/" + repo + "/api/v1/crates/" + crateName + "/" + version
                        + "/download")
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals((byte) 0x1f, fetched[0]);
        assertEquals((byte) 0x8b, fetched[1]);
        assertThat(fetched.length, greaterThan(100));
    }

    /**
     * Releases every Kellnr container this class started (see {@code
     * CodeArtifactCargoDockerIntegrationTest#releasesItsOwnKellnrContainerSoALaterUnrelatedResetDoesNotHaveToStopIt}'s
     * own javadoc for why this is a real ordered {@code @Test} and not {@code @AfterAll}),
     * including the mixed-case test's own separate repository.
     */
    @Test
    @Order(8)
    void releasesEveryKellnrContainerThisClassStarted() {
        given().header("Authorization", AUTH).delete("/v1/repository?domain=" + DOMAIN + "&repository=" + REPO)
                .then().statusCode(200);
        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO)
                .then().statusCode(200);
        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=cargo-external-connection-mixed-case-domain&repository="
                        + "cargo-external-connection-mixed-case-repo")
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
