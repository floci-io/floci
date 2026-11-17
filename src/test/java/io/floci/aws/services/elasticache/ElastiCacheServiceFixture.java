package io.floci.aws.services.elasticache;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerHandle;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.floci.aws.services.elasticache.container.ValkeyClusterFormation;
import io.floci.aws.services.elasticache.proxy.ElastiCacheProxyManager;
import io.floci.aws.services.kms.KmsService;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@link ElastiCacheService} over in-memory stores, with the container, proxy and KMS layers mocked:
 * every group gets a container named after it, its proxy starts, and no KMS key resolves.
 */
final class ElastiCacheServiceFixture {

    final ElastiCacheContainerManager containerManager = mock(ElastiCacheContainerManager.class);
    final ElastiCacheProxyManager proxyManager = mock(ElastiCacheProxyManager.class);
    /** The in-memory store behind each storage file, by file name. */
    final Map<String, AccountAwareStorageBackend<?>> backendsByFile = new ConcurrentHashMap<>();
    final ElastiCacheService service;

    ElastiCacheServiceFixture() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.proxyBasePort()).thenReturn(16379);
        when(ecConfig.proxyMaxPort()).thenReturn(16399);
        when(ecConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                backendsByFile.computeIfAbsent(inv.getArgument(1),
                        file -> AccountAwareStorageBackend.inMemory("000000000000")));
        when(containerManager.tryStart(anyString(), anyString(), any()))
                .thenAnswer(inv -> containerHandle(inv.getArgument(0)));
        doNothing().when(proxyManager).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        KmsService kmsService = mock(KmsService.class);
        when(kmsService.describeKey(any(), any())).thenThrow(new AwsException("NotFoundException", "Key not found", 404));
        service = new ElastiCacheService(containerManager, proxyManager, mock(ValkeyClusterFormation.class),
                storageFactory, config, mock(Ec2Service.class), new RegionResolver("us-east-1", "000000000000"),
                kmsService, new ElastiCacheProvisioningIds());
    }

    /** The container the group {@code groupId} gets. */
    static ElastiCacheContainerHandle containerHandle(String groupId) {
        return new ElastiCacheContainerHandle("container-" + groupId, groupId, "localhost", 6379);
    }
}
