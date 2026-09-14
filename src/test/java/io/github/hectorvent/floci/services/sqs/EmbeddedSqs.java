package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Experimental test fixture: same-JVM SQS JSON server, with no Quarkus bootstrap. */
public final class EmbeddedSqs implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(EmbeddedSqs.class);
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final SqsService service;
    private final SqsJsonHandler handler;
    private final RegionResolver regions = new RegionResolver("us-east-1", "000000000000");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final URI endpoint;

    private EmbeddedSqs() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("embedded-sqs-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        service = SqsServiceFactory.createInMemory(endpoint.toString(), regions);
        handler = new SqsJsonHandler(service, mapper);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public static EmbeddedSqs start() throws IOException {
        return new EmbeddedSqs();
    }

    public URI endpoint() {
        return endpoint;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
            if (!"POST".equals(exchange.getRequestMethod()) || target == null || !target.startsWith("AmazonSQS.")) {
                send(exchange, 400, Map.of("__type", "UnsupportedOperation", "message", "SQS JSON requests required"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                send(exchange, 413, Map.of("__type", "RequestEntityTooLarge", "message", "POC request limit exceeded"));
                return;
            }
            JsonNode request;
            try {
                request = mapper.readTree(body);
            } catch (IOException invalidJson) {
                send(exchange, 400, Map.of("__type", "InvalidParameterValue", "message", "Invalid JSON"));
                return;
            }
            if (request == null || !request.isObject()) {
                send(exchange, 400, Map.of("__type", "InvalidParameterValue", "message", "JSON object required"));
                return;
            }
            try (Response response = handler.handle(target.substring("AmazonSQS.".length()), request,
                    regions.resolveRegionFromAuth(exchange.getRequestHeaders().getFirst("Authorization")))) {
                send(exchange, response.getStatus(), response.getEntity());
            } catch (AwsException error) {
                send(exchange, error.getHttpStatus(), Map.of("__type", error.getErrorCode(), "message", error.getMessage()));
            } catch (Exception error) {
                LOG.errorv(error, "Embedded SQS handler failed for {0}", target);
                send(exchange, 500, Map.of("__type", "InternalError", "message", "Embedded handler failed"));
            }
        }
    }

    private void send(HttpExchange exchange, int status, Object entity) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(entity == null ? Map.of() : entity);
        exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.0");
        exchange.getResponseHeaders().set("x-amzn-RequestId", UUID.randomUUID().toString());
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            service.clear();
            service.stop();
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Embedded SQS workers did not stop");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing embedded SQS", interrupted);
            }
        }
    }
}
