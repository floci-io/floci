package io.floci.aws.services.lambda.launcher.kubernetes;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesInvocationLogTailTest {

    @Test
    void invocationLogsUseABoundedNonFollowingSnapshotAndLeaveStreamingAvailable() throws Exception {
        AtomicReference<URI> requested = new AtomicReference<>();
        byte[] logs = ("界".repeat(3000) + "\nlast line\n").getBytes(StandardCharsets.UTF_8);
        byte[] timestamped = ("2026-11-16T19:00:00.123456790Z " + "界".repeat(3000)
                + "\n2026-11-16T19:00:00.123456791Z last line\n").getBytes(StandardCharsets.UTF_8);
        HttpServer server = serveLogs(uri -> uri.getRawQuery().contains("timestamps=true") ? timestamped : logs, requested);
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
            assertTrue(query.contains("timestamps=true"), query);
            assertTrue(query.contains("sinceTime=2026-11-16T19:00:00Z"), query);

            try (InputStream stream = client.openPodLogStream("functions", "worker", "runtime")) {
                assertArrayEquals(logs, stream.readAllBytes());
            }
            assertEquals("container=runtime&follow=true", requested.get().getRawQuery());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientFilteringRemovesEarlierRecordsWithinTheSameSecond() throws Exception {
        byte[] logs = ("2026-11-16T19:00:00.123456788Z previous invocation\n"
                + "2026-11-16T19:00:00.123456789Z current invocation\n").getBytes(StandardCharsets.UTF_8);
        HttpServer server = serveLogs(uri -> logs, new AtomicReference<>());
        try (HttpClient http = HttpClient.newHttpClient()) {
            KubernetesApiClient client = new KubernetesApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), http, () -> "test-token");

            String tail = client.readPodLogTail("functions", "worker", Instant.parse("2026-11-16T19:00:00.123456789Z"));

            assertEquals("current invocation\n", new String(Base64.getDecoder().decode(tail), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2000-01-01T00:00:00Z", "2099-01-01T00:00:00Z"})
    void warmInvocationUsesTheKubeletsClock(String sourceTime) throws Exception {
        Instant previous = Instant.parse(sourceTime);
        String earlier = previous + " previous invocation\n";
        String current = previous.plusNanos(1) + " current invocation\n";
        HttpServer server = serveLogs(uri -> (uri.getRawQuery().contains("tailLines=1&")
                ? earlier : earlier + current).getBytes(StandardCharsets.UTF_8), new AtomicReference<>());
        try (HttpClient http = HttpClient.newHttpClient()) {
            KubernetesApiClient client = new KubernetesApiClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), http, () -> "test-token");

            String logs = client.capturePodLogTail("functions", "worker", false).get();

            assertEquals("current invocation\n", new String(Base64.getDecoder().decode(logs), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer serveLogs(Function<URI, byte[]> response, AtomicReference<URI> requested) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.set(exchange.getRequestURI());
            byte[] logs = response.apply(exchange.getRequestURI());
            exchange.sendResponseHeaders(200, logs.length);
            try (exchange) {
                exchange.getResponseBody().write(logs);
            }
        });
        server.start();
        return server;
    }
}
