package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RdsDataResourceResolverTest {

    @Test
    void resolvesClusterArnToContainerTarget() {
        RdsService rdsService = mock(RdsService.class);
        DbCluster cluster = new DbCluster("cluster1", DatabaseEngine.MYSQL, "8.0", "admin", "secret",
                "app", DbInstanceStatus.AVAILABLE, new DbEndpoint("localhost", 7001),
                new DbEndpoint("localhost", 7001), false, new ArrayList<>(), null, Instant.now(), 7001);
        cluster.setDbClusterArn("arn:aws:rds:us-east-1:000000000000:cluster:cluster1");
        cluster.setContainerHost("127.0.0.1");
        cluster.setContainerPort(3306);
        when(rdsService.getDbCluster("cluster1", "us-east-1")).thenReturn(cluster);

        RdsDataResourceResolver.DatabaseTarget target = new RdsDataResourceResolver(rdsService)
                .resolve("arn:aws:rds:us-east-1:000000000000:cluster:cluster1");

        assertEquals(DatabaseEngine.MYSQL, target.engine());
        assertEquals(cluster.getDbClusterArn(), target.arn());
        assertEquals(cluster.getDbClusterArn(), target.runtimeId());
        assertEquals("app", target.databaseName());
        verify(rdsService, never()).ensureClusterBackend(anyString(), anyString());
    }

    @Test
    void missingResourceUsesModeledDataApiBadRequestCode() {
        RdsService rdsService = mock(RdsService.class);
        when(rdsService.getDbCluster("missing", "us-east-1"))
                .thenThrow(new AwsException("DBClusterNotFoundFault", "missing", 404));

        AwsException error = assertThrows(AwsException.class, () -> new RdsDataResourceResolver(rdsService)
                .resolve("arn:aws:rds:us-east-1:000000000000:cluster:missing"));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void dataApiNamesTheMissingDockerDaemonWhenTheInstanceHasNoBackingContainer() {
        RdsService rdsService = mock(RdsService.class);
        DbInstance instance = daemonlessInstance();
        when(rdsService.getDbInstance("probe-db", "us-east-1")).thenReturn(instance);
        when(rdsService.ensureInstanceBackend("probe-db", "us-east-1")).thenReturn(instance);
        when(rdsService.isBackendRuntimeAvailable()).thenReturn(false);

        AwsException error = assertThrows(AwsException.class, () -> new RdsDataResourceResolver(rdsService)
                .resolve("arn:aws:rds:us-east-1:000000000000:db:probe-db"));

        assertEquals("InternalServerErrorException", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
        assertTrue(error.getMessage().contains("Docker"), error.getMessage());
    }

    @Test
    void dataApiRetriesTheBackendSoItResolvesOnceADaemonAppears() {
        RdsService rdsService = mock(RdsService.class);
        DbInstance started = daemonlessInstance();
        started.setContainerHost("127.0.0.1");
        started.setContainerPort(5432);
        when(rdsService.getDbInstance("probe-db", "us-east-1")).thenReturn(daemonlessInstance());
        when(rdsService.ensureInstanceBackend("probe-db", "us-east-1")).thenReturn(started);
        when(rdsService.instanceBackendRuntimeId(started)).thenReturn(started.getDbInstanceArn());

        RdsDataResourceResolver.DatabaseTarget target = new RdsDataResourceResolver(rdsService)
                .resolve("arn:aws:rds:us-east-1:000000000000:db:probe-db");

        assertEquals(started.getDbInstanceArn(), target.runtimeId());
    }

    @Test
    void dataApiRetriesAClusterBackendThroughTheClusterEntryPoint() {
        RdsService rdsService = mock(RdsService.class);
        DbCluster cluster = new DbCluster("cluster1", DatabaseEngine.POSTGRES, "16.3", "admin", "secret",
                "app", DbInstanceStatus.AVAILABLE, new DbEndpoint("localhost", 7001),
                new DbEndpoint("localhost", 7001), false, new ArrayList<>(), null, Instant.now(), 7001);
        cluster.setDbClusterArn("arn:aws:rds:us-east-1:000000000000:cluster:cluster1");
        DbCluster started = new DbCluster("cluster1", DatabaseEngine.POSTGRES, "16.3", "admin", "secret",
                "app", DbInstanceStatus.AVAILABLE, new DbEndpoint("localhost", 7001),
                new DbEndpoint("localhost", 7001), false, new ArrayList<>(), null, Instant.now(), 7001);
        started.setDbClusterArn(cluster.getDbClusterArn());
        started.setContainerHost("127.0.0.1");
        started.setContainerPort(5432);
        when(rdsService.getDbCluster("cluster1", "us-east-1")).thenReturn(cluster);
        when(rdsService.ensureClusterBackend("cluster1", "us-east-1")).thenReturn(started);

        RdsDataResourceResolver.DatabaseTarget target = new RdsDataResourceResolver(rdsService)
                .resolve("arn:aws:rds:us-east-1:000000000000:cluster:cluster1");

        assertEquals(started.getDbClusterArn(), target.runtimeId());
    }

    @Test
    void resolvesClusterMemberToItsSharedClusterRuntime() {
        RdsService rdsService = mock(RdsService.class);
        DbInstance member = clusterMember("member1", 7002, DbInstanceStatus.AVAILABLE);
        member.setContainerHost("127.0.0.1");
        member.setContainerPort(5432);
        String clusterArn = "arn:aws:rds:us-west-2:000000000000:cluster:cluster1";
        when(rdsService.getDbInstance("member1", "us-west-2")).thenReturn(member);
        when(rdsService.instanceBackendRuntimeId(member)).thenReturn(clusterArn);

        RdsDataResourceResolver.DatabaseTarget target = new RdsDataResourceResolver(rdsService)
                .resolve(member.getDbInstanceArn(), "us-west-2");

        assertEquals(member.getDbInstanceArn(), target.arn());
        assertEquals(clusterArn, target.runtimeId());
        verify(rdsService, never()).ensureInstanceBackend(anyString(), anyString());
    }

    @Test
    void repeatedClusterArnResolutionRetriesFailedMembersOfAnAlreadyRunningCluster() {
        ClusterFixture fixture = readyClusterFixture();
        DbCluster cluster = fixture.cluster();
        AccountAwareStorageBackend<DbInstance> instances = fixture.instances();
        RdsProxyManager proxies = fixture.proxies();
        RdsService rdsService = fixture.service();
        cluster.getDbClusterMembers().addAll(List.of("retry-member", "other-member", "stopped-member"));
        DbInstance retryMember = clusterMember("retry-member", 7002, DbInstanceStatus.FAILED);
        DbInstance otherMember = clusterMember("other-member", 7003, DbInstanceStatus.AVAILABLE);
        DbInstance stoppedMember = clusterMember("stopped-member", 7004, DbInstanceStatus.STOPPED);
        instances.put("retry-member", retryMember);
        instances.put("other-member", otherMember);
        instances.put("stopped-member", stoppedMember);
        doThrow(new IllegalStateException("temporary member relay error")).doNothing()
                .when(proxies).startProxy(eq("rds-resource:" + retryMember.getDbInstanceArn()),
                        eq(cluster.getDbClusterArn()), any(), anyBoolean(), anyInt(), any(), anyInt(),
                        any(), any(), any(), any(), any(), any());
        RdsDataResourceResolver resolver = new RdsDataResourceResolver(rdsService);

        RdsDataResourceResolver.DatabaseTarget firstTarget = resolver.resolve(cluster.getDbClusterArn(), "us-west-2");

        assertEquals(cluster.getDbClusterArn(), firstTarget.runtimeId());
        assertEquals(DbInstanceStatus.FAILED, retryMember.getStatus());
        assertEquals("cluster-container", otherMember.getContainerId());

        assertEquals(firstTarget, resolver.resolve(cluster.getDbClusterArn(), "us-west-2"));
        assertEquals(DbInstanceStatus.AVAILABLE, retryMember.getStatus());
        assertEquals("cluster-container", retryMember.getContainerId());
        assertEquals("127.0.0.1", retryMember.getContainerHost());
        assertEquals(15432, retryMember.getContainerPort());
        assertEquals(7002, retryMember.getEndpoint().port());
        assertEquals(firstTarget, resolver.resolve(cluster.getDbClusterArn(), "us-west-2"));
        assertEquals(DbInstanceStatus.STOPPED, stoppedMember.getStatus());
        verify(rdsService, times(2)).ensureClusterBackend("cluster1", "us-west-2");
        verify(proxies, times(2)).startProxy(eq("rds-resource:" + retryMember.getDbInstanceArn()),
                eq(cluster.getDbClusterArn()), any(), anyBoolean(), eq(7002), eq("127.0.0.1"),
                eq(15432), any(), any(), any(), any(), any(), any());
        verify(proxies).startProxy(eq("rds-resource:" + otherMember.getDbInstanceArn()),
                eq(cluster.getDbClusterArn()), any(), anyBoolean(), eq(7003), eq("127.0.0.1"),
                eq(15432), any(), any(), any(), any(), any(), any());
        verify(proxies, never()).startProxy(eq("rds-resource:" + stoppedMember.getDbInstanceArn()),
                any(), any(), anyBoolean(), anyInt(), any(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(proxies, never()).startProxy(eq("rds-resource:" + cluster.getDbClusterArn()),
                any(), any(), anyBoolean(), anyInt(), any(), anyInt(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(fixture.containers());
    }

    @Test
    void healthyClusterResolutionDoesNotWaitForTheServiceMonitor() throws Exception {
        ClusterFixture fixture = readyClusterFixture();
        DbInstance readyMember = clusterMember("ready-member", 7002, DbInstanceStatus.AVAILABLE);
        readyMember.setContainerHost("127.0.0.1");
        readyMember.setContainerPort(15432);
        fixture.instances().put("ready-member", readyMember);
        fixture.instances().put("stopped-member", clusterMember("stopped-member", 7003, DbInstanceStatus.STOPPED));
        fixture.instances().put("deleting-member", clusterMember("deleting-member", 7004, DbInstanceStatus.DELETING));
        fixture.cluster().getDbClusterMembers().addAll(
                List.of("ready-member", "stopped-member", "deleting-member", "removed-member"));
        RdsDataResourceResolver resolver = new RdsDataResourceResolver(fixture.service());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            // Container starts on unrelated databases hold this monitor while pulling images.
            synchronized (fixture.service()) {
                Future<RdsDataResourceResolver.DatabaseTarget> resolved = executor.submit(() ->
                        resolver.resolve(fixture.cluster().getDbClusterArn(), "us-west-2"));
                RdsDataResourceResolver.DatabaseTarget target = resolved.get(5, TimeUnit.SECONDS);
                assertEquals(fixture.cluster().getDbClusterArn(), target.runtimeId());
            }
        }

        verify(fixture.service(), never()).ensureClusterBackend(anyString(), anyString());
        verifyNoInteractions(fixture.containers(), fixture.proxies());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activeMemberWithAnIncompleteBackendIsRetried(boolean blankHost) {
        ClusterFixture fixture = readyClusterFixture();
        DbInstance member = clusterMember("retry-member", 7002, DbInstanceStatus.AVAILABLE);
        member.setContainerHost(blankHost ? " " : "127.0.0.1");
        member.setContainerPort(blankHost ? 15432 : 0);
        fixture.instances().put("retry-member", member);
        fixture.cluster().getDbClusterMembers().add("retry-member");

        RdsDataResourceResolver.DatabaseTarget target = new RdsDataResourceResolver(fixture.service())
                .resolve(fixture.cluster().getDbClusterArn(), "us-west-2");

        assertEquals(fixture.cluster().getDbClusterArn(), target.runtimeId());
        assertEquals("127.0.0.1", member.getContainerHost());
        assertEquals(15432, member.getContainerPort());
        verify(fixture.service()).ensureClusterBackend("cluster1", "us-west-2");
        verify(fixture.proxies()).startProxy(eq("rds-resource:" + member.getDbInstanceArn()),
                eq(fixture.cluster().getDbClusterArn()), any(), anyBoolean(), eq(7002),
                eq("127.0.0.1"), eq(15432), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(fixture.containers());
    }

    private static ClusterFixture readyClusterFixture() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.RdsServiceConfig rdsConfig = mock(EmulatorConfig.RdsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(servicesConfig.rds()).thenReturn(rdsConfig);
        when(rdsConfig.iamTokenEndpointBinding()).thenReturn(true);
        AccountAwareStorageBackend<DbCluster> clusters = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<DbInstance> instances = AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(storageFactory.<DbCluster>create(eq("rds"), eq("rds-clusters.json"), any())).thenReturn(clusters);
        when(storageFactory.<DbInstance>create(eq("rds"), eq("rds-instances.json"), any())).thenReturn(instances);
        DbCluster cluster = new DbCluster("cluster1", DatabaseEngine.POSTGRES, "16.3", "admin", "secret",
                "app", DbInstanceStatus.AVAILABLE, new DbEndpoint("localhost", 7001),
                new DbEndpoint("localhost", 7001), false, new ArrayList<>(), null, Instant.now(), 7001);
        cluster.setDbClusterArn("arn:aws:rds:us-west-2:000000000000:cluster:cluster1");
        cluster.setContainerId("cluster-container");
        cluster.setContainerHost("127.0.0.1");
        cluster.setContainerPort(15432);
        clusters.put("cluster1", cluster);
        RdsContainerManager containers = mock(RdsContainerManager.class);
        RdsProxyManager proxies = mock(RdsProxyManager.class);
        RdsService rdsService = spy(new RdsService(containers, proxies, mock(Ec2Service.class),
                new RegionResolver("us-east-1", "000000000000"), config, storageFactory,
                null, null, null, null, null, null));
        return new ClusterFixture(rdsService, cluster, instances, containers, proxies);
    }

    private record ClusterFixture(RdsService service, DbCluster cluster,
                                  AccountAwareStorageBackend<DbInstance> instances,
                                  RdsContainerManager containers, RdsProxyManager proxies) { }

    private static DbInstance clusterMember(String id, int port, DbInstanceStatus status) {
        DbInstance instance = new DbInstance(id, DatabaseEngine.POSTGRES, "16.3", "admin", "secret",
                "app", "db.serverless", 0, status, new DbEndpoint("localhost", port),
                false, null, null, Instant.now(), port);
        instance.setDbInstanceArn("arn:aws:rds:us-west-2:000000000000:db:" + id);
        instance.setDbClusterIdentifier("cluster1");
        return instance;
    }

    private static DbInstance daemonlessInstance() {
        DbInstance instance = new DbInstance("probe-db", DatabaseEngine.POSTGRES, "16.3", "admin",
                "secret", "app", "db.t3.micro", 20, DbInstanceStatus.AVAILABLE,
                new DbEndpoint("localhost", 7001), false, null, null, Instant.now(), 7001);
        instance.setDbInstanceArn("arn:aws:rds:us-east-1:000000000000:db:probe-db");
        return instance;
    }

    @Test
    void rejectsResourceArnOutsideSignedRequestRegionBeforeLookup() {
        RdsService rdsService = mock(RdsService.class);

        AwsException error = assertThrows(AwsException.class, () ->
                new RdsDataResourceResolver(rdsService).resolve(
                        "arn:aws:rds:us-west-2:000000000000:cluster:cluster1",
                        "us-east-1"));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        Mockito.verifyNoInteractions(rdsService);
    }
}
