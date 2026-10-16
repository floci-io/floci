package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2instanceconnect.InstanceConnectKeys;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

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
    /** IMDSv2 tokens live from one second up to six hours. */
    private static final int MAX_TOKEN_TTL_SECONDS = 21_600;
    private static final String IDENTITY_ROLE = "ec2-instance";
    private static final String INSTANCE_TAGS_PREFIX = "/latest/meta-data/tags/instance/";

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final Ec2InstanceCredentials credentials;
    private final Clock clock;

    /** What {@code services/domain} reports: the commercial partition's domain. */
    static final String SERVICES_DOMAIN = "amazonaws.com";

    /** IMDSv2: token value → Instance */
    private final Map<String, SessionToken> tokens = new ConcurrentHashMap<>();
    /** IMDSv1 fallback: container bridge IP → Instance */
    private final Map<String, Instance> containerIpToInstance = new ConcurrentHashMap<>();
    /** Cluster nodes whose instance-profile identity their registrar synthesizes rather than IAM holding it. */
    private final Set<Instance> synthesizedProfiles = ConcurrentHashMap.newKeySet();

    private volatile HttpServer httpServer;
    private final InstanceConnectKeys instanceConnect;

    @Inject
    public Ec2MetadataServer(Vertx vertx, EmulatorConfig config, IamService iamService,
                             InstanceConnectKeys instanceConnect) {
        this(vertx, config, iamService, Clock.systemUTC(), instanceConnect);
    }

    Ec2MetadataServer(Vertx vertx, EmulatorConfig config, IamService iamService, Clock clock) {
        this(vertx, config, iamService, clock, null);
    }

    Ec2MetadataServer(Vertx vertx, EmulatorConfig config, IamService iamService, Clock clock,
                      InstanceConnectKeys instanceConnect) {
        this.vertx = vertx;
        this.config = config;
        this.credentials = new Ec2InstanceCredentials(iamService);
        this.clock = clock;
        this.instanceConnect = instanceConnect;
    }

    /** EC2 Instance Connect's signing material, for installing its trust anchor in guests; empty in tests. */
    public Optional<InstanceConnectKeys> instanceConnect() {
        return Optional.ofNullable(instanceConnect);
    }

    /** Called by Ec2ContainerManager after a container starts to register its IP. */
    public void registerContainer(String containerIp, String instanceId, Instance instance) {
        if (containerIp != null && !containerIp.isBlank()) {
            credentials.register(instance);
            containerIpToInstance.put(containerIp, instance);
            LOG.debugv("IMDS: registered container {0} → instance {1}", containerIp, instanceId);
        }
    }

    /** Called by Ec2ContainerManager when a container is terminated. */
    public void unregisterContainer(String containerIp, Instance instance) {
        if (containerIp != null && instance != null) {
            containerIpToInstance.remove(containerIp, instance);
        }
    }

    /** Reconcile every Docker attachment without retaining stale addresses after restart. */
    public void reconcileContainerAddresses(Set<String> addresses, Instance instance) {
        for (String address : addresses) {
            registerContainer(address, instance.getInstanceId(), instance);
        }
        containerIpToInstance.entrySet().removeIf(entry ->
                entry.getValue() == instance && !addresses.contains(entry.getKey()));
    }

    /**
     * Registers a cluster node that another service synthesizes instead of EC2 launching it. Its
     * instance-profile ARN and ID are the registrar's own, so {@code iam/info} reports them while IAM
     * holds no such profile.
     */
    public void reconcileClusterNodeAddresses(Set<String> addresses, Instance instance) {
        synthesizedProfiles.add(instance);
        reconcileContainerAddresses(addresses, instance);
    }

    public void unregisterInstance(Instance instance) {
        if (instance != null) {
            synthesizedProfiles.remove(instance);
            credentials.unregister(instance);
            tokens.values().removeIf(token -> token.instance() == instance);
            containerIpToInstance.entrySet().removeIf(entry -> entry.getValue() == instance);
        }
    }

    Optional<Instance> registeredContainer(String containerIp) {
        return Optional.ofNullable(containerIpToInstance.get(containerIp));
    }

    public synchronized CompletableFuture<Void> start() {
        if (httpServer != null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        int port = config.services().ec2().imdsPort();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        // Ahead of every route, so a path IMDS does not serve also answers 401 when the instance
        // requires tokens, as on AWS. A method-free route, so methods IMDS does not serve keep the
        // router's own answers rather than all becoming 405.
        router.route().handler(this::refuseTokenlessReadsWhenTokensAreRequired);

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
                inst.getPlacement() != null
                        ? inst.getPlacement().getAvailabilityZone()
                        : config.defaultAvailabilityZone()));
        router.get("/latest/meta-data/placement/region").handler(ctx -> handleText(ctx, inst -> inst.getRegion()));
        router.get("/latest/meta-data/iam/info").handler(ctx -> handleIamInfo(ctx));
        router.get("/latest/meta-data/iam/security-credentials/").handler(ctx -> handleCredentialsList(ctx));
        router.get("/latest/meta-data/iam/security-credentials/:role").handler(ctx -> handleCredentials(ctx));
        router.get("/latest/meta-data/identity-credentials").handler(ctx -> handleListing(ctx, "ec2/"));
        router.get("/latest/meta-data/identity-credentials/").handler(ctx -> handleListing(ctx, "ec2/"));
        router.get("/latest/meta-data/identity-credentials/ec2").handler(ctx -> handleListing(ctx, "info\nsecurity-credentials/"));
        router.get("/latest/meta-data/identity-credentials/ec2/").handler(ctx -> handleListing(ctx, "info\nsecurity-credentials/"));
        router.get("/latest/meta-data/identity-credentials/ec2/info").handler(ctx -> handleIdentityInfo(ctx));
        router.get("/latest/meta-data/identity-credentials/ec2/security-credentials")
                .handler(ctx -> handleListing(ctx, IDENTITY_ROLE));
        router.get("/latest/meta-data/identity-credentials/ec2/security-credentials/")
                .handler(ctx -> handleListing(ctx, IDENTITY_ROLE));
        router.get("/latest/meta-data/identity-credentials/ec2/security-credentials/" + IDENTITY_ROLE)
                .handler(ctx -> handleIdentityCredentials(ctx));
        router.get("/latest/meta-data/tags/instance").handler(ctx -> handleInstanceTagKeys(ctx));
        router.get("/latest/meta-data/tags/instance/").handler(ctx -> handleInstanceTagKeys(ctx));
        router.getWithRegex("/latest/meta-data/tags/instance/.+").handler(ctx -> handleInstanceTagValue(ctx));
        router.get("/latest/meta-data/services/domain").handler(ctx -> handleText(ctx, inst -> SERVICES_DOMAIN));
        // EC2 Instance Connect. The agent probes active-keys with HEAD before fetching it.
        router.route("/latest/meta-data/managed-ssh-keys/active-keys/:user").method(HttpMethod.GET)
                .method(HttpMethod.HEAD).handler(this::handleManagedSshKeys);
        router.get("/latest/meta-data/managed-ssh-keys/signer-cert").handler(ctx -> handleInstanceConnect(ctx,
                (keys, inst) -> keys.signerCertificateChain(inst.getRegion())));
        router.get("/latest/meta-data/managed-ssh-keys/signer-ocsp").handler(ctx -> handleInstanceConnect(ctx,
                (keys, inst) -> String.join("\n", keys.ocspStaples(inst.getRegion()).keySet())));
        router.get("/latest/meta-data/managed-ssh-keys/signer-ocsp/:fingerprint").handler(ctx -> handleInstanceConnect(ctx,
                (keys, inst) -> keys.ocspStaples(inst.getRegion()).get(ctx.pathParam("fingerprint"))));
        router.get("/latest/user-data").handler(ctx -> handleUserData(ctx));
        router.get("/latest/dynamic/instance-identity/document").handler(ctx -> handleIdentityDocument(ctx));

        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(router).listen(port, result -> {
            if (result.succeeded()) {
                LOG.infof("EC2 IMDS server listening on port %d", port);
                future.complete(null);
            } else {
                LOG.warnf("EC2 IMDS server failed to start on port %d: %s", port, result.cause().getMessage());
                future.completeExceptionally(result.cause());
            }
        });
        return future;
    }

    public synchronized void stop() {
        credentials.clear();
        tokens.clear();
        containerIpToInstance.clear();
        synthesizedProfiles.clear();
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }

    // ── Token (IMDSv2) ────────────────────────────────────────────────────────

    private void handleToken(RoutingContext ctx) {
        String ttlHeader = ctx.request().getHeader("x-aws-ec2-metadata-token-ttl-seconds");
        Integer ttlSeconds = parseTokenTtl(ttlHeader);
        if (ttlSeconds == null) {
            ctx.response().setStatusCode(400).end(
                    "x-aws-ec2-metadata-token-ttl-seconds must be an integer from 1 to " + MAX_TOKEN_TTL_SECONDS);
            return;
        }

        Instance inst = resolveInstanceByIp(ctx);
        String token = UUID.randomUUID().toString().replace("-", "");
        if (inst != null) {
            Instant now = clock.instant();
            tokens.values().removeIf(existing -> existing.isExpiredAt(now));
            tokens.put(token, new SessionToken(inst, now.plusSeconds(ttlSeconds)));
        }
        else {
            LOG.debugv("IMDS: token requested from {0}, which is not a registered EC2 container; "
                    + "metadata requests with this token will fail", ctx.request().remoteAddress().host());
        }

        ctx.response()
                .setStatusCode(200)
                .putHeader("x-aws-ec2-metadata-token-ttl-seconds", ttlHeader)
                .end(token);
    }

    /** Returns the TTL in seconds, or null when the header is missing or outside 1..21600. */
    private static Integer parseTokenTtl(String ttlHeader) {
        if (ttlHeader == null) {
            return null;
        }
        try {
            int ttl = Integer.parseInt(ttlHeader.trim());
            return ttl >= 1 && ttl <= MAX_TOKEN_TTL_SECONDS ? ttl : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record SessionToken(Instance instance, Instant expiresAt) {
        boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
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

    private void handleManagedSshKeys(RoutingContext ctx) {
        handleInstanceConnect(ctx, (keys, inst) -> keys.activeKeysDocument(inst.getRegion(),
                inst.getInstanceId(), ctx.pathParam("user")).orElse(null));
    }

    private void handleInstanceConnect(RoutingContext ctx,
                                       BiFunction<InstanceConnectKeys, Instance, String> body) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String value = instanceConnect == null ? null : body.apply(instanceConnect, inst);
        if (value == null) {
            ctx.response().setStatusCode(404).end("not-available");
            return;
        }
        ctx.response().setStatusCode(200).putHeader("content-type", "text/plain");
        if (ctx.request().method() == HttpMethod.HEAD) {
            ctx.response().putHeader("content-length", String.valueOf(value.getBytes(StandardCharsets.UTF_8).length))
                    .end();
        } else {
            ctx.response().end(value);
        }
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
        for (GroupIdentifier sg : inst.getSecurityGroups()) {
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
        Optional<InstanceProfile> resolved = credentials.profile(inst);
        String profileArn;
        String profileId;
        if (resolved.isPresent()) {
            profileArn = resolved.get().getArn();
            profileId = resolved.get().getInstanceProfileId();
        } else if (synthesizedProfiles.contains(inst) && inst.getIamInstanceProfileArn() != null
                && !credentials.holdsProfile(inst)) {
            profileArn = inst.getIamInstanceProfileArn();
            profileId = inst.getIamInstanceProfileId();
        } else {
            ctx.response().setStatusCode(404).end("{}");
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("Code", "Success").put("LastUpdated", now())
                        .put("InstanceProfileArn", profileArn)
                        .put("InstanceProfileId", profileId).encode());
    }

    private void handleCredentialsList(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<IamRole> role = credentials.role(inst);
        if (role.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().putHeader("content-type", "text/plain").end(role.get().getRoleName());
    }

    private void handleCredentials(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<SessionCredential> result = credentials.get(inst, ctx.pathParam("role"), Instant.now());
        if (result.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        SessionCredential session = result.get();
        ctx.response().putHeader("content-type", "application/json").end(credentialsJson(session));
    }

    private void handleListing(RoutingContext ctx, String entries) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        ctx.response().setStatusCode(200).putHeader("content-type", "text/plain").end(entries);
    }

    private void handleIdentityInfo(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        ctx.response().putHeader("content-type", "application/json").end(new JsonObject()
                .put("Code", "Success")
                .put("LastUpdated", ISO.format(clock.instant()))
                .put("AccountId", ownerAccount(inst)).encode());
    }

    private void handleIdentityCredentials(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<SessionCredential> result = credentials.identity(inst, ownerAccount(inst), clock.instant());
        if (result.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().putHeader("content-type", "application/json").end(credentialsJson(result.get()));
    }

    /** The account that launched the instance; instances persisted before it was recorded belong to the default one. */
    private String ownerAccount(Instance inst) {
        return inst.getOwnerId() != null ? inst.getOwnerId() : config.defaultAccountId();
    }

    private static String credentialsJson(SessionCredential session) {
        return new JsonObject()
                .put("Code", "Success")
                .put("LastUpdated", ISO.format(session.getExpiration().minusSeconds(3600)))
                .put("Type", "AWS-HMAC")
                .put("AccessKeyId", session.getAccessKeyId())
                .put("SecretAccessKey", session.getSecretAccessKey())
                .put("Token", session.getSessionToken())
                .put("Expiration", ISO.format(session.getExpiration())).encode();
    }

    private void handleInstanceTagKeys(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(instanceTagKeys(inst));
    }

    private void handleInstanceTagValue(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }

        String path = ctx.request().path();
        String tagKey = path.length() <= INSTANCE_TAGS_PREFIX.length()
                ? ""
                : URLDecoder.decode(path.substring(INSTANCE_TAGS_PREFIX.length()), StandardCharsets.UTF_8);
        Optional<String> value = instanceTagValue(inst, tagKey);
        if (value.isEmpty()) {
            ctx.response().setStatusCode(404).end("not-found");
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(value.get());
    }

    private void handleUserData(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String encoded = inst.getEncodedUserData();
        byte[] userData;
        if (encoded != null) {
            try {
                userData = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                LOG.warnv("Ignoring malformed saved user-data encoding for instance {0}", inst.getInstanceId());
                userData = inst.getUserData() == null ? null : inst.getUserData().getBytes(StandardCharsets.UTF_8);
            }
        } else {
            userData = inst.getUserData() == null ? null : inst.getUserData().getBytes(StandardCharsets.UTF_8);
        }
        if (userData == null || userData.length == 0) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/octet-stream")
                .end(Buffer.buffer(userData));
    }

    private void handleIdentityDocument(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String body = instanceIdentityDocument(inst, ownerAccount(inst), config.defaultAvailabilityZone());
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(body);
    }

    /**
     * {@code defaultAvailabilityZone} answers for an instance with no placement, the same configured
     * zone {@code /latest/meta-data/placement/availability-zone} reports for it.
     */
    static String instanceIdentityDocument(Instance inst, String accountId, String defaultAvailabilityZone) {
        String az = inst.getPlacement() != null ? inst.getPlacement().getAvailabilityZone() : defaultAvailabilityZone;
        String architecture = inst.getArchitecture() == null || inst.getArchitecture().isBlank()
                ? "x86_64"
                : inst.getArchitecture();
        String body = "{\"accountId\":\"" + accountId + "\","
                + "\"architecture\":\"" + architecture + "\","
                + "\"availabilityZone\":\"" + az + "\","
                + "\"imageId\":\"" + inst.getImageId() + "\","
                + "\"instanceId\":\"" + inst.getInstanceId() + "\","
                + "\"instanceType\":\"" + inst.getInstanceType() + "\","
                + "\"privateIp\":\"" + nvl(inst.getPrivateIpAddress()) + "\","
                + "\"region\":\"" + inst.getRegion() + "\","
                + "\"version\":\"2017-09-30\"}";
        return body;
    }

    // ── Instance resolution ───────────────────────────────────────────────────

    private Instance resolveInstanceByIp(RoutingContext ctx) {
        String remoteIp = ctx.request().remoteAddress().host();
        return containerIpToInstance.get(remoteIp);
    }

    /**
     * An instance whose HttpTokens is required refuses IMDSv1: as AWS does, a GET or HEAD without a
     * token gets 401 before any data is looked up, whatever the path. (No route serves HEAD, so one
     * with a token gets the router's answer: 405 on a path a GET route serves, 404 elsewhere.)
     */
    private void refuseTokenlessReadsWhenTokensAreRequired(RoutingContext ctx) {
        HttpMethod method = ctx.request().method();
        String token = ctx.request().getHeader("x-aws-ec2-metadata-token");
        if ((HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) && (token == null || token.isBlank())) {
            Instance inst = resolveInstanceByIp(ctx);
            if (inst != null && "required".equals(inst.effectiveMetadataOptions().getHttpTokens())) {
                ctx.response().setStatusCode(401).end();
                return;
            }
        }
        ctx.next();
    }

    private Instance resolveInstance(RoutingContext ctx) {
        String remoteIp = ctx.request().remoteAddress().host();
        Instance inst = containerIpToInstance.get(remoteIp);

        // IMDSv2: a presented token must be valid; an invalid or expired one gets 401 so the
        // caller fetches a new token. A token is not valid on another instance, so a caller whose
        // IP maps to a different instance also gets 401; an unregistered IP defers to the token.
        // Requests without a token fall back to IMDSv1; one whose instance requires tokens has
        // already been refused before routing.
        String token = ctx.request().getHeader("x-aws-ec2-metadata-token");
        if (token != null && !token.isBlank()) {
            SessionToken session = tokens.get(token);
            if (session != null && !session.isExpiredAt(clock.instant())) {
                if (inst != null && !Objects.equals(inst.getInstanceId(), session.instance().getInstanceId())) {
                    ctx.response().setStatusCode(401).end();
                    return null;
                }
                return session.instance();
            }
            if (session != null) {
                tokens.remove(token, session);
            }
            if (inst != null) {
                ctx.response().setStatusCode(401).end();
                return null;
            }
        }

        if (inst == null) {
            String message = unregisteredContainerMessage(remoteIp);
            LOG.warnv("IMDS: {0}", message);
            ctx.response().setStatusCode(404)
                    .putHeader("content-type", "text/plain")
                    .end(message);
        }
        return inst;
    }

    /**
     * Explains why IMDS has nothing to serve for a request coming from {@code remoteIp}.
     *
     * <p>IMDS only knows about containers that {@link Ec2ContainerManager} launched through EC2
     * {@code RunInstances}. Registering a container's SSM agent as a managed instance
     * ({@code UpdateInstanceInformation}) does not create an EC2 instance record, so a container
     * that was only registered with SSM ends up here.
     */
    static String unregisteredContainerMessage(String remoteIp) {
        return "Instance not found: no EC2 instance is registered for source IP " + remoteIp + ". "
                + "IMDS only serves containers launched through EC2 RunInstances; "
                + "registering a container as an SSM managed instance does not register it with IMDS. "
                + "Launch the container with RunInstances first, then register its SSM agent.";
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String now() {
        return ISO.format(Instant.now());
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    static String instanceTagKeys(Instance instance) {
        StringBuilder tags = new StringBuilder();
        if (instance == null || instance.getTags() == null) {
            return "";
        }
        for (Tag tag : instance.getTags()) {
            if (tag.getKey() == null || tag.getKey().isBlank()) {
                continue;
            }
            if (!tags.isEmpty()) {
                tags.append("\n");
            }
            tags.append(tag.getKey());
        }
        return tags.toString();
    }

    static Optional<String> instanceTagValue(Instance instance, String key) {
        if (instance == null || instance.getTags() == null || key == null) {
            return Optional.empty();
        }
        for (Tag tag : instance.getTags()) {
            if (key.equals(tag.getKey())) {
                return Optional.of(nvl(tag.getValue()));
            }
        }
        return Optional.empty();
    }
}
