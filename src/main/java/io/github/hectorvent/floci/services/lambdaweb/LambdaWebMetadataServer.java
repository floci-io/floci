package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.AvailabilityZoneIds;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The Lambda metadata endpoint that {@code AWS_LAMBDA_METADATA_API} names, which answers the zone
 * id to a request with the environment's {@code AWS_LAMBDA_METADATA_TOKEN}. One server serves every
 * environment, and the token tells them apart. Its port is one the system picks, so it takes none
 * of the Runtime API range, whose width is the Lambda concurrency. AWS serves it with Go's
 * {@code ServeMux}, so paths, redirects and the 404 follow that.
 */
@ApplicationScoped
public class LambdaWebMetadataServer {

    private static final List<String> SEGMENTS = List.of("/2026-01-15/metadata/execution-environment".split("/", -1));
    private static final String BEARER = "Bearer ";
    private static final String JSON = "application/json";
    private static final String TEXT = "text/plain; charset=utf-8";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String UNAUTHORIZED = "\"Client.Unauthorized\"\n";
    private static final String NOT_FOUND = "404 page not found\n";
    private static final String NO_HOST = "Bad Request: missing required Host header";
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Logger LOG = Logger.getLogger(LambdaWebMetadataServer.class);

    private final Vertx vertx;
    private final ContainerReachableEndpoint reachableEndpoint;
    private final Map<String, String> zones = new ConcurrentHashMap<>();
    /** Guarded by this. */
    private HttpServer server;
    private int port;

    @Inject
    public LambdaWebMetadataServer(Vertx vertx, ContainerReachableEndpoint reachableEndpoint) {
        this.vertx = vertx;
        this.reachableEndpoint = reachableEndpoint;
    }

    /** A token for a new environment in {@code region}. Floci answers the region's first zone. */
    String register(String region) {
        String token = UUID.randomUUID().toString();
        zones.put(token, AvailabilityZoneIds.forIndex(region, 1));
        return token;
    }

    void forget(String token) {
        if (token != null) {
            zones.remove(token);
        }
    }

    /** The host and port an environment reaches the server at, the host being the one it reaches Floci at. */
    synchronized String address() {
        if (server == null) {
            start();
        }
        return URI.create(reachableEndpoint.baseUrl()).getHost() + ":" + port;
    }

    private void start() {
        HttpServer created = vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(false))
                .requestHandler(this::handle);
        try {
            created.listen(0, "0.0.0.0").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            created.close();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not start the Lambda Web metadata server", e);
        }
        server = created;
        port = created.actualPort();
        LOG.infov("Lambda Web metadata server started on port {0}", String.valueOf(port));
    }

    @PreDestroy
    synchronized void stop() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private void handle(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        if (request.version() == HttpVersion.HTTP_1_1 && request.headers().get("Host") == null) {
            response.setStatusCode(400).setStatusMessage(NO_HOST).putHeader("Content-Type", TEXT)
                    .putHeader("Connection", "close");
            end(response, "400 " + NO_HOST).onComplete(ignored -> request.connection().close());
            return;
        }
        String path = request.path();
        String clean = LambdaWebIamAuth.signedPath(path);
        if (!clean.equals(path)) {
            redirect(request, clean + (request.query() == null ? "" : "?" + request.query()));
            return;
        }
        if (!isMetadataPath(path)) {
            response.putHeader("Content-Type", TEXT).putHeader("X-Content-Type-Options", "nosniff");
            answer(request, 404, NOT_FOUND);
            return;
        }
        response.putHeader("Content-Type", JSON);
        String zone = zoneOf(request.headers().get("Authorization"));
        if (zone == null) {
            answer(request, 401, UNAUTHORIZED);
            return;
        }
        answer(request, 200, "{\"AvailabilityZoneID\":\"" + zone + "\"}\n");
    }

    private String zoneOf(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return null;
        }
        return zones.get(authorization.substring(BEARER.length()));
    }

    /** Go matches each segment unescaped, so an escaped slash stays inside its segment. */
    private static boolean isMetadataPath(String path) {
        String[] segments = path.split("/", -1);
        if (segments.length != SEGMENTS.size()) {
            return false;
        }
        try {
            for (int i = 0; i < segments.length; i++) {
                if (!URLDecoder.decode(segments[i].replace("+", "%2B"), StandardCharsets.UTF_8).equals(SEGMENTS.get(i))) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Go's {@code http.Redirect}: a body for GET only, and a content type for GET and HEAD. */
    private static void redirect(HttpServerRequest request, String location) {
        HttpServerResponse response = request.response().setStatusCode(307);
        HttpMethod method = request.method();
        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            response.putHeader("Content-Type", HTML);
        }
        response.putHeader("Location", location).putHeader("Date", HTTP_DATE.format(Instant.now()));
        if (method == HttpMethod.GET) {
            end(response, "<a href=\"" + htmlEscape(location) + "\">Temporary Redirect</a>.\n\n");
        } else if (method == HttpMethod.HEAD) {
            response.end();
        } else {
            end(response, "");
        }
    }

    private static void answer(HttpServerRequest request, int status, String body) {
        HttpServerResponse response = request.response().setStatusCode(status)
                .putHeader("Date", HTTP_DATE.format(Instant.now()));
        if (request.method() == HttpMethod.HEAD) {
            response.putHeader("Content-Length", String.valueOf(body.getBytes(StandardCharsets.UTF_8).length)).end();
        } else {
            end(response, body);
        }
    }

    private static Future<Void> end(HttpServerResponse response, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.putHeader("Content-Length", String.valueOf(bytes.length)).end(Buffer.buffer(bytes));
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&#34;").replace("'", "&#39;");
    }
}
