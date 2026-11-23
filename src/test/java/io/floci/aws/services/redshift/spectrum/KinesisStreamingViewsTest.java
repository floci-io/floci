package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.services.kinesis.model.KinesisRecord;
import io.floci.aws.services.kinesis.model.KinesisShard;
import io.floci.aws.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KinesisStreamingViewsTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final String SHARD = "shardId-000000000000";
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), false);
    private static final String SELECT = "SELECT sequence_number, json_parse(from_varbyte(kinesis_data, 'utf-8')) AS doc "
            + "FROM kds.events";
    private static final KinesisSchemaBinding SCHEMA =
            new KinesisSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "kds", "us-west-2", ROLE);

    private KinesisViewRegistry registry;
    private KinesisService kinesis;
    private ServiceRoleAuthorizer authorizer;
    private BackendSql backend;
    private KinesisStreamingViews views;

    @BeforeEach
    void setUp() {
        registry = new KinesisViewRegistry(AccountAwareStorageBackend.<KinesisViewBinding>inMemory(ACCOUNT));
        kinesis = mock(KinesisService.class);
        authorizer = mock(ServiceRoleAuthorizer.class);
        when(authorizer.refusal(anyString(), anyString(), anyString(), any(), anyList())).thenReturn(Optional.empty());
        backend = mock(BackendSql.class);
        views = new KinesisStreamingViews(registry, kinesis, authorizer, new RegionResolver("us-east-1", ACCOUNT),
                Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC));
        KinesisStream stream = new KinesisStream("events", "arn:aws:kinesis:us-west-2:" + ACCOUNT + ":stream/events");
        stream.setShards(List.of(new KinesisShard(SHARD, "0", "9", "1")));
        when(kinesis.describeStreamForAccount(ACCOUNT, "events", "us-west-2")).thenReturn(stream);
    }

    private static ExternalStatement.CreateStreamingView createStatement() {
        return new ExternalStatement.CreateStreamingView(null, "events_mv", false, SELECT);
    }

    private KinesisViewBinding createView() {
        views.create(createStatement(), "events", SCHEMA, SESSION, null, backend);
        return views.find(SESSION, null, "events_mv").orElseThrow();
    }

    private static KinesisRecord record(String sequence, String data) {
        return new KinesisRecord(data.getBytes(StandardCharsets.UTF_8), "pk-" + sequence, sequence,
                Instant.parse("2026-11-18T09:59:00Z"));
    }

    private void iteratorAfter(String sequence, String iterator) {
        when(kinesis.getShardIteratorForAccount(ACCOUNT, "events", SHARD, "AFTER_SEQUENCE_NUMBER", sequence,
                "us-west-2")).thenReturn(iterator);
    }

    private void page(String iterator, List<KinesisRecord> records, String next) {
        Map<String, Object> page = new HashMap<>();
        page.put("Records", records);
        page.put("NextShardIterator", next);
        when(kinesis.getRecordsForAccount(eq(ACCOUNT), eq(iterator), anyInt(), eq("us-west-2"))).thenReturn(page);
    }

    @Test
    void createsTheStagingTableAndAnEmptyViewTableAndRemembersTheBinding() {
        createView();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(backend).execute(sql.capture());
        assertThat(sql.getValue(), startsWith("CREATE TABLE IF NOT EXISTS \"kds\".\"events\" (approximate_arrival_timestamp "
                + "timestamp, partition_key varchar(256), shard_id char(20), sequence_number varchar(128), "
                + "refresh_time timestamp, kinesis_data pg_catalog.varbyte); CREATE TABLE \"public\".\"events_mv\" AS "));
        assertThat(sql.getValue(), containsString(SELECT + " WITH NO DATA"));
        assertThat(views.find(SESSION, "public", "events_mv").isPresent(), is(true));
    }

    @Test
    void aViewNameThatIsTakenIsRefusedBeforeAnythingRuns() {
        createView();

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.create(createStatement(), "events", SCHEMA, SESSION, null, backend));

        assertThat(error.sqlState(), equalTo("42P07"));
        verify(backend).execute(anyString());
    }

    @Test
    void aMissingStreamFailsTheCreateAndLeavesNoBinding() {
        when(kinesis.describeStreamForAccount(ACCOUNT, "events", "us-west-2"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Stream events not found", 400));

        assertThrows(AwsException.class, () -> views.create(createStatement(), "events", SCHEMA, SESSION, null, backend));

        verify(backend, never()).execute(anyString());
        assertThat(views.find(SESSION, null, "events_mv").isPresent(), is(false));
    }

    @Test
    void refreshReadsFromTrimHorizonLoadsOneStatementPerBatchAndSavesTheCheckpoint() {
        KinesisViewBinding view = createView();
        when(kinesis.getShardIteratorForAccount(ACCOUNT, "events", SHARD, "TRIM_HORIZON", null, "us-west-2"))
                .thenReturn("it-1");
        page("it-1", List.of(record("1", "a"), record("2", "b")), "it-2");
        page("it-2", List.of(), "it-3");

        long loaded = views.refresh(view, SCHEMA, null, backend);

        assertThat(loaded, equalTo(2L));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(backend, times(2)).execute(sql.capture());
        String load = sql.getAllValues().get(1);
        assertThat(load, startsWith("INSERT INTO \"kds\".\"events\" VALUES (TIMESTAMP '2026-11-18 09:59:00.000000', "
                + "E'pk-1', E'shardId-000000000000', E'1', TIMESTAMP '2026-11-18 10:00:00.000000', "
                + "decode('61', 'hex')::pg_catalog.varbyte), ("));
        assertThat(load, containsString("; INSERT INTO \"public\".\"events_mv\" " + SELECT + "; TRUNCATE \"kds\".\"events\""));
        assertThat(views.find(SESSION, null, "events_mv").orElseThrow().checkpoints(), equalTo(Map.of(SHARD, "2")));
    }

    @Test
    void aSecondRefreshStartsAfterTheCheckpointAndLoadsNothingWhenTheStreamHasNoNewRecords() {
        KinesisViewBinding view = createView();
        when(kinesis.getShardIteratorForAccount(ACCOUNT, "events", SHARD, "TRIM_HORIZON", null, "us-west-2"))
                .thenReturn("it-1");
        page("it-1", List.of(record("1", "a")), "it-2");
        page("it-2", List.of(), "it-3");
        views.refresh(view, SCHEMA, null, backend);
        iteratorAfter("1", "it-4");
        page("it-4", List.of(), "it-5");

        long loaded = views.refresh(views.find(SESSION, null, "events_mv").orElseThrow(), SCHEMA, null, backend);

        assertThat(loaded, equalTo(0L));
        verify(backend, times(2)).execute(anyString());
    }

    @Test
    void theCheckpointDoesNotMovePastABatchThatFailedToCommit() {
        KinesisViewBinding view = createView();
        when(kinesis.getShardIteratorForAccount(ACCOUNT, "events", SHARD, "TRIM_HORIZON", null, "us-west-2"))
                .thenReturn("it-1");
        page("it-1", List.of(record("1", "a")), "it-2");
        doThrow(new SpectrumReadException("22P02", "bad value")).when(backend)
                .execute(argThat(statement -> statement.startsWith("INSERT INTO")));

        assertThrows(SpectrumReadException.class, () -> views.refresh(view, SCHEMA, null, backend));

        assertThat(views.find(SESSION, null, "events_mv").orElseThrow().checkpoints().isEmpty(), is(true));
    }

    @Test
    void aRoleThatIsRefusedStopsTheRefreshBeforeAnyRecordIsRead() {
        KinesisViewBinding view = createView();
        when(authorizer.refusal(eq(ROLE), eq(ACCOUNT), eq("redshift.amazonaws.com"), isNull(), anyList()))
                .thenReturn(Optional.of("The role " + ROLE + " does not trust redshift.amazonaws.com."));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.refresh(view, SCHEMA, null, backend));

        assertThat(error.sqlState(), equalTo("42501"));
        verify(kinesis, never()).getRecordsForAccount(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void theRefreshAsksForEveryKinesisActionOnTheStreamAndListStreams() {
        KinesisViewBinding view = createView();
        when(kinesis.getShardIteratorForAccount(ACCOUNT, "events", SHARD, "TRIM_HORIZON", null, "us-west-2"))
                .thenReturn("it-1");
        page("it-1", List.of(), "it-2");

        views.refresh(view, SCHEMA, null, backend);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ServiceRoleAuthorizer.Permission>> permissions = ArgumentCaptor.forClass(List.class);
        verify(authorizer).refusal(eq(ROLE), eq(ACCOUNT), eq("redshift.amazonaws.com"), isNull(), permissions.capture());
        List<String> asked = new ArrayList<>();
        permissions.getValue().forEach(permission -> asked.add(permission.action() + " " + permission.resourceArn()));
        String stream = "arn:aws:kinesis:us-west-2:" + ACCOUNT + ":stream/events";
        assertThat(asked, hasSize(6));
        assertThat(asked.contains("kinesis:GetRecords " + stream), is(true));
        assertThat(asked.contains("kinesis:ListStreams *"), is(true));
    }

    @Test
    void aRefreshStartsFromTheCheckpointEvenWhenItWasGivenAStaleBinding() {
        KinesisViewBinding stale = createView();
        when(kinesis.getShardIteratorForAccount(ACCOUNT, "events", SHARD, "TRIM_HORIZON", null, "us-west-2"))
                .thenReturn("it-1");
        page("it-1", List.of(record("1", "a")), "it-2");
        page("it-2", List.of(), "it-3");
        views.refresh(stale, SCHEMA, null, backend);
        iteratorAfter("1", "it-4");
        page("it-4", List.of(), "it-5");

        // the binding read before the first refresh is stale; the second refresh must use the saved checkpoint
        long loaded = views.refresh(stale, SCHEMA, null, backend);

        assertThat(loaded, equalTo(0L));
        verify(kinesis, times(1)).getShardIteratorForAccount(ACCOUNT, "events", SHARD, "TRIM_HORIZON", null, "us-west-2");
    }

    @Test
    void aRefreshOfADroppedViewIsRefused() {
        KinesisViewBinding view = createView();
        views.drop(view, false, false, backend);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.refresh(view, SCHEMA, null, backend));

        assertThat(error.sqlState(), equalTo("42P01"));
    }

    @Test
    void aDropOfAViewThatIsAlreadyGoneDoesNothingWithIfExistsAndFailsWithout() {
        KinesisViewBinding stale = createView();
        views.drop(stale, false, false, backend);

        views.drop(stale, true, false, backend);
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.drop(stale, false, false, backend));

        assertThat(error.sqlState(), equalTo("42P01"));
        verify(backend, times(2)).execute(anyString());
    }

    @Test
    void aCascadeCleanupLeavesAReplacementOverAnotherStreamBound() {
        KinesisViewBinding captured = createView();
        views.unbind(captured);
        registry.bind(new KinesisViewBinding(ACCOUNT, SESSION.clusterKey(), "dev", "public", "events_mv", "kds",
                "other", SELECT, Map.of()));

        views.unbindIfCurrent(captured);

        assertThat(views.find(SESSION, null, "events_mv").isPresent(), is(true));
    }

    @Test
    void theStagingTableStaysWhileAnotherViewReadsTheStream() {
        KinesisViewBinding first = createView();
        views.create(new ExternalStatement.CreateStreamingView(null, "other_mv", false, SELECT), "events", SCHEMA,
                SESSION, null, backend);

        views.drop(first, false, false, backend);

        verify(backend).execute("DROP TABLE \"public\".\"events_mv\" RESTRICT");
    }

    @Test
    void theViewsOfASchemaAreListedAndDropSqlNamesTheViewTable() {
        KinesisViewBinding view = createView();

        assertThat(views.overSchema(SESSION, "kds"), equalTo(List.of(view)));
        assertThat(views.overSchema(SESSION, "other"), equalTo(List.of()));
        assertThat(StreamingViewSupport.dropSql(view.viewSchema(), view.viewName(), true, false),
                equalTo("DROP TABLE IF EXISTS \"public\".\"events_mv\" RESTRICT"));
    }
}
