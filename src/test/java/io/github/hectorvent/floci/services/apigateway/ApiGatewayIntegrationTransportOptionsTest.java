package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers the two REST integration transport settings AWS documents that Floci previously accepted
 * and ignored: {@code timeoutInMillis} and {@code tlsConfig.insecureSkipVerification}.
 *
 * <p>The TLS case matters because {@code HttpProxyInvoker} uses a default {@code HttpClient}: an
 * HTTPS backend presenting a self-signed certificate was unreachable with no way to opt out, which
 * is the common shape for an internal service behind a private CA.
 */
@QuarkusTest
class ApiGatewayIntegrationTransportOptionsTest {

    private static HttpServer slowServer;
    private static HttpsServer tlsServer;
    private static int slowPort;
    private static int tlsPort;

    private final List<String> createdApis = new ArrayList<>();

    @BeforeAll
    static void startBackends() throws Exception {
        slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowServer.createContext("/", ApiGatewayIntegrationTransportOptionsTest::slowHandler);
        slowServer.start();
        slowPort = slowServer.getAddress().getPort();

        tlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tlsServer.setHttpsConfigurator(new HttpsConfigurator(selfSignedContext()));
        tlsServer.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":\"ok\"}"));
        tlsServer.start();
        tlsPort = tlsServer.getAddress().getPort();
    }

    @AfterAll
    static void stopBackends() {
        if (slowServer != null) slowServer.stop(0);
        if (tlsServer != null) tlsServer.stop(0);
    }

    /** Sleeps when asked to, so a configured timeout can be observed firing. */
    private static void slowHandler(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("slow=true")) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(exchange, 200, "{\"from\":\"backend\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** An SSLContext serving a genuinely self-signed cert no default trust store will accept. */
    private static SSLContext selfSignedContext() throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = generator.generateSelfSignedCertificate(
                "localhost", List.of("localhost", "127.0.0.1"), KeyAlgorithm.RSA_2048);

        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        generated.certificatePem().getBytes(StandardCharsets.UTF_8)));

        // RSA keys are emitted as PKCS#1 ("RSA PRIVATE KEY"), not PKCS#8, so use the generator's
        // own parser rather than PKCS8EncodedKeySpec.
        PrivateKey privateKey = generator.parsePrivateKey(generated.privateKeyPem());

        char[] password = "floci-test".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("backend", privateKey, password, new Certificate[]{certificate});

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, null);
        return sslContext;
    }

    /** Builds a deployed REST API whose /{proxy+} ANY method is an HTTP_PROXY to {@code targetUri}. */
    private String createApi(String name, String targetUri, String integrationExtras) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"{proxy+}\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"ANY\",\"uri\":\"" + targetUri + "\""
                        + integrationExtras + "}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY/integration")
                .then().statusCode(201);

        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        return apiId;
    }

    @AfterEach
    void cleanup() {
        for (String apiId : createdApis) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
        createdApis.clear();
    }

    @Test
    void configuredTimeoutCutsOffASlowBackend() {
        String apiId = createApi("timeout-short", "http://127.0.0.1:" + slowPort + "/{proxy}",
                ",\"timeoutInMillis\":300");

        // Backend sleeps 2s; the 300ms integration timeout must fire and surface as 502.
        given().when().get("/execute-api/" + apiId + "/test/thing?slow=true")
                .then().statusCode(502);
    }

    @Test
    void aGenerousTimeoutLetsTheSameSlowBackendThrough() {
        String apiId = createApi("timeout-long", "http://127.0.0.1:" + slowPort + "/{proxy}",
                ",\"timeoutInMillis\":10000");

        given().when().get("/execute-api/" + apiId + "/test/thing?slow=true")
                .then().statusCode(200);
    }

    @Test
    void rejectsATimeoutBelowTheAwsMinimum() {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"timeout-invalid\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"GET\","
                        + "\"uri\":\"http://example.internal\",\"timeoutInMillis\":10}")
                .when().put("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET/integration")
                .then().statusCode(400);
    }

    @Test
    void selfSignedHttpsBackendIsRejectedWithoutTlsConfig() {
        String apiId = createApi("tls-verified", "https://localhost:" + tlsPort + "/{proxy}", "");

        // Default trust store cannot verify the backend's self-signed cert → 502 Bad Gateway.
        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(502);
    }

    @Test
    void insecureSkipVerificationReachesTheSelfSignedHttpsBackend() {
        String apiId = createApi("tls-insecure", "https://localhost:" + tlsPort + "/{proxy}",
                ",\"tlsConfig\":{\"insecureSkipVerification\":true}");

        given().when().get("/execute-api/" + apiId + "/test/thing")
                .then().statusCode(200)
                .body("tls", org.hamcrest.Matchers.equalTo("ok"));
    }
}
