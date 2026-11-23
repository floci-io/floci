package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2MetadataServer;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.eks.model.Nodegroup;

/**
 * Cluster managers for tests that leave some collaborators out. Each one left out is null, the
 * archive retry backoff is the default, and the Floci endpoint is {@link EksTestEndpoints#dockerHostEndpoint()}.
 */
final class EksClusterManagers {

    private EksClusterManagers() {
    }

    static EksClusterManager create(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector,
                                    PortAllocator portAllocator,
                                    DockerHostResolver dockerHostResolver,
                                    EcrRegistryManager ecrRegistryManager,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver) {
        return create(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, null, null, null, null);
    }

    static EksClusterManager create(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector,
                                    PortAllocator portAllocator,
                                    DockerHostResolver dockerHostResolver,
                                    EcrRegistryManager ecrRegistryManager,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver,
                                    Ec2MetadataServer metadataServer) {
        return create(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer, null, null, null);
    }

    static EksClusterManager create(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector,
                                    PortAllocator portAllocator,
                                    DockerHostResolver dockerHostResolver,
                                    EcrRegistryManager ecrRegistryManager,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver,
                                    Ec2MetadataServer metadataServer,
                                    EksOidcService oidcService) {
        return create(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer, oidcService, null, null);
    }

    static EksClusterManager create(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector,
                                    PortAllocator portAllocator,
                                    DockerHostResolver dockerHostResolver,
                                    EcrRegistryManager ecrRegistryManager,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver,
                                    Ec2MetadataServer metadataServer,
                                    EksOidcService oidcService,
                                    FlociCertificateAuthority certificateAuthority) {
        return create(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer, oidcService,
                certificateAuthority, null);
    }

    static EksClusterManager create(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector,
                                    PortAllocator portAllocator,
                                    DockerHostResolver dockerHostResolver,
                                    EcrRegistryManager ecrRegistryManager,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver,
                                    Ec2MetadataServer metadataServer,
                                    EksOidcService oidcService,
                                    ContainerLogStreamer logStreamer) {
        return create(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer, oidcService,
                null, logStreamer);
    }

    static EksClusterManager create(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector,
                                    PortAllocator portAllocator,
                                    DockerHostResolver dockerHostResolver,
                                    EcrRegistryManager ecrRegistryManager,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver,
                                    Ec2MetadataServer metadataServer,
                                    EksOidcService oidcService,
                                    FlociCertificateAuthority certificateAuthority,
                                    ContainerLogStreamer logStreamer) {
        return create(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer,
                oidcService, certificateAuthority, logStreamer, (StorageBackend<String, Nodegroup>) null);
    }

    static EksClusterManager create(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector,
                                    PortAllocator portAllocator,
                                    DockerHostResolver dockerHostResolver,
                                    EcrRegistryManager ecrRegistryManager,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver,
                                    Ec2MetadataServer metadataServer,
                                    EksOidcService oidcService,
                                    FlociCertificateAuthority certificateAuthority,
                                    ContainerLogStreamer logStreamer,
                                    StorageBackend<String, Nodegroup> nodeGroupStorage) {
        return create(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer,
                oidcService, certificateAuthority, logStreamer, nodeGroupStorage,
                RetryingTarCopier.DEFAULT_BACKOFF_MS);
    }

    static EksClusterManager create(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector,
                                    PortAllocator portAllocator,
                                    DockerHostResolver dockerHostResolver,
                                    EcrRegistryManager ecrRegistryManager,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver,
                                    Ec2MetadataServer metadataServer,
                                    EksOidcService oidcService,
                                    FlociCertificateAuthority certificateAuthority,
                                    ContainerLogStreamer logStreamer,
                                    StorageBackend<String, Nodegroup> nodeGroupStorage,
                                    long archiveRetryBackoffMillis) {
        return new EksClusterManager(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer,
                oidcService, certificateAuthority, logStreamer, nodeGroupStorage,
                archiveRetryBackoffMillis, new Ec2ImageCatalog(), EksTestEndpoints.dockerHostEndpoint());
    }
}
