package io.floci.aws.core.common;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spark Connect for the sessions Floci runs a server for. A client's gRPC calls to
 * {@code spark.connect.SparkConnectService} on Floci's own port carry the session's authentication token as their
 * {@code x-aws-proxy-auth} metadata, as EMR Serverless's connection string puts it
 * ({@code sc://<host>:443/;use_ssl=true;x-aws-proxy-auth=<token>}, EMR Serverless user guide, "Run interactive
 * sessions with Amazon EMR Serverless through Spark Connect"), and each goes on, over HTTP/2, to the server of the
 * session the token was issued for, which {@link SparkConnectTargets} names. A call with no token, or one no service
 * issued, ends at once with gRPC's UNAUTHENTICATED, as "When a token expires, gRPC calls fail with an authentication
 * error"; one the service refuses ends with the status it gives.
 */
@ApplicationScoped
public class SparkConnectProxy {

    private static final Logger LOG = Logger.getLogger(SparkConnectProxy.class);

    static final String SERVICE_PATH = "/spark.connect.SparkConnectService/";
    /** The call metadata that carries a session's authentication token. */
    public static final String TOKEN_METADATA = "x-aws-proxy-auth";
    // A call to a service no Spark Connect server has, which its gRPC server answers UNIMPLEMENTED once it runs.
    private static final String PROBE_PATH = "/grpc.health.v1.Health/Check";
    // An empty gRPC message's bytes: no compression, a length of 0.
    private static final int EMPTY_MESSAGE_LENGTH = 5;
    // How long a probe waits for the server's answer before it gives up on that call, its stream reset.
    private static final long PROBE_TIMEOUT_MILLIS = 5_000;
    private static final String GRPC_CONTENT_TYPE = "application/grpc";
    private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "upgrade", "http2-settings", TOKEN_METADATA);
    static final String NO_TOKEN = "The call carries no " + TOKEN_METADATA + " authentication token.";
    public static final String UNKNOWN_TOKEN =
            "The authentication token is not valid. Call GetSessionEndpoint for a new one.";

    private final Instance<SparkConnectTargets> targets;
    private final Vertx vertx;
    private final HttpClient client;

    @Inject
    public SparkConnectProxy(Instance<SparkConnectTargets> targets, Vertx vertx) {
        this.targets = targets;
        this.vertx = vertx;
        // gRPC's servers speak HTTP/2 from the first byte, with no upgrade from HTTP/1.1.
        this.client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false)
                .setConnectTimeout(5_000));
    }

    @PreDestroy
    void close() {
        client.close();
    }

    void register(@Observes Router router) {
        router.route().handler(this::handle);
    }

    void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        if (request.version() != HttpVersion.HTTP_2 || request.method() != HttpMethod.POST
                || !request.path().startsWith(SERVICE_PATH) || !grpc(request.getHeader("content-type"))) {
            context.next();
            return;
        }
        request.pause();
        String token = request.getHeader(TOKEN_METADATA);
        // A service reads its storage to route a call, so off the event loop.
        vertx.<SparkConnectTargets.Route>executeBlocking(() -> route(token), false).onComplete(routed -> {
            if (routed.failed()) {
                LOG.warnv(routed.cause(), "Spark Connect call {0} could not be routed", request.path());
                refuse(request, SparkConnectTargets.Route.UNAVAILABLE, "The call could not be routed.");
            } else if (routed.result().refusal() != null) {
                refuse(request, routed.result().status(), routed.result().refusal());
            } else {
                forward(request, routed.result());
            }
        });
    }

    /**
     * Why no Spark Connect server answers at {@code host} and {@code port}, or null once one does: any gRPC answer to
     * a call it has no method for says it runs. A port Docker publishes accepts a connection before the server in the
     * container listens, so a connection alone says nothing. A call with no answer within
     * {@value #PROBE_TIMEOUT_MILLIS} ms is reset, so probes that go unanswered leave no stream behind.
     */
    public Future<String> unanswered(String host, int port) {
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setHost(host).setPort(port)
                .setURI(PROBE_PATH)
                .setIdleTimeout(PROBE_TIMEOUT_MILLIS)
                .putHeader("content-type", GRPC_CONTENT_TYPE)
                .putHeader("te", "trailers");
        return client.request(options)
                // A Buffer of each call's own: one held in a static field would be built into the native image's
                // heap, which its build refuses.
                .compose(probe -> probe.send(Buffer.buffer(new byte[EMPTY_MESSAGE_LENGTH])))
                .compose(answer -> answer.body().map(body -> answer.statusCode() == 200 ? (String) null
                        : "the server answered HTTP " + answer.statusCode()))
                .recover(failure -> {
                    LOG.debugv("No Spark Connect server answers at {0}:{1}: {2}", host, port, failure.toString());
                    return Future.succeededFuture(failure.toString());
                });
    }

    private SparkConnectTargets.Route route(String token) {
        if (token == null || token.isBlank()) {
            return SparkConnectTargets.Route.refused(SparkConnectTargets.Route.UNAUTHENTICATED, NO_TOKEN);
        }
        for (SparkConnectTargets service : targets) {
            Optional<SparkConnectTargets.Route> route = service.route(token);
            if (route.isPresent()) {
                return route.get();
            }
        }
        return SparkConnectTargets.Route.refused(SparkConnectTargets.Route.UNAUTHENTICATED, UNKNOWN_TOKEN);
    }

    private void forward(HttpServerRequest request, SparkConnectTargets.Route route) {
        HttpServerResponse response = request.response();
        AtomicBoolean over = new AtomicBoolean();
        Runnable ended = () -> {
            if (over.compareAndSet(false, true)) {
                vertx.executeBlocking(() -> {
                    route.ended().run();
                    return null;
                }, false);
            }
        };
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setHost(route.host())
                .setPort(route.port()).setURI(request.uri());
        for (Map.Entry<String, String> header : request.headers()) {
            if (!NOT_FORWARDED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                options.addHeader(header.getKey(), header.getValue());
            }
        }
        client.request(options).onComplete(opened -> {
            if (opened.failed()) {
                refuse(request, SparkConnectTargets.Route.UNAVAILABLE,
                        "The session's Spark Connect server could not be reached: " + opened.cause().getMessage());
                ended.run();
                return;
            }
            HttpClientRequest upstream = opened.result();
            // A gRPC call's messages stream with no Content-Length, which Vert.x takes only on a streamed request.
            if (request.getHeader("content-length") == null) {
                upstream.setChunked(true);
            }
            // A client that goes away takes its call on the server with it.
            response.closeHandler(closed -> {
                upstream.reset();
                ended.run();
            });
            upstream.response().onComplete(answered -> {
                if (answered.failed()) {
                    if (response.headWritten()) {
                        response.reset();
                    } else {
                        refuse(request, SparkConnectTargets.Route.UNAVAILABLE,
                                "The session's Spark Connect server ended the call: " + answered.cause().getMessage());
                    }
                    ended.run();
                    return;
                }
                relay(answered.result(), response, ended);
            });
            // A call whose messages cannot reach the server is reset on both sides, and over.
            request.pipeTo(upstream).onFailure(failure -> {
                upstream.reset();
                if (!response.ended()) {
                    response.reset();
                }
                ended.run();
            });
        });
    }

    /**
     * The server's answer: its messages, then its trailers, which hold the call's gRPC status; or, where the server
     * answered "Trailers-Only", its status in its only headers, as grpc-java answers an error raised before any
     * message, those headers alone, ending the call, as a client finds the status nowhere else.
     */
    private static void relay(HttpClientResponse answer, HttpServerResponse response, Runnable ended) {
        response.setStatusCode(answer.statusCode());
        copy(answer.headers(), response.headers());
        if (answer.headers().contains("grpc-status")) {
            answer.body().onComplete(read -> {
                if (read.succeeded()) {
                    response.end();
                } else {
                    response.reset();
                }
                ended.run();
            });
            return;
        }
        answer.pipe().endOnComplete(false).to(response).onComplete(piped -> {
            if (piped.succeeded()) {
                copy(answer.trailers(), response.trailers());
                response.end();
            } else {
                response.reset();
            }
            ended.run();
        });
    }

    private static void copy(MultiMap from, MultiMap to) {
        for (Map.Entry<String, String> header : from) {
            if (!NOT_FORWARDED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                to.add(header.getKey(), header.getValue());
            }
        }
    }

    // A call that ends at once, with its status in the only headers it gets, as gRPC's "Trailers-Only" answer.
    private static void refuse(HttpServerRequest request, int status, String message) {
        request.resume();
        HttpServerResponse response = request.response();
        if (response.ended()) {
            return;
        }
        response.setStatusCode(200)
                .putHeader("content-type", GRPC_CONTENT_TYPE)
                .putHeader("grpc-status", String.valueOf(status))
                .putHeader("grpc-message", percentEncoded(message))
                .end();
    }

    static boolean grpc(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.equals(GRPC_CONTENT_TYPE) || type.startsWith(GRPC_CONTENT_TYPE + "+")
                || type.startsWith(GRPC_CONTENT_TYPE + ";");
    }

    // gRPC's grpc-message: UTF-8, its bytes outside printable ASCII, and '%', percent-encoded.
    static String percentEncoded(String message) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : message.getBytes(StandardCharsets.UTF_8)) {
            if (b >= 0x20 && b <= 0x7e && b != '%') {
                encoded.append((char) b);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return encoded.toString();
    }
}
