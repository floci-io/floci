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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retains a real pypi package version from the real, live {@code public:pypi} repository (not a
 * local fake), through the native simple-repository route, the way a real {@code pip install}
 * would. {@code is-even} 0.0.0 is picked because it is tiny (its one real sdist is about 1.3 KB)
 * and unlikely to ever be pulled from pypi.org or change. A genuine network dependency, unlike
 * every other sidecar-backed test here, which only ever talks to a container Floci itself
 * started; see {@code CodeArtifactPypiExternalConnectionImporterTest} for the mocked,
 * deterministic coverage of the claim/fence/mixed-origin logic this test does not re-exercise.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactPypiExternalConnectionDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "pypi-external-connection-domain";
    private static final String REPO = "pypi-external-connection-repo";
    private static final String BLOCKED_REPO = "pypi-external-connection-blocked-repo";
    private static final String PACKAGE_NAME = "is-even";
    private static final String VERSION = "0.0.0";
    private static final String FILENAME = "is-even-0.0.0.tar.gz";
    // The real pypi.org registry also has a wheel for this exact version: proves AWS's own
    // documented "CodeArtifact will import all the assets in that package version" by fetching
    // only the sdist above and then finding the wheel already retained alongside it, never
    // fetched directly.
    private static final String SIBLING_WHEEL_FILENAME = "is_even-0.0.0-py3-none-any.whl";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.pypi-image");
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
                        + "&external-connection=public:pypi")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO
                        + "&external-connection=public:pypi")
                .then().statusCode(200);

        bearerToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");
    }

    @Test
    @Order(1)
    void theSimpleIndexListsTheRealFileBeforeItIsEverDownloaded() {
        String indexHtml = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/simple/" + PACKAGE_NAME + "/")
                .then().statusCode(200)
                .extract().asString();

        assertThat(indexHtml, containsString(FILENAME));
    }

    @Test
    @Order(2)
    void fetchingAMissingFileRetainsItFromTheRealPypiOrgConnectionAndCountsItsBytes() {
        long assetSizeBytesBefore = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
        assertEquals(0L, assetSizeBytesBefore);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/packages/" + FILENAME)
                .then().statusCode(200)
                .extract().asByteArray();

        // A real sdist is a real gzip: 0x1f 0x8b magic bytes, not asserting exact content since
        // the real artifact is out of this test's control.
        assertEquals(0x1f, fetched[0] & 0xFF);
        assertEquals(0x8b, fetched[1] & 0xFF);

        // AWS: "CodeArtifact will import all the assets in that package version" -- the real
        // sibling wheel for this exact version must already be retained too, never fetched
        // directly by this test.
        byte[] siblingWheel = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/packages/" + SIBLING_WHEEL_FILENAME)
                .then().statusCode(200)
                .extract().asByteArray();
        // A real wheel is a real zip: "PK" magic bytes.
        assertEquals('P', (char) siblingWheel[0]);
        assertEquals('K', (char) siblingWheel[1]);

        // Proves both files' bytes are actually counted toward DescribeDomain's assetSizeBytes,
        // the same way a direct twine upload already is.
        long assetSizeBytesAfter = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
        assertEquals(assetSizeBytesBefore + fetched.length + siblingWheel.length, assetSizeBytesAfter);
    }

    @Test
    @Order(3)
    void refetchingTheSameFileServesItWithoutRetainingItAgain() {
        long assetSizeBytesBefore = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/packages/" + FILENAME)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals(0x1f, fetched[0] & 0xFF);
        assertEquals(0x8b, fetched[1] & 0xFF);

        long assetSizeBytesAfter = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
        assertEquals(assetSizeBytesBefore, assetSizeBytesAfter);
    }

    @Test
    @Order(4)
    void aRetainedVersionReportsAnExternalOriginNotInternal() {
        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=pypi&package="
                        + PACKAGE_NAME)
                .then().statusCode(200)
                .body("versions[0].version", equalTo(VERSION))
                .body("versions[0].origin.originType", equalTo("EXTERNAL"))
                .body("versions[0].origin.domainEntryPoint.externalConnectionName", equalTo("public:pypi"));
    }

    @Test
    @Order(5)
    void upstreamBlockPreventsRetentionEvenThoughTheRealPackageExists() {
        putPackageOriginConfiguration(AUTH, DOMAIN, BLOCKED_REPO, "pypi", null, PACKAGE_NAME, "ALLOW", "BLOCK");

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + BLOCKED_REPO + "/packages/" + FILENAME)
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO + "&format=pypi&package="
                        + PACKAGE_NAME)
                .then().statusCode(200)
                .body("package.originConfiguration.restrictions.upstream", equalTo("BLOCK"));
    }

    @Test
    @Order(6)
    void aFileTheRealRepositoryDoesNotHaveIsNeverRetained() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/pypi/" + DOMAIN + "/" + REPO + "/packages/does-not-exist-9.9.9.tar.gz")
                .then().statusCode(404);
    }
}
