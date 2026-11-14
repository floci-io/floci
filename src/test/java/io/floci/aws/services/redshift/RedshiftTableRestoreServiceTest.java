package io.floci.aws.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.RedshiftService.TableRestoreSource;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.model.TableRestoreStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftTableRestoreServiceTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String DUMP = "CREATE TABLE public.orders (\n    id integer\n);\n";

    private RedshiftService redshift;
    private RedshiftTableRestorer restorer;
    private RedshiftTableRestoreService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp(@TempDir Path directory) throws Exception {
        AccountAwareStorageBackend<TableRestoreStatus> store =
                new AccountAwareStorageBackend<>(new InMemoryStorage<String, TableRestoreStatus>(), null, ACCOUNT_ID);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("redshift"), eq("redshift-table-restores.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        Path dump = Files.writeString(directory.resolve("snapshot.sql"), DUMP);
        Cluster cluster = new Cluster();
        cluster.setMasterUsername("admin");
        redshift = mock(RedshiftService.class);
        when(redshift.tableRestoreSource("analytics", "snap")).thenReturn(new TableRestoreSource(cluster, dump, "dev"));
        when(redshift.hasCluster("analytics")).thenReturn(true);
        when(redshift.withResourceLock(anyString(), anyString(), anyString(), any())).thenAnswer(
                call -> ((Supplier<?>) call.getArgument(3)).get());
        restorer = mock(RedshiftTableRestorer.class);
        when(restorer.script(any(), anyString(), anyString(), anyString(), anyString(), any(Boolean.class)))
                .thenReturn(Optional.of("BEGIN;\nCOMMIT;\n"));
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        service = new RedshiftTableRestoreService(storageFactory, redshift, restorer, regionResolver,
                Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC));
    }

    private static RedshiftTableRestoreService.Request request(String sourceDatabase, String sourceTable) {
        return new RedshiftTableRestoreService.Request("analytics", "snap", sourceDatabase, null, sourceTable, null,
                null, "Orders_Copy", false);
    }

    @Test
    void theRequestIsReportedPendingAndLaterDescribedAsSucceeded() {
        TableRestoreStatus accepted = service.restoreTable(request("dev", "orders"));

        assertEquals(TableRestoreStatus.PENDING, accepted.getStatus());
        assertEquals("public", accepted.getSourceSchemaName());
        assertEquals("public", accepted.getTargetSchemaName());
        assertEquals("dev", accepted.getTargetDatabaseName());
        assertEquals("Orders_Copy", accepted.getNewTableName());
        verify(restorer).run(ACCOUNT_ID, "analytics", "admin", "dev", "BEGIN;\nCOMMIT;\n");
        verify(restorer).script(any(), eq("public"), eq("orders"), eq("public"), eq("orders_copy"), eq(false));

        TableRestoreStatus described = service.describeTableRestoreStatus(
                "analytics", accepted.getTableRestoreRequestId(), null, null).items().getFirst();
        assertEquals(TableRestoreStatus.SUCCEEDED, described.getStatus());
        assertNull(described.getMessage());
    }

    @Test
    void aFailingScriptIsRecordedAsFailedWithItsReason() {
        doThrow(new AwsException("InternalFailure", "relation already exists", 500))
                .when(restorer).run(anyString(), anyString(), anyString(), anyString(), anyString());

        TableRestoreStatus accepted = service.restoreTable(request("dev", "orders"));

        TableRestoreStatus described = service.describeTableRestoreStatus(
                "analytics", accepted.getTableRestoreRequestId(), null, null).items().getFirst();
        assertEquals(TableRestoreStatus.FAILED, described.getStatus());
        assertEquals("relation already exists", described.getMessage());
    }

    @Test
    void aTargetDatabaseOtherThanTheClustersIsRefused() {
        RedshiftTableRestoreService.Request request = new RedshiftTableRestoreService.Request("analytics", "snap",
                "dev", null, "orders", "dev host=evil", null, "copy", false);

        assertEquals("InvalidTableRestoreArgument",
                assertThrows(AwsException.class, () -> service.restoreTable(request)).getErrorCode());
    }

    @Test
    void aSourceTableOrDatabaseThatIsNotInTheSnapshotIsRefused() {
        when(restorer.script(any(), anyString(), anyString(), anyString(), anyString(), any(Boolean.class)))
                .thenReturn(Optional.empty());

        assertEquals("InvalidTableRestoreArgument", assertThrows(AwsException.class,
                () -> service.restoreTable(request("dev", "missing"))).getErrorCode());
        assertEquals("InvalidTableRestoreArgument", assertThrows(AwsException.class,
                () -> service.restoreTable(request("other", "orders"))).getErrorCode());
        assertTrue(service.describeTableRestoreStatus("analytics", null, null, null).items().isEmpty());
    }

    @Test
    void describeListsInRequestOrderFiltersAndRefusesUnknownIdsAndBadPageSizes() {
        TableRestoreStatus first = service.restoreTable(request("dev", "orders"));
        TableRestoreStatus second = service.restoreTable(request("dev", "orders"));

        PaginatedResult<TableRestoreStatus> all = service.describeTableRestoreStatus(null, null, null, null);
        assertEquals(2, all.items().size());
        assertEquals(Set.of(first.getTableRestoreRequestId(), second.getTableRestoreRequestId()),
                Set.of(all.items().get(0).getTableRestoreRequestId(),
                        all.items().get(1).getTableRestoreRequestId()));

        assertEquals("TableRestoreNotFoundFault", assertThrows(AwsException.class,
                () -> service.describeTableRestoreStatus(null, "nope", null, null)).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeTableRestoreStatus(null, null, 5, null)).getErrorCode());
    }

    @Test
    void requestsOfADeletedClusterAreDropped() {
        service.restoreTable(request("dev", "orders"));
        when(redshift.hasCluster("analytics")).thenReturn(false);

        assertTrue(service.describeTableRestoreStatus(null, null, null, null).items().isEmpty());
    }
}
