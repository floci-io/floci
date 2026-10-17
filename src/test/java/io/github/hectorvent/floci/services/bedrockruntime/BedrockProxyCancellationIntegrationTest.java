package io.github.hectorvent.floci.services.bedrockruntime;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(BedrockProxyCancellationIntegrationTest.Profile.class)
class BedrockProxyCancellationIntegrationTest {
    private static final int PORT = 18939;
    @TestHTTPResource
    URI base;

    @Test
    void disconnectClosesUpstreamWithoutAnotherToken() throws Exception {
        String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n";
        String firstToken = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(chunk.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + chunk + "\r\n";
        try (StallingUpstream upstream = new StallingUpstream(PORT, firstToken);
             HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/model/fixture/converse-stream"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request")
                .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}"))
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertTrue(body.read() >= 0);
            }
            assertTrue(upstream.awaitClosed(5, TimeUnit.SECONDS), "Client disconnect must close the stalled upstream");
        }
    }

    @Test
    void nonStreamingDisconnectBeforeHeadersClosesUpstream() throws Exception {
        disconnectWhileUpstreamStalls("converse", false);
    }

    @Test
    void streamingDisconnectBeforeHeadersClosesUpstream() throws Exception {
        disconnectWhileUpstreamStalls("converse-stream", false);
    }

    @Test
    void nonStreamingDisconnectDuringBodyClosesUpstream() throws Exception {
        disconnectWhileUpstreamStalls("converse", true);
    }

    private void disconnectWhileUpstreamStalls(String route, boolean partialResponse) throws Exception {
        String prefix = partialResponse
                ? "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{"
                : "";
        try (StallingUpstream upstream = new StallingUpstream(PORT, prefix);
             Socket frontend = new Socket(base.getHost(), base.getPort())) {
            String body = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}";
            String request = "POST /model/fixture/" + route + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Authorization: AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
            frontend.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            frontend.getOutputStream().flush();
            assertTrue(upstream.awaitResponded(5, TimeUnit.SECONDS), "Upstream must receive the request before disconnect");
            frontend.close();
            assertTrue(upstream.awaitClosed(5, TimeUnit.SECONDS), "Disconnect must close the stalled upstream request");
        }
    }

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.bedrock-runtime.backend", "proxy",
                "floci.services.bedrock-runtime.proxy.url", "http://127.0.0.1:" + PORT + "/v1",
                "floci.services.bedrock-runtime.proxy.passthrough", "true",
                "floci.services.bedrock-runtime.proxy.error-body-timeout-millis", "100");
        }
    }
}
