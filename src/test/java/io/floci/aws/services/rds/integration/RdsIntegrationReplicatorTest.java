package io.floci.aws.services.rds.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.rds.RdsService;
import io.floci.aws.services.rds.container.RdsContainerManager;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.SourceColumn;
import io.floci.aws.services.rds.integration.RdsIntegrationReplicator.Poll;
import io.floci.aws.services.rds.integration.RdsIntegrationReplicator.Replication;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.Change;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.Source;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.SourceTable;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.StagedChanges;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ColumnValue;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.RowChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private static final String LOAD_ID = "load-1";
    private static final String ID_VALUE = "CAST(CAST(CAST(? AS integer) AS text) AS INTEGER)";
    private static final String NAME_VALUE = "CAST(CAST(? AS text)::text AS text)";
    private static final int ITEMS = 16385;
    private static final int OTHER = 16390;

    private RdsIntegrationService integrations;
    private RdsIntegrationSource sources;
    private RedshiftZeroEtlWriter writer;
    private Integration integration;
    private Source source;
    private String name;
    private List<Change> changes;
    private List<RowChange> appliedChanges;

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
        appliedChanges = new ArrayList<>();
        when(integrations.isReplicating(integration, "orders", "orders_dw", LOAD_ID)).thenReturn(true);
        when(integrations.exists(integration)).thenReturn(true);
        when(sources.source(integration)).thenReturn(source);
        when(sources.slotExists(source, "orders", name)).thenReturn(true);
        when(sources.catalog(source, "orders")).thenReturn(List.of(
                new SourceTable("public", "items", List.of(column("id", "int4", "integer"),
                        column("name", "text", "text")), List.of("id")),
                new SourceTable("public", "no_key", List.of(column("id", "int4", "integer")), List.of())));
        when(sources.peekChanges(source, "orders", name, RdsIntegrationReplicator.BATCH_CHANGES))
                .thenAnswer(ignored -> StagedChanges.of(changes));
        doAnswer(invocation -> {
            Consumer<Consumer<RowChange>> producer = invocation.getArgument(3);
            producer.accept(appliedChanges::add);
            return null;
        }).when(writer).applyRowChanges(eq("000000000000"), eq(TARGET_ARN), eq("orders_dw"), any(Consumer.class));
    }

    private static SourceColumn column(String name, String type, String formatted) {
        return new SourceColumn(name, type, "b", "N", true, null, null, false, formatted);
    }

    private RdsIntegrationReplicator replicator() {
        return new RdsIntegrationReplicator(integrations, sources, writer, false, Runnable::run, Duration.ZERO,
                Duration.ZERO);
    }

    private Poll poll() {
        return replicator().poll(new Replication(integration, "orders", "orders_dw", LOAD_ID));
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
        verify(writer).applyRowChanges(eq("000000000000"), eq(TARGET_ARN), eq("orders_dw"), any(Consumer.class));
        return appliedChanges;
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

        verify(writer, never()).applyRowChanges(anyString(), anyString(), anyString(), any(Consumer.class));
    }

    @Test
    void triesAgainWithoutAdvancingWhenTheTargetCannotBeReached() {
        transaction(() -> message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1")
                .text("widget")));
        doThrow(new RedshiftZeroEtlWriter.TargetUnavailableException("connection refused", null))
                .when(writer).applyRowChanges(anyString(), anyString(), anyString(), any(Consumer.class));

        assertEquals(Poll.RETRY, poll());

        verify(sources, never()).advance(any(), anyString(), anyString(), anyString());
        verify(integrations, never()).failReplication(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void triesAgainWhenTheSourceCannotBeRead() {
        when(sources.peekChanges(source, "orders", name, RdsIntegrationReplicator.BATCH_CHANGES))
                .thenThrow(new IllegalStateException("container not running"));

        assertEquals(Poll.RETRY, poll());
    }

    @Test
    void peekChangesStagesOutputInATempFileStreamsItAndDeletesTheFileOnClose() {
        RdsContainerManager containers = mock(RdsContainerManager.class);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(4);
            out.write("0/1|01020304\n\n0/2|aabb\n".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(containers).queryPostgres(eq("container-1"), eq("postgres"), eq("orders"), anyString(),
                any(OutputStream.class), anyInt());
        RdsIntegrationSource realSource = new RdsIntegrationSource(mock(RdsService.class), containers,
                new ObjectMapper());

        Path stagedFile;
        try (StagedChanges staged = realSource.peekChanges(source, "orders", name, 10)) {
            stagedFile = staged.stagedFile();
            assertNotNull(stagedFile);
            assertTrue(Files.exists(stagedFile));
            assertFalse(staged.isEmpty());
            Change first = staged.next();
            assertEquals("0/1", first.lsn());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, first.data());
            Change second = staged.next();
            assertEquals("0/2", second.lsn());
            assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb}, second.data());
            assertNull(staged.next());
        }
        assertFalse(Files.exists(stagedFile));
    }

    @Test
    void peekChangesThrowsWhenSourceQueryFailsWithoutLeakingOpenStream() {
        RdsContainerManager containers = mock(RdsContainerManager.class);
        doThrow(new IllegalStateException("psql failed"))
                .when(containers).queryPostgres(eq("container-1"), eq("postgres"), eq("orders"), anyString(),
                        any(OutputStream.class), anyInt());
        RdsIntegrationSource realSource = new RdsIntegrationSource(mock(RdsService.class), containers,
                new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> realSource.peekChanges(source, "orders", name, 10));
    }

    @Test
    void peekChangesThrowsWhenStagingWriteFailsEvenIfExecSwallowsIoException() {
        RdsContainerManager containers = mock(RdsContainerManager.class);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(4);
            out.close();
            try {
                out.write("0/1|0102\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Simulates ContainerExec catching and logging IOException from stdout.write.
            }
            return null;
        }).when(containers).queryPostgres(eq("container-1"), eq("postgres"), eq("orders"), anyString(),
                any(OutputStream.class), anyInt());
        RdsIntegrationSource realSource = new RdsIntegrationSource(mock(RdsService.class), containers,
                new ObjectMapper());

        assertThrows(UncheckedIOException.class, () -> realSource.peekChanges(source, "orders", name, 10));
    }

    @Test
    void rejectsAStagedChangeLineThatExceedsTheLineLimit() throws IOException {
        Path staged = Files.createTempFile("floci-rds-integration-limit-", ".wal");
        Files.writeString(staged, "0/1|" + "aa".repeat(20) + "\n", StandardCharsets.UTF_8);

        try (StagedChanges reader = StagedChanges.fromFile(staged, 16)) {
            assertThrows(IllegalStateException.class, reader::next);
        }
        assertFalse(Files.exists(staged));
    }

    @Test
    void failsWithoutAdvancingWhenStagedChangesEndMidTransaction() {
        message(new PgOutputBytes().type('B').int64(0).int64(0).int32(1));
        message(new PgOutputBytes().type('R').int32(ITEMS).string("public").string("items").byte1('d').int16(2)
                .byte1(1).string("id").int32(23).int32(-1)
                .byte1(0).string("name").int32(25).int32(-1));
        message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1").text("widget"));

        assertEquals(Poll.STOPPED, poll());

        ArgumentCaptor<String> failure = ArgumentCaptor.forClass(String.class);
        verify(integrations).failReplication(eq(integration), eq("orders"), eq("orders_dw"), eq(LOAD_ID),
                failure.capture());
        assertThat(failure.getValue(), containsString("before committing a transaction"));
        verify(sources, never()).advance(any(), anyString(), anyString(), anyString());
    }

    @Test
    void nextIdleWaitBacksOffUpToMaxIdleInterval() {
        Duration max = Duration.ofSeconds(15);
        assertEquals(Duration.ofSeconds(2), RdsIntegrationReplicator.nextIdleWait(Duration.ofSeconds(1), max));
        assertEquals(Duration.ofSeconds(4), RdsIntegrationReplicator.nextIdleWait(Duration.ofSeconds(2), max));
        assertEquals(Duration.ofSeconds(8), RdsIntegrationReplicator.nextIdleWait(Duration.ofSeconds(4), max));
        assertEquals(Duration.ofSeconds(15), RdsIntegrationReplicator.nextIdleWait(Duration.ofSeconds(8), max));
        assertEquals(Duration.ofSeconds(15), RdsIntegrationReplicator.nextIdleWait(Duration.ofSeconds(15), max));
        assertEquals(Duration.ZERO, RdsIntegrationReplicator.nextIdleWait(Duration.ZERO, max));
    }

    @Test
    void failsTheDestinationAndDropsTheSlotWhenTheTargetRefusesAChange() {
        transaction(() -> message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1")
                .text("widget")));
        doThrow(new AwsException("InternalFailure", "relation \"public.items\" does not exist", 500))
                .when(writer).applyRowChanges(anyString(), anyString(), anyString(), any(Consumer.class));

        assertEquals(Poll.STOPPED, poll());

        ArgumentCaptor<String> failure = ArgumentCaptor.forClass(String.class);
        verify(integrations).failReplication(eq(integration), eq("orders"), eq("orders_dw"), eq(LOAD_ID),
                failure.capture());
        assertThat(failure.getValue(), containsString("does not exist"));
        verify(sources).dropReplication(source, "orders", name);
        verify(sources, never()).advance(any(), anyString(), anyString(), anyString());
    }

    @Test
    void dropsTheSlotOnceTheIntegrationIsDeleted() {
        when(integrations.isReplicating(integration, "orders", "orders_dw", LOAD_ID)).thenReturn(false);
        when(integrations.exists(integration)).thenReturn(false);

        assertEquals(Poll.STOPPED, poll());

        verify(sources).dropReplication(source, "orders", name);
    }

    @Test
    void dropsTheSlotWhenTheIntegrationIsDeletedDuringAFailingBatch() {
        transaction(() -> message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1")
                .text("widget")));
        when(integrations.isReplicating(integration, "orders", "orders_dw", LOAD_ID)).thenReturn(true, false);
        when(integrations.exists(integration)).thenReturn(false);
        doThrow(new AwsException("InternalFailure", "relation \"public.items\" does not exist", 500))
                .when(writer).applyRowChanges(anyString(), anyString(), anyString(), any(Consumer.class));

        assertEquals(Poll.STOPPED, poll());

        verify(sources).dropReplication(source, "orders", name);
    }

    @Test
    void keepsAReplacementLoadsSlotWhenAFailingBatchSeesTheDestinationReplaced() {
        transaction(() -> message(new PgOutputBytes().type('I').int32(ITEMS).byte1('N').int16(2).text("1")
                .text("widget")));
        when(integrations.isReplicating(integration, "orders", "orders_dw", LOAD_ID)).thenReturn(true, false);
        when(integrations.exists(integration)).thenReturn(true);
        doThrow(new AwsException("InternalFailure", "relation \"public.items\" does not exist", 500))
                .when(writer).applyRowChanges(anyString(), anyString(), anyString(), any(Consumer.class));

        assertEquals(Poll.STOPPED, poll());

        verify(sources, never()).dropReplication(any(), anyString(), anyString());
    }

    @Test
    void stopsWithoutDroppingTheSlotWhenTheDestinationIsLoadedAgain() {
        when(integrations.isReplicating(integration, "orders", "orders_dw", LOAD_ID)).thenReturn(false);

        assertEquals(Poll.STOPPED, poll());

        verify(sources, never()).dropReplication(any(), anyString(), anyString());
    }

    @Test
    void startsANewWorkerWithFreshTablesWhenADestinationIsReloadedUnderTheSameName() throws InterruptedException {
        CountDownLatch oldWorkerRunning = new CountDownLatch(1);
        CountDownLatch releaseOldWorker = new CountDownLatch(1);
        CountDownLatch newWorkerChecked = new CountDownLatch(1);
        when(integrations.isReplicating(integration, "orders", "orders_dw", "load-old")).thenAnswer(invocation -> {
            oldWorkerRunning.countDown();
            releaseOldWorker.await(5, TimeUnit.SECONDS);
            return false;
        });
        when(integrations.isReplicating(integration, "orders", "orders_dw", "load-new")).thenAnswer(invocation -> {
            newWorkerChecked.countDown();
            return false;
        });
        Executor asyncWorkers = task -> Thread.ofVirtual().start(task);
        RdsIntegrationReplicator asyncReplicator = new RdsIntegrationReplicator(integrations, sources, writer, false,
                asyncWorkers, Duration.ZERO, Duration.ZERO);

        asyncReplicator.start(integration, "orders", "orders_dw", "load-old");
        assertTrue(oldWorkerRunning.await(5, TimeUnit.SECONDS));

        asyncReplicator.start(integration, "orders", "orders_dw", "load-new");
        assertTrue(newWorkerChecked.await(5, TimeUnit.SECONDS));
        releaseOldWorker.countDown();
    }

    @Test
    void stopsWhenTheSourceHasNoSlotForTheDestination() {
        when(sources.slotExists(source, "orders", name)).thenReturn(false);

        assertEquals(Poll.STOPPED, poll());

        verify(sources, never()).peekChanges(any(), anyString(), anyString(), anyInt());
        verify(integrations, never()).failReplication(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void resumeReplication_startsEachLoadedDestination() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_SYNCED, null, "load-resumed"));
        integration.getDestinations().add(new Integration.Destination("failed_dw", "failed",
                Integration.Destination.STATE_FAILED, null, "load-failed"));
        when(integrations.integrationsWithLoadedDestinations()).thenReturn(List.of(integration));
        // The loop runs inline and stops on its first poll.
        when(integrations.isReplicating(integration, "orders", "orders_dw", "load-resumed")).thenReturn(false);

        replicator().resumeReplication();

        verify(integrations).isReplicating(integration, "orders", "orders_dw", "load-resumed");
        verify(integrations, never()).isReplicating(eq(integration), eq("failed"), eq("failed_dw"), anyString());
    }
}
