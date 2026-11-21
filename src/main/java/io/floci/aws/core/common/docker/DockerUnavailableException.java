package io.floci.aws.core.common.docker;

import java.io.IOException;

/**
 * Raised instead of a Docker call while {@link DockerCircuitBreaker} is open: the daemon failed
 * several calls in a row at the transport and did not answer a ping since, so the call fails at once
 * rather than waiting out its own timeouts. {@link DockerRetry#isTransientIo} never treats it as
 * transient, so no retry layer replays it.
 */
public final class DockerUnavailableException extends IOException {

    public DockerUnavailableException(String message) {
        super(message);
    }
}
