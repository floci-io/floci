package io.floci.aws.services.appsync.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.RequestHost;
import io.floci.aws.core.common.WebSocketUpgrades;
import io.floci.aws.services.appsync.AppSyncService;
import io.floci.aws.services.appsync.graphql.AppSyncTransportException;
import io.floci.aws.services.appsync.graphql.auth.AppSyncAuth;
import io.floci.aws.services.appsync.model.ChannelNamespace;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The realtime WebSocket of an Events API, {@code /event/realtime} on its {@code dns.REALTIME}
 * host, also served at {@code /_aws/appsync/events/<apiId>/event/realtime}.
 *
 * <p>The client offers the {@code aws-appsync-event-ws} subprotocol and a
 * {@code header-<base64url JSON>} subprotocol carrying its connection authorization, which is
 * checked against the API's {@code connectionAuthModes} before the upgrade completes. Then:
 * <ul>
 *   <li>{@code connection_init} is answered with {@code connection_ack} and its
 *       {@code connectionTimeoutMs}, and a {@code ka} follows every 60 seconds;</li>
 *   <li>{@code subscribe} is authorized for its channel and answered with
 *       {@code subscribe_success} or {@code subscribe_error}, after which every event published to a
 *       matching channel arrives as {@code data}, one event per message, as the JSON string it was
 *       published as;</li>
 *   <li>{@code unsubscribe} is answered with {@code unsubscribe_success} or
 *       {@code unsubscribe_error};</li>
 *   <li>{@code publish} is answered with {@code publish_success} or {@code publish_error}.</li>
 * </ul>
 * A connection whose authorization is refused gets a {@code connection_error} and is closed.
 */
@ApplicationScoped
public class AppSyncEventsWebSocketHandler {

    private static final Logger LOG = Logger.getLogger(AppSyncEventsWebSocketHandler.class);

    static final String PATH_FORM = AppSyncEventsController.PATH_PREFIX + "/:apiId/event/realtime";
    static final String HOST_FORM = "/event/realtime";
    static final String HEADER_SUBPROTOCOL_PREFIX = "header-";
    static final long CONNECTION_TIMEOUT_MS = 300_000L;
    static final long KEEP_ALIVE_INTERVAL_MS = 60_000L;

    private static final Pattern OPERATION_ID = Pattern.compile("[a-zA-Z0-9-_+]{1,128}");
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final AppSyncService appSyncService;
    private final EventsAuthorizer authorizer;
    private final EventsPublisher publisher;
    private final EventsChannelBroker broker;
    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;
    private final Vertx vertx;

    @Inject
    public AppSyncEventsWebSocketHandler(AppSyncService appSyncService, EventsAuthorizer authorizer,
                                         EventsPublisher publisher, EventsChannelBroker broker,
                                         ObjectMapper objectMapper, EmulatorConfig config, Vertx vertx) {
        this.appSyncService = appSyncService;
        this.authorizer = authorizer;
        this.publisher = publisher;
        this.broker = broker;
        this.objectMapper = objectMapper;
        this.config = config;
        this.vertx = vertx;
    }

    void init(@Observes Router router) {
        router.route(PATH_FORM).handler(ctx -> {
            if (!WebSocketUpgrades.isHandshake(ctx.request())) {
                ctx.next();
                return;
            }
            upgrade(ctx, ctx.pathParam("apiId"));
        });
        router.route(HOST_FORM).handler(ctx -> {
            String apiId = AppSyncEventsHosts.extractApiId(RequestHost.of(ctx.request()),
                    AppSyncEventsHosts.REALTIME_LABEL, config.hostname().orElse("localhost"));
            if (apiId == null || !WebSocketUpgrades.isHandshake(ctx.request())) {
                ctx.next();
                return;
            }
            upgrade(ctx, apiId);
        });
        LOG.debug("Registered the AppSync Events realtime WebSocket handler");
    }

