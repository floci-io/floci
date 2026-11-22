package io.floci.aws.services.emrcontainers;

import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.SparkConnectTargets;
import io.floci.aws.core.common.WebSocketUpgrades;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A managed endpoint's {@code serverUrl}, where a Jupyter client reaches its Jupyter Enterprise Gateway with
 * {@code JUPYTER_GATEWAY_URL} set to it and {@code JUPYTER_GATEWAY_AUTH_TOKEN} to a token
 * GetManagedEndpointSessionCredentials gave for the endpoint ("Example: Deploy a JupyterLab notebook", EMR on EKS
 * Development Guide). A request under the endpoint's path on Floci's own port goes on to its gateway
 * ({@link EmrContainersGateways}), its kernels' WebSocket a byte tunnel once the gateway takes the upgrade, when it
 * carries a token given for that endpoint and not yet expired, as Jupyter Enterprise Gateway 2.6.0 takes one: a
 * {@code token} parameter, or an {@code Authorization: token <token>} header. One without such a token gets that
 * gateway's answer, 401 with {@code {"reason": "Unauthorized", "message": ""}}; one whose endpoint is not ACTIVE,
 * 503, its message saying why.
 */
@ApplicationScoped
public class EmrContainersGatewayProxy {

    private static final Logger LOG = Logger.getLogger(EmrContainersGatewayProxy.class);

