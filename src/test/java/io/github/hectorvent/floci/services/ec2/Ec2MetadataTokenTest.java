package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.MutableClock;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IMDSv2 session tokens, as documented in the EC2 User Guide: the PUT must carry a TTL between
 * 1 and 21,600 seconds (otherwise 400), and a GET that presents an invalid or expired token gets
 * 401 so the caller fetches a new token. A token is not valid on other instances.
 */
class Ec2MetadataTokenTest {

    private static final String TTL_HEADER = "X-aws-ec2-metadata-token-ttl-seconds";
    private static final String TOKEN_HEADER = "X-aws-ec2-metadata-token";
    private static final String CALLER_IP = "127.0.0.1";

    private final MutableClock clock = new MutableClock();
    private final HttpClient client = HttpClient.newHttpClient();
    private Vertx vertx;
    private Instance instance;
    private Ec2MetadataServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().imdsPort()).thenReturn(port);
        vertx = Vertx.vertx();
        server = new Ec2MetadataServer(vertx, config, null, clock);
        server.start().get(10, TimeUnit.SECONDS);
        endpoint = "http://127.0.0.1:" + port;

        instance = instance("i-0123456789abcdef0");
        server.registerContainer(CALLER_IP, instance.getInstanceId(), instance);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "21601", "-1", "sixty", ""})
    void tokenRequestWithTtlOutsideOneToSixHoursIsRejected(String ttl) throws Exception {
        assertEquals(400, putToken(ttl).statusCode());
    }

    @Test
    void tokenWithTheMaximumTtlIsIssued() throws Exception {
        HttpResponse<String> token = putToken("21600");

        assertEquals(200, token.statusCode());
        assertEquals("21600", token.headers().firstValue(TTL_HEADER).orElseThrow());
    }

    @Test
    void tokenIsAcceptedUntilItsTtlElapses() throws Exception {
        String token = putToken("60").body();
        clock.advance(Duration.ofSeconds(59));

        HttpResponse<String> response = getInstanceId(token);

        assertEquals(200, response.statusCode());
        assertEquals("i-0123456789abcdef0", response.body());
    }

    @Test
    void expiredTokenIsRejectedAsUnauthorized() throws Exception {
        String token = putToken("60").body();
        clock.advance(Duration.ofSeconds(60));

        assertEquals(401, getInstanceId(token).statusCode());
    }

    @Test
    void unknownTokenIsRejectedAsUnauthorized() throws Exception {
        assertEquals(401, getInstanceId("not-a-token").statusCode());
    }

    @Test
    void tokenPresentedFromAnotherInstanceIsRejectedAsUnauthorized() throws Exception {
        String token = putToken("60").body();
        Instance other = instance("i-0fedcba9876543210");
        server.unregisterContainer(CALLER_IP, instance);
        server.registerContainer(CALLER_IP, other.getInstanceId(), other);

        assertEquals(401, getInstanceId(token).statusCode());
    }

    @Test
    void tokenIdentifiesItsInstanceWhenTheCallerIpIsNotRegistered() throws Exception {
        String token = putToken("60").body();
        server.unregisterContainer(CALLER_IP, instance);

        HttpResponse<String> response = getInstanceId(token);

        assertEquals(200, response.statusCode());
        assertEquals("i-0123456789abcdef0", response.body());
    }

    @Test
    void requestWithoutTokenStillUsesImdsV1() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/latest/meta-data/instance-id")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
    }

    private static Instance instance(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        return instance;
    }

    private HttpResponse<String> putToken(String ttl) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                        .header(TTL_HEADER, ttl).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getInstanceId(String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/meta-data/instance-id"))
                        .header(TOKEN_HEADER, token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