    private void upgrade(RoutingContext ctx, String apiId) {
        AppSyncService.EventApiOwner owner;
        try {
            owner = appSyncService.findEventApi(apiId).orElse(null);
        } catch (RuntimeException e) {
            LOG.debugv(e, "Events realtime handshake could not resolve API {0}", apiId);
            owner = null;
        }
        if (owner == null) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        Map<String, String> connectionAuth = headerSubprotocol(ctx.request());
        Map<String, String> requestHeaders = handshakeHeaders(ctx.request(), connectionAuth);
        AppSyncService.EventApiOwner apiOwner = owner;
        ctx.request().pause();
        vertx.<String>executeBlocking(() -> {
            if (connectionAuth == null) {
                return "The authorization header subprotocol is missing or unreadable";
            }
            try {
                authorizer.authorize(apiOwner, EventsOperation.EVENT_CONNECT, null, null, requestHeaders, null);
                return null;
            } catch (AppSyncTransportException e) {
                return e.getMessage();
            }
        }).onComplete(result -> {
            ctx.request().resume();
            String refusal = result.succeeded() ? result.result() : AppSyncAuth.UNAUTHORIZED_MESSAGE;
            ctx.request().toWebSocket().onSuccess(ws -> {
                if (refusal != null) {
                    send(ws, Map.of("type", "connection_error", "errors",
                            List.of(error(AppSyncAuth.UNAUTHORIZED_TYPE, refusal))));
                    ws.close((short) 1008, "Unauthorized");
                    return;
                }
                new Connection(ws, apiOwner, requestHeaders).start();
            }).onFailure(err -> LOG.debugv("Events realtime upgrade failed for API {0}: {1}", apiId, err.getMessage()));
        });
    }

    /**
     * The connection authorization a client sends as the {@code header-<base64url JSON>}
     * subprotocol, or null when it sent none or one that does not decode to a JSON object.
     */
    Map<String, String> headerSubprotocol(HttpServerRequest request) {
        for (String value : request.headers().getAll("Sec-WebSocket-Protocol")) {
            for (String token : value.split(",")) {
                String protocol = token.trim();
                if (protocol.startsWith(HEADER_SUBPROTOCOL_PREFIX)) {
                    return decodeHeader(protocol.substring(HEADER_SUBPROTOCOL_PREFIX.length()));
                }
            }
        }
        return null;
    }

    Map<String, String> decodeHeader(String encoded) {
        try {
            String json = Base64Url.decode(encoded);
            return stringMap(objectMapper.readValue(json, OBJECT_MAP));
        } catch (Exception e) {
            LOG.debugv("Unreadable Events authorization subprotocol: {0}", e.getMessage());
            return null;
        }
    }

    /** The handshake's own headers, with the decoded authorization entries laid over them. */
    private static Map<String, String> handshakeHeaders(HttpServerRequest request, Map<String, String> auth) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.headers().forEach(entry -> {
            if (!"Sec-WebSocket-Protocol".equalsIgnoreCase(entry.getKey())) {
                headers.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        });
        if (auth != null) {
            auth.forEach((name, value) -> headers.put(name.toLowerCase(), value));
        }
        return headers;
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> strings = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                strings.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return strings;
    }

    private static Map<String, Object> error(String errorType, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorType", errorType);
        error.put("message", message);
        return error;
    }

