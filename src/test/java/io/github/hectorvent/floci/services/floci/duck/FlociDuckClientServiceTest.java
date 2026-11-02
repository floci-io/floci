package io.github.hectorvent.floci.services.floci.duck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlociDuckClientServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void executeOmitsNullOutputPath() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/execute", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBody.set(mapper.readTree(body));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        FlociDuckManager duckManager = mock(FlociDuckManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(duckManager.ensureReady()).thenReturn("http://localhost:" + server.getAddress().getPort());
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.hostname()).thenReturn(Optional.of("floci.internal"));
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("127.0.0.1"));

        when(config.tls()).thenReturn(mock(EmulatorConfig.TlsConfig.class));

        FlociDuckClient client = new FlociDuckClient(duckManager, config,
                new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer, 4566), mapper);
        client.execute("CREATE VIEW example AS SELECT 1", "", null);

        assertEquals("CREATE VIEW example AS SELECT 1", requestBody.get().path("sql").asText());
        assertFalse(requestBody.get().has("output_s3_path"));
    }

    @Test
    void s3EndpointUsesListenPortNotAdvertisedPort() throws Exception {
        // docker run -p 4811:4566 -e FLOCI_BASE_URL=http://localhost:4811: 4811 exists only on the
        // host, so the sidecar must call Floci on the port it listens on.
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/execute", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        FlociDuckManager duckManager = mock(FlociDuckManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(duckManager.ensureReady()).thenReturn("http://localhost:" + server.getAddress().getPort());
        when(config.baseUrl()).thenReturn("http://localhost:4811");
        when(config.tls()).thenReturn(mock(EmulatorConfig.TlsConfig.class));
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        when(dockerHostResolver.resolve()).thenReturn("172.18.0.2");

        FlociDuckClient client = new FlociDuckClient(duckManager, config,
                new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer, 4566), mapper);
        client.execute("SELECT 1", "", null);

        assertEquals("http://172.18.0.2:4566", requestBody.get().path("s3_endpoint").asText());
    }

    @Test
    void executeInjectsSessionTokenDdlAndPassesCredentials() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/execute", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        FlociDuckManager duckManager = mock(FlociDuckManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(duckManager.ensureReady()).thenReturn("http://localhost:" + server.getAddress().getPort());
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.tls()).thenReturn(mock(EmulatorConfig.TlsConfig.class));
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        when(dockerHostResolver.resolve()).thenReturn("127.0.0.1");

        FlociDuckClient client = new FlociDuckClient(duckManager, config,
                new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer, 4566), mapper);
        client.execute("SELECT 1", "INSTALL iceberg;", "s3://bucket/out.csv",
                "ASIAEXAMPLE", "secretKey123", "tokenXYZ");

        JsonNode body = requestBody.get();
        assertEquals("ASIAEXAMPLE", body.path("s3_access_key").asText());
        assertEquals("secretKey123", body.path("s3_secret_key").asText());
        assertEquals("s3://bucket/out.csv", body.path("output_s3_path").asText());
        String setupSql = body.path("setup_sql").asText();
        assertTrue(setupSql.startsWith("SET s3_session_token = 'tokenXYZ';\n"));
        assertTrue(setupSql.contains("INSTALL iceberg;"));
    }

    @Test
    void queryInjectsSessionTokenDdlAndPassesCredentials() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            String response = "{\"status\": \"success\", \"rows\": [{\"col\": 1}]}";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();

        FlociDuckManager duckManager = mock(FlociDuckManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(duckManager.ensureReady()).thenReturn("http://localhost:" + server.getAddress().getPort());
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.tls()).thenReturn(mock(EmulatorConfig.TlsConfig.class));
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        when(dockerHostResolver.resolve()).thenReturn("127.0.0.1");

        FlociDuckClient client = new FlociDuckClient(duckManager, config,
                new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer, 4566), mapper);
        client.query("SELECT 1 AS col", "", "ASIAEXAMPLE", "secretKey123", "tokenXYZ");

        JsonNode body = requestBody.get();
        assertEquals("ASIAEXAMPLE", body.path("s3_access_key").asText());
        assertEquals("secretKey123", body.path("s3_secret_key").asText());
        String setupSql = body.path("setup_sql").asText();
        assertEquals("SET s3_session_token = 'tokenXYZ';\n", setupSql);
    }

    @Test
    void sessionTokenQuotesAreEscaped() {
        assertEquals("SET s3_session_token = 'a''b';\nINSTALL x;",
                FlociDuckClient.injectSessionToken("INSTALL x;", "a'b"));
    }
}
