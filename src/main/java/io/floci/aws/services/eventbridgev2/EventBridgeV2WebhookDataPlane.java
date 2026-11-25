package io.floci.aws.services.eventbridgev2;

import io.floci.aws.core.common.RequestHost;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.services.eventbridgev2.EventBridgeV2Webhooks.Answer;
import io.floci.aws.services.eventbridgev2.EventBridgeV2Webhooks.Request;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the URLs of webhook event sources. A request whose Host is {@code <id>.c01.events-webhook.<region>.<host>}
 * is answered here and any other host goes on to the other routes.
 */
@ApplicationScoped
public class EventBridgeV2WebhookDataPlane {
    private static final Logger LOG = Logger.getLogger(EventBridgeV2WebhookDataPlane.class);
    private static final Pattern WEBHOOK_HOST = Pattern.compile(
            "(a[a-z2-7]{37})\\.c01\\.events-webhook\\.[a-z0-9-]+\\..+", Pattern.CASE_INSENSITIVE);
    private static final String SERVICE = "eventsv2";
    private static final Answer INTERNAL_ERROR = new Answer(500, "Internal Server Error");

    private final EventBridgeV2Webhooks webhooks;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;

    @Inject
    public EventBridgeV2WebhookDataPlane(EventBridgeV2Webhooks webhooks, ServiceConfigAccess serviceConfigAccess,
                                         Vertx vertx) {
        this.webhooks = webhooks;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
    }

    void register(@Observes Router router) {
        router.route().handler(this::handle);
    }

    /** The host id of a webhook host, in lower case, or empty for any other host. */
    static Optional<String> hostId(String host) {
        if (host == null) {
            return Optional.empty();
        }
        Matcher matcher = WEBHOOK_HOST.matcher(host);
        return matcher.matches() ? Optional.of(matcher.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        Optional<String> hostId = hostId(RequestHost.of(request));
        if (hostId.isEmpty() || !serviceConfigAccess.isEnabled(SERVICE)) {
            context.next();
            return;
        }
        if ("100-continue".equalsIgnoreCase(request.getHeader("Expect"))) {
            request.response().writeContinue();
        }
        BoundedBody body = new BoundedBody();
        request.handler(body::add);
        request.exceptionHandler(e -> LOG.debugv(e, "A webhook request for {0} was cut short", hostId.get()));
        request.endHandler(ignored -> vertx.<Answer>executeBlocking(() -> webhooks.receive(new Request(
                        request.method().name(), hostId.get(), request.path(),
                        WebhookEvents.headerValues(request.headers()), body.bytes(), body.oversize())), false)
                .onComplete(answered -> {
                    if (answered.failed()) {
                        LOG.errorv(answered.cause(), "A webhook request for {0} failed", hostId.get());
                    }
                    answer(request, answered.succeeded() ? answered.result() : INTERNAL_ERROR);
                }));
        request.resume();
    }

    private static void answer(HttpServerRequest request, Answer answer) {
        byte[] text = answer.body().getBytes(StandardCharsets.UTF_8);
        HttpServerResponse response = request.response();
        response.setStatusCode(answer.status())
                .putHeader("x-amzn-RequestId", UUID.randomUUID().toString())
                .putHeader("Content-Type", "text/plain")
                .putHeader("Content-Length", Integer.toString(text.length));
        boolean closes = answer.closesConnection() && request.version() != HttpVersion.HTTP_2;
        if (closes) {
            response.putHeader("Connection", "close");
        }
        // Vert.x leaves the body out of a HEAD answer on HTTP/1.x only.
        Future<Void> ended = request.method() == HttpMethod.HEAD ? response.end() : response.end(Buffer.buffer(text));
        if (closes) {
            // Vert.x closes by the Connection header of the request, not of the response.
            ended.onComplete(ignored -> request.connection().close());
        }
    }

    /** The request body up to the size limit, then only whether it went past it. */
    private static final class BoundedBody {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean oversize;

        void add(Buffer chunk) {
            if (oversize) {
                return;
            }
            if (bytes.size() + chunk.length() > WebhookEvents.MAX_BODY_BYTES) {
                oversize = true;
                bytes.reset();
                return;
            }
            bytes.writeBytes(chunk.getBytes());
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }

        boolean oversize() {
            return oversize;
        }
    }
}
