package io.floci.aws.services.emr;

import io.floci.aws.core.common.Html;
import io.floci.aws.core.common.ServiceConfigAccess;
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
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The on-cluster application UIs Floci opens for real ({@link EmrAppUiService}): a request to an interface's own host,
 * {@code <secret>-<port>.emrappui.<Floci's host>}, goes on to that port of the cluster's primary node, and its answer
 * comes back with each link to one of the node's interfaces, which its daemons write by the node's name, made a link
 * to that interface's own host. The secret in the host is the link's authority, as no request to the interface carries
 * AWS credentials; one that is not this process's, or whose hour has passed, is answered with a page saying so.
 */
@ApplicationScoped
public class EmrAppUiProxy {

    private static final Logger LOG = Logger.getLogger(EmrAppUiProxy.class);

    // What does not go on: the connection's own headers, the host, which the interface is asked for under the
    // browser's, and the encodings the browser takes, as only an answer that is not compressed can have its links made
    // the browser's.
    private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "upgrade", "te", "trailer", "proxy-authorization", "proxy-authenticate",
            "accept-encoding");
    // The answers whose links are made the browser's: the interfaces' pages, scripts and REST answers.
    private static final List<String> TEXT_TYPES = List.of("text/", "application/json", "application/javascript",
            "application/xml");
    // The most of an answer held to make its links the browser's; a larger one, a long log for one, goes on as it is.
    private static final int MAX_REWRITTEN = 8 * 1024 * 1024;
    // How long the interface may send nothing, its answer or the rest of it, before the request is given up.
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(1);

    private final EmrAppUiService appUis;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;
    private final HttpClient client;
    private final Duration idleTimeout;

    @Inject
    public EmrAppUiProxy(EmrAppUiService appUis, ServiceConfigAccess serviceConfigAccess, Vertx vertx) {
        this(appUis, serviceConfigAccess, vertx, IDLE_TIMEOUT);
    }

    EmrAppUiProxy(EmrAppUiService appUis, ServiceConfigAccess serviceConfigAccess, Vertx vertx,
                  Duration idleTimeout) {
        this.appUis = appUis;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
        this.idleTimeout = idleTimeout;
        this.client = vertx.createHttpClient(new HttpClientOptions().setConnectTimeout(5_000));
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
        String hostHeader = request.getHeader("host");
        // This route runs ahead of the filter that refuses a disabled service, so it checks for itself.
        if (hostHeader == null || !hostHeader.toLowerCase(Locale.ROOT).contains("." + EmrAppUiService.UI_HOST + ".")
                || !serviceConfigAccess.isEnabled("emr")) {
            context.next();
            return;
        }
        request.pause();
        // Finding the interface may ask Docker where it is, so off the event loop.
        vertx.<EmrAppUiService.UiRoute>executeBlocking(() -> appUis.route(hostHeader, request.scheme()), false)
                .onComplete(routed -> {
                    if (routed.failed()) {
                        LOG.warnv(routed.cause(), "EMR application UI request to {0} could not be routed",
                                hostHeader);
                        refuse(request, 502, "The interface could not be reached.");
                    } else if (routed.result() == null) {
                        request.resume();
                        context.next();
                    } else if (routed.result().refusal() != null) {
                        refuse(request, routed.result().status(), routed.result().refusal());
                    } else {
                        forward(request, hostHeader, routed.result());
                    }
                });
    }

    private void forward(HttpServerRequest request, String hostHeader, EmrAppUiService.UiRoute route) {
        URI authority = URI.create("http://" + hostHeader);
        RequestOptions options = new RequestOptions().setMethod(request.method())
                .setServer(SocketAddress.inetSocketAddress(route.port(), route.host()))
                .setHost(authority.getHost())
                .setPort(authority.getPort() > 0 ? authority.getPort() : "https".equals(request.scheme()) ? 443 : 80)
                .setURI(request.uri())
                .setIdleTimeout(idleTimeout.toMillis());
        for (Map.Entry<String, String> header : request.headers()) {
            if (!NOT_FORWARDED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                options.addHeader(header.getKey(), header.getValue());
            }
        }
        options.putHeader("accept-encoding", "identity");
        HttpServerResponse response = request.response();
        client.request(options).onComplete(opened -> {
            if (opened.failed()) {
                route.unreachable().run();
                refuse(request, 502, "The cluster's interface did not answer: " + opened.cause().getMessage());
                return;
            }
            HttpClientRequest upstream = opened.result();
            // A body sent in chunks goes on in chunks.
            if (request.getHeader("content-length") == null && request.getHeader("transfer-encoding") != null) {
                upstream.setChunked(true);
            }
            // A browser that goes away takes its request with it.
            response.closeHandler(closed -> upstream.reset());
            // The answer is held as it arrives, its body read once the relay is ready for it.
            upstream.response().map(HttpClientResponse::pause).onComplete(answered -> {
                if (answered.failed()) {
                    route.unreachable().run();
                    if (response.headWritten()) {
                        response.reset();
                    } else {
                        refuse(request, 502, "The cluster's interface did not answer: "
                                + answered.cause().getMessage());
                    }
                    return;
                }
                relay(answered.result(), response, request.method(),
                        request.method() == HttpMethod.HEAD ? Map.of() : route.rewrites());
            });
            // The request's body goes on as it arrives, held back while the interface takes it more slowly, so none
            // is held whole.
            request.pipeTo(upstream).onFailure(failure -> {
                upstream.reset();
                if (!response.ended()) {
                    response.reset();
                }
            });
        });
    }

    // The interface's answer, its links to the node's interfaces made the browser's in the text it sends.
    private static void relay(HttpClientResponse answer, HttpServerResponse response, HttpMethod method,
                              Map<String, String> rewrites) {
        boolean rewritten = rewritable(answer.getHeader("content-type")) && !rewrites.isEmpty();
        response.setStatusCode(answer.statusCode()).setStatusMessage(answer.statusMessage());
        MultiMap headers = answer.headers();
        for (Map.Entry<String, String> header : headers) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (NOT_FORWARDED.contains(name) || rewritten && name.equals("content-length")) {
                continue;
            }
            response.headers().add(header.getKey(), name.equals("location")
                    ? rewrite(header.getValue(), rewrites) : header.getValue());
        }
        if (!rewritten) {
            // One the interface streams, with no length, goes on in chunks as it came.
            int status = answer.statusCode();
            if (answer.getHeader("content-length") == null && method != HttpMethod.HEAD && status != 204
                    && status != 304 && status >= 200) {
                response.setChunked(true);
            }
            answer.pipeTo(response).onFailure(failure -> response.reset());
            return;
        }
        Buffer held = Buffer.buffer();
        boolean[] streaming = {false};
        answer.handler(chunk -> {
            Buffer out = chunk;
            if (!streaming[0]) {
                held.appendBuffer(chunk);
                if (held.length() <= MAX_REWRITTEN) {
                    return;
                }
                streaming[0] = true;
                response.setChunked(true);
                out = held;
            }
            response.write(out);
            if (response.writeQueueFull()) {
                answer.pause();
                response.drainHandler(drained -> answer.resume());
            }
        });
        answer.exceptionHandler(failure -> response.reset());
        answer.endHandler(ended -> {
            if (streaming[0]) {
                response.end();
            } else {
                // Byte for byte: the links are ASCII, and every other byte goes back as it came.
                response.end(Buffer.buffer(rewrite(held.toString(StandardCharsets.ISO_8859_1), rewrites)
                        .getBytes(StandardCharsets.ISO_8859_1)));
            }
        });
        answer.resume();
    }

    static boolean rewritable(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return TEXT_TYPES.stream().anyMatch(type::startsWith);
    }

    static String rewrite(String text, Map<String, String> rewrites) {
        String result = text;
        for (Map.Entry<String, String> rewrite : rewrites.entrySet()) {
            result = result.replace(rewrite.getKey(), rewrite.getValue());
        }
        return result;
    }

    private static void refuse(HttpServerRequest request, int status, String message) {
        request.resume();
        HttpServerResponse response = request.response();
        if (response.ended()) {
            return;
        }
        response.setStatusCode(status).putHeader("content-type", "text/html; charset=utf-8")
                .end(Html.page("Application UI", List.of("<p>" + Html.escape(message) + "</p>")));
    }
}
