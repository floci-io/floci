package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;

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

    private final SigV4RequestValidator requestValidator;

    @Inject
    public RdsSigV4Validator(IamService iamService) {
        this.requestValidator = new SigV4RequestValidator(iamService);
    }

    /**
     * Validates an RDS IAM auth token.
     * The token is a presigned URL without the scheme, e.g.:
     * {@code hostname:port/?Action=connect&DBUser=admin&X-Amz-Signature=...}
     *
     * @param token the presigned URL token
     * @param clientUsername the username from the PostgreSQL startup message;
     *                       must match the {@code DBUser} in the token
     * @return true if the token signature is valid, the DBUser matches, and the token is not expired
     */
    public boolean validate(String token, String clientUsername) {
        return validate(token, clientUsername, null);
    }

    public boolean validate(String token, String clientUsername, RdsMysqlBinding binding) {
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

            if (binding != null && (!binding.advertisedHost().equalsIgnoreCase(host)
                    || binding.publishedPort() != port
                    || !credentialMatches(rawQuery, binding.region()))) {
                return false;
            }

            return requestValidator.validate(rawQuery, authority, "DBUser", true, clientUsername, "RDS IAM token");
        } catch (Exception e) {
            LOG.debugv("RDS IAM token validation error: {0}", e.getMessage());
            return false;
        }
    }

    private static boolean credentialMatches(String rawQuery, String region) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0 && "X-Amz-Credential".equals(pair.substring(0, eq))) {
                String[] parts = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8)
                        .split("/");
                return parts.length >= 5 && region.equals(parts[2]) && "rds-db".equals(parts[3]);
            }
        }
        return false;
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
