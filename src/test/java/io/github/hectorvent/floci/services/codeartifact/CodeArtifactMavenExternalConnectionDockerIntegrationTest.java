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

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retains a real Maven package version from the real, live {@code public:maven-central}
 * repository (not a local fake), through the native per-format route, the way a real {@code mvn}/
 * Gradle build would. junit 4.13.2 is picked because AWS's own User Guide uses it as its worked
 * example and it's small, stable, and unlikely to ever be pulled from Maven Central. A genuine
 * network dependency, unlike every other sidecar-backed test here, which only ever talks to a
 * container Floci itself started; see {@code CodeArtifactMavenExternalConnectionImporterTest}
 * for the mocked, deterministic coverage of the asset-selection and mixed-origin logic this test
 * does not re-exercise, and {@code ReposiliteSidecarClientTest} for the same of the
 * {@code maven-metadata.xml} merge a Maven version range or Gradle dynamic version actually relies
 * on to resolve at all.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactMavenExternalConnectionDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "maven-external-connection-domain";
    private static final String REPO = "maven-external-connection-repo";
    private static final String BLOCKED_REPO = "maven-external-connection-blocked-repo";
    private static final String DYNAMIC_REPO = "maven-external-connection-dynamic-repo";
    private static final String JUNIT_JAR = "junit/junit/4.13.2/junit-4.13.2.jar";
    private static final String JUNIT_POM = "junit/junit/4.13.2/junit-4.13.2.pom";
    private static final String JUNIT_METADATA = "junit/junit/maven-metadata.xml";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.codeartifact.maven-image");
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
                .post("/v1/repository?domain=" + DOMAIN + "&repository=" + DYNAMIC_REPO)
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + REPO
                        + "&external-connection=public:maven-central")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO
                        + "&external-connection=public:maven-central")
                .then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + DYNAMIC_REPO
                        + "&external-connection=public:maven-central")
                .then().statusCode(200);

        bearerToken = given().header("Authorization", AUTH)
                .post("/v1/authorization-token?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getString("authorizationToken");
    }

    @Test
    @Order(1)
    void fetchingAMissingJarRetainsItFromTheRealMavenCentralConnectionAndCountsItsBytes() {
        long assetSizeBytesBefore = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
        assertEquals(0L, assetSizeBytesBefore);

        byte[] fetched = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + JUNIT_JAR)
                .then().statusCode(200)
                .extract().asByteArray();

        // A real jar is a real zip: "PK" magic bytes, not asserting exact content since the real
        // artifact is out of this test's control.
        assertEquals('P', (char) fetched[0]);
        assertEquals('K', (char) fetched[1]);
        assertThat(fetched.length, greaterThan(1000));

        // Proves the retained bytes are actually counted toward DescribeDomain's assetSizeBytes,
        // the same way a direct mvn deploy already is: at least the jar's own length, since the
        // standard-asset import also retains the pom (and possibly javadoc/sources) alongside it.
        long assetSizeBytesAfter = given().header("Authorization", AUTH)
                .get("/v1/domain?domain=" + DOMAIN)
                .then().statusCode(200)
                .extract().jsonPath().getLong("domain.assetSizeBytes");
        assertThat(assetSizeBytesAfter, greaterThan((long) fetched.length));
    }

    @Test
    @Order(2)
    void theStandardPomSiblingWasRetainedInTheSameAttemptNotJustTheRequestedJar() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .delete("/v1/repository/external-connection?domain=" + DOMAIN + "&repository=" + REPO
                        + "&external-connection=public:maven-central")
                .then().statusCode(200);

        // With the external connection now gone, a fresh retention attempt is impossible: this can
        // only succeed because the standard-asset import on order(1)'s own jar request already
        // retained the pom alongside it.
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/" + JUNIT_POM)
                .then().statusCode(200);
    }

    @Test
    @Order(3)
    void republishingAVersionAlreadyPresentInTheExternalConnectionIsRejected() {
        given().header("Authorization", "Bearer " + bearerToken)
                .body("not-the-real-bytes".getBytes(StandardCharsets.UTF_8))
                .put("/codeartifact/maven/" + DOMAIN + "/" + BLOCKED_REPO + "/" + JUNIT_JAR)
                .then().statusCode(409);
    }

    @Test
    @Order(4)
    void upstreamBlockPreventsRetentionEvenThoughTheRealArtifactExists() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"restrictions\":{\"publish\":\"ALLOW\",\"upstream\":\"BLOCK\"}}")
                .post("/v1/package?domain=" + DOMAIN + "&repository=" + BLOCKED_REPO + "&format=maven"
                        + "&namespace=junit&package=junit")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + BLOCKED_REPO + "/" + JUNIT_JAR)
                .then().statusCode(404);
    }

    @Test
    @Order(5)
    void aSnapshotVersionIsNeverRetainedFromAnExternalConnection() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO
                        + "/com/example/does-not-exist/1.0-SNAPSHOT/does-not-exist-1.0-SNAPSHOT.jar")
                .then().statusCode(404);
    }

    /**
     * The sequence a real Maven version range or Gradle dynamic version actually produces: a bare
     * {@code maven-metadata.xml} request first, merging in the real release versions {@code
     * public:maven-central} names for junit, then a request for whichever exact version got picked
     * (4.13.2, the real latest). Runs against its own repository, never touched by an earlier
     * {@code @Order}, so the metadata request here is genuinely this package's first contact with
     * the sidecar: merging metadata before any version is ever retained once made {@code
     * packageExists} see the group/artifact directory as already present and permanently refuse to
     * ever retain a real version for it, since no claim could get recorded to prove the package's
     * origin was external rather than directly published. This is the regression test for that.
     */
    @Test
    @Order(6)
    void aVersionRangeResolvesThroughAMetadataRequestThenRetainsTheChosenVersion() {
        byte[] metadata = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + DYNAMIC_REPO + "/" + JUNIT_METADATA)
                .then().statusCode(200)
                .extract().asByteArray();
        String metadataXml = new String(metadata, StandardCharsets.UTF_8);
        assertThat(metadataXml, containsString("<version>4.13.2</version>"));

        byte[] jar = given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + DYNAMIC_REPO + "/" + JUNIT_JAR)
                .then().statusCode(200)
                .extract().asByteArray();
        assertEquals('P', (char) jar[0]);
        assertEquals('K', (char) jar[1]);
        assertThat(jar.length, greaterThan(1000));
    }
}
