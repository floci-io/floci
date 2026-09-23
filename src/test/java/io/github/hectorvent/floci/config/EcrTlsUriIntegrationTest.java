package io.github.hectorvent.floci.config;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.acm.CertificateGenerator.GeneratedCertificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;

@QuarkusTest
@TestProfile(EcrTlsUriIntegrationTest.Profile.class)
class EcrTlsUriIntegrationTest {

    static final String ACCOUNT = "123456789012";
    static final String REGION = "eu-west-1";
    static final int ADVERTISED_PORT = 4566;
    private static final String MANIFEST = "{\"schemaVersion\":2}";
    private static final String DIGEST = "sha256:" + "1".repeat(64);

    @ConfigProperty(name = "quarkus.http.test-ssl-port")
    int sslPort;

    @Inject
    Vertx vertx;

    @InjectSpy
    EcrRegistryManager registryManager;

    private final BlockingQueue<String> backendRequests = new LinkedBlockingQueue<>();
    private HttpServer backend;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void startRegistryBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/v2/", exchange -> {
            backendRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getResponseHeaders().set("Docker-Distribution-Api-Version", "registry/2.0");
            exchange.getResponseHeaders().set("Docker-Content-Digest", DIGEST);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.oci.image.manifest.v1+json");
            byte[] body = MANIFEST.getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        backend.start();
        // Only container startup and its HTTP endpoint are replaced. URI generation stays real.
        doNothing().when(registryManager).ensureStarted();
        doReturn(true).when(registryManager).tryEnsureStarted();
        doReturn(new RegistryHttpClient("http://127.0.0.1:" + backend.getAddress().getPort()))
                .when(registryManager).httpClient();
    }

    @AfterEach
    void stopRegistryBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    void advertisedRepositoryAndLoginEndpointReachTheRegistryWithVerifiedTls() throws Exception {
        String repository = "tls-uri/team-" + Long.toString(System.nanoTime(), 36);
        String expectedUri = expectedRepositoryUri(repository);
        String repositoryUri = request("CreateRepository", Map.of("repositoryName", repository))
                .body("repository.repositoryUri", equalTo(expectedUri))
                .body("repository.registryId", equalTo(ACCOUNT))
                .extract().path("repository.repositoryUri");
        request("DescribeRepositories", Map.of("repositoryNames", new String[]{repository}))
                .body("repositories[0].repositoryUri", equalTo(repositoryUri));

        URI image = URI.create("https://" + repositoryUri);
        String proxyEndpoint = request("GetAuthorizationToken", Map.of())
                .body("authorizationData[0].proxyEndpoint", equalTo("https://" + image.getAuthority()))
                .extract().path("authorizationData[0].proxyEndpoint");
        URI login = URI.create(proxyEndpoint);
        assertEquals("", login.getPath(), "Docker login uses the registry authority without a repository path");
        assertEquals(ADVERTISED_PORT, image.getPort());

        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true)
                .setForceSni(true)
                .setTrustOptions(new PemTrustOptions().addCertPath(
                        FlociCertificateAuthority.loadOrCreate(Profile.TLS_DIR).certificatePath().toString()))
                .setVerifyHost(true));
        try {
            RegistryResponse ping = execute(client, HttpMethod.GET, login, "/v2/");
            assertEquals(200, ping.status());
            assertEquals("GET /v2/", backendRequests.poll(5, TimeUnit.SECONDS));

            String manifestPath = "/v2" + image.getPath() + "/manifests/latest";
            RegistryResponse manifest = execute(client, HttpMethod.GET, image, manifestPath);
            assertEquals(200, manifest.status());
            assertEquals(MANIFEST, manifest.body());
            assertEquals(DIGEST, manifest.digest());
            String storagePath = "/v2/" + ACCOUNT + "/" + REGION + "/" + repository + "/manifests/latest";
            assertEquals("GET " + storagePath, backendRequests.poll(5, TimeUnit.SECONDS));

            RegistryResponse head = execute(client, HttpMethod.HEAD, image, manifestPath);
            assertEquals(200, head.status());
            assertEquals(DIGEST, head.digest());
            assertEquals("HEAD " + storagePath, backendRequests.poll(5, TimeUnit.SECONDS));
        } finally {
            client.close();
        }
    }

    String expectedRepositoryUri(String repository) {
        return ACCOUNT + ".dkr.ecr." + REGION + ".localhost.floci.io:" + ADVERTISED_PORT + "/" + repository;
    }

    private ValidatableResponse request(String action, Map<String, Object> body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260923/" + REGION + "/ecr/aws4_request")
                .body(body)
                .when().post("/")
                .then().statusCode(200);
    }

    private RegistryResponse execute(HttpClient client, HttpMethod method, URI advertised, String path)
            throws Exception {
        // Quarkus tests bind a random TLS port, while the production proxy uses fixed backend ports.
        // Override only the transport destination; retain the advertised authority and TLS hostname.
        RequestOptions options = new RequestOptions()
                .setServer(SocketAddress.inetSocketAddress(sslPort, "127.0.0.1"))
                .setHost(advertised.getHost())
                .setPort(advertised.getPort())
                .setMethod(method)
                .setURI(path);
        return client.request(options)
                .compose(request -> request.send())
                .compose(response -> response.body().map(body -> new RegistryResponse(
                        response.statusCode(), body.toString(), response.getHeader("Docker-Content-Digest"))))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record RegistryResponse(int status, String body, String digest) {}

    public static class Profile implements QuarkusTestProfile {

        static final Path DATA_DIR = Path.of("target", "floci-ecr-tls-uri-test").toAbsolutePath();
        static final Path TLS_DIR = DATA_DIR.resolve("tls");

        static {
            try {
                Files.createDirectories(TLS_DIR);
                FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(TLS_DIR);
                GeneratedCertificate leaf = ca.issueServerCertificate(
                        "localhost", TlsConfigSource.DEFAULT_SAN_HOSTNAMES, KeyAlgorithm.RSA_2048, null);
                Files.writeString(TLS_DIR.resolve("floci-server.crt"), leaf.certificatePem());
                Files.writeString(TLS_DIR.resolve("floci-server.key"), leaf.privateKeyPem());
            } catch (IOException e) {
                throw new IllegalStateException("Could not prepare ECR TLS URI test certificates", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("floci.services.ecr.tls-uri", "true"),
                    Map.entry("floci.services.ecr.uri-style", "hostname"),
                    Map.entry("floci.default-account-id", ACCOUNT),
                    Map.entry("floci.default-region", "us-east-1"),
                    Map.entry("floci.port", String.valueOf(ADVERTISED_PORT)),
                    Map.entry("floci.tls.enabled", "true"),
                    Map.entry("floci.tls.aws-https-port", "0"),
                    Map.entry("floci.storage.persistent-path", DATA_DIR.toString()),
                    Map.entry("quarkus.tls.key-store.pem.0.cert", TLS_DIR.resolve("floci-server.crt").toString()),
                    Map.entry("quarkus.tls.key-store.pem.0.key", TLS_DIR.resolve("floci-server.key").toString()),
                    Map.entry("quarkus.http.test-ssl-port", "0"),
                    Map.entry("quarkus.http.insecure-requests", "enabled"));
        }
    }
}
