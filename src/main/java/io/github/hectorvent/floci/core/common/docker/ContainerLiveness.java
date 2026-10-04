package io.github.hectorvent.floci.core.common.docker;

/**
 * Outcome of probing a container's liveness through the Docker API.
 * {@link #UNKNOWN} means the probe itself failed, so nothing is known about the container.
 */
public enum ContainerLiveness {
    RUNNING,
    NOT_RUNNING,
    UNKNOWN
}
