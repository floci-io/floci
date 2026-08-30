package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;

/**
 * Starts and stops Lambda execution environments. Implementations back each
 * environment with a concrete runtime: a Docker container ({@link ContainerLauncher})
 * or a Kubernetes pod ({@code KubernetesPodLauncher}). Selected at startup by
 * {@code floci.services.lambda.executor}.
 */
public interface LambdaRuntimeLauncher {

    ContainerHandle launch(LambdaFunction fn);

    void stop(ContainerHandle handle);

    boolean isAlive(ContainerHandle handle);
}
