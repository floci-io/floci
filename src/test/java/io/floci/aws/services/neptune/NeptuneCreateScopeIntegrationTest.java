package io.floci.aws.services.neptune;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.neptune.container.NeptuneContainerHandle;
import io.floci.aws.services.neptune.container.NeptuneContainerManager;
import io.floci.aws.services.neptune.model.NeptuneCluster;
import io.floci.aws.services.neptune.model.NeptuneDbType;
import io.floci.aws.services.neptune.proxy.NeptuneProxyManager;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class NeptuneCreateScopeIntegrationTest {

    @Inject
    EmulatorConfig config;

    @Inject
    RegionResolver regionResolver;

    @Test
    void pendingCreateOnlyClaimsItsRequestAccountAndRegion() throws Exception {
        NeptuneContainerManager containers = mock(NeptuneContainerManager.class);
        NeptuneProxyManager proxies = mock(NeptuneProxyManager.class);
        NeptuneService service = new NeptuneService(config, regionResolver, containers, proxies,
                new InMemoryStorageFactory("000000000000"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(containers.tryStart(anyString(), anyString(), any(NeptuneDbType.class), anyString(), anyString()))
                .thenAnswer(call -> {
                    if ("000000000000".equals(call.getArgument(3)) && "us-east-1".equals(call.getArgument(4))) {
                        started.countDown();
                        assertTrue(finish.await(5, TimeUnit.SECONDS), "The test must release backend startup");
                    }
                    return new NeptuneContainerHandle(
                            call.getArgument(3) + "-" + call.getArgument(4), "shared", "localhost", 8182);
                });

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<NeptuneCluster> owner = executor.submit(() ->
                    RequestScopes.callAs("000000000000", "us-east-1",
                            () -> service.createDbCluster("shared", "1.3.2.1", false)));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS), "The first create must enter backend startup");
                RequestScopes.runAs("000000000000", "us-east-1", () -> {
                    AwsException duplicate = assertThrows(AwsException.class,
                            () -> service.createDbCluster("shared", "1.3.2.1", false));
                    assertEquals("DBClusterAlreadyExistsFault", duplicate.getErrorCode());
                    verify(containers, times(1)).tryStart(
                            anyString(), anyString(), any(NeptuneDbType.class), anyString(), anyString());
                });
                RequestScopes.runAs("111111111111", "us-east-1", () -> {
                    NeptuneCluster cluster = service.createDbCluster("shared", "1.3.2.1", false);
                    assertEquals("111111111111-us-east-1", cluster.getContainerId());
                    assertEquals("arn:aws:neptune:us-east-1:111111111111:cluster:shared", cluster.getDbClusterArn());
                    assertEquals(cluster.getContainerId(), service.getDbCluster("shared").getContainerId());
                });
                RequestScopes.runAs("000000000000", "eu-west-1", () -> {
                    NeptuneCluster cluster = service.createDbCluster("shared", "1.3.2.1", false);
                    assertEquals("000000000000-eu-west-1", cluster.getContainerId());
                    assertEquals("arn:aws:neptune:eu-west-1:000000000000:cluster:shared", cluster.getDbClusterArn());
                    assertEquals(cluster.getContainerId(), service.getDbCluster("shared").getContainerId());
                });
                verify(containers, never()).stopByClusterId(anyString());
                verify(proxies, never()).stopProxy(anyString());
            } finally {
                finish.countDown();
            }
            assertEquals("000000000000-us-east-1", owner.get(5, TimeUnit.SECONDS).getContainerId());
            RequestScopes.runAs("000000000000", "us-east-1", () ->
                    assertEquals("000000000000-us-east-1", service.getDbCluster("shared").getContainerId()));
        }
    }
}
