package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.Json;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pushes an image through the ECR API alone, as SDK-based copy tools do: a layer uploaded in parts,
 * a config blob, then {@code PutImage}. The result must be the image the registry data plane, and
 * so {@code docker pull}, serves, and a retag through {@code PutImage} must reach it too.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EcrImagePushApiDockerIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AmazonEC2ContainerRegistry_V20150921.";
    private static final int MIB = 1024 * 1024;
    private static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";

    private final String repo = "push-api/" + UUID.randomUUID().toString().substring(0, 8);
    private final String immutableRepo = repo + "-immutable";
    private final byte[] layer = randomBytes(5 * MIB + 1000);
    private final byte[] config = "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
    private final String layerDigest = "sha256:" + Digests.sha256Hex(layer);
    private final String configDigest = "sha256:" + Digests.sha256Hex(config);
    private String manifest;
    private String manifestDigest;

    @Inject
    Vertx vertx;

    private boolean created;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.ecr.registry-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Once, but per test: RestAssured has its port only once a test starts. */
    @BeforeEach
    void setUp() {
        if (created) {
            return;
        }
        created = true;
        call("CreateRepository", "{\"repositoryName\":\"" + repo + "\"}").statusCode(200);
        call("CreateRepository", "{\"repositoryName\":\"" + immutableRepo + "\",\"imageTagMutability\":\"IMMUTABLE\"}")
                .statusCode(200);
        manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "%s",
                  "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": %d, "digest": "%s"},
                  "layers": [{"mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip", "size": %d, "digest": "%s"}]
                }""".formatted(DOCKER_MANIFEST, config.length, configDigest, layer.length, layerDigest);
        manifestDigest = "sha256:" + Digests.sha256Hex(manifest.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Order(1)
    void aLayerUploadsInPartsThatMustFollowEachOther() {
        String uploadId = initiate(repo);
        byte[] first = Arrays.copyOfRange(layer, 0, 5 * MIB);
        byte[] rest = Arrays.copyOfRange(layer, 5 * MIB, layer.length);

        part(repo, uploadId, 0, first).statusCode(200).body("lastByteReceived", equalTo(5 * MIB - 1));
        part(repo, uploadId, 0, first).statusCode(400)
                .body("__type", equalTo("InvalidLayerPartException"))
                .body("lastValidByteReceived", equalTo(5 * MIB - 1));
        part(repo, uploadId, 5 * MIB, rest).statusCode(200).body("lastByteReceived", equalTo(layer.length - 1));

        complete(repo, uploadId, layerDigest).statusCode(200)
                .body("layerDigest", equalTo(layerDigest))
                .body("uploadId", equalTo(uploadId));
        complete(repo, uploadId, layerDigest).statusCode(400).body("__type", equalTo("UploadNotFoundException"));

        String configUpload = initiate(repo);
        part(repo, configUpload, 0, config).statusCode(200);
        complete(repo, configUpload, configDigest).statusCode(200);
    }

    @Test
    @Order(2)
    void aBadUploadIsRefused() {
        String again = initiate(repo);
        part(repo, again, 0, config).statusCode(200);
        complete(repo, again, configDigest).statusCode(400).body("__type", equalTo("LayerAlreadyExistsException"));

        String empty = initiate(repo);
        complete(repo, empty, layerDigest).statusCode(400).body("__type", equalTo("EmptyUploadException"));

        String small = initiate(repo);
        part(repo, small, 0, new byte[10]).statusCode(200);
        part(repo, small, 10, new byte[10]).statusCode(200);
        complete(repo, small, "sha256:" + Digests.sha256Hex(new byte[20])).statusCode(400)
                .body("__type", equalTo("LayerPartTooSmallException"));

        String mismatch = initiate(repo);
        part(repo, mismatch, 0, "other".getBytes(StandardCharsets.UTF_8)).statusCode(200);
        complete(repo, mismatch, "sha256:" + Digests.sha256Hex(new byte[1])).statusCode(400)
                .body("__type", equalTo("InvalidLayerException"));
    }

    @Test
    @Order(3)
    void uploadedLayersAreAvailable() {
        String missing = "sha256:" + "0".repeat(64);
        call("BatchCheckLayerAvailability", "{\"repositoryName\":\"" + repo + "\",\"layerDigests\":[\""
                + layerDigest + "\",\"" + configDigest + "\",\"" + missing + "\",\"bad\"]}")
                .statusCode(200)
                .body("layers", hasSize(2))
                .body("layers[0].layerDigest", equalTo(layerDigest))
                .body("layers[0].layerAvailability", equalTo("AVAILABLE"))
                .body("layers[0].layerSize", equalTo(layer.length))
                .body("layers[1].layerSize", equalTo(config.length))
                .body("failures.failureCode", hasItems("MissingLayerDigest", "InvalidLayerDigest"))
                .body("failures.layerDigest", hasItems(missing, "bad"));
    }

    @Test
    @Order(4)
    void putImageWritesTheManifestThatPullsServe() throws Exception {
        call("PutImage", putImage(repo, manifest, "v1")).statusCode(200)
                .body("image.imageId.imageTag", equalTo("v1"))
                .body("image.imageId.imageDigest", equalTo(manifestDigest))
                .body("image.imageManifestMediaType", equalTo(DOCKER_MANIFEST))
                .body("image.repositoryName", equalTo(repo));
        call("PutImage", putImage(repo, manifest, "v1")).statusCode(400)
                .body("__type", equalTo("ImageAlreadyExistsException"));

        String unknownLayer = manifest.replace(layerDigest, "sha256:" + "1".repeat(64));
        call("PutImage", putImage(repo, unknownLayer, "broken")).statusCode(400)
                .body("__type", equalTo("LayersNotFoundException"));

        Fetched pulled = registryGet("000000000000.dkr.ecr.us-east-1.localhost",
                "/v2/" + repo + "/manifests/v1", DOCKER_MANIFEST);
        assertEquals(200, pulled.statusCode());
        assertEquals(manifestDigest, pulled.digest());
    }

    @Test
    @Order(5)
    void aRetagReadsTheManifestAndPutsItUnderANewTag() {
        String read = call("BatchGetImage", "{\"repositoryName\":\"" + repo + "\",\"imageIds\":[{\"imageTag\":\"v1\"}]}")
                .statusCode(200)
                .extract().path("images[0].imageManifest");

        call("PutImage", putImage(repo, read, "release")).statusCode(200)
                .body("image.imageId.imageDigest", equalTo(manifestDigest));
        call("DescribeImages", "{\"repositoryName\":\"" + repo + "\",\"imageIds\":[{\"imageTag\":\"release\"}]}")
                .statusCode(200)
                .body("imageDetails[0].imageDigest", equalTo(manifestDigest));
    }

    @Test
    @Order(6)
    void theDownloadUrlServesTheLayer() throws Exception {
        String url = call("GetDownloadUrlForLayer", "{\"repositoryName\":\"" + repo + "\",\"layerDigest\":\""
                + layerDigest + "\"}")
                .statusCode(200)
                .body("layerDigest", equalTo(layerDigest))
                .extract().path("downloadUrl");
        URI uri = URI.create(url);
        assertEquals("/v2/" + repo + "/blobs/" + layerDigest, uri.getPath());

        Fetched response = registryGet(uri.getHost(), uri.getPath(), null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(layer, response.body().getBytes());

        call("GetDownloadUrlForLayer", "{\"repositoryName\":\"" + repo + "\",\"layerDigest\":\"sha256:"
                + "2".repeat(64) + "\"}")
                .statusCode(400).body("__type", equalTo("LayersNotFoundException"));
    }

    @Test
    @Order(7)
    void batchDeleteImageByTagLeavesTheSurvivingTagPullable() throws Exception {
        call("BatchDeleteImage", "{\"repositoryName\":\"" + repo + "\",\"imageIds\":["
                + "{\"imageTag\":\"v1\"},{\"imageTag\":\"nope\"}]}")
                .statusCode(200)
                .body("imageIds", hasSize(1))
                .body("imageIds[0].imageTag", equalTo("v1"))
                .body("imageIds[0].imageDigest", equalTo(manifestDigest))
                .body("failures", hasSize(1))
                .body("failures[0].imageId.imageTag", equalTo("nope"))
                .body("failures[0].failureCode", equalTo("ImageNotFound"));

        call("DescribeImages", "{\"repositoryName\":\"" + repo + "\",\"imageIds\":[{\"imageTag\":\"release\"}]}")
                .statusCode(200)
                .body("imageDetails[0].imageDigest", equalTo(manifestDigest))
                .body("imageDetails[0].imageTags", hasSize(1))
                .body("imageDetails[0].imageTags[0]", equalTo("release"));

        Fetched byTag = registryGet("000000000000.dkr.ecr.us-east-1.localhost",
                "/v2/" + repo + "/manifests/release", DOCKER_MANIFEST);
        assertEquals(200, byTag.statusCode());
        assertEquals(manifestDigest, byTag.digest());

        Fetched byDigest = registryGet("000000000000.dkr.ecr.us-east-1.localhost",
                "/v2/" + repo + "/manifests/" + manifestDigest, DOCKER_MANIFEST);
        assertEquals(200, byDigest.statusCode());
        assertEquals(manifestDigest, byDigest.digest());
    }

    @Test
    @Order(8)
    void batchDeleteImageByDigestRemovesTheManifestAndEveryTag() throws Exception {
        call("PutImage", putImage(repo, manifest, "v1")).statusCode(200)
                .body("image.imageId.imageDigest", equalTo(manifestDigest));

        call("BatchDeleteImage", "{\"repositoryName\":\"" + repo + "\",\"imageIds\":"
                + "[{\"imageDigest\":\"" + manifestDigest + "\"}]}")
                .statusCode(200)
                .body("imageIds", hasSize(2))
                .body("imageIds.imageTag", hasItems("v1", "release"))
                .body("imageIds.imageDigest", hasItems(manifestDigest, manifestDigest))
                .body("failures", hasSize(0));

        call("ListImages", "{\"repositoryName\":\"" + repo + "\"}")
                .statusCode(200)
                .body("imageIds", hasSize(0));

        Fetched byDigest = registryGet("000000000000.dkr.ecr.us-east-1.localhost",
                "/v2/" + repo + "/manifests/" + manifestDigest, DOCKER_MANIFEST);
        assertEquals(404, byDigest.statusCode());

        call("BatchGetImage", "{\"repositoryName\":\"" + repo + "\",\"imageIds\":[{\"imageTag\":\"release\"}]}")
                .statusCode(200)
                .body("images", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].imageId.imageTag", equalTo("release"))
                .body("failures[0].failureCode", equalTo("ImageNotFound"));
    }

    @Test
    @Order(9)
    void anImmutableTagIsWrittenOnce() {
        String upload = initiate(immutableRepo);
        part(immutableRepo, upload, 0, config).statusCode(200);
        complete(immutableRepo, upload, configDigest).statusCode(200);
        String first = """
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",\
                "config":{"mediaType":"application/vnd.oci.image.config.v1+json","size":%d,"digest":"%s"},\
                "layers":[]}""".formatted(config.length, configDigest);
        String second = first.replace("\"layers\":[]", "\"layers\":[],\"annotations\":{\"build\":\"2\"}");

        call("PutImage", putImage(immutableRepo, first, "v1")).statusCode(200);
        call("PutImage", putImage(immutableRepo, second, "v1")).statusCode(400)
                .body("__type", equalTo("ImageTagAlreadyExistsException"));
        call("PutImage", putImage(immutableRepo, second, "v2")).statusCode(200)
                .body("image.imageManifestMediaType", equalTo("application/vnd.oci.image.manifest.v1+json"));
    }

    /** Last, since every class on this profile shares one registry. */
    @Test
    @Order(10)
    void cleanUp() {
        call("DeleteRepository", "{\"repositoryName\":\"" + repo + "\",\"force\":true}").statusCode(200);
        call("DeleteRepository", "{\"repositoryName\":\"" + immutableRepo + "\",\"force\":true}").statusCode(200);
    }

    private String initiate(String repository) {
        return call("InitiateLayerUpload", "{\"repositoryName\":\"" + repository + "\"}")
                .statusCode(200)
                .body("partSize", equalTo(10 * MIB))
                .extract().path("uploadId");
    }

    private static ValidatableResponse part(String repository, String uploadId, long first, byte[] bytes) {
        return call("UploadLayerPart", "{\"repositoryName\":\"" + repository + "\",\"uploadId\":\"" + uploadId
                + "\",\"partFirstByte\":" + first + ",\"partLastByte\":" + (first + bytes.length - 1)
                + ",\"layerPartBlob\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}");
    }

    private static ValidatableResponse complete(String repository, String uploadId, String digest) {
        return call("CompleteLayerUpload", "{\"repositoryName\":\"" + repository + "\",\"uploadId\":\"" + uploadId
                + "\",\"layerDigests\":[\"" + digest + "\"]}");
    }

    private static String putImage(String repository, String imageManifest, String tag) {
        return "{\"repositoryName\":\"" + repository + "\",\"imageTag\":\"" + tag + "\",\"imageManifest\":"
                + Json.encode(imageManifest) + "}";
    }

    /** A GET through Floci's registry data plane, by the host name a Docker client would use. */
    private Fetched registryGet(String host, String path, String accept) throws Exception {
        int port = RestAssured.port;
        HttpClient client = vertx.createHttpClient();
        RequestOptions options = new RequestOptions()
                .setServer(SocketAddress.inetSocketAddress(port, "127.0.0.1"))
                .setHost(host)
                .setPort(port)
                .setMethod(HttpMethod.GET)
                .setURI(path);
        return client.request(options)
                .compose(request -> {
                    if (accept != null) {
                        request.putHeader("Accept", accept);
                    }
                    return request.send();
                })
                .compose(response -> response.body().map(body -> new Fetched(
                        response.statusCode(), response.getHeader("Docker-Content-Digest"), body)))
                .onComplete(ignored -> client.close())
                .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** A data plane response: its status, its {@code Docker-Content-Digest} and its body. */
    private record Fetched(int statusCode, String digest, Buffer body) {}

    private static ValidatableResponse call(String action, String body) {
        return given()
                .header("X-Amz-Target", PREFIX + action)
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(5735).nextBytes(bytes);
        return bytes;
    }
}
