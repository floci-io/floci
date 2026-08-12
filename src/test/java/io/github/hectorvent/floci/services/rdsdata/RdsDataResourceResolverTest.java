package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
        when(rdsService.listDbClusters(null)).thenReturn(List.of(cluster));
        when(rdsService.listDbInstances(null)).thenReturn(List.of());

        RdsDataResourceResolver.DatabaseTarget target = new RdsDataResourceResolver(rdsService)
                .resolve("arn:aws:rds:us-east-1:000000000000:cluster:cluster1");

        assertEquals(DatabaseEngine.MYSQL, target.engine());
        assertEquals("127.0.0.1", target.host());
        assertEquals(3306, target.port());
        assertEquals("app", target.databaseName());
    }

    @Test
    void missingResourceUsesModeledDataApiBadRequestCode() {
        RdsService rdsService = mock(RdsService.class);
        when(rdsService.listDbClusters(null)).thenReturn(List.of());
        when(rdsService.listDbInstances(null)).thenReturn(List.of());
        when(rdsService.getDbCluster("missing")).thenThrow(new AwsException("DBClusterNotFoundFault", "missing", 404));
        when(rdsService.getDbInstance("missing")).thenThrow(new AwsException("DBInstanceNotFound", "missing", 404));

        AwsException error = assertThrows(AwsException.class, () -> new RdsDataResourceResolver(rdsService)
                .resolve("arn:aws:rds:us-east-1:000000000000:cluster:missing"));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void dataApiNamesTheMissingDockerDaemonWhenTheInstanceHasNoBackingContainer() {
        RdsService rdsService = mock(RdsService.class);
        DbInstance instance = daemonlessInstance();
        when(rdsService.listDbClusters(null)).thenReturn(List.of());
        when(rdsService.listDbInstances(null)).thenReturn(List.of(instance));
        when(rdsService.ensureInstanceBackend("probe-db")).thenReturn(instance);
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
        DbInstance instance = daemonlessInstance();
        DbInstance started = daemonlessInstance();
        started.setContainerHost("127.0.0.1");
        started.setContainerPort(5432);
        when(rdsService.listDbClusters(null)).thenReturn(List.of());
        when(rdsService.listDbInstances(null)).thenReturn(List.of(instance));
        when(rdsService.ensureInstanceBackend("probe-db")).thenReturn(started);

        RdsDataResourceResolver.DatabaseTarget target = new RdsDataResourceResolver(rdsService)
                .resolve("arn:aws:rds:us-east-1:000000000000:db:probe-db");

        assertEquals("127.0.0.1", target.host());
        assertEquals(5432, target.port());
    }

    private static DbInstance daemonlessInstance() {
        DbInstance instance = new DbInstance("probe-db", DatabaseEngine.POSTGRES, "16.3", "admin",
                "secret", "app", "db.t3.micro", 20, DbInstanceStatus.AVAILABLE,
                new DbEndpoint("localhost", 7001), false, null, null, Instant.now(), 7001);
        instance.setDbInstanceArn("arn:aws:rds:us-east-1:000000000000:db:probe-db");
        return instance;
    }
}
