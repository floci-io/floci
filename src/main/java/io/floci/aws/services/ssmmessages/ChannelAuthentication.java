package io.floci.aws.services.ssmmessages;

import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.RequestHost;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.auth.CredentialScope;
import io.floci.aws.core.common.auth.SigV4Canonicalization;
import io.floci.aws.core.common.auth.SigV4RequestValidator;
import io.floci.aws.core.common.auth.SigV4SignedRequest;
import io.floci.aws.services.iam.IamService;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.floci.aws.core.common.auth.SigV4RequestValidator.LEGACY_ACCESS_KEY_ID;
import static io.floci.aws.core.common.auth.SigV4RequestValidator.LEGACY_SECRET_KEY;

/** Authenticates the signed HTTP requests that enter the message gateway outside JAX-RS. */
@ApplicationScoped
public class ChannelAuthentication {

    public static final String SIGNING_SERVICE = "ssmmessages";

    private static final Logger LOG = Logger.getLogger(ChannelAuthentication.class);
    private static final String AUTHORIZATION = "Authorization";
    private static final String DATE = "X-Amz-Date";
    private static final String TOKEN = "X-Amz-Security-Token";
    private static final Set<String> PRESIGN_PARAMETERS = Set.of("X-Amz-Algorithm", "X-Amz-Credential",
            DATE, "X-Amz-Expires", "X-Amz-SignedHeaders", "X-Amz-Signature", TOKEN);
    private static final byte[] EMPTY_BODY = new byte[0];

    private final IamService iamService;
    private final AccountResolver accountResolver;
    private final IamEnforcementFilter iamEnforcement;
    private final Clock clock;

    @Inject
    public ChannelAuthentication(IamService iamService, AccountResolver accountResolver,
                                 IamEnforcementFilter iamEnforcement) {
        this(iamService, accountResolver, iamEnforcement, Clock.systemUTC());
    }

    ChannelAuthentication(IamService iamService, AccountResolver accountResolver,
                          IamEnforcementFilter iamEnforcement, Clock clock) {
        this.iamService = iamService;
        this.accountResolver = accountResolver;
        this.iamEnforcement = iamEnforcement;
        this.clock = clock;
    }

    public record AgentIdentity(String accountId, String region, String instanceId) {}

    private record Claim(SigV4SignedRequest signature, CredentialScope scope, String sessionToken, String host) {}

    /** Returns the signing instance. The caller must match it to the target of the requested channel. */
    public AgentIdentity authenticateAgent(HttpServerRequest request, byte[] body, String action) {
        Claim claim = readClaim(request);
        IamService.Ec2InstanceIdentity instance = signingInstance(claim).orElseThrow(ChannelAuthentication::denied);
        return authenticateAgent(request, body, action, claim, instance);
    }

    /** Authenticates the signing instance when an IMDS credential signs a data-channel upgrade. */
    public Optional<AgentIdentity> authenticateAgentUpgradeIfClaimed(HttpServerRequest request, String action) {
        Claim claim = readClaim(request);
        return signingInstance(claim).map(instance -> authenticateAgent(request, EMPTY_BODY, action, claim, instance));
    }

    private Optional<IamService.Ec2InstanceIdentity> signingInstance(Claim claim) {
        return claim == null ? Optional.empty()
                : iamService.findEc2InstanceIdentity(claim.scope().accessKeyId(), claim.sessionToken());
    }

    private AgentIdentity authenticateAgent(HttpServerRequest request, byte[] body, String action, Claim claim,
                                            IamService.Ec2InstanceIdentity instance) {
        if (claim.signature().presigned()) {
            throw denied();
        }
        String key = claim.scope().accessKeyId();
        String region = claim.scope().region();
        return RequestScopes.callAs(instance.accountId(), region, () -> {
            String secret = iamService.findSecretKey(key, claim.sessionToken())
                    .orElseThrow(ChannelAuthentication::denied);
            verify(request, body, claim, secret);
            iamEnforcement.authorizeRequestParameters(request.getHeader(AUTHORIZATION), action, "*", request::getParam);
            return new AgentIdentity(instance.accountId(), region, instance.instanceId());
        });
    }

