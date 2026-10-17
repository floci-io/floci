package io.github.hectorvent.floci.services.cloudfront;

import java.io.IOException;

/**
 * Signals that a custom origin did not answer before the timeout configured on the origin expired.
 * CloudFront answers the viewer with a 504 Gateway Timeout for this case, distinct from the 502 it
 * answers for a genuine bad gateway, so the serving layer must be able to tell the two apart.
 */
final class CloudFrontOriginTimeoutException extends IOException {

    CloudFrontOriginTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
