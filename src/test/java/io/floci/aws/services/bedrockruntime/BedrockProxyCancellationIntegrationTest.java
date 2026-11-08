package io.floci.aws.services.bedrockruntime;

import io.floci.aws.testing.BedrockProxyBackendProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.BedrockProxyBackendProfile.PORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a caller who disconnects closes the upstream request, whether the upstream has
 * sent nothing yet, part of a response, or one streamed token.
 *
 * <p>The backend URL and passthrough come from {@link BedrockProxyBackendProfile}, which this class
 * shares with the other proxy classes that talk to a stand-in. The {@code fixture} model id is not
 * in that profile's mapping, so it reaches the stand-in through passthrough. The profile keeps the
 * default 60-second request timeout, far longer than these tests wait, so only the disconnect can
 * close an upstream that stalls before its headers.
 */
@QuarkusTest
@TestProfile(BedrockProxyBackendProfile.class)
class BedrockProxyCancellationIntegrationTest {
    @TestHTTPResource
    URI base;

    @Test
    void disconnectClosesUpstreamWithoutAnotherToken() throws Exception {
        String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n";
        String firstToken = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(chunk.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + chunk + "\r\n";
        try (StallingUpstream upstream = new StallingUpstream(PORT, firstToken);
             HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<InputStream> response = client.send(ConverseRequests.to(base, "converse-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
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
            String body = ConverseRequests.BODY;
            String request = "POST /model/fixture/" + route + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Authorization: " + ConverseRequests.AUTHORIZATION + "\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
            frontend.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            frontend.getOutputStream().flush();
            assertTrue(upstream.awaitResponded(5, TimeUnit.SECONDS), "Upstream must receive the request before disconnect");
            frontend.close();
            assertTrue(upstream.awaitClosed(5, TimeUnit.SECONDS), "Disconnect must close the stalled upstream request");
        }
    }
}