    /** Verifies and authorises a client WebSocket's SigV4 claim when present. The channel grant is still required. */
    public Optional<String> authorizeClient(HttpServerRequest request, String accountId, String region, String sessionArn) {
        Claim claim = readClaim(request);
        if (claim == null) {
            return Optional.empty();
        }
        String key = claim.scope().accessKeyId();
        boolean legacy = LEGACY_ACCESS_KEY_ID.equals(key);
        String callerAccount = legacy ? accountResolver.resolve(null) : iamService.resolveAccountId(key).orElse(null);
        if (!accountId.equals(callerAccount) || !region.equals(claim.scope().region())) {
            throw denied();
        }
        return RequestScopes.callAs(callerAccount, region, () -> {
            String secret = legacy ? LEGACY_SECRET_KEY : iamService.findSecretKey(key, claim.sessionToken())
                    .orElseThrow(ChannelAuthentication::denied);
            verify(request, EMPTY_BODY, claim, secret);
            iamEnforcement.authorizeRequestParameters(AccountResolver.SIGV4_SCHEME + " Credential="
                    + claim.signature().credential(), "ssmmessages:OpenDataChannel", sessionArn, request::getParam);
            return Optional.of(key);
        });
    }

    private Claim readClaim(HttpServerRequest request) {
        MultiMap headers = request.headers();
        String authorization = single(headers.getAll(AUTHORIZATION));
        Map<String, List<String>> params = queryParameters(request.query());
        boolean presigned = PRESIGN_PARAMETERS.stream().anyMatch(params::containsKey);
        if (presigned && authorization != null) {
            throw denied();
        }
        if (!presigned && authorization == null) {
            return null;
        }
        if (presigned && !SigV4SignedRequest.ALGORITHM.equals(single(params.get("X-Amz-Algorithm")))) {
            throw denied();
        }
        SigV4SignedRequest signature = presigned
                ? SigV4SignedRequest.fromQuery(name -> single(params.get(name)))
                : SigV4SignedRequest.fromHeader(authorization, single(headers.getAll(DATE)));
        CredentialScope scope = signature == null ? null : CredentialScope.parse(signature.credential());
        String host = single(headers.getAll("host"));
        if (host == null && request.version() == HttpVersion.HTTP_2) {
            host = RequestHost.of(request);
        }
        if (scope == null || !SIGNING_SERVICE.equals(scope.service()) || !signature.amzDate().startsWith(scope.date())
                || signature.freshness(clock.instant()) != SigV4SignedRequest.Freshness.FRESH
                || !SigV4RequestValidator.containsHeader(signature.signedHeaders(), "host")
                || host == null || host.isBlank()) {
            throw denied();
        }
        String sessionToken = presigned ? single(params.get(TOKEN)) : single(headers.getAll(TOKEN));
        if (!presigned && (!SigV4RequestValidator.containsHeader(signature.signedHeaders(), "x-amz-date")
                || (sessionToken != null && !SigV4RequestValidator.containsHeader(signature.signedHeaders(),
                        "x-amz-security-token")))) {
            throw denied();
        }
        return new Claim(signature, scope, sessionToken, host);
    }

    private static Map<String, List<String>> queryParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        try {
            return QueryStringDecoder.builder().hasPath(false).semicolonIsNormalChar(true).htmlQueryDecoding(false)
                    .maxParams(rawQuery.length()).build(rawQuery).parameters();
        } catch (IllegalArgumentException expected) {
            throw denied();
        }
    }

    private static void verify(HttpServerRequest request, byte[] body, Claim claim, String secret) {
        SigV4SignedRequest signed = claim.signature();
        try {
            String headers = SigV4Canonicalization.canonicalHeaders(signed.signedHeaders(),
                    name -> "host".equals(name) ? SigV4RequestValidator.normalizeHeaderValue(claim.host())
                            : request.headers().getAll(name).stream().map(SigV4RequestValidator::normalizeHeaderValue)
                            .collect(Collectors.joining(",")));
            String query = SigV4Canonicalization.canonicalQueryString(request.query(), signed.presigned());
            if (matches(request, claim, secret, headers, query, Digests.sha256Hex(body))
                    || (signed.presigned() && matches(request, claim, secret, headers, query, "UNSIGNED-PAYLOAD"))) {
                return;
            }
        } catch (Exception e) {
            LOG.debug("Message gateway signature could not be verified", e);
        }
        throw denied();
    }

    private static boolean matches(HttpServerRequest request, Claim claim, String secret, String headers,
                                   String query, String payloadHash) throws Exception {
        SigV4SignedRequest signed = claim.signature();
        return SigV4Canonicalization.signatureMatches(request.method().name(), request.path(), query, headers,
                signed.signedHeaders(), payloadHash, signed.amzDate(), claim.scope(), secret, signed.signature());
    }

    private static String single(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw denied();
        }
        return values.getFirst();
    }

    private static AwsException denied() {
        return new AwsException("AccessDeniedException", "The message gateway request could not be authenticated.", 403);
    }
}
