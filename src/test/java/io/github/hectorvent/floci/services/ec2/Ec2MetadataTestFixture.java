package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.vertx.core.Vertx;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class Ec2MetadataTestFixture implements AutoCloseable {

    static final String TTL_HEADER = "X-aws-ec2-metadata-token-ttl-seconds";
    static final String TOKEN_HEADER = "X-aws-ec2-metadata-token";
    static final String CALLER_IP = "127.0.0.1";

    private final MutableClock clock = new MutableClock();
    private final HttpClient client = HttpClient.newHttpClient();
    private final Instance instance = instance("i-0123456789abcdef0");
    private Vertx vertx;
    private Ec2MetadataServer server;
    private String endpoint;

    void start() throws Exception {
        start(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), null, instance, clock);
    }

    void start(IamService iam, EmulatorConfig config, Instance registeredInstance) throws Exception {
        start(config, iam, registeredInstance, Clock.systemUTC());
    }

    void startUnregistered(EmulatorConfig config) throws Exception {
        start(config, null, null, Clock.systemUTC());
    }

    private void start(EmulatorConfig config, IamService iam, Instance registeredInstance,
                       Clock serverClock) throws Exception {
        int port = FreePorts.anyInterfacePort();
        when(config.services().ec2().imdsPort()).thenReturn(port);
        vertx = Vertx.vertx();
        server = new Ec2MetadataServer(vertx, config, iam, serverClock);
        server.start().get(10, TimeUnit.SECONDS);
        endpoint = "http://127.0.0.1:" + port;
        if (registeredInstance != null) {
            server.registerContainer(CALLER_IP, registeredInstance.getInstanceId(), registeredInstance);
        }
    }

    @Override
    public void close() throws Exception {
        try (client) {
            try {
                if (server != null) {
                    server.stop();
                }
            } finally {
                if (vertx != null) {
                    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    MutableClock clock() {
        return clock;
    }

    Ec2MetadataServer server() {
        return server;
    }

    Instance instance() {
        return instance;
    }

    static Instance instance(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        return instance;
    }

    HttpResponse<String> putToken(String ttl) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                        .timeout(Duration.ofSeconds(5)).header(TTL_HEADER, ttl).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        return get(path, token, HttpResponse.BodyHandlers.ofString());
    }

    <T> HttpResponse<T> get(String path, String token, HttpResponse.BodyHandler<T> bodyHandler) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(5));
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        return client.send(request.GET().build(), bodyHandler);
    }
}
