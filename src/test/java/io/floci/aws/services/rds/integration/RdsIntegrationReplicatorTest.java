package io.floci.aws.services.rds.integration;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.SourceColumn;
import io.floci.aws.services.rds.integration.RdsIntegrationReplicator.Poll;
import io.floci.aws.services.rds.integration.RdsIntegrationReplicator.Replication;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.Change;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.Source;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.SourceTable;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ColumnValue;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.RowChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One poll of a destination's replication slot, with the source and the Redshift writer mocked: the row changes the
 * slot's messages become, when the slot advances, and what stops the replication.
 */
class RdsIntegrationReplicatorTest {

    private static final String TARGET_ARN = "arn:aws:redshift:us-east-1:000000000000:cluster:orders-dw";
    private static final String ID_VALUE = "CAST(CAST(CAST(? AS integer) AS text) AS INTEGER)";
    private static final String NAME_VALUE = "CAST(CAST(CAST(? AS text)::text AS text) AS VARCHAR(65535))";
    private static final int ITEMS = 16385;
    private static final int OTHER = 16390;

    private RdsIntegrationService integrations;
    private RdsIntegrationSource sources;
    private RedshiftZeroEtlWriter writer;
    private Integration integration;
    private Source source;
    private String name;
    private List<Change> changes;

    @BeforeEach
    void setUp() {
        integrations = mock(RdsIntegrationService.class);
        sources = mock(RdsIntegrationSource.class);
        writer = mock(RedshiftZeroEtlWriter.class);
        integration = new Integration();
        integration.setIntegrationArn("arn:aws:rds:us-east-1:000000000000:integration:4b2f2f9c");
        integration.setSourceArn("arn:aws:rds:us-east-1:000000000000:db:orders-src");
        integration.setTargetArn(TARGET_ARN);
        integration.setDataFilter("include: orders.*.*");
        integration.setAccountId("000000000000");
        integration.setRegion("us-east-1");
        source = new Source("container-1", "postgres");
        name = RdsIntegrationSource.replicationName(integration, "orders");
        changes = new ArrayList<>();
        when(integrations.isReplicating(integration, "orders", "orders_dw")).thenReturn(true);
        when(integrations.exists(integration)).thenReturn(true);
        when(sources.source(integration)).thenReturn(source);
        when(sources.slotExists(source, "orders", name)).thenReturn(true);
        when(sources.catalog(source, "orders")).thenReturn(List.of(
                new SourceTable("public", "items", List.of(column("id", "int4", "integer"),
                        column("name", "text", "text")), List.of("id")),
                new SourceTable("public", "no_key", List.of(column("id", "int4", "integer")), List.of())));
        when(sources.peekChanges(source, "orders", name, RdsIntegrationReplicator.BATCH_CHANGES))
                .thenReturn(changes);
    }

    private static SourceColumn column(String name, String type, String formatted) {
        return new SourceColumn(name, type, "b", "N", true, null, null, false, formatted);
    }

    private RdsIntegrationReplicator replicator() {
        return new RdsIntegrationReplicator(integrations, sources, writer, false, Runnable::run, Duration.ZERO,
                Duration.ZERO);
    }

    private Poll poll() {
        return replicator().poll(new Replication(integration, "orders", "orders_dw"));
    }

    private void message(PgOutputBytes bytes) {
        changes.add(new Change("0/1", bytes.build()));
    }

    private void transaction(Runnable body) {
        message(new PgOutputBytes().type('B').int64(0).int64(0).int32(1));
        message(new PgOutputBytes().type('R').int32(ITEMS).string("public").string("items").byte1('d').int16(2)
                .byte1(1).string("id").int32(23).int32(-1)
                .byte1(0).string("name").int32(25).int32(-1));
        body.run();
        message(new PgOutputBytes().type('C').byte1(0).int64(0x100).int64(0x1A0).int64(0));
    }

