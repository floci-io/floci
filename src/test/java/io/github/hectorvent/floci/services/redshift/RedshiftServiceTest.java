package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;
import java.util.Optional;

class RedshiftServiceTest {
    @Test
    void testCreateCluster() {
        StorageFactory sf = mock(StorageFactory.class);
        AccountAwareStorageBackend<Cluster> backend = mock(AccountAwareStorageBackend.class);
        when(sf.<Cluster>create(eq("redshift"), eq("redshift-clusters.json"), any())).thenReturn(backend);
        when(backend.get(anyString())).thenReturn(Optional.empty());
        
        RedshiftContainerManager cm = mock(RedshiftContainerManager.class);
        when(cm.start(any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));
        
        RedshiftService service = new RedshiftService(sf, cm);
        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123");
        assertNotNull(cluster);
        assertEquals("my-cluster", cluster.getClusterIdentifier());
        assertEquals("available", cluster.getClusterStatus());
        assertEquals("localhost", cluster.getEndpoint().getAddress());
    }
}
