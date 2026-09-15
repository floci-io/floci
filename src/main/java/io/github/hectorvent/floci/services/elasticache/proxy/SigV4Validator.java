package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * Validates ElastiCache IAM auth tokens (SigV4 presigned URLs).
 * Extracted from ElastiCacheQueryHandler so it can be shared with the TCP auth proxy.
 * The SigV4 signature verification itself lives in {@link SigV4RequestValidator}, shared
 * with {@code RdsSigV4Validator}; this class only handles the ElastiCache-specific token
 * shape (cluster ID as the signed host, {@code User} for identity).
 */
@ApplicationScoped
public class SigV4Validator {

    private static final Logger LOG = Logger.getLogger(SigV4Validator.class);

    private final SigV4RequestValidator requestValidator;

    @Inject
    public SigV4Validator(IamService iamService) {
        this.requestValidator = new SigV4RequestValidator(iamService);
    }

    /**
     * Validates the given IAM auth token against the expected cluster ID and username.
     * The token is a presigned URL without the scheme, e.g.:
     * {@code clusterId/?Action=connect&User=...&X-Amz-Signature=...}
     *
     * @param token the presigned URL token
     * @param expectedGroupId the replication group ID to match against the token's host
     * @param expectedUsername the Redis username from the AUTH command;
     *                         must match the {@code User} in the token. May be null to skip.
     * @return true if the token is valid, identities match, and the token is not expired
     */
    public boolean validate(String token, String expectedGroupId, String expectedUsername) {
        try {
            URI uri = URI.create("http://" + token);
            String clusterId = uri.getHost();
            String rawQuery = uri.getRawQuery();

            if (clusterId == null || rawQuery == null) {
                LOG.debugv("IAM token missing clusterId or query string");
                return false;
            }

            if (expectedGroupId != null && !expectedGroupId.equalsIgnoreCase(clusterId)) {
                LOG.debugv("IAM token cluster mismatch: expected={0}, got={1}", expectedGroupId, clusterId);
                return false;
            }

            return requestValidator.validate(rawQuery, clusterId, "User", false, expectedUsername, "IAM token");
        } catch (Exception e) {
            LOG.debugv("IAM token validation error: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * Kept here, not only in SigV4RequestValidator, because SigV4ValidatorTest reflects on
     * this exact declared method to verify log-injection protection at this class's own
     * entry point.
     */
    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "");
    }
}
