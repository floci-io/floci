package io.floci.aws.core.common;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * What GetSessionEndpoint answers for a Spark Connect session, EMR Serverless's and EMR's alike: the endpoint a client
 * connects to and an authentication token, with when it expires. Each call gives a new token. Where a service runs a
 * Spark Connect server for its sessions, as EMR Serverless does in its docker runner mode, {@link SparkConnectProxy}
 * checks the token on each call; elsewhere nothing checks one.
 */
public record SparkConnectEndpoint(String url, String authToken, Instant authTokenExpiresAt) {

    private static final int TOKEN_BYTES = 32;

    /**
     * The endpoint at {@code path} on Floci's {@code baseUrl} (Floci's address itself for an empty one), a trailing
     * slash of which is dropped, with a new random token that expires {@code validity} after {@code now}.
     */
    public static SparkConnectEndpoint issue(String baseUrl, String path, Instant now, Duration validity) {
        String base = baseUrl == null ? "" : baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        byte[] token = new byte[TOKEN_BYTES];
        // A SecureRandom of the call's own: one held in a static field would be built into the native image's heap,
        // which its build refuses.
        new SecureRandom().nextBytes(token);
        return new SparkConnectEndpoint(base + path, HexFormat.of().formatHex(token), now.plus(validity));
    }
}
