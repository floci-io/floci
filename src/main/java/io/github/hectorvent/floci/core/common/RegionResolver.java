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

    // Matches ONLY a real AWS region label immediately after ".execute-api.", e.g.
    //   abc123.execute-api.ap-northeast-2.localhost:4566 -> "ap-northeast-2"
    //   abc123.execute-api.us-east-1.amazonaws.com        -> "us-east-1"
    // A region id is {geo}-{direction(s)}-{number} (us-east-1, ap-northeast-2, us-gov-east-1).
    // This deliberately does NOT match Floci's built-in execute-api DNS suffixes
    // (…execute-api.localhost, …execute-api.localhost.floci.io, …execute-api.localhost.localstack.cloud):
    // those carry no region label, so the older `[a-zA-Z0-9-]+` pattern mis-parsed "localhost"
    // as the region and broke the region-scoped API lookup. Case-insensitive per DNS.
    private static final Pattern HOST_REGION_PATTERN =
            Pattern.compile("\\.execute-api\\.([a-z]{2}-[a-z-]+-\\d+)\\.", Pattern.CASE_INSENSITIVE);

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


    /**
     * Resolves the region from a SigV4 Authorization credential scope, or
     * null when the header is absent or carries no parseable credential —
     * for callers that treat an unresolved region differently from the
     * default region.
     */
    public String resolveRegionFromAuthOrNull(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            return null;
        }
        Matcher matcher = CREDENTIAL_REGION_PATTERN.matcher(authorizationHeader);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Resolves the AWS region embedded in a region-bearing execute-api virtual host, e.g.
     * {@code {apiId}.execute-api.{region}.localhost:4566} or the real
     * {@code {apiId}.execute-api.{region}.amazonaws.com}. A WebSocket handshake carries no
     * SigV4 {@code Authorization} header, so a region-bearing host is the only place the region
     * is available there. Returns {@code null} when the host is null, is not an execute-api host,
     * or carries no region label — including Floci's built-in suffixes
     * ({@code …execute-api.localhost[.floci.io|.localstack.cloud]}), which have no region — so the
     * caller falls back (default region and/or a cross-region apiId lookup) rather than treating
     * {@code localhost} as a region.
     */
    public String resolveRegionFromHost(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        Matcher matcher = HOST_REGION_PATTERN.matcher(host);
        // Region ids are lowercase (as are the region-scoped API store keys); normalize an
        // uppercase host label like US-EAST-1 so the lookup does not 403 on a casing mismatch.
        return matcher.find() ? matcher.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    public String getDefaultRegion() {
        return defaultRegion;
    }

    public String getDefaultAccountId() {
        return defaultAccountId;
    }

    /**
     * Returns the region for the current request when called from a request context,
     * or the configured default region otherwise (async workers, startup, tests).
     */
    public String getRegion() {
        if (requestContextInstance != null) {
            try {
                String region = requestContextInstance.get().getRegion();
                if (region != null) {
                    return region;
                }
            } catch (ContextNotActiveException ignored) {
                // outside request scope — fall through to default
            }
        }
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
