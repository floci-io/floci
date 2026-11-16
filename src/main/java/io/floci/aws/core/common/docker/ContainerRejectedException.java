package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.exception.DockerException;

/**
 * The Docker daemon refused to create a container with a client error, such as an authorization
 * plugin denying a host config option. Repeating the same create gets the same answer, so a
 * service reports it as a non-retryable error instead of {@code InternalFailure}.
 */
public class ContainerRejectedException extends RuntimeException {

    public ContainerRejectedException(DockerException cause) {
        super("Docker rejected the container create: " + cause.getMessage(), cause);
    }

    /**
     * Whether the daemon answered a create with a client error that a retry would get again. A
     * name conflict and a missing object keep their own types, since callers recover from or
     * report them, and a timeout (408) or throttling (429) stays retryable.
     */
    static boolean isRejection(DockerException e) {
        int status = e.getHttpStatus();
        return status >= 400 && status < 500
                && status != 404 && status != 408 && status != 409 && status != 429;
    }
}
