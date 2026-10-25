package io.github.hectorvent.floci.services.cloudfront;

import java.time.Duration;

/**
 * The origin-timeout settings of a CloudFront distribution, shared by the distribution service
 * (which validates them on create and update) and the serving layer (which applies them to origin
 * requests), so both sides agree on the values the {@code Origin} and {@code CustomOriginConfig}
 * API references document. {@code OriginReadTimeout} accepts 1-120 seconds, default 30;
 * {@code OriginKeepaliveTimeout} accepts 1-300 seconds, default 5; {@code ConnectionTimeout}
 * accepts 1-10 seconds, default 10; {@code ConnectionAttempts} accepts 1-3, default 3.
 * {@code ResponseCompletionTimeout} has no maximum: unset, or 0, leaves the origin as long as it
 * wants to take, and a set value bounds the whole request to the origin.
 */
final class CloudFrontOriginTimeouts {

    static final int DEFAULT_ORIGIN_READ_TIMEOUT_SECONDS = 30;
    static final int MIN_ORIGIN_READ_TIMEOUT_SECONDS = 1;
    static final int MAX_ORIGIN_READ_TIMEOUT_SECONDS = 120;
    static final int DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT_SECONDS = 5;
    static final int MIN_ORIGIN_KEEPALIVE_TIMEOUT_SECONDS = 1;
    static final int MAX_ORIGIN_KEEPALIVE_TIMEOUT_SECONDS = 300;
    static final int DEFAULT_CONNECTION_TIMEOUT_SECONDS = 10;
    static final int MIN_CONNECTION_TIMEOUT_SECONDS = 1;
    static final int MAX_CONNECTION_TIMEOUT_SECONDS = 10;
    static final int DEFAULT_CONNECTION_ATTEMPTS = 3;
    static final int MIN_CONNECTION_ATTEMPTS = 1;
    static final int MAX_CONNECTION_ATTEMPTS = 3;

    static final Duration DEFAULT_ORIGIN_READ_TIMEOUT =
            Duration.ofSeconds(DEFAULT_ORIGIN_READ_TIMEOUT_SECONDS);
    static final Duration DEFAULT_CONNECTION_TIMEOUT =
            Duration.ofSeconds(DEFAULT_CONNECTION_TIMEOUT_SECONDS);
    /** No completion timeout: the origin may take as long as it wants to answer. */
    static final Duration DEFAULT_RESPONSE_COMPLETION_TIMEOUT = Duration.ZERO;
    static final Duration DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT =
            Duration.ofSeconds(DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT_SECONDS);

    private CloudFrontOriginTimeouts() {
    }
}