    private void send(ServerWebSocket ws, Map<String, Object> message) {
        try {
            ws.writeTextMessage(objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            LOG.debugv("Could not write to an Events realtime connection: {0}", e.getMessage());
        }
    }

    /** One open realtime connection and the subscriptions it holds. */
    private final class Connection {
        private final ServerWebSocket ws;
        private final AppSyncService.EventApiOwner owner;
        private final Map<String, String> connectionHeaders;
        private final String connectionId = UUID.randomUUID().toString();
        private final AtomicLong keepAliveTimer = new AtomicLong(-1);

        Connection(ServerWebSocket ws, AppSyncService.EventApiOwner owner, Map<String, String> connectionHeaders) {
            this.ws = ws;
            this.owner = owner;
            this.connectionHeaders = connectionHeaders;
        }

        void start() {
            ws.textMessageHandler(this::onMessage);
            ws.closeHandler(ignored -> close());
            LOG.debugv("Events realtime connection {0} opened on API {1}", connectionId, owner.api().getApiId());
        }

        private void close() {
            long timer = keepAliveTimer.getAndSet(-1);
            if (timer >= 0) {
                vertx.cancelTimer(timer);
            }
            broker.removeConnection(connectionId);
            LOG.debugv("Events realtime connection {0} closed", connectionId);
        }

        private void onMessage(String text) {
            Map<String, Object> message;
            try {
                message = objectMapper.readValue(text, OBJECT_MAP);
            } catch (Exception e) {
                send(ws, Map.of("type", "error", "errors", List.of(error("BadRequestException", "Unreadable message"))));
                return;
            }
            Object type = message == null ? null : message.get("type");
            switch (type == null ? "" : String.valueOf(type)) {
                case "connection_init" -> acknowledge();
                case "subscribe" -> subscribe(message);
                case "unsubscribe" -> unsubscribe(message);
                case "publish" -> publish(message);
                default -> send(ws, Map.of("type", "error", "errors",
                        List.of(error("UnsupportedOperation", "Unsupported message type: " + type))));
            }
        }

        private void acknowledge() {
            send(ws, Map.of("type", "connection_ack", "connectionTimeoutMs", CONNECTION_TIMEOUT_MS));
            if (keepAliveTimer.get() < 0) {
                keepAliveTimer.set(vertx.setPeriodic(KEEP_ALIVE_INTERVAL_MS, id -> send(ws, Map.of("type", "ka"))));
            }
        }

        private void subscribe(Map<String, Object> message) {
            String id = operationId(message, "subscribe_error");
            if (id == null) {
                return;
            }
            String apiId = owner.api().getApiId();
            if (broker.hasSubscription(owner.accountId(), apiId, connectionId, id)) {
                fail("subscribe_error", id, "SubscriptionProcessingError", "Duplicate subscription id " + id);
                return;
            }
            Map<String, String> credentials = credentials(message);
            vertx.<EventsChannel>executeBlocking(() -> {
                EventsChannel channel = subscribeChannel(message.get("channel"));
                ChannelNamespace namespace = publisher.namespace(owner, channel);
                authorizer.authorize(owner, EventsOperation.EVENT_SUBSCRIBE, namespace, channel.path(),
                        credentials, null);
                return channel;
            }).onSuccess(channel -> {
                broker.subscribe(owner.accountId(), apiId, new EventsChannelBroker.Subscription(connectionId, id,
                        channel, (subscriptionId, event) -> send(ws, data(subscriptionId, event))));
                send(ws, Map.of("type", "subscribe_success", "id", id));
            }).onFailure(e -> failed("subscribe_error", id, e));
        }

        private void unsubscribe(Map<String, Object> message) {
            String id = operationId(message, "unsubscribe_error");
            if (id == null) {
                return;
            }
            if (broker.unsubscribe(owner.accountId(), owner.api().getApiId(), connectionId, id)) {
                send(ws, Map.of("type", "unsubscribe_success", "id", id));
            } else {
                fail("unsubscribe_error", id, "UnknownOperationError", "Unknown operation id " + id);
            }
        }

        private void publish(Map<String, Object> message) {
            String id = operationId(message, "publish_error");
            if (id == null) {
                return;
            }
            Map<String, String> credentials = credentials(message);
            vertx.<EventsPublisher.Result>executeBlocking(() -> publisher.publish(owner, message.get("channel"),
                    message.get("events"), credentials, null)).onSuccess(result -> {
                        Map<String, Object> success = new LinkedHashMap<>();
                        success.put("id", id);
                        success.put("type", "publish_success");
                        success.put("successful", result.successful());
                        success.put("failed", result.failed());
                        send(ws, success);
                    }).onFailure(e -> failed("publish_error", id, e));
        }

        /** The operation's {@code authorization} object, over the connection's handshake headers. */
        private Map<String, String> credentials(Map<String, Object> message) {
            Map<String, String> credentials = new LinkedHashMap<>();
            connectionHeaders.forEach((name, value) -> {
                if (!"authorization".equals(name) && !"x-api-key".equals(name)) {
                    credentials.put(name, value);
                }
            });
            Map<String, String> authorization = stringMap(message.get("authorization"));
            if (authorization != null) {
                authorization.forEach((name, value) -> credentials.put(name.toLowerCase(), value));
            }
            return credentials;
        }

        private String operationId(Map<String, Object> message, String errorType) {
            Object id = message.get("id");
            if (id instanceof String value && OPERATION_ID.matcher(value).matches()) {
                return value;
            }
            send(ws, Map.of("type", errorType, "errors",
                    List.of(error("BadRequestException", "An operation id of 1 to 128 characters is required"))));
            return null;
        }

        private void failed(String type, String id, Throwable e) {
            if (e instanceof AppSyncTransportException transport) {
                fail(type, id, transport.getErrorType(), transport.getMessage());
            } else {
                LOG.debugv(e, "Events realtime {0} {1} failed", type, id);
                fail(type, id, "InternalFailure", "There was an error processing the operation");
            }
        }

        private void fail(String type, String id, String errorType, String message) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("type", type);
            failure.put("id", id);
            failure.put("errors", List.of(error(errorType, message)));
            send(ws, failure);
        }
    }

    private static Map<String, Object> data(String subscriptionId, String event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "data");
        data.put("id", subscriptionId);
        data.put("event", event);
        return data;
    }

    private static EventsChannel subscribeChannel(Object value) {
        if (!(value instanceof String path)) {
            throw AppSyncAuth.badRequest("A channel is required");
        }
        try {
            return EventsChannel.forSubscribe(path);
        } catch (IllegalArgumentException e) {
            throw AppSyncAuth.badRequest(e.getMessage());
        }
    }
}