    private static final String TOKEN_PREFIX = "token ";
    private static final String TOKEN_PARAMETER = "token";
    // What the gateway never gets: the connection's own headers, which the upgrade puts back where it is one, and the
    // token.
    private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "keep-alive", "proxy-connection",
            "proxy-authorization", "proxy-authenticate", "te", "trailer", "transfer-encoding", "upgrade",
            "authorization");

    private final EmrContainersService service;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;
    private final HttpClient client;

    @Inject
    public EmrContainersGatewayProxy(EmrContainersService service, ServiceConfigAccess serviceConfigAccess,
                                     Vertx vertx) {
        this.service = service;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
        // Keep-alive, since a request without it sends Connection: close in place of Connection: Upgrade.
        this.client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true).setConnectTimeout(5_000));
    }

    @PreDestroy
    void close() {
        client.close();
    }

    void register(@Observes Router router) {
        router.route(EmrContainersService.GATEWAY_PATH + "*").handler(this::handle);
    }

    void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        // This route runs ahead of the filter that refuses a disabled service, so it checks for itself.
        if (!serviceConfigAccess.isEnabled(EmrContainersService.SERVICE)) {
            context.next();
            return;
        }
        String rest = request.path().substring(EmrContainersService.GATEWAY_PATH.length());
        int slash = rest.indexOf('/');
        String endpointId = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        String token = token(request);
        request.pause();
        // A service reads its storage to route a request, so off the event loop.
        vertx.<Optional<SparkConnectTargets.Route>>executeBlocking(() -> service.gatewayRoute(endpointId, token),
                false).onComplete(routed -> {
                    if (routed.failed()) {
                        LOG.warnv(routed.cause(), "A request to endpoint {0}'s gateway could not be routed",
                                endpointId);
                        refuse(request, 502, "Bad Gateway", "The request could not be routed.");
                    } else if (routed.result().isEmpty()) {
                        refuse(request, 401, "Unauthorized", "");
                    } else if (routed.result().get().refusal() != null) {
                        SparkConnectTargets.Route refused = routed.result().get();
                        if (refused.status() == SparkConnectTargets.Route.UNAUTHENTICATED) {
                            refuse(request, 401, "Unauthorized", refused.refusal());
                        } else {
                            refuse(request, 503, "Service Unavailable", refused.refusal());
                        }
                    } else {
                        forward(request, path + query(request), routed.result().get());
                    }
                });
    }

    // The request's token, as Jupyter Enterprise Gateway reads it with Tornado's get_argument: its last token
    // parameter, else its Authorization header.
    static String token(HttpServerRequest request) {
        String token = null;
        for (String parameter : parameters(request.query())) {
            int equals = parameter.indexOf('=');
            if (TOKEN_PARAMETER.equals(decoded(equals < 0 ? parameter : parameter.substring(0, equals)))) {
                token = equals < 0 ? "" : decoded(parameter.substring(equals + 1));
            }
        }
        if (token != null) {
            return token;
        }
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.startsWith(TOKEN_PREFIX)
                ? authorization.substring(TOKEN_PREFIX.length()) : null;
    }

    // The request's query as the gateway gets it: without its token.
    static String query(HttpServerRequest request) {
        List<String> kept = new ArrayList<>();
        for (String parameter : parameters(request.query())) {
            int equals = parameter.indexOf('=');
            if (!TOKEN_PARAMETER.equals(decoded(equals < 0 ? parameter : parameter.substring(0, equals)))) {
                kept.add(parameter);
            }
        }
        return kept.isEmpty() ? "" : "?" + String.join("&", kept);
    }

    private static List<String> parameters(String query) {
        return query == null || query.isEmpty() ? List.of() : List.of(query.split("&"));
    }

    // A query parameter's name or value with its escapes decoded. Tornado leaves a malformed escape as it came, so
    // a name holding one is never the token's, and a value holding one is no token Floci gave.
    private static String decoded(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    private void forward(HttpServerRequest request, String uri, SparkConnectTargets.Route route) {
        AtomicBoolean over = new AtomicBoolean();
        Runnable ended = () -> {
            if (over.compareAndSet(false, true)) {
                vertx.executeBlocking(() -> {
                    route.ended().run();
                    return null;
                }, false);
            }
        };
        RequestOptions options = new RequestOptions().setMethod(request.method()).setHost(route.host())
                .setPort(route.port()).setURI(uri);
        for (Map.Entry<String, String> header : request.headers()) {
            if (!NOT_FORWARDED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                options.addHeader(header.getKey(), header.getValue());
            }
        }
        client.request(options).onComplete(opened -> {
            if (opened.failed()) {
                refuse(request, 502, "Bad Gateway",
                        "The endpoint's gateway could not be reached: " + opened.cause().getMessage());
                ended.run();
            } else if (WebSocketUpgrades.isHandshake(request)) {
                upgrade(request, opened.result(), ended);
            } else {
                exchange(request, opened.result(), ended);
            }
        });
    }

    // A request and its answer, each streamed through.
    private static void exchange(HttpServerRequest request, HttpClientRequest upstream, Runnable ended) {
        HttpServerResponse response = request.response();
        // A body sent in chunks goes on in chunks.
        if (request.getHeader("content-length") == null && request.getHeader("transfer-encoding") != null) {
            upstream.setChunked(true);
        }
        response.closeHandler(closed -> {
            upstream.reset();
            ended.run();
        });
        // The answer is held as it arrives, its body read once the relay is ready for it.
        upstream.response().map(HttpClientResponse::pause).onComplete(answered -> {
            if (answered.failed()) {
                if (response.headWritten()) {
                    response.reset();
                } else {
                    refuse(request, 502, "Bad Gateway",
                            "The endpoint's gateway ended the request: " + answered.cause().getMessage());
                }
                ended.run();
                return;
            }
            HttpClientResponse answer = answered.result();
            int status = answer.statusCode();
            response.setStatusCode(status);
            copy(answer.headers(), response.headers());
            // One the gateway streams, with no length, goes on in chunks as it came.
            if (answer.getHeader("content-length") == null && request.method() != HttpMethod.HEAD && status != 204
                    && status != 304 && status >= 200) {
                response.setChunked(true);
            }
            // A gateway that breaks off its answer breaks off the viewer's too, which would otherwise wait for the
            // rest of a body whose length it was given.
            answer.pipe().endOnFailure(false).to(response).onComplete(piped -> {
                if (piped.failed()) {
                    response.reset();
                }
                ended.run();
            });
        });
        request.pipeTo(upstream).onFailure(failure -> {
            upstream.reset();
            if (!response.ended()) {
                response.reset();
            }
            ended.run();
        });
    }

    // A kernel's WebSocket: the handshake goes to the gateway, and once it switches protocols the two connections
    // are joined; any other answer goes back as it is.
    private static void upgrade(HttpServerRequest request, HttpClientRequest upstream, Runnable ended) {
        upstream.putHeader("Connection", "Upgrade");
        upstream.putHeader("Upgrade", request.getHeader("Upgrade"));
        upstream.connect().onComplete(connected -> {
            HttpServerResponse response = request.response();
            if (connected.failed()) {
                refuse(request, 502, "Bad Gateway",
                        "The endpoint's gateway refused the connection: " + connected.cause().getMessage());
                ended.run();
                return;
            }
            HttpClientResponse answer = connected.result();
            response.setStatusCode(answer.statusCode());
            copy(answer.headers(), response.headers());
            if (answer.statusCode() != 101) {
                request.resume();
                answer.body().onComplete(read -> {
                    if (read.succeeded()) {
                        response.end(read.result());
                    } else {
                        response.end();
                    }
                    WebSocketUpgrades.closeRefusedRequest(upstream);
                    ended.run();
                });
                return;
            }
            response.putHeader("Connection", "Upgrade");
            response.putHeader("Upgrade", answer.getHeader("Upgrade"));
            NetSocket gateway = answer.netSocket();
            request.toNetSocket()
                    .onSuccess(viewer -> WebSocketUpgrades.splice(viewer, gateway, ended))
                    .onFailure(failure -> {
                        gateway.close();
                        ended.run();
                    });
        });
    }

    private static void copy(MultiMap from, MultiMap to) {
        for (Map.Entry<String, String> header : from) {
            if (!NOT_FORWARDED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                to.add(header.getKey(), header.getValue());
            }
        }
    }

    // Jupyter Enterprise Gateway's JSON error: its reason, and its message.
    private static void refuse(HttpServerRequest request, int status, String reason, String message) {
        request.resume();
        HttpServerResponse response = request.response();
        if (response.ended() || response.headWritten()) {
            return;
        }
        response.setStatusCode(status)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("reason", reason).put("message", message).encode());
    }
}
