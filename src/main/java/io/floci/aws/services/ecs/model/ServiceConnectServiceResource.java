package io.floci.aws.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One endpoint of a service's Service Connect configuration: the Cloud Map service ECS created
 * for it in the namespace, named {@code discoveryName}, at {@code discoveryArn}. Reported on the
 * service's deployment as AWS's {@code ServiceConnectServiceResource}.
 */
@RegisterForReflection
public record ServiceConnectServiceResource(String discoveryName, String discoveryArn) {
}
