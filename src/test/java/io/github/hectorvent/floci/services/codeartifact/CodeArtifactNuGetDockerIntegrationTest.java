package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real NuGet V3 push/install round trip against a live, per-repository BaGetter container,
 * started by {@link BagetterSidecarManager} exactly as in production. The pushed {@code .nupkg}
 * files are hand-built with the JDK's own {@code java.util.zip} (a real nuspec manifest at the
 * archive root plus, for one test, an embedded readme file), not a captured fixture: what this
 * class needs to prove is the real container's behavior, not a client library's fidelity to the
 * wire format.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactNuGetDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "nuget-sidecar-domain";
    private static final String REPO = "nuget-sidecar-repo";
    private static final String PACKAGE_ID = "Demo.Package";
    private static final String LOWER_ID = "demo.package";
    private static final String VERSION = "1.0.0";

    private static String bearerToken;
    // A fresh ZipEntry carries the current wall-clock time in its DOS-format timestamp field
    // whenever one isn't set explicitly, so two separate buildNupkg(...) calls with otherwise
    // identical inputs are never byte-identical; every "same bytes" assertion below reuses this
    // one build from the original push rather than reconstructing it.
    private static byte[] originalNupkg;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.nuget-image");
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
    void pushThenInstallRoundTripsTheExactBytesThroughTheFlatContainer() {
        originalNupkg = buildNupkg(PACKAGE_ID, VERSION, "A description.", null, null);
        long assetSizeBytesBeforePush = describeDomainAssetSizeBytes();

        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", originalNupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(201);

        // assetSizeBytes used to have no bridge for nuget at all; this is the real round trip
        // proving a real push now counts, the same way the other three formats already do.
        assertEquals(assetSizeBytesBeforePush + originalNupkg.length, describeDomainAssetSizeBytes());

        // Found by Qodo review: the index used to advertise bare paths instead of absolute
        // URLs, which a real NuGet client's own service-index parser silently drops (it only
        // keeps a resource whose @id parses as an absolute URI). Both resources must be rooted
        // at the exact endpoint GetRepositoryEndpoint itself returns.
        String repositoryEndpoint = given().header("Authorization", AUTH)
                .get("/v1/repository/endpoint?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget")
                .then().statusCode(200)
                .extract().jsonPath().getString("repositoryEndpoint");
        String endpointNoTrailingSlash = repositoryEndpoint.substring(0, repositoryEndpoint.length() - 1);
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/index.json")
                .then().statusCode(200)
                .body(containsString(endpointNoTrailingSlash + "/v3/package/"))
                .body(containsString(endpointNoTrailingSlash + "/api/v2/package"));

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID + "/index.json")
                .then().statusCode(200)
                .body("versions", Matchers.hasItem(VERSION));

        byte[] fetchedNupkg = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID + "/" + VERSION + "/"
                        + LOWER_ID + "." + VERSION + ".nupkg")
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(originalNupkg, fetchedNupkg);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID + "/" + VERSION + "/"
                        + LOWER_ID + ".nuspec")
                .then().statusCode(200)
                .body(containsString(PACKAGE_ID));

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget&package=" + PACKAGE_ID)
                .then().statusCode(200).body("package.name", equalTo(PACKAGE_ID))
                .body("package.originConfiguration.restrictions.publish", equalTo("ALLOW"));
    }

    /**
     * The real-world gap this closes: {@code GetPackageVersionAsset} used to 404 for every
     * nuget-published file since there was no nuget sidecar at all. Proves the JSON API bridges
     * to the same file {@link #pushThenInstallRoundTripsTheExactBytesThroughTheFlatContainer}
     * already confirmed is really there, through the real sidecar, not a mock. No namespace:
     * NuGet packages don't have one.
     */
    @Test
    @Order(2)
    void getPackageVersionAssetBridgesToTheRealBagetterSidecar() {
        String assetName = LOWER_ID + "." + VERSION + ".nupkg";
        byte[] fetched = given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget"
                        + "&package=" + PACKAGE_ID + "&version=" + VERSION + "&asset=" + assetName)
                .then().statusCode(200)
                .header("X-AssetName", equalTo(assetName))
                .extract().asByteArray();
        assertArrayEquals(originalNupkg, fetched);
    }

    @Test
    @Order(3)
    void getPackageVersionAssetReturns404ForAnAssetNameThatIsNotTheNormalizedForm() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget"
                        + "&package=" + PACKAGE_ID + "&version=" + VERSION + "&asset=" + PACKAGE_ID + ".nupkg")
                .then().statusCode(404);
    }

    /**
     * The real-world gap this closes: {@code DescribePackageVersion} used to 404 for every
     * nuget-uploaded version the same reason {@code GetPackageVersionAsset} did before it was
     * bridged. Confirms the bridge reads the real BaGetter container's own flat-container
     * version listing (not a mock) to confirm the version exists, and 404s for one that was
     * never pushed.
     */
    @Test
    @Order(4)
    void describePackageVersionBridgesToTheRealBagetterSidecar() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget"
                        + "&package=" + PACKAGE_ID + "&version=" + VERSION)
                .then().statusCode(200)
                .body("packageVersion.status", equalTo("Published"))
                .body("packageVersion.packageName", equalTo(PACKAGE_ID))
                .body("packageVersion.version", equalTo(VERSION));

        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget"
                        + "&package=" + PACKAGE_ID + "&version=9.9.9")
                .then().statusCode(404);
    }

    /**
     * No readme file was embedded in the pushed package, so this must fall back to the nuspec's
     * own {@code <description>} rather than 404ing for a version that genuinely exists.
     */
    @Test
    @Order(5)
    void getPackageVersionReadmeFallsBackToTheNuspecDescription() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget&package="
                        + PACKAGE_ID + "&version=" + VERSION)
                .then().statusCode(200)
                .body("readme", equalTo("A description."))
                .body("package", equalTo(PACKAGE_ID))
                .body("version", equalTo(VERSION));
    }

    /**
     * AWS's own documented idempotent-republish exception ("Overwriting package assets"): a
     * byte-identical re-push succeeds without BaGetter's own {@code AllowPackageOverwrites: false}
     * ever rejecting it, since Floci's proxy short-circuits before forwarding anything.
     */
    @Test
    @Order(6)
    void repushingTheExactSameBytesSucceedsIdempotently() {
        long assetSizeBytesBeforeRepush = describeDomainAssetSizeBytes();

        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", originalNupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(200);

        // The idempotent-match short-circuit never forwards to BaGetter, so no new bytes were
        // written; assetSizeBytes must not count this push a second time.
        assertEquals(assetSizeBytesBeforeRepush, describeDomainAssetSizeBytes());
    }

    @Test
    @Order(7)
    void repushingTheSameCoordinatesWithDifferentContentIsRejectedAndTheOriginalSurvives() {
        byte[] differentNupkg = buildNupkg(PACKAGE_ID, VERSION, "A different description.", null, null);

        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", differentNupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(409);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID + "/" + VERSION + "/"
                        + LOWER_ID + "." + VERSION + ".nupkg")
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(originalNupkg, fetched);
    }

    @Test
    @Order(8)
    void missingPackageAndMissingRepositoryAreNotFound() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/does-not-exist/index.json")
                .then().statusCode(404);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/no-such-repo/v3/index.json")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget&package=does-not-exist")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(9)
    void repositoriesAreIsolatedFromEachOther() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=other-nuget-repo")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/other-nuget-repo/v3/package/" + LOWER_ID + "/index.json")
                .then().statusCode(404);
    }

    @Test
    @Order(10)
    void missingOrWrongDomainTokensAreUnauthorized() {
        // Challenges as Basic, not Bearer: AWS's own NuGet CLI configuration doc has the client
        // authenticate with Basic (username=aws, password=<token>), so this is the scheme a
        // retry would actually honor.
        given().get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/index.json")
                .then().statusCode(401)
                .header("WWW-Authenticate", startsWith("Basic"));

        given().header("Authorization", "Bearer not-a-real-token")
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/index.json")
                .then().statusCode(401);

        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=other-nuget-token-domain")
                .then().statusCode(200);
        String otherDomainToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=other-nuget-token-domain")
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");

        given().header("Authorization", "Bearer " + otherDomainToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/index.json")
                .then().statusCode(401);
    }

    /**
     * A separate package, with a {@code <readme>} element and its referenced file both packed
     * in: the readme file must win over the description, proving the two-tier fallback really
     * prefers the embedded file when one exists rather than always falling back.
     */
    @Test
    @Order(11)
    void getPackageVersionReadmePrefersTheEmbeddedReadmeFileOverTheDescription() {
        String packageId = "Readme.Spike";
        String readmeText = "# Real Readme\n\nPacked alongside the nuspec.";
        byte[] nupkg = buildNupkg(packageId, "1.0.0", "A description nobody should see.", "README.md", readmeText);

        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", nupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(201);

        given().header("Authorization", AUTH)
                .get("/v1/package/version/readme?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget&package="
                        + packageId + "&version=1.0.0")
                .then().statusCode(200)
                .body("readme", equalTo(readmeText));
    }

    @Test
    @Order(12)
    void deletePackageRemovesTheFileFromTheRealBagetterSidecarAndAllowsARepublish() {
        given().header("Authorization", AUTH)
                .delete("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget&package="
                        + PACKAGE_ID)
                .then().statusCode(200).body("deletedPackage.package", equalTo(PACKAGE_ID));

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + LOWER_ID + "/index.json")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget&package=" + PACKAGE_ID)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        // BaGetter's own default (PackageDeletionBehavior: Unlist) would leave the old content in
        // place and reject this as a duplicate; Floci overrides it to HardDelete specifically so
        // a deleted package's coordinates are genuinely free again, matching every other
        // container-backed format's own DeletePackage semantics.
        byte[] nupkg = buildNupkg(PACKAGE_ID, VERSION, "A description.", null, null);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", nupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(201);
    }

    /**
     * The real-world gap this closes: {@code CopyPackageVersions} used to support only generic
     * and maven; nuget keeps its pushed packages straight on BaGetter's own flat storage rather
     * than a {@code packageVersions} record. Confirms the bridge reads the {@code .nupkg} from
     * the real sidecar (not a mock) and republishes it to the destination repository's own
     * BaGetter instance, byte for byte. {@code other-nuget-repo} is the empty repository {@link
     * #repositoriesAreIsolatedFromEachOther} already created.
     */
    @Test
    @Order(13)
    void copyPackageVersionsBridgesToTheRealBagetterSidecar() {
        String packageId = "CopyMe.Package";
        byte[] nupkg = buildNupkg(packageId, VERSION, "copy-me nuget package", null, null);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", nupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(201);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"" + VERSION + "\"]}")
                .post("/v1/package/versions/copy?domain=" + DOMAIN + "&source-repository=" + REPO
                        + "&destination-repository=other-nuget-repo&format=nuget&package=" + packageId)
                .then().statusCode(200)
                .body("successfulVersions.'" + VERSION + "'.status", equalTo("Published"))
                .body("failedVersions.size()", equalTo(0));

        byte[] copied = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/other-nuget-repo/v3/package/"
                        + packageId.toLowerCase(Locale.ROOT) + "/" + VERSION + "/"
                        + packageId.toLowerCase(Locale.ROOT) + "." + VERSION + ".nupkg")
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(nupkg, copied);
    }

    /**
     * The real-world bug this would otherwise hit: BaGetter rejects a plain re-push of an
     * already-pushed id/version outright (a real {@code 409}, confirmed live and already proven
     * by {@code repushingTheSameCoordinatesWithDifferentContentIsRejectedAndTheOriginalSurvives}
     * for the native push path), even for an id/version whose packument entry alone was removed,
     * since BaGetter's storage is content-addressed by id/version. Confirms the bridge's own
     * delete-then-republish ({@code DELETE /api/v2/package/<id>/<version>}, then push, both
     * confirmed live) actually clears the way for different content.
     */
    @Test
    @Order(14)
    void copyPackageVersionsOverwritesAnExistingNuGetVersionWithDifferentContentAtTheRealSidecar() {
        String packageId = "CopyOver.Package";
        String lowerId = packageId.toLowerCase(Locale.ROOT);
        byte[] oldNupkg = buildNupkg(packageId, VERSION, "old overwrite content", null, null);
        byte[] newNupkg = buildNupkg(packageId, VERSION, "new overwrite content", null, null);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", oldNupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/other-nuget-repo/api/v2/package")
                .then().statusCode(201);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", newNupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(201);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"" + VERSION + "\"],\"allowOverwrite\":true}")
                .post("/v1/package/versions/copy?domain=" + DOMAIN + "&source-repository=" + REPO
                        + "&destination-repository=other-nuget-repo&format=nuget&package=" + packageId)
                .then().statusCode(200)
                .body("successfulVersions.'" + VERSION + "'.status", equalTo("Published"))
                .body("failedVersions.size()", equalTo(0));

        byte[] copied = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/other-nuget-repo/v3/package/" + lowerId + "/" + VERSION
                        + "/" + lowerId + "." + VERSION + ".nupkg")
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(newNupkg, copied);
    }

    /**
     * The real-world gap this closes: {@code ListPackageVersionAssets} used to not exist at all.
     * Confirms the bridge enumerates NuGet's one-asset-per-version flat container against the
     * real BaGetter sidecar (not a mock) and reports the exact byte size and hash of a freshly
     * pushed {@code .nupkg}. Self-contained, own package id, rather than reusing {@link
     * #PACKAGE_ID}: that fixture is re-pushed by {@link
     * #deletePackageRemovesTheFileFromTheRealBagetterSidecarAndAllowsARepublish} with a freshly
     * built (not necessarily byte-identical) zip, so asserting against it here would depend on
     * exactly what that unrelated test last wrote.
     */
    @Test
    @Order(15)
    void listPackageVersionAssetsBridgesToTheRealBagetterSidecar() {
        String packageId = "ListAssets.Package";
        String lowerId = packageId.toLowerCase(Locale.ROOT);
        byte[] nupkg = buildNupkg(packageId, VERSION, "list assets description", null, null);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", nupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(201);

        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/package/version/assets?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget"
                        + "&package=" + packageId + "&version=" + VERSION)
                .then().statusCode(200)
                .body("package", equalTo(packageId))
                .body("assets.size()", equalTo(1))
                .body("assets[0].name", equalTo(lowerId + "." + VERSION + ".nupkg"))
                .body("assets[0].size", equalTo(nupkg.length))
                .extract().jsonPath();
        Map<String, String> hashes = body.getMap("assets[0].hashes");
        assertEquals(Digests.sha256Hex(nupkg), hashes.get("SHA-256"));
    }

    /**
     * Bridges to the same real BaGetter sidecar {@link #listPackageVersionAssetsBridgesToTheRealBagetterSidecar}
     * already pushed {@code ListAssets.Package}'s {@link #VERSION} to, reading the flat
     * container's {@code index.json} version list plus the NuGet V3 Registration resource's
     * {@code catalogEntry.published} for ordering, instead of one version's own asset.
     */
    @Test
    @Order(16)
    void listPackageVersionsBridgesToTheRealBagetterSidecar() {
        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget"
                        + "&package=ListAssets.Package")
                .then().statusCode(200)
                .body("package", equalTo("ListAssets.Package"))
                .body("defaultDisplayVersion", equalTo(VERSION))
                .body("versions.size()", equalTo(1))
                .body("versions[0].version", equalTo(VERSION))
                .body("versions[0].status", equalTo("Published"))
                .body("versions[0].origin.originType", equalTo("INTERNAL"));
    }

    /**
     * Bridges to BaGetter's own NuGet V3 Search resource, confirmed live: {@code
     * BagetterSidecarClient#listPackages} presses it into service purely for this admin listing
     * (this class's own class-level documentation explains why the native NuGet client protocol
     * never gets a Search endpoint of its own). A mixed-case id, confirmed back lowercased, since
     * a live search response returns the id real-cased exactly as pushed, and {@code
     * CodeArtifactService}'s own {@code accountingPackageName} normalizes every NuGet name to
     * lowercase (AWS's own documented NuGet normalization).
     */
    @Test
    @Order(17)
    void listPackagesBridgesToTheRealBagetterSearchResource() {
        String packageId = "ListPackages.Probe";
        byte[] nupkg = buildNupkg(packageId, VERSION, "list packages description", null, null);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", nupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(201);

        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/packages?domain=" + DOMAIN + "&repository=" + REPO + "&format=nuget")
                .then().statusCode(200)
                .extract().jsonPath();
        List<String> names = body.getList("packages.package", String.class);
        assertTrue(names.contains(packageId.toLowerCase(Locale.ROOT)), "expected "
                + packageId.toLowerCase(Locale.ROOT) + " among " + names);
    }

    /**
     * Closes 10-NATIVE-STATUS for nuget: a plain {@code dotnet restore}/{@code nuget install}
     * download of an Archived version's real {@code .nupkg} must 404 against the live BaGetter
     * sidecar, even though the file itself is still sitting there untouched. Its own package, not
     * {@link #PACKAGE_ID}, so archiving it can't disturb any other ordered test in this class.
     */
    @Test
    @Order(17)
    void archivingAVersionBlocksItsRealBagetterContentDownloadWhileLeavingTheBytesInPlace() {
        String packageId = "Archive.Download.Package";
        String lowerId = packageId.toLowerCase(Locale.ROOT);
        byte[] nupkg = buildNupkg(packageId, VERSION, "Archive download throwaway.", null, null);
        given().header("Authorization", "Bearer " + bearerToken)
                .contentType("multipart/form-data; boundary=boundary123")
                .body(pushBody("boundary123", nupkg))
                .put("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/api/v2/package")
                .then().statusCode(201);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"" + VERSION + "\"],\"targetStatus\":\"Archived\"}")
                .post("/v1/package/versions/update_status?domain=" + DOMAIN + "&repository=" + REPO
                        + "&format=nuget&package=" + packageId)
                .then().statusCode(200)
                .body("successfulVersions['" + VERSION + "'].status", equalTo("Archived"));

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + lowerId + "/" + VERSION + "/"
                        + lowerId + "." + VERSION + ".nupkg")
                .then().statusCode(404);

        // Proves the block above is this check's own override, not BaGetter having genuinely
        // lost the file: un-archiving must make the exact same bytes fetchable again, with
        // nothing re-pushed in between.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"" + VERSION + "\"],\"targetStatus\":\"Published\"}")
                .post("/v1/package/versions/update_status?domain=" + DOMAIN + "&repository=" + REPO
                        + "&format=nuget&package=" + packageId)
                .then().statusCode(200)
                .body("successfulVersions['" + VERSION + "'].status", equalTo("Published"));
        byte[] fetchedAfterUnarchive = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/nuget/" + DOMAIN + "/" + REPO + "/v3/package/" + lowerId + "/" + VERSION + "/"
                        + lowerId + "." + VERSION + ".nupkg")
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(nupkg, fetchedAfterUnarchive);
    }

    private static long describeDomainAssetSizeBytes() {
        return given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
    }

    /**
     * The real NuGet push protocol's own documented shape: {@code multipart/form-data} whose
     * first item is the raw bytes of the {@code .nupkg}; the part name and filename are ignored
     * by both the real protocol and {@code CodeArtifactNuGetController}, so {@code "package"}/
     * {@code "p.nupkg"} here are arbitrary.
     */
    private static byte[] pushBody(String boundary, byte[] nupkg) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"package\"; filename=\"p.nupkg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(nupkg);
        out.writeBytes(footer.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /**
     * A real {@code .nupkg} is a plain ZIP with a {@code .nuspec} manifest at its root (built with
     * the JDK's own {@code java.util.zip}, matching {@code NuspecXml.extractFromNupkg}'s own
     * expectations); {@code readmePathOrNull}/{@code readmeTextOrNull} additionally pack a second
     * entry and reference it with a nuspec {@code <readme>} element, the real NuGet readme-file
     * feature.
     */
    private static byte[] buildNupkg(String id, String version, String description, String readmePathOrNull,
            String readmeTextOrNull) {
        String readmeElement = readmePathOrNull == null ? "" : "<readme>" + readmePathOrNull + "</readme>";
        String nuspec = "<?xml version=\"1.0\"?>"
                + "<package xmlns=\"http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd\">"
                + "<metadata><id>" + id + "</id><version>" + version + "</version><authors>floci</authors>"
                + readmeElement + "<description>" + description + "</description></metadata></package>";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry(id + ".nuspec"));
                zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                if (readmePathOrNull != null) {
                    zip.putNextEntry(new ZipEntry(readmePathOrNull));
                    zip.write(readmeTextOrNull.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
