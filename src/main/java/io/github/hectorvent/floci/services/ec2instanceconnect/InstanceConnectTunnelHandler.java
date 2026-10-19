package io.github.hectorvent.floci.services.ec2instanceconnect;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.SessionAccountLookup;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.core.common.auth.SigV4SignedRequest;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceConnectEndpoint;
import io.github.hectorvent.floci.services.iam.IamService;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The EC2 Instance Connect Endpoint data plane: {@code wss://<endpoint DnsName>/openTunnel}, the
 * SigV4-presigned WebSocket the AWS CLI opens for {@code aws ec2-instance-connect open-tunnel} and
 * {@code ssh --connection-type eice}. Each binary frame carries raw TCP bytes to and from the
 * instance's port, the same contract as AWS's endpoint.
 *
 * <p>This is a plain Vert.x route, so none of the JAX-RS filters run for it; it applies their rules
 * itself. Checked before the upgrade, each refusal answered as an HTTP error the CLI prints:
 * <ul>
 *   <li>the presigned query: scoped to {@code ec2-instance-connect}, {@code X-Amz-Expires} from 1
 *       to 604800 seconds, not expired, not signed more than five minutes ahead;</li>
 *   <li>with {@code floci.auth.validate-signatures}, the signature itself, verified with
 *       {@link SigV4Canonicalization} as {@code SigV4HeaderSignatureFilter} verifies every other
 *       service's;</li>
 *   <li>the account: the credential's access key picks it, as {@code AccountContextFilter} does,
 *       and the endpoint and instance are looked up in that account only;</li>
 *   <li>with IAM enforcement, {@code ec2-instance-connect:OpenTunnel} on the endpoint's ARN;</li>
 *   <li>the endpoint (known, usable), the target (a running instance at that private address in
 *       the endpoint's VPC), the port (22 or 3389, as AWS allows), and the security groups (the
 *       endpoint's egress and the instance's ingress must both allow the port).</li>
 * </ul>
 */
@ApplicationScoped
public class InstanceConnectTunnelHandler {

    private static final Logger LOG = Logger.getLogger(InstanceConnectTunnelHandler.class);

    public static final String PATH = "/openTunnel";
    static final String SIGNING_SERVICE = "ec2-instance-connect";
    static final String OPEN_TUNNEL_ACTION = "ec2-instance-connect:OpenTunnel";
    /** The ports an EC2 Instance Connect Endpoint forwards: SSH and RDP. */
    static final Set<Integer> ALLOWED_PORTS = Set.of(22, 3389);
    /** AWS's limit, and the CLI's default, for {@code maxTunnelDuration}. */
    static final int MAX_TUNNEL_SECONDS = 3600;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /** The well-known local-dev pair, honoured as every other SigV4 check in Floci honours it. */
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";

    private final Ec2Service ec2Service;
    private final Vertx vertx;
    private final EmulatorConfig config;
    private final IamService iamService;
    private final AccountResolver accountResolver;
    private final SessionAccountLookup sessionAccountLookup;
    private final IamEnforcementFilter iamEnforcement;
    private final Clock clock;
    private NetClient netClient;

    @Inject
    public InstanceConnectTunnelHandler(Ec2Service ec2Service, Vertx vertx, EmulatorConfig config,
                                        IamService iamService, AccountResolver accountResolver,
                                        SessionAccountLookup sessionAccountLookup,
                                        IamEnforcementFilter iamEnforcement) {
        this(ec2Service, vertx, config, iamService, accountResolver, sessionAccountLookup, iamEnforcement,
                Clock.systemUTC());
    }

    InstanceConnectTunnelHandler(Ec2Service ec2Service, Vertx vertx, EmulatorConfig config, IamService iamService,
                                 AccountResolver accountResolver, SessionAccountLookup sessionAccountLookup,
                                 IamEnforcementFilter iamEnforcement, Clock clock) {
        this.ec2Service = ec2Service;
        this.vertx = vertx;
        this.config = config;
        this.iamService = iamService;
        this.accountResolver = accountResolver;
        this.sessionAccountLookup = sessionAccountLookup;
        this.iamEnforcement = iamEnforcement;
        this.clock = clock;
    }

    void init(@Observes Router router) {
        router.route(PATH).handler(this::open);
        LOG.debugv("Registered the EC2 Instance Connect Endpoint tunnel on {0}", PATH);
    }

    /** What a valid request resolved to. */
    record Target(InstanceConnectEndpoint endpoint, Instance instance, String host, int port, int maxSeconds) {}

    /** Thrown for a request the endpoint refuses; the status and message become the HTTP answer. */
    static final class Refusal extends RuntimeException {
        final int status;

        Refusal(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    void open(RoutingContext ctx) {
        String upgrade = ctx.request().getHeader("Upgrade");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) {
            ctx.next();
            return;
        }
        Target target;
        try {
            target = resolve(ctx.request().path(), ctx.request().query(), ctx.request()::getHeader);
        } catch (Refusal refusal) {
            ctx.response().setStatusCode(refusal.status).end(refusal.getMessage());
            return;
        }
        // Connect first, so an unreachable instance is a clean HTTP error rather than a socket
        // that opens and immediately closes.
        ctx.request().pause();
        client().connect(target.port(), target.host()).onComplete(connected -> {
            if (connected.failed()) {
                ctx.request().resume();
                ctx.response().setStatusCode(504).end("Unable to reach " + target.instance().getPrivateIpAddress()
                        + ":" + target.port() + ": " + connected.cause().getMessage());
                return;
            }
            NetSocket socket = connected.result();
            ctx.request().resume();
            ctx.request().toWebSocket().onSuccess(webSocket -> {
                LOG.infov("EC2 Instance Connect Endpoint {0}: tunnel to {1} ({2}:{3}) opened",
                        target.endpoint().getInstanceConnectEndpointId(), target.instance().getInstanceId(),
                        target.instance().getPrivateIpAddress(), String.valueOf(target.port()));
                webSocket.pipeTo(socket);
                socket.pipeTo(webSocket);
                long timer = vertx.setTimer(target.maxSeconds() * 1000L, ignored -> webSocket.close());
                webSocket.closeHandler(ignored -> {
                    vertx.cancelTimer(timer);
                    socket.close();
                });
                socket.closeHandler(ignored -> {
                    if (!webSocket.isClosed()) {
                        webSocket.close();
                    }
                });
            }).onFailure(cause -> {
                socket.close();
                LOG.warnv("EC2 Instance Connect Endpoint tunnel upgrade failed: {0}", cause.getMessage());
            });
        });
    }

    /** Validates a tunnel request into its target, or throws {@link Refusal}. */
    Target resolve(String rawPath, String rawQuery, UnaryOperator<String> header) {
        Map<String, List<String>> params = new QueryStringDecoder(rawQuery == null ? "" : rawQuery, false)
                .parameters();
        UnaryOperator<String> query = name -> {
            List<String> values = params.get(name);
            return values == null || values.isEmpty() ? null : values.getFirst();
        };
        SigV4SignedRequest signed = SigV4SignedRequest.ALGORITHM.equals(query.apply("X-Amz-Algorithm"))
                ? SigV4SignedRequest.fromQuery(query) : null;
        CredentialScope scope = signed == null ? null : CredentialScope.parse(signed.credential());
        if (scope == null || !SIGNING_SERVICE.equals(scope.service())) {
            throw new Refusal(403, "The request is not presigned for " + SIGNING_SERVICE);
        }
        switch (signed.freshness(clock.instant())) {
            case FRESH -> { }
            case EXPIRED -> throw new Refusal(403, "Request has expired");
            case NOT_YET_VALID -> throw new Refusal(403, "Request is signed in the future");
            case BAD_EXPIRES -> throw new Refusal(403, "X-Amz-Expires must be from 1 to "
                    + SigV4SignedRequest.MAX_PRESIGNED_EXPIRY_SECONDS + " seconds");
            case MALFORMED_DATE -> throw new Refusal(403, "The request has no valid X-Amz-Date");
        }
        String akid = scope.accessKeyId();
        if (config.auth().validateSignatures()) {
            verifySignature(signed, scope, rawPath, rawQuery, header, query.apply("X-Amz-Security-Token"));
        }
        String accountId = accountResolver.resolveFromPresignedCredential(signed.credential());
        if (!akid.matches("\\d{12}")) {
            accountId = sessionAccountLookup.resolveAccountId(akid).orElse(accountId);
        }
        String account = accountId;
        return RequestScopes.callAs(account, scope.region(),
                () -> resolveTarget(scope, account, signed.credential(), query));
    }

    /** Runs as the credential's account, so every store read below is that account's. */
    private Target resolveTarget(CredentialScope scope, String accountId, String credential,
                                 UnaryOperator<String> query) {
        String region = scope.region();
        String endpointId = required(query, "instanceConnectEndpointId");
        String privateIp = required(query, "privateIpAddress");
        int port = integer(query, "remotePort", null);
        if (!ALLOWED_PORTS.contains(port)) {
            throw new Refusal(400, "remotePort " + port + " is not supported; an EC2 Instance Connect Endpoint "
                    + "forwards ports 22 and 3389");
        }
        int maxSeconds = integer(query, "maxTunnelDuration", MAX_TUNNEL_SECONDS);
        if (maxSeconds < 1 || maxSeconds > MAX_TUNNEL_SECONDS) {
            throw new Refusal(400, "maxTunnelDuration must be from 1 to " + MAX_TUNNEL_SECONDS + " seconds");
        }
        InstanceConnectEndpoint endpoint = ec2Service.findUsableInstanceConnectEndpoint(region, endpointId)
                .orElseThrow(() -> new Refusal(404, "No available EC2 Instance Connect Endpoint " + endpointId
                        + " in " + region));
        authorize(scope.accessKeyId(), accountId, credential, endpoint.getInstanceConnectEndpointArn());
        Instance instance = ec2Service.findRunningInstanceByPrivateIp(region, endpoint.getVpcId(), privateIp)
                .orElseThrow(() -> new Refusal(404, "No running instance at " + privateIp + " in "
                        + endpoint.getVpcId()));
        if (!ec2Service.instanceConnectEndpointMayReach(endpoint, instance, port)) {
            throw new Refusal(403, "The security groups of " + endpointId + " and " + instance.getInstanceId()
                    + " do not allow TCP " + port + " from the endpoint to the instance");
        }
        String host = Optional.ofNullable(instance.getContainerBridgeIp()).filter(ip -> !ip.isBlank())
                .orElse(privateIp);
        return new Target(endpoint, instance, host, port, maxSeconds);
    }

    /**
     * IAM enforcement for {@code ec2-instance-connect:OpenTunnel}, by the rules
     * {@code IamEnforcementFilter} applies to every JAX-RS request: an access key IAM does not know
     * is refused, and a known one must be allowed the action on the endpoint.
     */
    private void authorize(String akid, String accountId, String credential, String endpointArn) {
        if (!config.services().iam().enforcementEnabled() || LEGACY_ACCESS_KEY_ID.equals(akid)) {
            return;
        }
        if (!akid.equals(accountId) && !iamService.isKnownAccessKey(akid)
                && iamService.resolveCallerContext(akid, clock.instant()) == null) {
            throw new Refusal(403, "The security token included in the request is invalid.");
        }
        try {
            iamEnforcement.authorizeAdditionalResource(AccountResolver.SIGV4_SCHEME + " Credential=" + credential,
                    OPEN_TUNNEL_ACTION, endpointArn);
        } catch (AwsException e) {
            throw new Refusal(e.getHttpStatus(), e.getMessage());
        }
    }

    /**
     * The presigned signature, verified as the AWS CLI signs it: method {@code GET}, the wire path,
     * the query without {@code X-Amz-Signature}, the signed headers ({@code host}), and the SHA-256
     * of an empty body.
     */
    private void verifySignature(SigV4SignedRequest signed, CredentialScope scope, String rawPath, String rawQuery,
                                 UnaryOperator<String> header, String sessionToken) {
        if (!SigV4RequestValidator.containsHeader(signed.signedHeaders(), "host")) {
            throw new Refusal(403, "X-Amz-SignedHeaders must include host");
        }
        String secret = LEGACY_ACCESS_KEY_ID.equals(scope.accessKeyId()) ? LEGACY_SECRET_KEY
                : iamService.findSecretKey(scope.accessKeyId(), sessionToken).orElse(null);
        if (secret == null) {
            throw new Refusal(403, "The security token included in the request is invalid.");
        }
        boolean matches;
        try {
            matches = SigV4Canonicalization.signatureMatches("GET", rawPath,
                    SigV4Canonicalization.canonicalQueryString(rawQuery, true),
                    SigV4Canonicalization.canonicalHeaders(signed.signedHeaders(), name -> {
                        String value = header.apply(name);
                        return value == null ? "" : value;
                    }),
                    signed.signedHeaders(),
                    SigV4Canonicalization.payloadHash(null, signed.signedHeaders(), new byte[0], false),
                    signed.amzDate(), scope, secret, signed.signature());
        } catch (Exception e) {
            LOG.debugv(e, "EC2 Instance Connect tunnel signature could not be verified");
            matches = false;
        }
        if (!matches) {
            throw new Refusal(403, "The request signature we calculated does not match the signature you provided.");
        }
    }

    private static String required(UnaryOperator<String> query, String name) {
        String value = query.apply(name);
        if (value == null || value.isBlank()) {
            throw new Refusal(400, name + " is required");
        }
        return value;
    }

    private static int integer(UnaryOperator<String> query, String name, Integer fallback) {
        String value = query.apply(name);
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw new Refusal(400, name + " is required");
            }
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new Refusal(400, name + " must be an integer");
        }
    }

    private synchronized NetClient client() {
        if (netClient == null) {
            netClient = vertx.createNetClient(new NetClientOptions().setConnectTimeout(CONNECT_TIMEOUT_MILLIS));
        }
        return netClient;
    }
}
