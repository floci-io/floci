package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.core.common.AwsException;

/** A failed launch whose remaining resources still need teardown. */
public class EcsTaskLaunchException extends AwsException {

    private final EcsTaskHandle handle;

    public EcsTaskLaunchException(Exception cause, EcsTaskHandle handle) {
        super(cause instanceof AwsException aws ? aws.getErrorCode() : "ClientException",
                cause.getMessage(), cause instanceof AwsException aws ? aws.getHttpStatus() : 400);
        initCause(cause);
        this.handle = handle;
    }

    public EcsTaskHandle getHandle() {
        return handle;
    }
}
