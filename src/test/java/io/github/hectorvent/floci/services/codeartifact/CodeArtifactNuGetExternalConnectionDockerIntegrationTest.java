package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retains a real NuGet package version from the real, live {@code public:nuget-org} repository
 * (not a local fake), through the native per-format route, the way a real {@code dotnet restore}/
 * {@code nuget install} would. {@code Newtonsoft.Json} 13.0.3 is picked because it is small,
 * stable, and unlikely to ever be pulled from nuget.org. A genuine network dependency, unlike
 * every other sidecar-backed test here, which only ever talks to a container Floci itself
 * started; see {@code CodeArtifactNuGetExternalConnectionImporterTest} for the mocked,
 * deterministic coverage of the claim/fence/mixed-origin logic this test does not re-exercise.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactNuGetExternalConnectionDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "nuget-external-connection-domain";
    private static final String REPO = "nuget-external-connection-repo";
    private static final String BLOCKED_REPO = "nuget-external-connection-blocked-repo";
    private static final String PACKAGE_ID = "Newtonsoft.Json";
    private static final String LOWER_ID = "newtonsoft.json";
    private static final String VERSION = "13.0.3";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.nuget-image");
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
                        + "&external-connection=public:nuget-org")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO
                        + "&external-connection=public:nuget-org")
                .then().statusCode(200);

        bearerToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");
    }

    @Test
    @Order(1)
    void fetchingAMissingNupkgRetainsItFromTheRealNuGetOrgConnectionAndCountsItsBytes() {
        long assetSizeBytesBefore = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
        assertEquals(0L, assetSizeBytesBefore);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID + "/" + VERSION + "/"
                        + LOWER_ID + "." + VERSION + ".nupkg")
                .then().statusCode(200)
                .extract().asByteArray();

        // A real .nupkg is a real zip: "PK" magic bytes, not asserting exact content since the
        // real artifact is out of this test's control.
        assertEquals('P', (char) fetched[0]);
        assertEquals('K', (char) fetched[1]);
        assertThat(fetched.length, greaterThan(1000));

        // Proves the retained bytes are actually counted toward DescribeDomain's assetSizeBytes,
        // the same way a direct nuget push already is.
        long assetSizeBytesAfter = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
        assertEquals(assetSizeBytesBefore + fetched.length, assetSizeBytesAfter);
    }

    @Test
    @Order(2)
    void refetchingTheSameVersionServesItWithoutRetainingItAgain() {
        long assetSizeBytesBefore = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID + "/" + VERSION + "/"
                        + LOWER_ID + "." + VERSION + ".nupkg")
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals('P', (char) fetched[0]);
        assertEquals('K', (char) fetched[1]);

        long assetSizeBytesAfter = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
        assertEquals(assetSizeBytesBefore, assetSizeBytesAfter);
    }

    /**
     * Found by Greptile's review: {@code PackageOriginConfig} used to be keyed by whatever raw
     * spelling a caller used, so a restriction set through one casing silently never matched a
     * request spelled another way. Deliberately mixed-case here ({@link #PACKAGE_ID}) against the
     * native route's own lowercase spelling, the exact mismatch the fix closes: an operator
     * blocking upstream retention through the management API is not required to already know (or
     * guess at) the normalized spelling nuget's own URL convention happens to use.
     */
    @Test
    @Order(3)
    void upstreamBlockPreventsRetentionEvenThoughTheRealPackageExists() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"restrictions\":{\"publish\":\"ALLOW\",\"upstream\":\"BLOCK\"}}")
                .post("/v1/package?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO + "&format=nuget"
                        + "&package=" + PACKAGE_ID)
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + BLOCKED_REPO + "/v3/package/" + LOWER_ID + "/" + VERSION
                        + "/" + LOWER_ID + "." + VERSION + ".nupkg")
                .then().statusCode(404);

        // The block itself is visible under either spelling too, not just enforced: proves the
        // fix is a real normalization, not a one-off match on this test's own two spellings.
        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO + "&format=nuget&package="
                        + LOWER_ID)
                .then().statusCode(200)
                .body("package.originConfiguration.restrictions.upstream", equalTo("BLOCK"));
    }

    @Test
    @Order(4)
    void aVersionTheRealRepositoryDoesNotHaveIsNeverRetained() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID
                        + "/999.999.999/" + LOWER_ID + ".999.999.999.nupkg")
                .then().statusCode(404);
    }

    /**
     * Found by Greptile's review: a successfully retained version used to still report {@code
     * originType: INTERNAL}, since {@code versionOrigin} special-cased Maven only. Confirms the
     * fix against the real sidecar's own retained version from {@link
     * #fetchingAMissingNupkgRetainsItFromTheRealNuGetOrgConnectionAndCountsItsBytes}, not a mock.
     */
    @Test
    @Order(5)
    void aRetainedVersionReportsAnExternalOriginNotInternal() {
        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget&package="
                        + LOWER_ID)
                .then().statusCode(200)
                .body("versions[0].version", equalTo(VERSION))
                .body("versions[0].origin.originType", equalTo("EXTERNAL"))
                .body("versions[0].origin.domainEntryPoint.externalConnectionName",
                        equalTo("public:nuget-org"));
    }

    /**
     * Found by Greptile's review: the outbound request to the real public repository only
     * lowercased the version instead of fully normalizing it, so a request spelled equivalently
     * but not canonically (AWS's own worked example, {@code 1.00} for {@code 1.0.0}) built a path
     * the real {@code api.nuget.org/v3-flatcontainer} storage has nothing under and 404s on,
     * confirmed directly against it, even though the exact same real package version exists under
     * its canonical spelling. A distinct, never-before-retained version ({@code 9.0.1}) proves the
     * fetch itself succeeds under the equivalent spelling, not just that local matching tolerates
     * it once something is already retained.
     */
    @Test
    @Order(6)
    void fetchingAnEquivalentlySpelledVersionStillRetainsItFromTheRealNuGetOrgConnection() {
        String equivalentVersion = "9.00.1";
        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID + "/"
                        + equivalentVersion + "/" + LOWER_ID + "." + equivalentVersion + ".nupkg")
                .then().statusCode(200)
                .extract().asByteArray();

        assertEquals('P', (char) fetched[0]);
        assertEquals('K', (char) fetched[1]);
        assertThat(fetched.length, greaterThan(1000));
    }
}
