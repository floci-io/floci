package io.floci.aws.services.lambda.launcher.kubernetes;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesInvocationLogTailTest {

    @Test
    void invocationLogsUseABoundedNonFollowingSnapshotAndLeaveStreamingAvailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<URI> requested = new AtomicReference<>();
        byte[] logs = ("界".repeat(3000) + "\nlast line\n").getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requested.set(exchange.getRequestURI());
            exchange.sendResponseHeaders(200, logs.length);
            try (exchange) {
                exchange.getResponseBody().write(logs);
            }
        });
        server.start();
        try (HttpClient http = HttpClient.newHttpClient()) {
            KubernetesApiClient client = new KubernetesApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), http, () -> "test-token");
            Instant since = Instant.parse("2026-11-16T19:00:00.123456789Z");

            byte[] tail = Base64.getDecoder().decode(client.readPodLogTail("functions", "worker", since));

            assertArrayEquals(Arrays.copyOfRange(logs, logs.length - 4096, logs.length), tail);
            assertEquals("/api/v1/namespaces/functions/pods/worker/log", requested.get().getPath());
            String query = URLDecoder.decode(requested.get().getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("container=runtime"), query);
            assertTrue(query.contains("follow=false"), query);
            assertTrue(query.contains("tailLines=4096"), query);
            assertTrue(query.contains("sinceTime=" + since), query);

            try (InputStream stream = client.openPodLogStream("functions", "worker", "runtime")) {
                assertArrayEquals(logs, stream.readAllBytes());
            }
            assertEquals("container=runtime&follow=true", requested.get().getRawQuery());
        } finally {
            server.stop(0);
        }
    }
}
