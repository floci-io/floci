package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.services.kinesis.model.KinesisShard;
import io.floci.aws.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpectrumKinesisViewPreparationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), false);
    private static final SpectrumSession IN_TRANSACTION =
            new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), true);
    private static final String CREATE_VIEW = "CREATE MATERIALIZED VIEW events_mv AUTO REFRESH NO AS "
            + "SELECT sequence_number FROM kds.events";
    private static final String REFRESH = "REFRESH MATERIALIZED VIEW events_mv";

    private KinesisSchemaRegistry kinesisSchemas;
    private KinesisService kinesis;
    private BackendSql backend;
    private SpectrumQueryPreparation preparation;

    @BeforeEach
    void setUp() {
        kinesisSchemas = new KinesisSchemaRegistry(AccountAwareStorageBackend.<KinesisSchemaBinding>inMemory(ACCOUNT));
        kinesisSchemas.bind(new KinesisSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "kds", null, ROLE));
        kinesis = mock(KinesisService.class);
        ServiceRoleAuthorizer authorizer = mock(ServiceRoleAuthorizer.class);
        when(authorizer.refusal(anyString(), anyString(), anyString(), any(), anyList())).thenReturn(Optional.empty());
        KinesisStreamingViews views = new KinesisStreamingViews(
                new KinesisViewRegistry(AccountAwareStorageBackend.<KinesisViewBinding>inMemory(ACCOUNT)), kinesis,
                authorizer, new RegionResolver("us-east-1", ACCOUNT), Clock.systemUTC());
        KinesisStream stream = new KinesisStream("events", "arn:aws:kinesis:us-east-1:" + ACCOUNT + ":stream/events");
        stream.setShards(List.of(new KinesisShard("shardId-000000000000", "0", "9", "1")));
        when(kinesis.describeStreamForAccount(ACCOUNT, "events", "us-east-1")).thenReturn(stream);
        backend = mock(BackendSql.class);
        when(backend.permitsCachePublication()).thenReturn(true);
        ExternalMetadataWriter metadata = new ExternalMetadataWriter(mock(GlueService.class), new ObjectMapper());
        preparation = new SpectrumQueryPreparation(mock(ExternalCatalogRegistry.class), kinesisSchemas,
                mock(ExternalTableMaterializer.class), new ExternalStatementParser(), mock(GlueService.class), metadata,
                new ExternalCatalogLocks(), views, (accountId, clusterIdentifier) -> null);
    }

    @Test
    void aViewOverAStreamIsHandledAndAViewOverAnythingElseIsLeftToPostgres() {
        assertThat(preparation.handlesDdl(CREATE_VIEW, SESSION), is(true));
        assertThat(preparation.handlesDdl("CREATE MATERIALIZED VIEW v AS SELECT 1 FROM public.t", SESSION), is(false));
        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(false));
    }

    @Test
    void createRefreshAndDropRunAsTheirOwnCommands() {
        assertThat(preparation.prepare(CREATE_VIEW, SESSION, backend), is(true));
        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(true));
        Map<String, Object> page = new HashMap<>();
        page.put("Records", List.of());
        page.put("NextShardIterator", null);
        when(kinesis.getShardIteratorForAccount(ACCOUNT, "events", "shardId-000000000000", "TRIM_HORIZON", null,
                "us-east-1")).thenReturn("it");
        when(kinesis.getRecordsForAccount(anyString(), anyString(), anyInt(), anyString())).thenReturn(page);

        assertThat(preparation.prepare(REFRESH, SESSION, backend), is(true));
        assertThat(preparation.prepare("DROP MATERIALIZED VIEW events_mv", SESSION, backend), is(true));

        verify(backend).execute("DROP TABLE \"public\".\"events_mv\" RESTRICT; DROP TABLE IF EXISTS \"kds\".\"events\"");
        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(false));
        assertThat(preparation.commandTag(CREATE_VIEW), equalTo("CREATE MATERIALIZED VIEW"));
        assertThat(preparation.commandTag(REFRESH), equalTo("REFRESH MATERIALIZED VIEW"));
        assertThat(preparation.commandTag("DROP MATERIALIZED VIEW events_mv"), equalTo("DROP MATERIALIZED VIEW"));
    }

    @Test
    void autoRefreshYesAndTransactionBlocksAreRefused() {
        SpectrumSqlException auto = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE MATERIALIZED VIEW v AUTO REFRESH YES AS SELECT * FROM kds.events", SESSION, backend));
        SpectrumSqlException inTransaction = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE_VIEW, IN_TRANSACTION, backend));

        assertThat(auto.sqlState(), equalTo("0A000"));
        assertThat(auto.getMessage(), containsString("AUTO REFRESH YES"));
        assertThat(inTransaction.sqlState(), equalTo("0A000"));
        verify(backend, never()).execute(anyString());
    }

    @Test
    void aJoinInTheViewIsRefused() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE MATERIALIZED VIEW v AS SELECT * FROM kds.events e JOIN public.t ON e.shard_id = t.id",
                SESSION, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        assertThat(error.getMessage(), containsString("JOIN"));
        verify(backend, never()).execute(anyString());
    }

    @Test
    void aGroupingOrAggregateInTheViewIsRefused() {
        for (String select : new String[] {
                "SELECT partition_key, count(*) FROM kds.events GROUP BY partition_key",
                "SELECT max(sequence_number) FROM kds.events",
                "SELECT DISTINCT shard_id FROM kds.events"}) {
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                    "CREATE MATERIALIZED VIEW v AS " + select, SESSION, backend));

            assertThat(select, error.sqlState(), equalTo("0A000"));
            assertThat(select, error.getMessage(), containsString("aggregate"));
        }
        preparation.prepare("CREATE MATERIALIZED VIEW v AS SELECT 'count(x)' AS c, \"group by\" FROM kds.events",
                SESSION, backend);
    }

    @Test
    void anEscapedQuoteInAnEscapeStringDoesNotHideAJoinOrAnAggregateFromTheChecks() {
        SpectrumSqlException join = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE MATERIALIZED VIEW v AS SELECT E'\\'' AS q FROM kds.events e JOIN public.t ON e.shard_id = 'a'",
                SESSION, backend));
        SpectrumSqlException aggregate = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE MATERIALIZED VIEW v AS SELECT E'\\'' AS q, count(*) FROM kds.events WHERE shard_id = 'a'",
                SESSION, backend));

        assertThat(join.getMessage(), containsString("JOIN"));
        assertThat(aggregate.getMessage(), containsString("aggregate"));
    }

    @Test
    void aKeywordInsideAnEscapeStringWithAnEscapedQuoteIsNotTakenForAJoin() {
        preparation.prepare("CREATE MATERIALIZED VIEW v AS SELECT E'it\\'s a join' AS c FROM kds.events",
                SESSION, backend);
    }

    @Test
    void droppingTheLastViewAlsoDropsTheStagingTable() {
        preparation.prepare(CREATE_VIEW, SESSION, backend);

        preparation.prepare("DROP MATERIALIZED VIEW events_mv", SESSION, backend);

        verify(backend).execute("DROP TABLE \"public\".\"events_mv\" RESTRICT; DROP TABLE IF EXISTS \"kds\".\"events\"");
    }

    @Test
    void refreshInsideATransactionBlockIsRefused() {
        preparation.prepare(CREATE_VIEW, SESSION, backend);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(REFRESH, IN_TRANSACTION, backend));

        assertThat(error.sqlState(), equalTo("25001"));
    }

    @Test
    void dropSchemaCascadeTakesTheViewsThatReadItWithIt() {
        preparation.prepare(CREATE_VIEW, SESSION, backend);

        preparation.prepare("DROP SCHEMA kds CASCADE", SESSION, backend);

        verify(backend).execute(argThat(sql -> sql.startsWith(
                "DROP TABLE IF EXISTS \"public\".\"events_mv\" CASCADE; DROP SCHEMA \"kds\" CASCADE")));
        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(false));
    }
}
