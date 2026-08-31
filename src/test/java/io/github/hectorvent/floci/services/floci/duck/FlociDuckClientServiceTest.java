package io.github.hectorvent.floci.services.floci.duck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

        FlociDuckClient client = new FlociDuckClient(
                duckManager, config, embeddedDnsServer, dockerHostResolver, mapper);
        client.execute("CREATE VIEW example AS SELECT 1", "", null);

        assertEquals("CREATE VIEW example AS SELECT 1", requestBody.get().path("sql").asText());
        assertFalse(requestBody.get().has("output_s3_path"));
    }
}
