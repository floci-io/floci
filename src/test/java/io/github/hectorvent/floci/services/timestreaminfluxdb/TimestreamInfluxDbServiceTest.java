package io.github.hectorvent.floci.services.timestreaminfluxdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.timestreaminfluxdb.TimestreamInfluxDbService.RestoreResult;
import io.github.hectorvent.floci.services.timestreaminfluxdb.container.TimestreamInfluxDbContainerManager;
import io.github.hectorvent.floci.services.timestreaminfluxdb.container.TimestreamInfluxDbContainerManager.InfluxDbEndpoint;
import io.github.hectorvent.floci.services.timestreaminfluxdb.container.TimestreamInfluxDbContainerManager.InfluxDbSetup;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbBackup;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbCluster;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbInstance;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbParameterGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimestreamInfluxDbServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TimestreamInfluxDbContainerManager containerManager;
    private TimestreamInfluxDbService service;

    @BeforeEach
    void setUp() {
        containerManager = mock(TimestreamInfluxDbContainerManager.class);
        when(containerManager.isDockerReachable()).thenReturn(true);
        when(containerManager.start(anyString(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(new InfluxDbEndpoint("container-1", "localhost", 18086));
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        Secret secret = mock(Secret.class);
        when(secret.getArn()).thenReturn("arn:aws:secretsmanager:us-east-1:000000000000:secret:influx");
        when(secrets.createSecret(anyString(), anyString(), isNull(), anyString(), isNull(), isNull(), eq(REGION)))
                .thenReturn(secret);
        service = new TimestreamInfluxDbService(
                AccountAwareStorageBackend.<DbInstance>inMemory(ACCOUNT),
                AccountAwareStorageBackend.<DbCluster>inMemory(ACCOUNT),
                AccountAwareStorageBackend.<DbParameterGroup>inMemory(ACCOUNT),
                AccountAwareStorageBackend.<DbBackup>inMemory(ACCOUNT),
                new RegionResolver(REGION, ACCOUNT), secrets, objectMapper, containerManager, false, Runnable::run);
    }

    @Test
    void createInstanceStartsAContainerWithTheInitialAuthParametersAndBecomesAvailable() throws Exception {
        DbInstance created = service.createDbInstance(json("""
                {"name":"real-db","username":"operator","password":"password123","organization":"acme",
                 "bucket":"metrics","dbInstanceType":"db.influx.medium","allocatedStorage":20,
                 "vpcSubnetIds":["subnet-abc"],"vpcSecurityGroupIds":["sg-abc"]}
                """), REGION).resource();

        ArgumentCaptor<InfluxDbSetup> setup = ArgumentCaptor.forClass(InfluxDbSetup.class);
        verify(containerManager).start(eq(created.getId()), eq(ACCOUNT), eq(REGION), setup.capture(), eq(Map.of()));
        assertEquals(new InfluxDbSetup("operator", "password123", "acme", "metrics"), setup.getValue());

        DbInstance stored = service.getDbInstance(json("{\"identifier\":\"" + created.getId() + "\"}"), REGION);
        assertEquals("AVAILABLE", stored.getStatus());
        assertEquals("localhost", stored.getEndpoint());
        assertEquals(18086, stored.getPort());
        assertEquals("container-1", stored.getContainerId());
    }

    @Test
    void instanceIsFailedWhenTheContainerDoesNotBecomeHealthy() throws Exception {
        doThrow(new IllegalStateException("not healthy")).when(containerManager).waitUntilReady(any());

        String id = createInstance("unhealthy-db", null).getId();

        assertEquals("FAILED", service.getDbInstance(json("{\"identifier\":\"" + id + "\"}"), REGION).getStatus());
    }

    @Test
    void instanceIsAvailableWithoutAContainerWhenNoDockerDaemonIsReachable() throws Exception {
        when(containerManager.isDockerReachable()).thenReturn(false);

        String id = createInstance("no-docker-db", null).getId();

        DbInstance stored = service.getDbInstance(json("{\"identifier\":\"" + id + "\"}"), REGION);
        assertEquals("AVAILABLE", stored.getStatus());
        assertNull(stored.getContainerId());
        verify(containerManager, never()).start(anyString(), anyString(), anyString(), any(), anyMap());
    }

    @Test
    void parameterGroupValuesReachTheContainerAsInfluxdEnvironment() throws Exception {
        String groupId = service.createDbParameterGroup(json("""
                {"name":"tuned","parameters":{"InfluxDBv2":{"logLevel":"debug",
                 "httpIdleTimeout":{"durationType":"minutes","value":5}}}}
                """), REGION).getId();

        createInstance("tuned-db", groupId);

        verify(containerManager).start(anyString(), eq(ACCOUNT), eq(REGION), any(),
                eq(Map.of("INFLUXD_LOG_LEVEL", "debug", "INFLUXD_HTTP_IDLE_TIMEOUT", "5m")));
    }

    @Test
    void switchingParameterGroupRecreatesTheContainerWithoutSetup() throws Exception {
        String id = createInstance("switch-db", null).getId();
        String groupId = service.createDbParameterGroup(json("""
                {"name":"no-ui","parameters":{"InfluxDBv2":{"uiDisabled":true}}}
                """), REGION).getId();

        service.updateDbInstance(json("{\"identifier\":\"" + id + "\",\"dbParameterGroupIdentifier\":\"" + groupId + "\"}"),
                REGION);

        verify(containerManager).start(eq(id), eq(ACCOUNT), eq(REGION), isNull(), eq(Map.of("INFLUXD_UI_DISABLED", "true")));
        assertEquals("AVAILABLE", service.getDbInstance(json("{\"identifier\":\"" + id + "\"}"), REGION).getStatus());
    }

    @Test
    void rebootRestartsTheContainerAndDeleteRemovesContainerAndVolumes() throws Exception {
        String id = createInstance("reboot-db", null).getId();

        service.rebootDbInstance(json("{\"identifier\":\"" + id + "\"}"), REGION);
        verify(containerManager).restart("container-1");
        assertEquals("AVAILABLE", service.getDbInstance(json("{\"identifier\":\"" + id + "\"}"), REGION).getStatus());

        service.deleteDbInstance(json("{\"identifier\":\"" + id + "\"}"), REGION);
        verify(containerManager).stop(id, "container-1");
        verify(containerManager).removeStorage(id);
    }

    @Test
    void rebootFailureIsReportedAsRebootFailed() throws Exception {
        String id = createInstance("reboot-fail-db", null).getId();
        doThrow(new IllegalStateException("restart failed")).when(containerManager).restart("container-1");

        service.rebootDbInstance(json("{\"identifier\":\"" + id + "\"}"), REGION);

        assertEquals("REBOOT_FAILED", service.getDbInstance(json("{\"identifier\":\"" + id + "\"}"), REGION).getStatus());
    }

    @Test
    void backupCapturesContainerDataAndRestoreLoadsItIntoANewContainer() throws Exception {
        String sourceId = createInstance("backup-db", null).getId();

        DbBackup backup = service.createDbBackup(json("{\"name\":\"snap\",\"dbResourceId\":\"" + sourceId + "\"}"), REGION)
                .resource();
        verify(containerManager).backup("container-1", backup.getId());
        DbBackup stored = service.getDbBackup(json("{\"identifier\":\"" + backup.getId() + "\"}"), REGION);
        assertEquals("COMPLETED", stored.getStatus());
        assertTrue(stored.isDataCaptured());

        when(containerManager.start(anyString(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(new InfluxDbEndpoint("container-2", "localhost", 18087));
        RestoreResult result = service.restoreFromDbBackup(
                json("{\"name\":\"restored\",\"dbBackupId\":\"" + backup.getId() + "\"}"), REGION);

        verify(containerManager).restore("container-2", backup.getId());
        DbInstance restored = service.getDbInstance(json("{\"identifier\":\"" + result.restoredDbResourceId() + "\"}"),
                REGION);
        assertEquals("AVAILABLE", restored.getStatus());
        assertEquals(18087, restored.getPort());

        service.deleteDbBackup(json("{\"identifier\":\"" + backup.getId() + "\"}"), REGION);
        verify(containerManager).deleteBackupArtifacts(backup.getId());
    }

    @Test
    void failedCaptureMarksTheBackupFailedAndBlocksRestore() throws Exception {
        String sourceId = createInstance("failed-backup-db", null).getId();
        doThrow(new IllegalStateException("influx backup failed")).when(containerManager).backup(anyString(), anyString());

        String backupId = service.createDbBackup(json("{\"name\":\"broken\",\"dbResourceId\":\"" + sourceId + "\"}"),
                REGION).resource().getId();

        assertEquals("FAILED", service.getDbBackup(json("{\"identifier\":\"" + backupId + "\"}"), REGION).getStatus());
        AwsException error = assertThrows(AwsException.class, () -> service.restoreFromDbBackup(
                json("{\"name\":\"never\",\"dbBackupId\":\"" + backupId + "\"}"), REGION));
        assertEquals("ConflictException", error.getErrorCode());
    }

    @Test
    void readReplicaClusterSharesOneContainerAcrossItsNodes() throws Exception {
        String clusterId = service.createDbCluster(json("""
                {"name":"replicas","password":"password123","dbInstanceType":"db.influx.large",
                 "vpcSubnetIds":["subnet-abc"],"vpcSecurityGroupIds":["sg-abc"]}
                """), REGION).resource().getId();

        verify(containerManager).start(eq(clusterId), eq(ACCOUNT), eq(REGION), any(), anyMap());
        DbCluster cluster = service.getDbCluster(json("{\"dbClusterId\":\"" + clusterId + "\"}"), REGION);
        assertEquals("AVAILABLE", cluster.getStatus());
        List<DbInstance> members = service.listDbInstancesForCluster(
                json("{\"dbClusterId\":\"" + clusterId + "\"}"), REGION).items();
        assertEquals(2, members.size());
        for (DbInstance member : members) {
            assertEquals("AVAILABLE", member.getStatus());
            assertEquals(18086, member.getPort());
        }
    }

    @Test
    void influxDb3ClustersHaveNoInfluxDb2Container() throws Exception {
        String groupId = service.createDbParameterGroup(json("{\"name\":\"core\",\"parameters\":{\"InfluxDBv3Core\":{}}}"),
                REGION).getId();

        String clusterId = service.createDbCluster(json("""
                {"name":"core-cluster","dbInstanceType":"db.influx.large","dbParameterGroupIdentifier":"%s",
                 "vpcSubnetIds":["subnet-abc"],"vpcSecurityGroupIds":["sg-abc"]}
                """.formatted(groupId)), REGION).resource().getId();

        verify(containerManager, never()).start(anyString(), anyString(), anyString(), any(), anyMap());
        DbCluster cluster = service.getDbCluster(json("{\"dbClusterId\":\"" + clusterId + "\"}"), REGION);
        assertEquals("AVAILABLE", cluster.getStatus());
        assertEquals(8181, cluster.getPort());
        List<DbInstance> members = service.listDbInstancesForCluster(
                json("{\"dbClusterId\":\"" + clusterId + "\"}"), REGION).items();
        assertEquals(1, members.size());
        assertEquals(List.of("INGEST", "QUERY", "COMPACT", "PROCESS"), members.get(0).getInstanceModes());
    }

    @Test
    void restoreToTimeIsRejectedBecausePointInTimeRestoreNeedsContinuousBackups() throws Exception {
        String sourceId = createInstance("pitr-db", null).getId();
        String backupId = service.createDbBackup(json("{\"name\":\"pitr\",\"dbResourceId\":\"" + sourceId + "\"}"),
                REGION).resource().getId();

        AwsException error = assertThrows(AwsException.class, () -> service.restoreFromDbBackup(json(
                "{\"name\":\"pitr-copy\",\"dbBackupId\":\"" + backupId + "\",\"restoreToTime\":1700000000}"), REGION));

        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains("continuous"), error.getMessage());
        verify(containerManager, never()).restore(anyString(), anyString());
    }

    @Test
    void replaceExistingRejectsOverridesAndRestoresDataWithoutThem() throws Exception {
        String sourceId = createInstance("replace-db", null).getId();
        String backupId = service.createDbBackup(json("{\"name\":\"replace\",\"dbResourceId\":\"" + sourceId + "\"}"),
                REGION).resource().getId();
        String replaceBase = "{\"name\":\"replace-db\",\"dbBackupId\":\"" + backupId
                + "\",\"restoreMode\":\"REPLACE_EXISTING\"";

        AwsException error = assertThrows(AwsException.class, () -> service.restoreFromDbBackup(
                json(replaceBase + ",\"port\":9100}"), REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().contains("port"), error.getMessage());
        verify(containerManager, never()).restore(anyString(), anyString());
        assertEquals(18086, service.getDbInstance(json("{\"identifier\":\"" + sourceId + "\"}"), REGION).getPort());

        service.restoreFromDbBackup(json(replaceBase + "}"), REGION);
        verify(containerManager).restore("container-1", backupId);
    }

    @Test
    void tagsBeyondTheQuotaAreRejected() throws Exception {
        String arn = createInstance("quota-db", null).getArn();
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            tags.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"v\"");
        }
        service.tagResource(json("{\"resourceArn\":\"" + arn + "\",\"tags\":{" + tags + "}}"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.tagResource(json("{\"resourceArn\":\"" + arn + "\",\"tags\":{\"extra\":\"v\"}}")));
        assertEquals("ServiceQuotaExceededException", error.getErrorCode());
    }

    @Test
    void higherStorageTiersRequireAtLeast400GiB() {
        AwsException error = assertThrows(AwsException.class, () -> service.createDbInstance(json("""
                {"name":"t2-db","password":"password123","dbInstanceType":"db.influx.medium","allocatedStorage":100,
                 "dbStorageType":"InfluxIOIncludedT2","vpcSubnetIds":["subnet-abc"],"vpcSecurityGroupIds":["sg-abc"]}
                """), REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    private DbInstance createInstance(String name, String parameterGroupId) throws Exception {
        String groupField = parameterGroupId == null ? "" : ",\"dbParameterGroupIdentifier\":\"" + parameterGroupId + "\"";
        return service.createDbInstance(json("""
                {"name":"%s","password":"password123","dbInstanceType":"db.influx.medium","allocatedStorage":20,
                 "vpcSubnetIds":["subnet-abc"],"vpcSecurityGroupIds":["sg-abc"]%s}
                """.formatted(name, groupField)), REGION).resource();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