    private List<RowChange> applied() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RowChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).applyRowChanges(eq("000000000000"), eq(TARGET_ARN), eq("orders_dw"), captor.capture());
        return captor.getValue();
    }

    @Test
    void appliesInsertsUpdatesAndDeletesThenAdvancesTheSlotPastTheTransaction() {
        transaction(() -> {
            message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1").text("widget"));
            message(new PgOutputBytes().type('U').int32(ITEMS).byte1('N').int16(2).text("1").byte1('n'));
            message(new PgOutputBytes().type('D').int32(ITEMS).byte1('K').int16(2).text("1").byte1('n'));
        });

        assertEquals(Poll.APPLIED, poll());

        List<RowChange> applied = applied();
        assertThat(applied.stream().map(RowChange::kind).toList(),
                contains(RowChange.Kind.UPSERT, RowChange.Kind.UPSERT, RowChange.Kind.DELETE));
        assertThat(applied.get(0).values(), contains(new ColumnValue("id", ID_VALUE, "1"),
                new ColumnValue("name", NAME_VALUE, "widget")));
        assertThat(applied.get(1).values(), contains(new ColumnValue("id", ID_VALUE, "1"),
                new ColumnValue("name", NAME_VALUE, null)));
        assertThat(applied.get(2).keys(), contains(new ColumnValue("id", ID_VALUE, "1")));
        assertEquals("items", applied.get(0).table().table());
        verify(sources).advance(source, "orders", name, "0/1A0");
    }

    @Test
    void anUpdateThatChangesTheKeyDeletesTheOldRowFirst() {
        transaction(() -> message(new PgOutputBytes().type('U').int32(ITEMS)
                .byte1('K').int16(2).text("1").byte1('n')
                .byte1('N').int16(2).text("2").text("gadget")));

        poll();

        List<RowChange> applied = applied();
        assertThat(applied.stream().map(RowChange::kind).toList(),
                contains(RowChange.Kind.DELETE, RowChange.Kind.UPSERT));
        assertThat(applied.get(0).keys(), contains(new ColumnValue("id", ID_VALUE, "1")));
        assertThat(applied.get(1).values(), contains(new ColumnValue("id", ID_VALUE, "2"),
                new ColumnValue("name", NAME_VALUE, "gadget")));
    }

    @Test
    void anUpdateThatLeavesAToastedValueOutUpdatesOnlyTheColumnsItCarries() {
        transaction(() -> message(new PgOutputBytes().type('U').int32(ITEMS)
                .byte1('N').int16(2).text("1").byte1('u')));

        poll();

        RowChange update = applied().get(0);
        assertEquals(RowChange.Kind.UPDATE, update.kind());
        assertThat(update.values(), contains(new ColumnValue("id", ID_VALUE, "1")));
        assertThat(update.keys(), contains(new ColumnValue("id", ID_VALUE, "1")));
    }

    @Test
    void aTruncateEmptiesTheTable() {
        transaction(() -> message(new PgOutputBytes().type('T').int32(1).byte1(0).int32(ITEMS)));

        poll();

        assertEquals(RowChange.Kind.TRUNCATE, applied().get(0).kind());
    }

    @Test
    void leavesOutTheChangesOfATableItDoesNotReplicate() {
        transaction(() -> {
            message(new PgOutputBytes().type('R').int32(OTHER).string("public").string("no_key").byte1('d')
                    .int16(1).byte1(0).string("id").int32(23).int32(-1));
            message(new PgOutputBytes().type('I').int32(OTHER).byte1('N').int16(1).text("5"));
        });

        assertEquals(Poll.APPLIED, poll());

        assertThat(applied(), empty());
        verify(sources).advance(source, "orders", name, "0/1A0");
    }

    @Test
    void waitsWhenTheSlotHoldsNoChanges() {
        assertEquals(Poll.IDLE, poll());

        verify(writer, never()).applyRowChanges(anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void triesAgainWithoutAdvancingWhenTheTargetCannotBeReached() {
        transaction(() -> message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1")
                .text("widget")));
        doThrow(new RedshiftZeroEtlWriter.TargetUnavailableException("connection refused", null))
                .when(writer).applyRowChanges(anyString(), anyString(), anyString(), anyList());

        assertEquals(Poll.RETRY, poll());

        verify(sources, never()).advance(any(), anyString(), anyString(), anyString());
        verify(integrations, never()).failReplication(any(), anyString(), anyString(), anyString());
    }

    @Test
    void triesAgainWhenTheSourceCannotBeRead() {
        when(sources.peekChanges(source, "orders", name, RdsIntegrationReplicator.BATCH_CHANGES))
                .thenThrow(new IllegalStateException("container not running"));

        assertEquals(Poll.RETRY, poll());
    }

    @Test
    void failsTheDestinationAndDropsTheSlotWhenTheTargetRefusesAChange() {
        transaction(() -> message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1")
                .text("widget")));
        doThrow(new AwsException("InternalFailure", "relation \"public.items\" does not exist", 500))
                .when(writer).applyRowChanges(anyString(), anyString(), anyString(), anyList());

        assertEquals(Poll.STOPPED, poll());

        ArgumentCaptor<String> failure = ArgumentCaptor.forClass(String.class);
        verify(integrations).failReplication(eq(integration), eq("orders"), eq("orders_dw"), failure.capture());
        assertThat(failure.getValue(), containsString("does not exist"));
        verify(sources).dropReplication(source, "orders", name);
        verify(sources, never()).advance(any(), anyString(), anyString(), anyString());
    }

    @Test
    void dropsTheSlotOnceTheIntegrationIsDeleted() {
        when(integrations.isReplicating(integration, "orders", "orders_dw")).thenReturn(false);
        when(integrations.exists(integration)).thenReturn(false);

        assertEquals(Poll.STOPPED, poll());

        verify(sources).dropReplication(source, "orders", name);
    }

    @Test
    void dropsTheSlotWhenTheIntegrationIsDeletedDuringAFailingBatch() {
        transaction(() -> message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1")
                .text("widget")));
        when(integrations.isReplicating(integration, "orders", "orders_dw")).thenReturn(true, false);
        when(integrations.exists(integration)).thenReturn(false);
        doThrow(new AwsException("InternalFailure", "relation \"public.items\" does not exist", 500))
                .when(writer).applyRowChanges(anyString(), anyString(), anyString(), anyList());

        assertEquals(Poll.STOPPED, poll());

        verify(sources).dropReplication(source, "orders", name);
    }

    @Test
    void keepsAReplacementLoadsSlotWhenAFailingBatchSeesTheDestinationReplaced() {
        transaction(() -> message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1")
                .text("widget")));
        when(integrations.isReplicating(integration, "orders", "orders_dw")).thenReturn(true, false);
        when(integrations.exists(integration)).thenReturn(true);
        doThrow(new AwsException("InternalFailure", "relation \"public.items\" does not exist", 500))
                .when(writer).applyRowChanges(anyString(), anyString(), anyString(), anyList());

        assertEquals(Poll.STOPPED, poll());

        verify(sources, never()).dropReplication(any(), anyString(), anyString());
    }

    @Test
    void stopsWithoutDroppingTheSlotWhenTheDestinationIsLoadedAgain() {
        when(integrations.isReplicating(integration, "orders", "orders_dw")).thenReturn(false);

        assertEquals(Poll.STOPPED, poll());

        verify(sources, never()).dropReplication(any(), anyString(), anyString());
    }

    @Test
    void stopsWhenTheSourceHasNoSlotForTheDestination() {
        when(sources.slotExists(source, "orders", name)).thenReturn(false);

        assertEquals(Poll.STOPPED, poll());

        verify(sources, never()).peekChanges(any(), anyString(), anyString(), anyInt());
        verify(integrations, never()).failReplication(any(), anyString(), anyString(), anyString());
    }

    @Test
    void resumeReplication_startsEachLoadedDestination() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_SYNCED, null));
        integration.getDestinations().add(new Integration.Destination("failed_dw", "failed",
                Integration.Destination.STATE_FAILED, null));
        when(integrations.integrationsWithLoadedDestinations()).thenReturn(List.of(integration));
        // The loop runs inline and stops on its first poll.
        when(integrations.isReplicating(integration, "orders", "orders_dw")).thenReturn(false);

        replicator().resumeReplication();

        verify(integrations).isReplicating(integration, "orders", "orders_dw");
        verify(integrations, never()).isReplicating(integration, "failed", "failed_dw");
    }
}
