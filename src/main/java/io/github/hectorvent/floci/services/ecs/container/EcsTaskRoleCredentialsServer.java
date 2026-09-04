package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Private AWS ECS container-credentials endpoint. Tasks receive only a relative URI; Docker
 * networking supplies the link-local route and no host port is published by this listener. The
 * exact-address bind intentionally rejects native or custom deployments that do not assign the
 * standard metadata address to Floci, instead of advertising an unreachable provider to tasks.
 */
@ApplicationScoped
public class EcsTaskRoleCredentialsServer {

    /** AWS ECS container-credentials link-local metadata address. */
    public static final String TASK_METADATA_HOST = "169.254.170.2";

    private static final Logger LOG = Logger.getLogger(EcsTaskRoleCredentialsServer.class);

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final EcsTaskRoleCredentials credentials;
    private volatile HttpServer httpServer;

    @Inject
    public EcsTaskRoleCredentialsServer(Vertx vertx, EmulatorConfig config,
                                        EcsTaskRoleCredentials credentials) {
        this.vertx = vertx;
        this.config = config;
        this.credentials = credentials;
    }

    void onStart(@Observes StartupEvent ignored) {
        try {
            start().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("ECS task credential listener could not start on "
                    + TASK_METADATA_HOST + "; assign that link-local address to the Floci container", e);
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        stop();
    }

    /** Starts the opt-in listener and completes when the configured port is bound. */
    public CompletableFuture<Void> start() {
        if (!credentials.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        HttpServer existing = httpServer;
        if (existing != null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> started = new CompletableFuture<>();
        Router router = Router.router(vertx);
        router.get(EcsTaskRoleCredentials.RELATIVE_URI_PREFIX + ":token")
                .handler(this::handleCredentials);
        int port = config.services().ecs().taskRoleCredentialsPort();
        httpServer = vertx.createHttpServer()
                .requestHandler(router)
                .listen(port, TASK_METADATA_HOST, result -> {
                    if (result.succeeded()) {
                        LOG.infof("ECS task credential listener started on %s:%d",
                                TASK_METADATA_HOST, port);
                        started.complete(null);
                    } else {
                        httpServer = null;
                        LOG.warnf("ECS task credential listener failed on %s:%d: %s",
                                TASK_METADATA_HOST, port,
                                result.cause() == null ? "unknown error" : result.cause().getMessage());
                        started.completeExceptionally(result.cause());
                    }
                });
        return started;
    }

    public void stop() {
        HttpServer server = httpServer;
        httpServer = null;
        credentials.revokeAll();
        if (server != null) {
            try {
                server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warnf("ECS task credential listener did not stop cleanly: %s", e.getMessage());
            }
        }
    }

    private void handleCredentials(RoutingContext ctx) {
        String token = ctx.pathParam("token");
        String path = EcsTaskRoleCredentials.RELATIVE_URI_PREFIX + (token == null ? "" : token);
        if (!path.matches("^/v2/credentials/[A-Za-z0-9_-]{32,128}$")) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        credentials.current(path).ifPresentOrElse(value -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(credentialsJson(value)),
                () -> ctx.response().setStatusCode(404).end());
    }

    /** Serialises the AWS ECS container-credentials response without exposing internal metadata. */
    public static String credentialsJson(EcsTaskRoleCredentials.IssuedCredentials issued) {
        if (issued == null || issued.credentials() == null) {
            return "{}";
        }
        return "{"
                + "\"Code\":\"Success\",\"Type\":\"AWS-HMAC\""
                + ",\"AccessKeyId\":" + quote(issued.credentials().accessKeyId())
                + ",\"SecretAccessKey\":" + quote(issued.credentials().secretAccessKey())
                + ",\"Token\":" + quote(issued.credentials().sessionToken())
                + ",\"Expiration\":" + quote(issued.expiration() == null ? null : issued.expiration().toString())
                + ",\"RoleArn\":" + quote(issued.roleArn())
                + ",\"LastUpdated\":" + quote(issued.lastUpdated() == null ? null : issued.lastUpdated().toString())
                + "}";
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
