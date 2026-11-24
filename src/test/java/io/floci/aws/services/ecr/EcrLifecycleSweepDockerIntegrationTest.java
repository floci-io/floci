package io.floci.aws.services.ecr;

import io.floci.aws.core.common.Digests;
import io.floci.aws.testing.EcrTestImages;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;

/**
 * A repository's lifecycle policy applied by the sweep against the real backing registry: images pushed through the
 * registry API as {@code docker push} sends them, the sweep run, and the expired image gone from the registry and from
 * {@code DescribeImages}.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class EcrLifecycleSweepDockerIntegrationTest {

    private static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";
    private static final String KEEP_ONE = "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"any\","
            + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1},\"action\":{\"type\":\"expire\"}}]}";

    @Inject
    EcrLifecycleScheduler scheduler;

    private final String repo = "lifecycle/" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.ecr.registry-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteRepository() {
        call("DeleteRepository", "{\"repositoryName\":\"" + repo + "\",\"force\":true}");
    }

    @Test
    void theSweepExpiresTheImagesTheRepositorysPolicySelects() {
        call("CreateRepository", "{\"repositoryName\":\"" + repo + "\"}").statusCode(200);
        String host = "000000000000.dkr.ecr.us-east-1.localhost:" + RestAssured.port;
        byte[] layer = ("layer " + repo).getBytes(StandardCharsets.UTF_8);
        String layerDigest = "sha256:" + Digests.sha256Hex(layer);
        EcrTestImages.uploadBlob(host, repo, layerDigest, layer);
        for (String tag : List.of("v1", "v2", "v3")) {
            push(host, tag, layer.length, layerDigest);
        }
        call("PutLifecyclePolicy", "{\"repositoryName\":\"" + repo + "\",\"lifecyclePolicyText\":"
                + quoted(KEEP_ONE) + "}").statusCode(200);
        call("GetLifecyclePolicy", "{\"repositoryName\":\"" + repo + "\"}").statusCode(200)
                .body("lastEvaluatedAt", equalTo(0));

        scheduler.tick(Instant.now());

        call("DescribeImages", "{\"repositoryName\":\"" + repo + "\"}").statusCode(200)
                .body("imageDetails", hasSize(1))
                .body("imageDetails[0].imageTags", equalTo(List.of("v3")));
        given().header("Host", host).accept(DOCKER_MANIFEST)
                .when().get("/v2/" + repo + "/manifests/v1")
                .then().statusCode(404);
        call("GetLifecyclePolicy", "{\"repositoryName\":\"" + repo + "\"}").statusCode(200)
                .body("lastEvaluatedAt", greaterThan(0));
        call("DeleteLifecyclePolicy", "{\"repositoryName\":\"" + repo + "\"}").statusCode(200)
                .body("lifecyclePolicyText", equalTo(KEEP_ONE))
                .body("lastEvaluatedAt", greaterThan(0));
    }

    /** One image per tag: each has its own config blob over the shared layer, so each has its own digest. */
    private void push(String host, String tag, int layerSize, String layerDigest) {
        byte[] config = ("{\"architecture\":\"amd64\",\"os\":\"linux\",\"config\":{\"Labels\":{\"tag\":\"" + tag
                + "\"}},\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[]}}").getBytes(StandardCharsets.UTF_8);
        String configDigest = "sha256:" + Digests.sha256Hex(config);
        EcrTestImages.uploadBlob(host, repo, configDigest, config);
        String manifest = """
                {"schemaVersion":2,"mediaType":"%s",
                "config":{"mediaType":"application/vnd.docker.container.image.v1+json","size":%d,"digest":"%s"},
                "layers":[{"mediaType":"application/vnd.docker.image.rootfs.diff.tar.gzip","size":%d,"digest":"%s"}]}
                """.formatted(DOCKER_MANIFEST, config.length, configDigest, layerSize, layerDigest);
        given().header("Host", host).contentType(DOCKER_MANIFEST).body(manifest)
                .when().put("/v2/" + repo + "/manifests/" + tag)
                .then().statusCode(201);
    }

    private static String quoted(String json) {
        return "\"" + json.replace("\"", "\\\"") + "\"";
    }

    private static ValidatableResponse call(String action, String body) {
        return given().header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/")
                .then();
    }
}
