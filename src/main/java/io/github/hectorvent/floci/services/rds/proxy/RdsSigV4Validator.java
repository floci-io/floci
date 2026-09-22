package io.github.hectorvent.floci.services.rds.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Validates RDS IAM auth tokens (SigV4 presigned URLs).
 * RDS tokens sign {@code host:port} in the canonical host header, unlike ElastiCache
 * which signs only the cluster hostname. The token format is:
 * {@code hostname:port/?Action=connect&DBUser=user&X-Amz-*=...}
 * The SigV4 signature verification itself lives in {@link SigV4RequestValidator}, shared
 * with {@code SigV4Validator} (ElastiCache); this class only handles the RDS-specific
 * token shape. Reused as-is by Redshift, whose IAM auth works identically.
 */
@ApplicationScoped
public class RdsSigV4Validator {

    private static final Logger LOG = Logger.getLogger(RdsSigV4Validator.class);

    private static final IamPolicyEvaluator POLICY_EVALUATOR = new IamPolicyEvaluator(new ObjectMapper());

    private final IamService iamService;
    private final SigV4RequestValidator requestValidator;
    private final BooleanSupplier enforcementEnabled;

    @Inject
    public RdsSigV4Validator(IamService iamService, EmulatorConfig config) {
        this(iamService, () -> config.services().iam().enforcementEnabled());
    }

    /** Signature-only validation, for callers that run without IAM enforcement. */
    public RdsSigV4Validator(IamService iamService) {
        this(iamService, () -> false);
    }

    RdsSigV4Validator(IamService iamService, BooleanSupplier enforcementEnabled) {
        this.iamService = iamService;
        this.requestValidator = new SigV4RequestValidator(iamService);
        this.enforcementEnabled = enforcementEnabled;
    }

    /**
     * Validates an RDS IAM auth token.
     * The token is a presigned URL without the scheme, e.g.:
     * {@code hostname:port/?Action=connect&DBUser=admin&X-Amz-Signature=...}
     *
     * @param token the presigned URL token
     * @param clientUsername the username from the client's startup or handshake message;
     *                       must match the {@code DBUser} in the token
     * @param binding what the proxy the token arrived at publishes; a token without one is
     *                refused, since there is nothing to check it against
     * @return true if the token signature is valid, the DBUser matches, the token is not expired,
     *         it names the bound endpoint when the binding requires that, and with IAM
     *         enforcement on its principal is allowed {@code rds-db:connect} on the DBUser
     */
    public boolean validate(String token, String clientUsername, RdsProxyBinding binding) {
        if (binding == null) {
            LOG.warn("Refusing an RDS IAM token that arrived without a proxy binding to validate it against");
            return false;
        }
        try {
            URI uri = URI.create("http://" + token);
            String host = uri.getHost();
            int port = uri.getPort();
            String rawQuery = uri.getRawQuery();

            if (host == null || rawQuery == null) {
                LOG.debugv("RDS IAM token missing host or query string");
                return false;
            }

            // RDS tokens sign host:port in the canonical host header
            String authority = (port > 0) ? host + ":" + port : host;

            String[] credential = credentialScope(rawQuery);
            if (credential == null) {
                LOG.debugv("RDS IAM token missing its credential scope");
                return false;
            }
            if (binding.tokensBoundToEndpoint() && (!binding.acceptsHost(host)
                    || binding.publishedPort() != port
                    || !binding.region().equals(credential[2])
                    || !"rds-db".equals(credential[3]))) {
                return false;
            }

            if (!requestValidator.validate(rawQuery, authority, "DBUser", true, clientUsername, "RDS IAM token")) {
                return false;
            }
            return !enforcementEnabled.getAsBoolean()
                    || isAllowedToConnect(credential[0], clientUsername, binding);
        } catch (Exception e) {
            LOG.debugv("RDS IAM token validation error: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * The {@code rds-db:connect} check AWS runs after the token signature is verified: the
     * token's principal must be allowed to connect as {@code dbUser} to the bound database.
     * An access key that IAM does not know is bypassed, as {@code IamEnforcementFilter} does.
     */
    private boolean isAllowedToConnect(String accessKeyId, String dbUser, RdsProxyBinding binding) {
        CallerContext caller = iamService.resolveCallerContext(accessKeyId);
        if (caller == null) {
            return true;
        }
        String resource = "arn:aws:rds-db:" + binding.region() + ":" + binding.accountId()
                + ":dbuser:" + binding.resourceId() + "/" + dbUser;
        IamPolicyEvaluator.SimulationDecision decision =
                POLICY_EVALUATOR.simulatePrincipalPolicy(caller, "rds-db:connect", resource, Map.of());
        if (decision != IamPolicyEvaluator.SimulationDecision.ALLOWED) {
            LOG.warnv("Failed to authorize the connection request for user {0} because the IAM policy "
                            + "assumed by the caller ''{1}'' is not authorized to perform rds-db:connect on {2}",
                    sanitizeForLog(dbUser),
                    iamService.resolveCallerArn(accessKeyId).orElse(sanitizeForLog(accessKeyId)),
                    resource);
            return false;
        }
        return true;
    }

    /**
     * The {@code X-Amz-Credential} scope split into its parts
     * ({@code accessKeyId/date/region/service/aws4_request}), or {@code null} if absent or malformed.
     */
    private static String[] credentialScope(String rawQuery) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0 && "X-Amz-Credential".equals(pair.substring(0, eq))) {
                String[] parts = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                        .split("/");
                return parts.length >= 5 ? parts : null;
            }
        }
        return null;
    }

    /**
     * Kept here, not only in SigV4RequestValidator, because RdsSigV4ValidatorTest reflects
     * on this exact declared method to verify log-injection protection at this class's own
     * entry point.
     */
    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "");
    }
}
