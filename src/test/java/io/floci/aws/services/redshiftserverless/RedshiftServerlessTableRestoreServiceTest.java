package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.redshift.RedshiftTableRestorer;
import io.floci.aws.services.redshift.container.RedshiftContainerManager;
import io.floci.aws.services.redshift.model.TableRestoreStatus;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService.TableRestoreSource;
import io.floci.aws.services.redshiftserverless.model.Namespace;
import io.floci.aws.services.redshiftserverless.model.Workgroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessTableRestoreServiceTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String REGION = "us-east-1";

    private RedshiftServerlessService serverless;
    private RedshiftContainerManager containerManager;
    private Path dump;
    private RedshiftServerlessTableRestoreService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp(@TempDir Path directory) throws Exception {
        AccountAwareStorageBackend<TableRestoreStatus> store =
                new AccountAwareStorageBackend<>(new InMemoryStorage<String, TableRestoreStatus>(), null, ACCOUNT_ID);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-table-restores.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        Namespace namespace = new Namespace();
        namespace.setDbName("dev");
        Workgroup workgroup = new Workgroup();
        workgroup.setMasterUsername("admin");
        dump = Files.writeString(directory.resolve("snapshot.sql"),
                String.join("\n", "CREATE TABLE public.orders (", "    id integer", ");", ""));
        serverless = mock(RedshiftServerlessService.class);
        when(serverless.tableRestoreSource("ns", "wg", "snap", REGION))
                .thenReturn(new TableRestoreSource(workgroup, namespace, dump));
        when(serverless.findWorkgroup("wg", REGION)).thenReturn(Optional.of(workgroup));
        containerManager = mock(RedshiftContainerManager.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        service = new RedshiftServerlessTableRestoreService(storageFactory, serverless,
                new RedshiftTableRestorer(containerManager), regionResolver,
                Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC));
    }

    private static RedshiftServerlessTableRestoreService.Request request(String sourceTable) {
        return new RedshiftServerlessTableRestoreService.Request("ns", "wg", "snap", "dev", null, sourceTable, null,
                null, "orders_copy", false);
    }

    @Test
    void theRequestIsReportedPendingAndLaterReadAsSucceeded() {
        TableRestoreStatus accepted = service.restoreTable(request("orders"), REGION);

        assertEquals(TableRestoreStatus.PENDING, accepted.getStatus());
        assertEquals("public", accepted.getTargetSchemaName());
        assertEquals("wg", accepted.getWorkgroupName());
        verify(containerManager).runTableRestoreScript(eq(ACCOUNT_ID), eq("serverless_us-east-1_wg"), eq("admin"),
                eq("dev"), any(Path.class));

        TableRestoreStatus read = service.getTableRestoreStatus(accepted.getTableRestoreRequestId(), REGION);
        assertEquals(TableRestoreStatus.SUCCEEDED, read.getStatus());
        assertEquals(1, service.listTableRestoreStatus("ns", "wg", null, null, REGION).items().size());
        assertTrue(service.listTableRestoreStatus("other", null, null, null, REGION).items().isEmpty());
    }

    @Test
    void aTargetDatabaseOtherThanTheNamespacesIsRefused() {
        RedshiftServerlessTableRestoreService.Request request = new RedshiftServerlessTableRestoreService.Request(
                "ns", "wg", "snap", "dev", null, "orders", "dev host=evil", null, "copy", false);

        assertEquals("ValidationException",
                assertThrows(AwsException.class, () -> service.restoreTable(request, REGION)).getErrorCode());
    }

    @Test
    void aFailingScriptIsRecordedAsFailed() {
        doThrow(new AwsException("InternalFailure", "psql:/tmp/x.sql:2: ERROR:  relation already exists", 500))
                .when(containerManager).runTableRestoreScript(anyString(), anyString(), anyString(), anyString(),
                        any(Path.class));

        TableRestoreStatus accepted = service.restoreTable(request("orders"), REGION);

        TableRestoreStatus read = service.getTableRestoreStatus(accepted.getTableRestoreRequestId(), REGION);
        assertEquals(TableRestoreStatus.FAILED, read.getStatus());
        assertEquals("The table could not be restored: relation already exists", read.getMessage());
    }

    @Test
    void anUnknownTableDatabaseOrRequestIsRefused() {
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.restoreTable(request("missing"), REGION)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.restoreTable(
                new RedshiftServerlessTableRestoreService.Request("ns", "wg", "snap", "other", null, "orders", null,
                        null, "copy", false), REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getTableRestoreStatus("nope", REGION)).getErrorCode());
    }

    @Test
    void missingRequiredMembersAreAValidationErrorAndWriteNothing() {
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.restoreTable(
                new RedshiftServerlessTableRestoreService.Request("ns", "wg", "snap", null, null, "orders", null, null,
                        "copy", false), REGION)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.getTableRestoreStatus(null, REGION)).getErrorCode());
        assertTrue(service.listTableRestoreStatus(null, null, null, null, REGION).items().isEmpty());
    }

    @Test
    void aPartitionedTableIsRefused() throws Exception {
        Files.writeString(dump, String.join("\n", "CREATE TABLE public.orders (", "    id integer", ")",
                "PARTITION BY RANGE (id);", ""));

        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.restoreTable(request("orders"), REGION)).getErrorCode());
    }

    @Test
    void aBadNextTokenIsAnInvalidPaginationException() {
        assertEquals("InvalidPaginationException", assertThrows(AwsException.class,
                () -> service.listTableRestoreStatus(null, null, null, "not-a-token", REGION)).getErrorCode());
    }

    @Test
    void requestsOfADeletedWorkgroupAreDropped() {
        TableRestoreStatus accepted = service.restoreTable(request("orders"), REGION);
        when(serverless.findWorkgroup("wg", REGION)).thenReturn(Optional.empty());

        assertTrue(service.listTableRestoreStatus(null, null, null, null, REGION).items().isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getTableRestoreStatus(accepted.getTableRestoreRequestId(), REGION)).getErrorCode());
    }
}
