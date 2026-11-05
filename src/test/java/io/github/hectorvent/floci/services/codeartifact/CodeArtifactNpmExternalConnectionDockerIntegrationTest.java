package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static io.github.hectorvent.floci.testing.CodeArtifactRequests.putPackageOriginConfiguration;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retains a real npm package version from the real, live {@code public:npmjs} registry (not a
 * local fake), through the native per-format route, the way a real {@code npm install} would.
 * {@code is-even} is picked because it is tiny, stable, and unlikely to ever need republishing.
 * A genuine network dependency, unlike every other sidecar-backed test here, which only ever
 * talks to a container Floci itself started; see {@code CodeArtifactNpmExternalConnectionImporterTest}
 * for the mocked, deterministic coverage of the merge/claim/fence logic this test does not
 * re-exercise.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactNpmExternalConnectionDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "npm-external-connection-domain";
    private static final String REPO = "npm-external-connection-repo";
    private static final String BLOCKED_REPO = "npm-external-connection-blocked-repo";
    private static final String LATEST_REPO = "npm-external-connection-latest-repo";
    private static final String PACKAGE_NAME = "is-even";
    private static final String VERSION = "1.0.0";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.npm-image");
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
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + LATEST_REPO)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + REPO
                        + "&external-connection=public:npmjs")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO
                        + "&external-connection=public:npmjs")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + LATEST_REPO
                        + "&external-connection=public:npmjs")
                .then().statusCode(200);

        bearerToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");
    }

    /**
     * A bare packument read merges the real registry's own versions in, rewriting {@code
     * dist.tarball} to this repository's own proxy URL, without ever fetching the real tarball
     * itself yet: {@code assetSizeBytes} must stay exactly where it started.
     */
    @Test
    @Order(1)
    void aBarePackumentReadMergesTheRealVersionWithoutRetainingItsTarballYet() throws Exception {
        long assetSizeBytesBefore = describeDomainAssetSizeBytes();

        String body = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200)
                .extract().asString();
        JsonNode packument = new ObjectMapper().readTree(body);
        JsonNode versionDoc = packument.path("versions").path(VERSION);
        assertEquals(VERSION, versionDoc.path("version").asText());
        String tarballUrl = versionDoc.path("dist").path("tarball").asText();
        assertThat(tarballUrl, startsWith("http"));
        assertThat(tarballUrl, containsString("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/"));

        assertEquals(assetSizeBytesBefore, describeDomainAssetSizeBytes());
    }

    @Test
    @Order(2)
    void fetchingTheMergedTarballUrlRetainsItFromTheRealNpmjsConnectionAndCountsItsBytes() {
        long assetSizeBytesBefore = describeDomainAssetSizeBytes();

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME + "/-/" + PACKAGE_NAME + "-"
                        + VERSION + ".tgz")
                .then().statusCode(200)
                .extract().asByteArray();

        // A real .tgz is a real gzip stream: 0x1f 0x8b magic bytes, not asserting exact content
        // since the real artifact is out of this test's control.
        assertEquals((byte) 0x1f, fetched[0]);
        assertEquals((byte) 0x8b, fetched[1]);
        assertThat(fetched.length, greaterThan(50));

        long assetSizeBytesAfter = describeDomainAssetSizeBytes();
        assertEquals(assetSizeBytesBefore + fetched.length, assetSizeBytesAfter);
    }

    @Test
    @Order(3)
    void refetchingTheSameTarballServesItWithoutRetainingItAgain() {
        long assetSizeBytesBefore = describeDomainAssetSizeBytes();

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME + "/-/" + PACKAGE_NAME + "-"
                        + VERSION + ".tgz")
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals((byte) 0x1f, fetched[0]);

        assertEquals(assetSizeBytesBefore, describeDomainAssetSizeBytes());
    }

    @Test
    @Order(4)
    void upstreamBlockPreventsBothTheMergeAndTheRetention() {
        putPackageOriginConfiguration(AUTH, DOMAIN, BLOCKED_REPO, "npm", null, PACKAGE_NAME, "ALLOW", "BLOCK");

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + BLOCKED_REPO + "/" + PACKAGE_NAME)
                .then().statusCode(404);
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + BLOCKED_REPO + "/" + PACKAGE_NAME + "/-/" + PACKAGE_NAME
                        + "-" + VERSION + ".tgz")
                .then().statusCode(404);
    }

    @Test
    @Order(5)
    void aPackageTheRealRegistryDoesNotHaveIsNeverMerged() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO
                        + "/floci-this-package-definitely-does-not-exist-on-npmjs")
                .then().statusCode(404);
    }

    /**
     * Confirms the fix against the real sidecar's own retained version from {@link
     * #fetchingTheMergedTarballUrlRetainsItFromTheRealNpmjsConnectionAndCountsItsBytes}, not a
     * mock, the same way {@code CodeArtifactNuGetExternalConnectionDockerIntegrationTest} proves
     * its own equivalent origin-reporting fix.
     */
    @Test
    @Order(6)
    void aRetainedVersionReportsAnExternalOriginNotInternal() {
        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + PACKAGE_NAME)
                .then().statusCode(200)
                .body("versions[0].version", equalTo(VERSION))
                .body("versions[0].origin.originType", equalTo("EXTERNAL"))
                .body("versions[0].origin.domainEntryPoint.externalConnectionName", equalTo("public:npmjs"));
    }

    /**
     * Found by Greptile's review: a retained version used to be written as a minimal document
     * with no {@code dependencies}/{@code engines}/{@code scripts}, and its readme was never
     * captured at all, so {@code GetPackageVersionReadme} 404ed on an otherwise fully retained
     * version. Confirms both fixes against the real sidecar's own stored version from {@link
     * #fetchingTheMergedTarballUrlRetainsItFromTheRealNpmjsConnectionAndCountsItsBytes}, reading
     * it back the same way a real {@code npm install} and {@code aws codeartifact
     * get-package-version-readme} would, not a mock.
     */
    @Test
    @Order(7)
    void aRetainedVersionKeepsItsRealDependenciesAndReadme() throws Exception {
        String body = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200)
                .extract().asString();
        JsonNode versionDoc = new ObjectMapper().readTree(body).path("versions").path(VERSION);
        assertThat(versionDoc.path("dependencies").has("is-odd"), equalTo(true));
        assertThat(versionDoc.path("engines").has("node"), equalTo(true));

        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + PACKAGE_NAME + "&version=" + VERSION)
                .then().statusCode(200)
                .body("readme", startsWith("# is-even"));
    }

    /**
     * Found by Greptile's review: {@code DisposePackageVersions} deletes a version's real
     * Verdaccio content outright, so a bare packument read could not tell that apart from a
     * version it had simply never retained, and would merge it straight back in from the real
     * registry's own still-unaware listing. A distinct, never-before-retained version
     * ({@code 0.1.2}) is retained here specifically to dispose of, so it never disturbs the
     * other tests in this class that share {@link #PACKAGE_NAME}'s {@link #VERSION}.
     */
    @Test
    @Order(8)
    void aDisposedVersionIsNeverMergedBackIn() throws Exception {
        String disposedVersion = "0.1.2";
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME + "/-/" + PACKAGE_NAME + "-"
                        + disposedVersion + ".tgz")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"" + disposedVersion + "\"]}")
                .post("/v1/package/versions/dispose?domain=" + DOMAIN + "&repository=" + REPO + "&format=npm&package="
                        + PACKAGE_NAME)
                .then().statusCode(200)
                .body("successfulVersions['" + disposedVersion + "'].status", equalTo("Disposed"));

        String body = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200)
                .extract().asString();
        JsonNode packument = new ObjectMapper().readTree(body);
        assertThat(packument.path("versions").has(disposedVersion), equalTo(false));
        assertThat(packument.path("versions").has(VERSION), equalTo(true));
    }

    /**
     * Found by hectorvent's review: the retention write used to send its own {@code dist-tags.
     * latest}, so retaining any real external version at all, even an old one, would clobber a
     * genuine local {@code latest} a direct publish had already set. {@code is-even}'s own real
     * {@code 0.1.2} (older than the real registry's own actual latest, {@link #VERSION}) is
     * retained here specifically, into a repository whose own local publish already claims {@code
     * latest} for a version number ({@code 9.9.9}) the real registry could never have, so there is
     * no ambiguity about which one is the genuine local choice. A separate repository from {@link
     * #REPO}: this one's own local publish must not interact with the other ordered tests there.
     */
    @Test
    @Order(9)
    void retainingAnOlderRealVersionNeverClobbersAGenuineLocalLatest() throws Exception {
        byte[] localContent = "a-genuine-local-publish".getBytes(StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode localVersionDoc = mapper.createObjectNode();
        localVersionDoc.put("name", PACKAGE_NAME);
        localVersionDoc.put("version", "9.9.9");
        ObjectNode localDist = localVersionDoc.putObject("dist");
        localDist.put("shasum", Digests.sha1Hex(localContent));
        localDist.put("integrity", "sha512-" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-512").digest(localContent)));
        ObjectNode publishBody = mapper.createObjectNode();
        publishBody.put("_id", PACKAGE_NAME);
        publishBody.put("name", PACKAGE_NAME);
        publishBody.put("description", "");
        publishBody.putObject("dist-tags").put("latest", "9.9.9");
        publishBody.putObject("versions").set("9.9.9", localVersionDoc);
        ObjectNode attachment = publishBody.putObject("_attachments").putObject(PACKAGE_NAME + "-9.9.9.tgz");
        attachment.put("content_type", "application/octet-stream");
        attachment.put("data", Base64.getEncoder().encodeToString(localContent));
        attachment.put("length", localContent.length);

        given().header("Authorization", "Bearer " + bearerToken).contentType("application/json")
                .body(mapper.writeValueAsString(publishBody))
                .put("/codeartifact/npm/" + DOMAIN + "/" + LATEST_REPO + "/" + PACKAGE_NAME)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));

        // AWS's own documented default: a package that already has a real, directly-published
        // version defaults to upstream: BLOCK unless a caller explicitly sets ALLOW, exactly the
        // scenario hectorvent's own finding names ("with upstream: ALLOW on a locally published
        // package, retention can lose a real local latest"). Without this, the retention attempt
        // below would simply be refused rather than exercising the dist-tags bug at all.
        putPackageOriginConfiguration(AUTH, DOMAIN, LATEST_REPO, "npm", null, PACKAGE_NAME, "ALLOW", "ALLOW");

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + LATEST_REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200)
                .body("dist-tags.latest", equalTo("9.9.9"));

        String olderRealVersion = "0.1.2";
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + LATEST_REPO + "/" + PACKAGE_NAME + "/-/" + PACKAGE_NAME
                        + "-" + olderRealVersion + ".tgz")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/npm/" + DOMAIN + "/" + LATEST_REPO + "/" + PACKAGE_NAME)
                .then().statusCode(200)
                .body("dist-tags.latest", equalTo("9.9.9"))
                .body("versions['" + olderRealVersion + "'].version", equalTo(olderRealVersion));
    }

    private static long describeDomainAssetSizeBytes() {
        return given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
    }
}
