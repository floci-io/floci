package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class RegionResolver {

    // Matches: Credential=AKID/20260215/us-west-2/s3/aws4_request
    private static final Pattern CREDENTIAL_REGION_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/([^/]+)/");

    private final String defaultRegion;
    private final String defaultAccountId;

    // Field-injected so the two-arg constructor used in tests remains valid.
    @Inject
    Instance<RequestContext> requestContextInstance;

    @Inject
    public RegionResolver(EmulatorConfig config) {
        this(config.defaultRegion(), config.defaultAccountId());
    }

    public RegionResolver(String defaultRegion, String defaultAccountId) {
        this.defaultRegion = defaultRegion;
        this.defaultAccountId = defaultAccountId;
    }

    public String resolveRegion(HttpHeaders headers) {
        if (headers == null) {
            return defaultRegion;
        }
        return resolveRegionFromAuth(headers.getHeaderString("Authorization"));
    }

    public String resolveRegionFromAuth(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            return defaultRegion;
        }
        Matcher matcher = CREDENTIAL_REGION_PATTERN.matcher(authorizationHeader);
        return matcher.find() ? matcher.group(1) : defaultRegion;
    }

    /**
     * Resolves the region from an X-Amz-Credential value found in
     * presigned URL query parameters.
     * Format: accessKeyID/date/region/service/aws4_request
     * Falls back to the configured default region if the credential value
     * is null, empty, or does not contain enough segments.
     */
    public String resolveRegionFromPresignedCredential(String credentialValue) {
        if (credentialValue == null || credentialValue.isEmpty()) {
            return defaultRegion;
        }
        String[] parts = credentialValue.split("/");
        if (parts.length >= 3) {
            return parts[2];
        }
        return defaultRegion;
    }

    public String getDefaultRegion() {
        return defaultRegion;
    }

    /**
     * Returns the account ID for the current request when called from a request context,
     * or the configured default account ID otherwise (async workers, startup, tests).
     */
    public String getAccountId() {
        if (requestContextInstance != null) {
            try {
                String accountId = requestContextInstance.get().getAccountId();
                if (accountId != null) {
                    return accountId;
                }
            } catch (ContextNotActiveException ignored) {
                // outside request scope — fall through to default
            }
        }
        return defaultAccountId;
    }

    public String buildArn(String service, String region, String resource) {
        return AwsArnUtils.Arn.of(service, region, getAccountId(), resource).toString();
    }
}
