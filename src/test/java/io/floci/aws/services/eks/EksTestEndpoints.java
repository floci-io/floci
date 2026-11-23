package io.floci.aws.services.eks;

import io.floci.aws.core.common.docker.ContainerReachableEndpoint;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Floci endpoint EKS tests give the cluster manager: the Docker host, under a certificate that names it. */
final class EksTestEndpoints {

    static final String DOCKER_HOST_HTTPS = "https://host.docker.internal:4566";

    private EksTestEndpoints() {
    }

    static ContainerReachableEndpoint dockerHostEndpoint() {
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.httpsBaseUrl(false)).thenReturn(Optional.of(DOCKER_HOST_HTTPS));
        when(endpoint.baseUrl(false)).thenReturn("http://host.docker.internal:4566");
        return endpoint;
    }
}
