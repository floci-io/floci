package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Serves the AWS container-credentials wire contract that
 * {@code AWS_CONTAINER_CREDENTIALS_RELATIVE_URI} points at: {@code GET /v2/credentials/<id>}
 * returning {@code RoleArn}/{@code AccessKeyId}/{@code SecretAccessKey}/{@code Token}/
 * {@code Expiration}.
 *
 * <p>Bound to a normal Floci-host port, not to 169.254.170.2: the AWS SDK hardcodes that address
 * (it is not configurable, unlike EC2's {@code AWS_EC2_METADATA_SERVICE_ENDPOINT}), and nothing in
 * Floci's own process can bind an address it does not otherwise own on a task's Docker network.
 * {@link EcsCredentialsProxy} is what actually holds 169.254.170.2, one small proxy container per
 * ECS network, forwarding here over {@code host.docker.internal}.
 */
@ApplicationScoped
public class EcsTaskRoleCredentialsServer {

    private static final Logger LOG = Logger.getLogger(EcsTaskRoleCredentialsServer.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final EcsTaskRoleCredentials credentials;

    private volatile HttpServer httpServer;

    @Inject
    public EcsTaskRoleCredentialsServer(Vertx vertx, EmulatorConfig config, EcsTaskRoleCredentials credentials) {
        this.vertx = vertx;
        this.config = config;
        this.credentials = credentials;
    }

    public synchronized CompletableFuture<Void> start() {
        if (httpServer != null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        int port = config.services().ecs().taskRoleCredentials().port();

        Router router = Router.router(vertx);
        router.get("/v2/credentials/:id").handler(this::handleCredentials);

        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(router).listen(port, result -> {
            if (result.succeeded()) {
                LOG.infof("ECS task-role credentials server listening on port %d", port);
                future.complete(null);
            } else {
                LOG.warnf("ECS task-role credentials server failed to start on port %d: %s",
                        port, result.cause().getMessage());
                future.completeExceptionally(result.cause());
            }
        });
        return future;
    }

    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }

    private void handleCredentials(RoutingContext ctx) {
        String path = "/v2/credentials/" + ctx.pathParam("id");
        Optional<SessionCredential> session = credentials.resolveByPath(path, Instant.now());
        if (session.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        SessionCredential creds = session.get();
        ctx.response().putHeader("content-type", "application/json").end(new JsonObject()
                .put("RoleArn", creds.getRoleArn())
                .put("AccessKeyId", creds.getAccessKeyId())
                .put("SecretAccessKey", creds.getSecretAccessKey())
                .put("Token", creds.getSessionToken())
                .put("Expiration", ISO.format(creds.getExpiration())).encode());
    }
}
