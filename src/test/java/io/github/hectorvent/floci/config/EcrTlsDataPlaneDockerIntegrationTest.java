package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator.GeneratedCertificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-gated integration test verifying OCI Distribution image push and pull
 * over TLS against the ECR registry data plane, including hostname verification
 * of the regional wildcard SANs and regression coverage for plain HTTP.
 */
@QuarkusTest
@TestProfile(EcrTlsDataPlaneDockerIntegrationTest.Profile.class)
class EcrTlsDataPlaneDockerIntegrationTest {

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int sslPort;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @Inject
    Vertx vertx;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for ECR data plane tests");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void pushAndPullImageOverTlsAndVerifyPlainHttpRegression() throws Exception {
        String tlsHost = "000000000000.dkr.ecr.us-east-1.localhost.floci.io";
        HttpClient tlsClient = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true)
                .setForceSni(true)
                .setTrustOptions(new PemTrustOptions().addCertPath(
                        FlociCertificateAuthority.loadOrCreate(Profile.TLS_DIR).certificatePath().toString()))
                .setVerifyHost(true));

        try {
            // 1. Verify registry ping over TLS with hostname verification
            SimpleResponse pingResponse = execute(tlsClient, HttpMethod.GET, sslPort, tlsHost, "/v2/", null, null, null);
            assertEquals(200, pingResponse.statusCode());
            assertEquals("registry/2.0", pingResponse.header("Docker-Distribution-Api-Version"));

            // 2. Upload layer blob over TLS
            byte[] layerBytes = "floci-ecr-tls-layer-content-v1".getBytes(StandardCharsets.UTF_8);
            String layerDigest = "sha256:" + sha256Hex(layerBytes);

            SimpleResponse startLayerUpload = execute(tlsClient, HttpMethod.POST, sslPort, tlsHost,
                    "/v2/tls-repo/blobs/uploads/", null, null, null);
            assertEquals(202, startLayerUpload.statusCode());
            String layerUploadLocation = startLayerUpload.header("Location");
            assertNotNull(layerUploadLocation);

            String layerUploadUri = layerUploadLocation + (layerUploadLocation.contains("?") ? "&" : "?")
                    + "digest=" + layerDigest;
            SimpleResponse finishLayerUpload = execute(tlsClient, HttpMethod.PUT, sslPort, tlsHost,
                    layerUploadUri, "application/octet-stream", null, Buffer.buffer(layerBytes));
            assertEquals(201, finishLayerUpload.statusCode());

            // 3. Upload config blob over TLS
            byte[] configBytes = "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
            String configDigest = "sha256:" + sha256Hex(configBytes);

            SimpleResponse startConfigUpload = execute(tlsClient, HttpMethod.POST, sslPort, tlsHost,
                    "/v2/tls-repo/blobs/uploads/", null, null, null);
            assertEquals(202, startConfigUpload.statusCode());
            String configUploadLocation = startConfigUpload.header("Location");
            assertNotNull(configUploadLocation);

            String configUploadUri = configUploadLocation + (configUploadLocation.contains("?") ? "&" : "?")
                    + "digest=" + configDigest;
            SimpleResponse finishConfigUpload = execute(tlsClient, HttpMethod.PUT, sslPort, tlsHost,
                    configUploadUri, "application/octet-stream", null, Buffer.buffer(configBytes));
            assertEquals(201, finishConfigUpload.statusCode());

            // 4. Put manifest over TLS
            String manifestJson = """
                    {
                      "schemaVersion": 2,
                      "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                      "config": {
                        "mediaType": "application/vnd.docker.container.image.v1+json",
                        "size": %d,
                        "digest": "%s"
                      },
                      "layers": [
                        {
                          "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                          "size": %d,
                          "digest": "%s"
                        }
                      ]
                    }
                    """.formatted(configBytes.length, configDigest, layerBytes.length, layerDigest);

            SimpleResponse putManifestResponse = execute(tlsClient, HttpMethod.PUT, sslPort, tlsHost,
                    "/v2/tls-repo/manifests/v1",
                    "application/vnd.docker.distribution.manifest.v2+json",
                    null,
                    Buffer.buffer(manifestJson));
            assertEquals(201, putManifestResponse.statusCode());
            String manifestDigest = putManifestResponse.header("Docker-Content-Digest");
            assertNotNull(manifestDigest);

            // 5. Pull manifest over TLS
            String manifestMediaType = "application/vnd.docker.distribution.manifest.v2+json";
            SimpleResponse getManifestResponse = execute(tlsClient, HttpMethod.GET, sslPort, tlsHost,
                    "/v2/tls-repo/manifests/v1", null, manifestMediaType, null);
            assertEquals(200, getManifestResponse.statusCode());
            assertEquals(manifestDigest, getManifestResponse.header("Docker-Content-Digest"));

            // 6. Pull layer blob over TLS
            SimpleResponse getLayerResponse = execute(tlsClient, HttpMethod.GET, sslPort, tlsHost,
                    "/v2/tls-repo/blobs/" + layerDigest, null, null, null);
            assertEquals(200, getLayerResponse.statusCode());
            assertArrayEquals(layerBytes, getLayerResponse.body().getBytes());

            // 7. List tags over TLS
            SimpleResponse getTagsResponse = execute(tlsClient, HttpMethod.GET, sslPort, tlsHost,
                    "/v2/tls-repo/tags/list", null, null, null);
            assertEquals(200, getTagsResponse.statusCode());
            assertTrue(getTagsResponse.body().toString().contains("v1"));

            // 8. Plain HTTP regression: verify the plain HTTP path still works on port
            HttpClient httpClient = vertx.createHttpClient(new HttpClientOptions().setSsl(false));
            try {
                String httpHost = "000000000000.dkr.ecr.us-east-1.localhost:" + httpPort;
                SimpleResponse httpPing = execute(httpClient, HttpMethod.GET, httpPort, httpHost,
                        "/v2/", null, null, null);
                assertEquals(200, httpPing.statusCode());
                assertEquals("registry/2.0", httpPing.header("Docker-Distribution-Api-Version"));

                SimpleResponse httpManifestGet = execute(httpClient, HttpMethod.GET, httpPort, httpHost,
                        "/v2/tls-repo/manifests/v1", null, manifestMediaType, null);
                assertEquals(200, httpManifestGet.statusCode());
                assertEquals(manifestDigest, httpManifestGet.header("Docker-Content-Digest"));
            } finally {
                httpClient.close();
            }
        } finally {
            tlsClient.close();
        }
    }

    private SimpleResponse execute(HttpClient client, HttpMethod method, int port, String host,
                                   String uri, String contentType, String accept, Buffer body) throws Exception {
        String pureHost = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        RequestOptions options = new RequestOptions()
                .setServer(SocketAddress.inetSocketAddress(port, "127.0.0.1"))
                .setHost(pureHost)
                .setPort(port)
                .setMethod(method)
                .setURI(uri);

        return client.request(options)
                .compose(request -> {
                    request.putHeader("Host", host);
                    if (contentType != null) {
                        request.putHeader("Content-Type", contentType);
                    }
                    if (accept != null) {
                        request.putHeader("Accept", accept);
                    }
                    if (body != null) {
                        request.putHeader("Content-Length", String.valueOf(body.length()));
                        return request.send(body);
                    }
                    return request.send();
                })
                .compose(response -> response.body().map(responseBody ->
                        new SimpleResponse(response.statusCode(), response.headers(), responseBody)))
                .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private record SimpleResponse(int statusCode, MultiMap headers, Buffer body) {
        String header(String name) {
            return headers.get(name);
        }
    }

    public static final class Profile implements QuarkusTestProfile {

        static final Path DATA_DIR = Path.of("target", "floci-ecr-tls-dataplane-test").toAbsolutePath();
        static final Path TLS_DIR = DATA_DIR.resolve("tls");
        static final Path CERT_FILE = TLS_DIR.resolve("floci-server.crt");
        static final Path KEY_FILE = TLS_DIR.resolve("floci-server.key");

        static {
            try {
                Files.createDirectories(TLS_DIR);
                FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(TLS_DIR);
                List<String> sans = new ArrayList<>(TlsConfigSource.DEFAULT_SAN_HOSTNAMES);
                GeneratedCertificate leaf = ca.issueServerCertificate("localhost", sans, KeyAlgorithm.RSA_2048, null);
                Files.writeString(CERT_FILE, leaf.certificatePem());
                Files.writeString(KEY_FILE, leaf.privateKeyPem());
                ContainerCaBundle.write(TLS_DIR, ca.certificatePath());
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("Failed to prepare TLS test files", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.tls.enabled", "true",
                    "floci.tls.self-signed", "true",
                    "floci.tls.aws-https-port", "0",
                    "floci.storage.persistent-path", DATA_DIR.toString(),
                    "quarkus.tls.key-store.pem.0.cert", CERT_FILE.toString(),
                    "quarkus.tls.key-store.pem.0.key", KEY_FILE.toString(),
                    "quarkus.http.insecure-requests", "enabled"
            );
        }
    }
}
