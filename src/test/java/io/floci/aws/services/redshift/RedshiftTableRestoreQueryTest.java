package io.floci.aws.services.redshift;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.redshift.model.TableRestoreStatus;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftTableRestoreQueryTest {

    private RedshiftTableRestoreService tableRestores;
    private RedshiftQueryHandler handler;

    @BeforeEach
    void setUp() {
        tableRestores = mock(RedshiftTableRestoreService.class);
        handler = new RedshiftQueryHandler(mock(RedshiftService.class), new RedshiftCredentialBroker(),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(RedshiftIamDbUserResolver.class),
                mock(RegionResolver.class), null, null, null, tableRestores);
    }

    private static TableRestoreStatus status(String state) {
        TableRestoreStatus status = new TableRestoreStatus();
        status.setTableRestoreRequestId("req-1");
        status.setStatus(state);
        status.setRequestTime(Instant.parse("2026-11-20T12:00:00Z"));
        status.setClusterIdentifier("analytics");
        status.setSnapshotIdentifier("snap");
        status.setSourceDatabaseName("dev");
        status.setSourceSchemaName("public");
        status.setSourceTableName("orders");
        status.setTargetDatabaseName("dev");
        status.setTargetSchemaName("public");
        status.setNewTableName("orders_copy");
        status.setProgressInMegaBytes(0L);
        status.setTotalDataInMegaBytes(0L);
        return status;
    }

    private static MultivaluedMap<String, String> restoreParams() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("ClusterIdentifier", "analytics");
        params.add("SnapshotIdentifier", "snap");
        params.add("SourceDatabaseName", "dev");
        params.add("SourceTableName", "orders");
        params.add("NewTableName", "orders_copy");
        return params;
    }

    @Test
    void restoreReturnsTheTableRestoreStatusAndPassesTheRequestMembers() {
        when(tableRestores.restoreTable(any())).thenReturn(status("PENDING"));
        MultivaluedMap<String, String> params = restoreParams();
        params.add("SourceSchemaName", "sales");
        params.add("EnableCaseSensitiveIdentifier", "true");

        Response response = handler.handle("RestoreTableFromClusterSnapshot", params);

        String xml = String.valueOf(response.getEntity());
        assertTrue(xml.contains("<RestoreTableFromClusterSnapshotResult><TableRestoreStatus>"), xml);
        assertTrue(xml.contains("<TableRestoreRequestId>req-1</TableRestoreRequestId>"), xml);
        assertTrue(xml.contains("<Status>PENDING</Status>"), xml);
        assertTrue(xml.contains("<RequestTime>2026-11-20T12:00:00Z</RequestTime>"), xml);
        assertTrue(xml.contains("<NewTableName>orders_copy</NewTableName>"), xml);
        assertFalse(xml.contains("<Message>"), xml);
        ArgumentCaptor<RedshiftTableRestoreService.Request> request =
                ArgumentCaptor.forClass(RedshiftTableRestoreService.Request.class);
        verify(tableRestores).restoreTable(request.capture());
        assertEquals("sales", request.getValue().sourceSchemaName());
        assertTrue(request.getValue().caseSensitive());
    }

    @Test
    void aMissingRequiredMemberIsRefused() {
        MultivaluedMap<String, String> params = restoreParams();
        params.remove("NewTableName");

        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> handler.handle("RestoreTableFromClusterSnapshot", params)).getErrorCode());
    }

    @Test
    void describeListsTheRequestsWithTheMarker() {
        when(tableRestores.describeTableRestoreStatus("analytics", null, null, null))
                .thenReturn(new PaginatedResult<>(List.of(status("SUCCEEDED")), "next"));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("ClusterIdentifier", "analytics");

        String xml = String.valueOf(handler.handle("DescribeTableRestoreStatus", params).getEntity());

        assertTrue(xml.contains("<TableRestoreStatusDetails><TableRestoreStatus>"), xml);
        assertTrue(xml.contains("<Status>SUCCEEDED</Status>"), xml);
        assertTrue(xml.contains("<Marker>next</Marker>"), xml);
    }
}
