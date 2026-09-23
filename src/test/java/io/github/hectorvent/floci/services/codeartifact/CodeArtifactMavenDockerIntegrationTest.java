package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
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
@TestProfile(CodeArtifactMavenSidecarProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactMavenDockerIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/codeartifact/aws4_request";
    private static final String DOMAIN = "maven-sidecar-domain";
    private static final String REPO = "maven-sidecar-repo";
    private static final String GAV = "com/example/spike/1.0.0/spike-1.0.0.jar";

    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        CodeArtifactMavenSidecarProfile.requireDockerAndTheSidecarImage();
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
    }

    @Test
    @Order(2)
    void missingArtifactAndMissingRepositoryAreNotFound() {
        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/" + REPO + "/does/not/exist.jar")
                .then().statusCode(404);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/no-such-repo/does/not/exist.jar")
                .then().statusCode(404);
    }

    @Test
    @Order(3)
    void repositoriesAreIsolatedFromEachOther() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/v1/repository?domain=" + DOMAIN + "&repository=other-repo")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bearerToken)
                .get("/codeartifact/maven/" + DOMAIN + "/other-repo/" + GAV)
                .then().statusCode(404);
    }

    @Test
    @Order(4)
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
    @Order(5)
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
    @Order(6)
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
    @Order(7)
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
    @Order(8)
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
    @Order(9)
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
}
