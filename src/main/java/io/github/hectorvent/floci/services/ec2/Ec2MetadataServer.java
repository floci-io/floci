package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IMDS-compatible HTTP server bound to port 9169 on the Floci host.
 * EC2 containers are launched with AWS_EC2_METADATA_SERVICE_ENDPOINT pointing here.
 *
 * Implements IMDSv2 (token-based) and IMDSv1 (no token) — containers using the
 * standard AWS SDK credential chain will hit /latest/meta-data/iam/security-credentials/
 * to obtain temporary credentials backed by the instance's IAM instance profile.
 */
@ApplicationScoped
public class Ec2MetadataServer {

    private static final Logger LOG = Logger.getLogger(Ec2MetadataServer.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Vertx vertx;
    private final EmulatorConfig config;

    /** IMDSv2: token value → Instance */
    private final Map<String, Instance> tokenToInstance = new ConcurrentHashMap<>();
    /** IMDSv1 fallback: container bridge IP → Instance */
    private final Map<String, Instance> containerIpToInstance = new ConcurrentHashMap<>();

    private volatile HttpServer httpServer;

    @Inject
    public Ec2MetadataServer(Vertx vertx, EmulatorConfig config) {
        this.vertx = vertx;
        this.config = config;
    }

    /** Called by Ec2ContainerManager after a container starts to register its IP. */
    public void registerContainer(String containerIp, String instanceId, Instance instance) {
        if (containerIp != null && !containerIp.isBlank()) {
            containerIpToInstance.put(containerIp, instance);
            LOG.debugv("IMDS: registered container {0} → instance {1}", containerIp, instanceId);
        }
    }

    /** Called by Ec2ContainerManager when a container is terminated. */
    public void unregisterContainer(String containerIp) {
        if (containerIp != null) {
            containerIpToInstance.remove(containerIp);
        }
    }

    public CompletableFuture<Void> start() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        int port = config.services().ec2().imdsPort();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // IMDSv2 token endpoint
        router.put("/latest/api/token").handler(this::handleToken);

        // Metadata endpoints
        router.get("/latest/meta-data/instance-id").handler(ctx -> handleText(ctx, inst -> inst.getInstanceId()));
        router.get("/latest/meta-data/ami-id").handler(ctx -> handleText(ctx, inst -> inst.getImageId()));
        router.get("/latest/meta-data/instance-type").handler(ctx -> handleText(ctx, inst -> inst.getInstanceType()));
        router.get("/latest/meta-data/local-ipv4").handler(ctx -> handleText(ctx, inst -> inst.getPrivateIpAddress()));
        router.get("/latest/meta-data/public-ipv4").handler(ctx -> handleText(ctx, inst -> inst.getPublicIpAddress()));
        router.get("/latest/meta-data/public-hostname").handler(ctx -> handleText(ctx, inst -> inst.getPublicDnsName()));
        router.get("/latest/meta-data/local-hostname").handler(ctx -> handleText(ctx, inst -> inst.getPrivateDnsName()));
        router.get("/latest/meta-data/hostname").handler(ctx -> handleText(ctx, inst -> inst.getPrivateDnsName()));
        router.get("/latest/meta-data/mac").handler(ctx -> handleMac(ctx));
        router.get("/latest/meta-data/security-groups").handler(ctx -> handleSecurityGroups(ctx));
        router.get("/latest/meta-data/placement/availability-zone").handler(ctx -> handleText(ctx, inst ->
                inst.getPlacement() != null ? inst.getPlacement().getAvailabilityZone() : "us-east-1a"));
        router.get("/latest/meta-data/placement/region").handler(ctx -> handleText(ctx, inst -> inst.getRegion()));
        router.get("/latest/meta-data/iam/info").handler(ctx -> handleIamInfo(ctx));
        router.get("/latest/meta-data/iam/security-credentials/").handler(ctx -> handleCredentialsList(ctx));
        router.get("/latest/meta-data/iam/security-credentials/:role").handler(ctx -> handleCredentials(ctx));
        router.get("/latest/user-data").handler(ctx -> handleUserData(ctx));
        router.get("/latest/dynamic/instance-identity/document").handler(ctx -> handleIdentityDocument(ctx));

        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(router).listen(port, result -> {
            if (result.succeeded()) {
                LOG.infov("EC2 IMDS server listening on port {0}", port);
                future.complete(null);
            } else {
                LOG.warnv("EC2 IMDS server failed to start on port {0}: {1}", port, result.cause().getMessage());
                future.completeExceptionally(result.cause());
            }
        });
        return future;
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.close();
        }
    }

    // ── Token (IMDSv2) ────────────────────────────────────────────────────────

    private void handleToken(RoutingContext ctx) {
        String ttlHeader = ctx.request().getHeader("x-aws-ec2-metadata-token-ttl-seconds");
        if (ttlHeader == null) {
            ctx.response().setStatusCode(400).end("Missing x-aws-ec2-metadata-token-ttl-seconds");
            return;
        }

        Instance inst = resolveInstanceByIp(ctx);
        String token = UUID.randomUUID().toString().replace("-", "");
        if (inst != null) {
            tokenToInstance.put(token, inst);
        }

        ctx.response()
                .setStatusCode(200)
                .putHeader("x-aws-ec2-metadata-token-ttl-seconds", ttlHeader)
                .end(token);
    }

    // ── Metadata helpers ──────────────────────────────────────────────────────

    @FunctionalInterface
    interface InstanceField {
        String get(Instance instance);
    }

    private void handleText(RoutingContext ctx, InstanceField field) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String value = field.get(inst);
        if (value == null) {
            ctx.response().setStatusCode(404).end("not-available");
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(value);
    }

    private void handleMac(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String mac = inst.getNetworkInterfaces().isEmpty()
                ? "02:42:ac:11:00:02"
                : inst.getNetworkInterfaces().get(0).getMacAddress();
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(mac != null ? mac : "02:42:ac:11:00:02");
    }

    private void handleSecurityGroups(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (var sg : inst.getSecurityGroups()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(sg.getGroupName() != null ? sg.getGroupName() : sg.getGroupId());
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(sb.toString());
    }

    private void handleIamInfo(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String profileArn = inst.getIamInstanceProfileArn();
        if (profileArn == null) {
            ctx.response().setStatusCode(404).end("{}");
            return;
        }
        String profileId = "AIPA" + inst.getInstanceId().toUpperCase().substring(2, 16);
        String body = "{\"Code\":\"Success\",\"LastUpdated\":\"" + now() + "\","
                + "\"InstanceProfileArn\":\"" + profileArn + "\","
                + "\"InstanceProfileId\":\"" + profileId + "\"}";
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(body);
    }

    private void handleCredentialsList(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String profileArn = inst.getIamInstanceProfileArn();
        if (profileArn == null) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        String roleName = extractRoleName(profileArn);
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(roleName);
    }

    private void handleCredentials(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        if (inst.getIamInstanceProfileArn() == null) {
            ctx.response().setStatusCode(404).end();
            return;
        }

        String expiration = ISO.format(Instant.now().plusSeconds(3600));
        String body = "{\"Code\":\"Success\","
                + "\"LastUpdated\":\"" + now() + "\","
                + "\"Type\":\"AWS-HMAC\","
                + "\"AccessKeyId\":\"test\","
                + "\"SecretAccessKey\":\"test\","
                + "\"Token\":\"test-session-token\","
                + "\"Expiration\":\"" + expiration + "\"}";
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(body);
    }

    private void handleUserData(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String userData = inst.getUserData();
        if (userData == null || userData.isBlank()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(userData);
    }

    private void handleIdentityDocument(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String az = inst.getPlacement() != null ? inst.getPlacement().getAvailabilityZone() : "us-east-1a";
        String body = "{\"accountId\":\"" + config.defaultAccountId() + "\","
                + "\"architecture\":\"x86_64\","
                + "\"availabilityZone\":\"" + az + "\","
                + "\"imageId\":\"" + inst.getImageId() + "\","
                + "\"instanceId\":\"" + inst.getInstanceId() + "\","
                + "\"instanceType\":\"" + inst.getInstanceType() + "\","
                + "\"privateIp\":\"" + nvl(inst.getPrivateIpAddress()) + "\","
                + "\"region\":\"" + inst.getRegion() + "\","
                + "\"version\":\"2017-09-30\"}";
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(body);
    }

    // ── Instance resolution ───────────────────────────────────────────────────

    private Instance resolveInstanceByIp(RoutingContext ctx) {
        String remoteIp = ctx.request().remoteAddress().host();
        return containerIpToInstance.get(remoteIp);
    }

    private Instance resolveInstance(RoutingContext ctx) {
        // Try IMDSv2 token first
        String token = ctx.request().getHeader("x-aws-ec2-metadata-token");
        if (token != null && !token.isBlank()) {
            Instance inst = tokenToInstance.get(token);
            if (inst != null) {
                return inst;
            }
        }

        // Fall back to source IP (IMDSv1)
        String remoteIp = ctx.request().remoteAddress().host();
        Instance inst = containerIpToInstance.get(remoteIp);
        if (inst == null) {
            LOG.warnv("IMDS: could not identify instance for request from {0}", remoteIp);
            ctx.response().setStatusCode(404).end("Instance not found");
        }
        return inst;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String extractRoleName(String profileArn) {
        // arn:aws:iam::000000000000:instance-profile/my-role
        int lastSlash = profileArn.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < profileArn.length() - 1) {
            return profileArn.substring(lastSlash + 1);
        }
        return "instance-role";
    }

    private static String now() {
        return ISO.format(Instant.now());
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
