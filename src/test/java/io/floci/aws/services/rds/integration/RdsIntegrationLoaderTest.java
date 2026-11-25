package io.floci.aws.services.rds.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.rds.RdsService;
import io.floci.aws.services.rds.container.RdsContainerManager;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ReplicatedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The initial load with the source's container and the Redshift writer mocked: which tables are copied, the
 * columns they get, the COPY that reads them out, and the destination state the load ends in.
 */
class RdsIntegrationLoaderTest {

    private static final String CLUSTER_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:orders-src";
    private static final String TARGET_ARN = "arn:aws:redshift:us-east-1:000000000000:cluster:orders-dw";
    private static final String LOAD_ID = "load-1";

    private RdsIntegrationService integrations;
    private RdsService rdsService;
    private RdsContainerManager containers;
    private RedshiftZeroEtlWriter writer;
    private RdsIntegrationReplicator replicator;
    private List<String> loadedRows;
    private Integration integration;

    @BeforeEach
    void setUp() {
        integrations = mock(RdsIntegrationService.class);
        rdsService = mock(RdsService.class);
        containers = mock(RdsContainerManager.class);
        writer = mock(RedshiftZeroEtlWriter.class);
        replicator = mock(RdsIntegrationReplicator.class);
        loadedRows = new ArrayList<>();
        DbCluster cluster = new DbCluster();
        cluster.setContainerId("container-1");
        cluster.setMasterUsername("postgres");
        when(rdsService.ensureClusterBackend("orders-src", "us-east-1")).thenReturn(cluster);
        when(integrations.isLoading(any(), eq("orders"), eq("orders_dw"), eq(LOAD_ID))).thenReturn(true);
        walLevel("replica");
        doAnswer(invocation -> {
            invocation.<OutputStream>getArgument(4).write("1,widget\n".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(containers).queryPostgres(eq("container-1"), eq("postgres"), eq("orders"), startsWith("COPY"),
                any(OutputStream.class), anyInt());
        when(writer.loadTable(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            try (InputStream in = invocation.getArgument(4)) {
                loadedRows.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return 1L;
        });
        integration = new Integration();
        integration.setIntegrationArn("arn:aws:rds:us-east-1:000000000000:integration:4b2f2f9c");
        integration.setSourceArn(CLUSTER_ARN);
        integration.setTargetArn(TARGET_ARN);
        integration.setDataFilter("include: orders.*.*, exclude: orders.audit.*");
        integration.setAccountId("000000000000");
        integration.setRegion("us-east-1");
    }

    private RdsIntegrationLoader loader(boolean mock) {
        return new RdsIntegrationLoader(integrations, new RdsIntegrationSource(rdsService, containers,
                new ObjectMapper()), replicator, writer, mock, Runnable::run, 2, Duration.ZERO);
    }

    private void walLevel(String level) {
        when(containers.queryPostgres("container-1", "postgres", "orders", "SHOW wal_level"))
                .thenReturn(level + "\n");
    }

    private void catalog(String... rows) {
        when(containers.queryPostgres("container-1", "postgres", "orders", RdsIntegrationSource.CATALOG_SQL))
                .thenReturn(String.join("\n", rows));
    }

    private static String column(String schema, String table, String column, String type, String formatted,
                                 int key) {
        return "{\"schema\":\"" + schema + "\",\"table\":\"" + table + "\",\"column\":\"" + column
                + "\",\"type\":\"" + type + "\",\"typtype\":\"b\",\"category\":\"N\",\"builtin\":true,"
                + "\"element\":null,\"elementtype\":null,\"elementbuiltin\":false,\"formatted\":\"" + formatted
                + "\",\"key\":" + key + "}";
    }

    @Test
    void copiesEachIncludedTableWithAPrimaryKey() {
        catalog(column("public", "items", "id", "int4", "integer", 1),
                column("public", "items", "name", "text", "text", 0),
                column("public", "notes", "body", "text", "text", 0),
                column("audit", "events", "id", "int4", "integer", 1));

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        ArgumentCaptor<ReplicatedTable> table = ArgumentCaptor.forClass(ReplicatedTable.class);
        verify(writer, times(1)).loadTable(eq("000000000000"), eq(TARGET_ARN), eq("orders_dw"), table.capture(),
                any());
        assertEquals("items", table.getValue().table());
        assertThat(table.getValue().columns(), contains(new ReplicatedTable.Column("id", "INTEGER"),
                new ReplicatedTable.Column("name", "VARCHAR(65535)")));
        assertThat(table.getValue().primaryKey(), contains("id"));
        assertThat(loadedRows, contains("1,widget\n"));
        ArgumentCaptor<String> copy = ArgumentCaptor.forClass(String.class);
        verify(containers).queryPostgres(eq("container-1"), eq("postgres"), eq("orders"), copy.capture(),
                any(OutputStream.class), anyInt());
        assertEquals("COPY (SELECT \"id\", \"name\"::text FROM \"public\".\"items\") TO STDOUT WITH (FORMAT csv)",
                copy.getValue());
        verify(integrations).finishDestination(integration, "orders", "orders_dw", LOAD_ID, null);
    }

    @Test
    void catalogQueryRestrictsPrimaryKeysToKeyColumnsOnly() {
        assertThat(RdsIntegrationSource.CATALOG_SQL, containsString("k.n <= i.indnkeyatts"));
    }

    @Test
    void ordersACompositePrimaryKeyByItsKeyPositions() {
        catalog(column("public", "lines", "line", "int4", "integer", 2),
                column("public", "lines", "order_id", "int8", "bigint", 1));

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        ArgumentCaptor<ReplicatedTable> table = ArgumentCaptor.forClass(ReplicatedTable.class);
        verify(writer).loadTable(anyString(), anyString(), anyString(), table.capture(), any());
        assertThat(table.getValue().primaryKey(), contains("order_id", "line"));
    }

    @Test
    void skipsATableWithAColumnTypeZeroEtlDoesNotReplicate() {
        catalog(column("public", "items", "id", "int4", "integer", 1),
                column("public", "items", "price", "numeric", "numeric", 0));

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(writer, never()).loadTable(anyString(), anyString(), anyString(), any(), any());
        verify(integrations).finishDestination(integration, "orders", "orders_dw", LOAD_ID, null);
    }

    @Test
    void carriesOnWithTheOtherTablesWhenOneFailsToLoadAndReportsFailure() {
        catalog(column("public", "broken", "id", "int4", "integer", 1),
                column("public", "items", "id", "int4", "integer", 1));
        doThrow(new IllegalStateException("value too long")).doReturn(1L).when(writer)
                .loadTable(anyString(), anyString(), anyString(), any(), any());

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(writer, times(2)).loadTable(anyString(), anyString(), anyString(), any(), any());
        ArgumentCaptor<String> failure = ArgumentCaptor.forClass(String.class);
        verify(integrations).finishDestination(eq(integration), eq("orders"), eq("orders_dw"), eq(LOAD_ID),
                failure.capture());
        assertThat(failure.getValue(), containsString("public.broken"));
    }

    @Test
    void retriesWhenTargetIsUnavailableDuringCopy() {
        catalog(column("public", "items", "id", "int4", "integer", 1));
        doThrow(new RedshiftZeroEtlWriter.TargetUnavailableException("connection refused", null))
                .doReturn(1L)
                .when(writer).loadTable(anyString(), anyString(), anyString(), any(), any());

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(writer, times(2)).loadTable(anyString(), anyString(), anyString(), any(), any());
        verify(integrations).finishDestination(integration, "orders", "orders_dw", LOAD_ID, null);
    }

    @Test
    void retriesWhenSourceFailsDuringCopyAfterCatalogDiscovery() {
        catalog(column("public", "items", "id", "int4", "integer", 1));
        doThrow(new IllegalStateException("source container stopped"))
                .doAnswer(invocation -> {
                    invocation.<OutputStream>getArgument(4).write("1\n".getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(containers).queryPostgres(eq("container-1"), eq("postgres"), eq("orders"),
                        startsWith("COPY"), any(OutputStream.class), anyInt());

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(writer, times(1)).loadTable(anyString(), anyString(), anyString(), any(), any());
        verify(integrations).finishDestination(integration, "orders", "orders_dw", LOAD_ID, null);
    }

    @Test
    void triesAgainWhenTheSourceIsNotBackYet() {
        when(containers.queryPostgres("container-1", "postgres", "orders", RdsIntegrationSource.CATALOG_SQL))
                .thenThrow(new IllegalStateException("container not running"))
                .thenReturn(column("public", "items", "id", "int4", "integer", 1));

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(writer).loadTable(anyString(), anyString(), anyString(), any(), any());
        verify(integrations).finishDestination(integration, "orders", "orders_dw", LOAD_ID, null);
    }

    @Test
    void failsTheDestinationWhenTheSourceCannotBeRead() {
        when(containers.queryPostgres("container-1", "postgres", "orders", RdsIntegrationSource.CATALOG_SQL))
                .thenThrow(new IllegalStateException("psql failed: database \"orders\" does not exist"));

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        ArgumentCaptor<String> failure = ArgumentCaptor.forClass(String.class);
        verify(integrations).finishDestination(eq(integration), eq("orders"), eq("orders_dw"), eq(LOAD_ID),
                failure.capture());
        assertThat(failure.getValue(), containsString("does not exist"));
    }

    @Test
    void stopsWhenTheIntegrationIsNoLongerLoading() {
        when(integrations.isLoading(any(), eq("orders"), eq("orders_dw"), eq(LOAD_ID))).thenReturn(false);
        catalog(column("public", "items", "id", "int4", "integer", 1));

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(writer, never()).loadTable(anyString(), anyString(), anyString(), any(), any());
        verify(integrations, never()).finishDestination(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void stopsBeforeWritingWhenLoadIsSupersededDuringSourceExport() {
        when(integrations.isLoading(any(), eq("orders"), eq("orders_dw"), eq("load-old")))
                .thenReturn(true, false);
        catalog(column("public", "items", "id", "int4", "integer", 1));

        loader(false).submit(integration, "orders", "orders_dw", "load-old");

        verify(writer, never()).loadTable(anyString(), anyString(), anyString(), any(), any());
        verify(integrations, never()).finishDestination(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void inMockModeCopiesNothingAndSyncs() {
        loader(true).submit(integration, "orders", "orders_dw", LOAD_ID);

        verifyNoInteractions(containers, writer);
        verify(integrations).finishDestination(eq(integration), eq("orders"), eq("orders_dw"), eq(LOAD_ID), isNull());
    }

    @Test
    void resumeInterruptedLoads_loadsTheSyncingDestinationsAgain() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_SYNCING, null, "load-resumed"));
        integration.getDestinations().add(new Integration.Destination("done_dw", "done",
                Integration.Destination.STATE_SYNCED, null, "load-done"));
        when(integrations.integrationsWithLoadsInProgress()).thenReturn(List.of(integration));

        loader(true).resumeInterruptedLoads();

        verify(integrations).reconcileInterruptedCreations();
        verify(integrations).finishDestination(any(), eq("orders"), eq("orders_dw"), eq("load-resumed"), isNull());
        verify(integrations, never()).finishDestination(any(), eq("done"), anyString(), any(), any());
    }

    @Test
    void withLogicalReplication_publishesTheCopiedTablesAndCreatesTheSlotBeforeCopying() {
        walLevel("logical");
        catalog(column("public", "items", "id", "int4", "integer", 1),
                column("public", "notes", "body", "text", "text", 0));
        when(integrations.isReplicating(any(), eq("orders"), eq("orders_dw"))).thenReturn(true);
        String name = RdsIntegrationSource.replicationName(integration, "orders");

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        InOrder order = inOrder(containers);
        order.verify(containers).queryPostgres("container-1", "postgres", "orders",
                "CREATE PUBLICATION " + name + " FOR TABLE \"public\".\"items\" "
                        + "WITH (publish_via_partition_root = true)");
        order.verify(containers).queryPostgres("container-1", "postgres", "orders",
                "SELECT pg_create_logical_replication_slot('" + name + "', 'pgoutput')");
        order.verify(containers).queryPostgres(eq("container-1"), eq("postgres"), eq("orders"), startsWith("COPY"),
                any(OutputStream.class), anyInt());
        verify(integrations).finishDestination(integration, "orders", "orders_dw", LOAD_ID, null);
        verify(replicator).start(integration, "orders", "orders_dw");
    }

    @Test
    void withoutLogicalReplication_copiesOnceAndDoesNotReplicate() {
        catalog(column("public", "items", "id", "int4", "integer", 1));

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(containers, never()).queryPostgres(anyString(), anyString(), anyString(),
                startsWith("CREATE PUBLICATION"));
        verify(integrations).finishDestination(integration, "orders", "orders_dw", LOAD_ID, null);
        verifyNoInteractions(replicator);
    }

    @Test
    void aFailedLoadDropsTheReplicationItSetUp() {
        walLevel("logical");
        catalog(column("public", "items", "id", "int4", "integer", 1));
        doThrow(new IllegalStateException("value too long")).when(writer)
                .loadTable(anyString(), anyString(), anyString(), any(), any());

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(integrations).finishDestination(eq(integration), eq("orders"), eq("orders_dw"), eq(LOAD_ID),
                anyString());
        verify(containers, times(2)).queryPostgres(anyString(), anyString(), anyString(),
                startsWith("SELECT pg_drop_replication_slot"));
        verifyNoInteractions(replicator);
    }

    @Test
    void aLoadStoppedByTheIntegrationsDeletionDropsTheReplicationItSetUp() {
        walLevel("logical");
        catalog(column("public", "items", "id", "int4", "integer", 1));
        when(integrations.isLoading(any(), eq("orders"), eq("orders_dw"), eq(LOAD_ID))).thenReturn(true, false);

        loader(false).submit(integration, "orders", "orders_dw", LOAD_ID);

        verify(writer, never()).loadTable(anyString(), anyString(), anyString(), any(), any());
        verify(containers, times(2)).queryPostgres(anyString(), anyString(), anyString(),
                startsWith("SELECT pg_drop_replication_slot"));
        verifyNoInteractions(replicator);
    }
}
