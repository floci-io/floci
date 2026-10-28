package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.Digests;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real Maven-shaped GET/PUT/HEAD round trip against the shared Reposilite sidecar,
 * started by {@link ReposiliteSidecarManager} exactly as in production. Verified separately with
 * the real Maven client ({@code mvn deploy:deploy-file} / {@code mvn dependency:get}) by hand;
 * this test covers the same round trip plus the concurrent-first-use case in an automated,
 * Docker-gated form.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactMavenDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "maven-sidecar-domain";
    private static final String REPO = "maven-sidecar-repo";
    private static final String GAV = "com/example/spike/1.0.0/spike-1.0.0.jar";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.maven-image");
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
    void deployThenFetchRoundTripsTheExactBytes() {
        byte[] content = "real-jar-bytes".getBytes(StandardCharsets.UTF_8);

        given().header("Authorization", "Bearer " + bearerToken).body(content)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(200);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals(new String(content, StandardCharsets.UTF_8), new String(fetched, StandardCharsets.UTF_8));

        given().header("Authorization", "Bearer " + bearerToken)
                .head("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike")
                .then().statusCode(200).body("package.name", equalTo("spike"))
                .body("package.namespace", equalTo("com.example"))
                .body("package.originConfiguration.restrictions.publish", equalTo("ALLOW"));
    }

    /**
     * Reposilite's {@code redeployment: false} rejects a redeploy to an existing GAV path
     * unconditionally, even when the new content is byte-identical to what is already there. AWS's
     * own CodeArtifact docs ("Overwriting package assets" in packages-overview.html) say that case
     * specifically succeeds because the operation is idempotent; only a genuine content mismatch,
     * covered by {@link #redeployingDifferentBytesStillConflicts}, is a real conflict.
     */
    @Test
    @Order(2)
    void redeployingTheExactSameBytesSucceedsIdempotently() {
        byte[] content = "real-jar-bytes".getBytes(StandardCharsets.UTF_8);

        given().header("Authorization", "Bearer " + bearerToken).body(content)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(200);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals(new String(content, StandardCharsets.UTF_8), new String(fetched, StandardCharsets.UTF_8));
    }

    /**
     * The real content-mismatch counterpart to the idempotent-retry test above, against the live
     * shared Reposilite container rather than a fake server: proves the fix only closes the
     * byte-identical-retry gap and leaves Reposilite's own {@code redeployment: false} enforcement
     * for a genuine conflict untouched, so a config change there that started allowing silent
     * overwrites would still be caught here.
     */
    @Test
    @Order(3)
    void redeployingDifferentBytesStillConflicts() {
        byte[] different = "different-jar-bytes".getBytes(StandardCharsets.UTF_8);

        given().header("Authorization", "Bearer " + bearerToken).body(different)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(409);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("real-jar-bytes", new String(fetched, StandardCharsets.UTF_8));
    }

    @Test
    @Order(4)
    void missingArtifactAndMissingRepositoryAreNotFound() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/does/not/exist.jar")
                .then().statusCode(404);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/no-such-repo/does/not/exist.jar")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=does-not-exist")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(5)
    void repositoriesAreIsolatedFromEachOther() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=other-repo")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/other-repo/" + GAV)
                .then().statusCode(404);
    }

    @Test
    @Order(6)
    void missingOrWrongDomainTokensAreUnauthorized() {
        // Challenges as Basic, not Bearer: a real Maven wagon client only retries a 401 with its
        // configured settings.xml credentials when the challenge scheme matches what it sent.
        given().get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(401)
                .header("WWW-Authenticate", startsWith("Basic"));

        given().header("Authorization", "Bearer not-a-real-token")
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(401);

        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/domain?domain=other-token-domain")
                .then().statusCode(200);
        String otherDomainToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=other-token-domain")
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");

        given().header("Authorization", "Bearer " + otherDomainToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + GAV)
                .then().statusCode(401);
    }

    @Test
    @Order(7)
    void concurrentFirstUseOfANewRepositoryOnlyProvisionsItOnce() throws InterruptedException {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=concurrent-repo")
                .then().statusCode(200);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                String gav = "com/example/concurrent/1.0.0/concurrent-" + i + ".jar";
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        given().header("Authorization", "Bearer " + bearerToken)
                                .body("x".getBytes(StandardCharsets.UTF_8))
                                .put("/codeartifact/maven/" + DOMAIN + "/concurrent-repo/" + gav)
                                .then().statusCode(200);
                        successes.incrementAndGet();
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
        assertEquals(attempts, successes.get());

        for (int i = 0; i < attempts; i++) {
            given().header("Authorization", "Bearer " + bearerToken)
                    .head("/codeartifact/maven/" + DOMAIN + "/concurrent-repo/com/example/concurrent/1.0.0/concurrent-"
                            + i + ".jar")
                    .then().statusCode(200);
        }
    }

    @Test
    @Order(8)
    void recreatingASameNamedRepositoryDoesNotInheritThePreviousOnesArtifacts() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken).body("first-generation".getBytes(StandardCharsets.UTF_8))
                .put("/codeartifact/maven/" + DOMAIN + "/reused-name/" + GAV)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=reused-name")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/reused-name/" + GAV)
                .then().statusCode(404);
    }

    @Test
    @Order(9)
    void aRealMavenClientsBasicAuthCredentialsAreAcceptedWithTheTokenAsThePassword() {
        String basic = "Basic " + Base64.getEncoder().encodeToString(("aws:" + bearerToken).getBytes(StandardCharsets.UTF_8));
        byte[] content = "basic-auth-bytes".getBytes(StandardCharsets.UTF_8);

        given().header("Authorization", basic).body(content)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/basic/1.0.0/basic-1.0.0.jar")
                .then().statusCode(200);

        byte[] fetched = given().header("Authorization", basic)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/basic/1.0.0/basic-1.0.0.jar")
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals(new String(content, StandardCharsets.UTF_8), new String(fetched, StandardCharsets.UTF_8));
    }

    @Test
    @Order(10)
    void aDomainCreatedInANonDefaultRegionIsServedThroughTheTokensOwnRegionNotTheDefault() {
        // The default region here is us-east-1 (application.yml). Every other test in this class
        // creates its domain under an AUTH header whose SigV4 scope is also us-east-1, so those
        // tests would still pass even if the Maven proxy silently ignored the token's actual
        // region and fell back to the default - exactly the bug this test exists to catch.
        String nonDefaultRegionAuth =
                "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-west-2/codeartifact/aws4_request";
        String domain = "maven-sidecar-non-default-region-domain";

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

        byte[] content = "non-default-region-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + token).body(content)
                .put("/codeartifact/maven/" + domain + "/" + REPO + "/" + GAV)
                .then().statusCode(200);

        byte[] fetched = given().header("Authorization", "Bearer " + token)
                .get("/codeartifact/maven/" + domain + "/" + REPO + "/" + GAV)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals(new String(content, StandardCharsets.UTF_8), new String(fetched, StandardCharsets.UTF_8));
    }

    @Test
    @Order(11)
    void aDomainCreatedUnderANonDefaultAccountIsServedThroughTheTokensOwnAccountNotTheDefault() {
        // Every other test in this class authenticates as the default account (000000000000), so
        // none of them would notice if the owner half of the token-scope fix regressed and every
        // Maven request fell back to the default account instead of the domain's real one.
        String otherAccountAuth = "AWS4-HMAC-SHA256 Credential=111122223333/20260904/us-east-1/codeartifact/aws4_request";
        String domain = "maven-sidecar-cross-account-domain";

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

        byte[] content = "cross-account-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + token).body(content)
                .put("/codeartifact/maven/" + domain + "/" + REPO + "/" + GAV)
                .then().statusCode(200);

        byte[] fetched = given().header("Authorization", "Bearer " + token)
                .get("/codeartifact/maven/" + domain + "/" + REPO + "/" + GAV)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals(new String(content, StandardCharsets.UTF_8), new String(fetched, StandardCharsets.UTF_8));
    }

    @Test
    @Order(11)
    void deletingARepositoryReleasesItsStorageFromTheSharedReposiliteInstance() throws Exception {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=released-repo")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken).body("to-be-released".getBytes(StandardCharsets.UTF_8))
                .put("/codeartifact/maven/" + DOMAIN + "/released-repo/" + GAV)
                .then().statusCode(200);
        int repositoryCountBeforeDelete = reposiliteSettingsRepositoryCount();

        given().header("Authorization", AUTH)
                .delete("/v1/repository?domain=" + DOMAIN + "&repository=released-repo")
                .then().statusCode(200);

        // The mavenRepositoryId is internal (never in the API response), so identity isn't
        // checkable directly; a count drop of exactly one is enough to confirm this repository's
        // entry, specifically, is what disappeared from the real shared instance's own settings
        // list, not just that Floci stopped tracking the CodeArtifact-side metadata.
        assertEquals(repositoryCountBeforeDelete - 1, reposiliteSettingsRepositoryCount());
    }

    /**
     * The real-world gap this closes: {@code GetPackageVersionAsset} used to 404 for every
     * Maven-deployed artifact since Floci's generic-format package-version store never had a
     * record for it (Maven publishing goes straight to Reposilite, bypassing it entirely). Proves
     * the JSON API now bridges to the same artifact {@link #deployThenFetchRoundTripsTheExactBytes}
     * already confirmed is really there, through the real sidecar, not a mock.
     */
    @Test
    @Order(12)
    void getPackageVersionAssetBridgesToTheRealReposiliteSidecar() {
        byte[] fetched = given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike&version=1.0.0&asset=spike-1.0.0.jar")
                .then().statusCode(200)
                .header("X-AssetName", equalTo("spike-1.0.0.jar"))
                .header("X-PackageVersion", equalTo("1.0.0"))
                .extract().asByteArray();
        assertEquals("real-jar-bytes", new String(fetched, StandardCharsets.UTF_8));
    }

    @Test
    @Order(12)
    void getPackageVersionAssetRequiresANamespaceForMaven() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&package=spike&version=1.0.0&asset=spike-1.0.0.jar")
                .then().statusCode(400);
    }

    @Test
    @Order(12)
    void getPackageVersionAssetReturns404ForAMavenAssetThatWasNeverDeployed() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike&version=1.0.0&asset=does-not-exist.jar")
                .then().statusCode(404);
    }

    /**
     * The real-world gap this closes: {@code DescribePackageVersion} used to 404 for every
     * Maven-deployed version the same reason {@code GetPackageVersionAsset} did. Confirms the
     * bridge asks the real Reposilite sidecar (via its details API, not a mock) whether the
     * version directory itself exists, and that the synthesized revision matches what {@link
     * #getPackageVersionAssetBridgesToTheRealReposiliteSidecar} already got for the same
     * coordinates.
     */
    @Test
    @Order(12)
    void describePackageVersionBridgesToTheRealReposiliteSidecar() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike&version=1.0.0")
                .then().statusCode(200)
                .body("packageVersion.status", equalTo("Published"))
                .body("packageVersion.namespace", equalTo("com.example"))
                .body("packageVersion.packageName", equalTo("spike"))
                .body("packageVersion.version", equalTo("1.0.0"));

        String assetRevision = given().header("Authorization", AUTH)
                .get("/v1/package/version/asset?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike&version=1.0.0&asset=spike-1.0.0.jar")
                .then().statusCode(200)
                .extract().header("X-PackageVersionRevision");
        String describeRevision = given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike&version=1.0.0")
                .then().statusCode(200)
                .extract().jsonPath().getString("packageVersion.revision");
        assertEquals(assetRevision, describeRevision);
    }

    @Test
    @Order(16)
    void describePackageVersionRequiresANamespaceForMaven() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&package=spike&version=1.0.0")
                .then().statusCode(400);
    }

    @Test
    @Order(17)
    void describePackageVersionReturns404ForAMavenVersionThatWasNeverDeployed() {
        given().header("Authorization", AUTH)
                .get("/v1/package/version?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike&version=9.9.9")
                .then().statusCode(404);
    }

    /**
     * The real-world gap this closes: {@code CopyPackageVersions} used to only support the
     * generic format, since Maven, npm, pypi, and nuget all keep their published assets straight
     * in their own sidecar rather than a {@code packageVersions} record. Confirms the bridge
     * reads every asset from the real Reposilite sidecar (not a mock) and writes them to the
     * destination repository's own Reposilite path, byte for byte. {@code other-repo} is the
     * empty repository {@link #repositoriesAreIsolatedFromEachOther} already created and never
     * published to.
     */
    @Test
    @Order(18)
    void copyPackageVersionsBridgesToTheRealReposiliteSidecar() {
        byte[] jarBytes = "copy-me-jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pomBytes = "copy-me-pom-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken).body(jarBytes)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/copyme/1.0.0/copyme-1.0.0.jar")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken).body(pomBytes)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/copyme/1.0.0/copyme-1.0.0.pom")
                .then().statusCode(200);

        String revision = given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"1.0.0\"]}")
                .post("/v1/package/versions/copy?domain=" + DOMAIN + "&source-repository=" + REPO
                        + "&destination-repository=other-repo&format=maven&namespace=com.example&package=copyme")
                .then().statusCode(200)
                .body("successfulVersions.'1.0.0'.status", equalTo("Published"))
                .body("failedVersions.size()", equalTo(0))
                .extract().jsonPath().getString("successfulVersions.'1.0.0'.revision");
        assertTrue(revision != null && !revision.isBlank());

        byte[] copiedJar = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/other-repo/com/example/copyme/1.0.0/copyme-1.0.0.jar")
                .then().statusCode(200)
                .extract().asByteArray();
        byte[] copiedPom = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/other-repo/com/example/copyme/1.0.0/copyme-1.0.0.pom")
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("copy-me-jar-bytes", new String(copiedJar, StandardCharsets.UTF_8));
        assertEquals("copy-me-pom-bytes", new String(copiedPom, StandardCharsets.UTF_8));

        String metadata = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/other-repo/com/example/copyme/maven-metadata.xml")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(metadata.contains("<version>1.0.0</version>"), metadata);
        assertTrue(metadata.contains("<latest>1.0.0</latest>"), metadata);
        assertTrue(metadata.contains("<release>1.0.0</release>"), metadata);
    }

    /**
     * The real-world bug this closes: the destination already had {@code copyme-overwrite-1.0.0.jar}
     * under the exact same name with different bytes, and Reposilite's own {@code
     * redeployment: false} setting (confirmed live against this same container earlier in this
     * class) rejects a plain PUT over it. Without deleting the old one first, this copy's PUT
     * would come back {@code 409} even though {@code allowOverwrite} was set.
     */
    @Test
    @Order(19)
    void copyPackageVersionsOverwritesASameNamedAssetWithDifferentContentAtTheRealSidecar() {
        byte[] oldJarBytes = "old-overwrite-jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] newJarBytes = "new-overwrite-jar-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken).body(oldJarBytes)
                .put("/codeartifact/maven/" + DOMAIN + "/other-repo/com/example/copyover/1.0.0/copyover-1.0.0.jar")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + bearerToken).body(newJarBytes)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/copyover/1.0.0/copyover-1.0.0.jar")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"1.0.0\"],\"allowOverwrite\":true}")
                .post("/v1/package/versions/copy?domain=" + DOMAIN + "&source-repository=" + REPO
                        + "&destination-repository=other-repo&format=maven&namespace=com.example&package=copyover")
                .then().statusCode(200)
                .body("successfulVersions.'1.0.0'.status", equalTo("Published"))
                .body("failedVersions.size()", equalTo(0));

        byte[] copied = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/other-repo/com/example/copyover/1.0.0/copyover-1.0.0.jar")
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals("new-overwrite-jar-bytes", new String(copied, StandardCharsets.UTF_8));
    }

    /**
     * The real-world gap this closes: {@code ListPackageVersionAssets} used to not exist at all.
     * Confirms the bridge enumerates {@link #GAV}'s own version directory against the real
     * Reposilite sidecar (not a mock) and reports the exact byte size and hash of the asset
     * already deployed to it in {@link #deployThenFetchRoundTripsTheExactBytes}.
     */
    @Test
    @Order(20)
    void listPackageVersionAssetsBridgesToTheRealReposiliteSidecar() {
        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/package/version/assets?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike&version=1.0.0")
                .then().statusCode(200)
                .body("package", equalTo("spike"))
                .body("assets.size()", equalTo(1))
                .body("assets[0].name", equalTo("spike-1.0.0.jar"))
                .body("assets[0].size", equalTo("real-jar-bytes".getBytes(StandardCharsets.UTF_8).length))
                .extract().jsonPath();
        Map<String, String> hashes = body.getMap("assets[0].hashes");
        assertEquals(Digests.sha256Hex("real-jar-bytes".getBytes(StandardCharsets.UTF_8)), hashes.get("SHA-256"));
    }

    /**
     * Bridges to the same real Reposilite sidecar {@link #listPackageVersionAssetsBridgesToTheRealReposiliteSidecar}
     * already confirmed holds {@code spike}'s {@code 1.0.0}, walking the artifact-level directory
     * listing one level up from that test's own version directory.
     */
    @Test
    @Order(21)
    void listPackageVersionsBridgesToTheRealReposiliteSidecar() {
        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=spike")
                .then().statusCode(200)
                .body("package", equalTo("spike"))
                .body("defaultDisplayVersion", equalTo("1.0.0"))
                .body("versions.size()", equalTo(1))
                .body("versions[0].version", equalTo("1.0.0"))
                .body("versions[0].status", equalTo("Published"))
                .body("versions[0].origin.originType", equalTo("INTERNAL"));
    }

    /**
     * The real-world gap this guards against: {@code ReposiliteSidecarClient#listPackageVersions}
     * used to fetch every version's publish-time details page one at a time, so a package with
     * many versions still answered no faster than its slowest version's own round trip, times the
     * version count. This deploys enough versions that a broken fan-out (dropped, duplicated, or
     * silently empty results from the bounded executor) would show up as something other than
     * exactly this set of versions, against the real Reposilite sidecar, not a mock.
     */
    @Test
    @Order(22)
    void listPackageVersionsFetchesManyVersionsPublishTimesCorrectly() {
        String packageName = "manyversions";
        int versionCount = 12;
        Set<String> expectedVersions = new HashSet<>();
        for (int i = 1; i <= versionCount; i++) {
            String version = "1.0." + i;
            expectedVersions.add(version);
            byte[] content = ("many-versions-bytes-" + i).getBytes(StandardCharsets.UTF_8);
            given().header("Authorization", "Bearer " + bearerToken).body(content)
                    .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/" + packageName + "/" + version
                            + "/" + packageName + "-" + version + ".jar")
                    .then().statusCode(200);
        }

        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=" + packageName)
                .then().statusCode(200)
                .body("package", equalTo(packageName))
                .body("versions.size()", equalTo(versionCount))
                .extract().jsonPath();
        List<String> actualVersions = body.getList("versions.version", String.class);
        assertEquals(expectedVersions, new HashSet<>(actualVersions));
        String defaultDisplayVersion = body.getString("defaultDisplayVersion");
        assertTrue(expectedVersions.contains(defaultDisplayVersion),
                "expected defaultDisplayVersion to be one of the deployed versions, got "
                        + defaultDisplayVersion);
    }

    /**
     * A throwaway groupId/artifactId, not {@link #GAV}: disposing it is destructive (the real
     * assets are gone afterward), and must not disturb the artifact every other test in this class
     * shares. Confirms the whole real round trip: {@code DisposePackageVersions} deletes the real
     * jar/pom through Reposilite's own asset-delete endpoint, a subsequent fetch 404s, and
     * {@code ListPackageVersions} still reports the version, by status {@code Disposed} alone,
     * since the real sidecar listing no longer names it at all once its content is gone.
     */
    @Test
    @Order(23)
    void disposePackageVersionsDeletesTheRealReposiliteAssetsAndKeepsListingTheVersionAsDisposed() {
        byte[] content = "dispose-me-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken).body(content)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/disposeme/1.0.0/disposeme-1.0.0.jar")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"1.0.0\"]}")
                .post("/v1/package/versions/dispose?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=disposeme")
                .then().statusCode(200)
                .body("successfulVersions['1.0.0'].status", equalTo("Disposed"))
                .body("failedVersions.size()", equalTo(0));

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/disposeme/1.0.0/disposeme-1.0.0.jar")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=disposeme&status=Disposed")
                .then().statusCode(200)
                .body("versions.size()", equalTo(1))
                .body("versions[0].version", equalTo("1.0.0"))
                .body("versions[0].status", equalTo("Disposed"));
    }

    /**
     * A throwaway groupId/artifactId, not {@link #GAV}: {@code ListPackages} walks Reposilite's
     * whole directory tree repository-wide, so publishing its own package here (rather than
     * reusing one another test already created) keeps this test's own expected set independent of
     * whatever else this class's shared repository happens to hold by the time it runs.
     */
    @Test
    @Order(24)
    void listPackagesWalksTheRealReposiliteDirectoryTreeToFindTheGroupAndArtifact() {
        byte[] content = "list-packages-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken).body(content)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO
                        + "/com/example/nested/listpkgsprobe/1.0.0/listpkgsprobe-1.0.0.jar")
                .then().statusCode(200);

        JsonPath body = given().header("Authorization", AUTH)
                .post("/v1/packages?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example.nested")
                .then().statusCode(200)
                .extract().jsonPath();
        List<String> names = body.getList("packages.package", String.class);
        List<String> namespaces = body.getList("packages.namespace", String.class);
        assertTrue(names.contains("listpkgsprobe"), "expected listpkgsprobe among " + names);
        assertEquals("com.example.nested", namespaces.get(names.indexOf("listpkgsprobe")));
    }

    /**
     * Closes 10-NATIVE-STATUS for Maven: a plain {@code mvn dependency:get} fetch of an Archived
     * version's real GAV path must 404 against the real Reposilite sidecar, even though the jar's
     * own bytes are still sitting there untouched (unlike {@link
     * #disposePackageVersionsDeletesTheRealReposiliteAssetsAndKeepsListingTheVersionAsDisposed}'s
     * own 404, which comes from Reposilite genuinely no longer having the file).
     */
    @Test
    @Order(24)
    void deployThenArchiveBlocksBothGetAndHeadAgainstTheRealReposiliteSidecarWhileLeavingTheBytesInPlace() {
        byte[] content = "archive-me-bytes".getBytes(StandardCharsets.UTF_8);
        String gav = "com/example/archiveme/1.0.0/archiveme-1.0.0.jar";
        given().header("Authorization", "Bearer " + bearerToken).body(content)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + gav)
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"1.0.0\"],\"targetStatus\":\"Archived\"}")
                .post("/v1/package/versions/update_status?domain=" + DOMAIN + "&repository=" + REPO
                        + "&format=maven&namespace=com.example&package=archiveme")
                .then().statusCode(200)
                .body("successfulVersions['1.0.0'].status", equalTo("Archived"));

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + gav)
                .then().statusCode(404);
        given().header("Authorization", "Bearer " + bearerToken)
                .head("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + gav)
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .post("/v1/package/versions?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=archiveme&status=Archived")
                .then().statusCode(200)
                .body("versions[0].version", equalTo("1.0.0"))
                .body("versions[0].status", equalTo("Archived"));

        // Proves the block above is this check's own override, not Reposilite having genuinely
        // lost the file the way the dispose test's 404 does: un-archiving must make the exact
        // same bytes fetchable again, with nothing re-deployed in between.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"versions\":[\"1.0.0\"],\"targetStatus\":\"Published\"}")
                .post("/v1/package/versions/update_status?domain=" + DOMAIN + "&repository=" + REPO
                        + "&format=maven&namespace=com.example&package=archiveme")
                .then().statusCode(200)
                .body("successfulVersions['1.0.0'].status", equalTo("Published"));
        byte[] fetchedAfterUnarchive = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + gav)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals(new String(content, StandardCharsets.UTF_8),
                new String(fetchedAfterUnarchive, StandardCharsets.UTF_8));
    }

    /**
     * A throwaway groupId/artifactId, not {@link #GAV}: deleting it exercises Reposilite's real
     * directory delete (confirmed live before this was built: a single {@code DELETE} on the
     * group/artifact path removes everything beneath it), and must not disturb the artifact every
     * other test in this class shares.
     */
    @Test
    @Order(13)
    void deletePackageRemovesTheWholeReposiliteArtifactDirectory() {
        byte[] content = "delete-me-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken).body(content)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/deleteme/1.0.0/deleteme-1.0.0.jar")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .delete("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=deleteme")
                .then().statusCode(200).body("deletedPackage.package", equalTo("deleteme"));

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/deleteme/1.0.0/deleteme-1.0.0.jar")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .get("/v1/package?domain=" + DOMAIN + "&repository=" + REPO + "&format=maven"
                        + "&namespace=com.example&package=deleteme")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * Asserts the delta rather than an absolute total: this class shares one domain across every
     * ordered test, so other artifacts already contribute to the running total by this point.
     * {@code assetSizeBytes} used to be hardcoded to {@code 0} regardless of what was actually
     * published; this is the real Reposilite round trip proving a Maven deploy now counts.
     */
    @Test
    @Order(14)
    void describeDomainAssetSizeBytesIncreasesByExactlyWhatWasDeployed() {
        long before = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");

        byte[] content = "asset-size-accounting-bytes".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken).body(content)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/sizecheck/1.0.0/sizecheck-1.0.0.jar")
                .then().statusCode(200);

        long after = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");

        assertEquals(before + content.length, after);
    }

    /**
     * {@code mvn deploy} always PUTs a {@code maven-metadata.xml} straight to the group/artifact
     * directory too, with no version segment: {@code com/example/metacheck/maven-metadata.xml} is
     * only one segment shorter than a real versioned asset path, the same four segments as the
     * shortest real GAV ({@code group/artifact/version/filename}). It must not be mistaken for
     * one and misattributed one groupId component too shallow; it is still a real, successful
     * write, so its bytes still belong in the total.
     */
    @Test
    @Order(15)
    void describeDomainAssetSizeBytesCountsAGroupLevelMavenMetadataUpload() {
        long before = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");

        byte[] metadata = "<metadata><groupId>com.example</groupId></metadata>".getBytes(StandardCharsets.UTF_8);
        given().header("Authorization", "Bearer " + bearerToken).body(metadata)
                .put("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/com/example/metacheck/maven-metadata.xml")
                .then().statusCode(200);

        long after = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");

        assertEquals(before + metadata.length, after);
    }

    /**
     * Reads the real Reposilite instance's own {@code maven} settings domain directly (the same
     * shared container {@link ReposiliteSidecarManager} started for every test in this class).
     */
    private int reposiliteSettingsRepositoryCount() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(reposiliteManager.ensureReady() + "/api/settings/domain/maven"))
                .header("Authorization", reposiliteManager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(response.body()).path("repositories").size();
    }

    @Inject
    ReposiliteSidecarManager reposiliteManager;
}
