package io.floci.aws.services.rds.container;

import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.services.rds.model.DatabaseEngine;

/** Resolves the current reachable endpoint of the container owned by an RDS runtime. */
@FunctionalInterface
public interface RdsBackendEndpointResolver {

    EndpointInfo resolveBackendEndpoint(String runtimeId, DatabaseEngine engine);
}
