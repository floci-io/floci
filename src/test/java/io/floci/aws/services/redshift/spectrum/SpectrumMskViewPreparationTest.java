package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.kafka.KafkaConsumerHandle;
import io.floci.aws.core.common.kafka.KafkaPartitionOffsets;
import io.floci.aws.core.common.kafka.KafkaRecordDto;
import io.floci.aws.core.common.kafka.KafkaRestClient;
import io.floci.aws.core.common.kafka.KarapaceManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.msk.MskService;
import io.floci.aws.services.msk.model.ClusterState;
import io.floci.aws.services.msk.model.MskCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpectrumMskViewPreparationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final String CLUSTER_ARN = "arn:aws:kafka:us-east-1:000000000000:cluster/orders/abc-123";
    private static final URI REST = URI.create("http://localhost:18082/");
    private static final KafkaConsumerHandle CONSUMER =
            new KafkaConsumerHandle("c1", URI.create("http://localhost:18082/consumers/g/instances/c1"));
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), false);
    private static final SpectrumSession IN_TRANSACTION =
            new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), true);
    private static final String CREATE_VIEW = "CREATE MATERIALIZED VIEW orders_mv AUTO REFRESH NO AS "
            + "SELECT kafka_offset FROM msk.\"orders\"";
    private static final String REFRESH = "REFRESH MATERIALIZED VIEW orders_mv";

    private MskSchemaRegistry mskSchemas;
    private KafkaRestClient restClient;
    private BackendSql backend;
    private SpectrumQueryPreparation preparation;

    @BeforeEach
    void setUp() {
        mskSchemas = new MskSchemaRegistry(AccountAwareStorageBackend.<MskSchemaBinding>inMemory(ACCOUNT));
        mskSchemas.bind(new MskSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "msk", ROLE, "IAM", CLUSTER_ARN));
        MskService msk = mock(MskService.class);
        MskCluster cluster = new MskCluster(CLUSTER_ARN, "orders", "3.6.0");
        cluster.setState(ClusterState.ACTIVE);
        cluster.setBootstrapBrokers("localhost:19092");
        when(msk.getClusterForAccount(ACCOUNT, CLUSTER_ARN)).thenReturn(Optional.of(cluster));
        KarapaceManager karapace = mock(KarapaceManager.class);
        when(karapace.ensureStarted("localhost:19092")).thenReturn(REST);
        restClient = mock(KafkaRestClient.class);
        when(restClient.listPartitions(REST, "orders")).thenReturn(List.of(0));
        when(restClient.partitionOffsets(REST, "orders", 0)).thenReturn(new KafkaPartitionOffsets(0, 1));
        when(restClient.createConsumer(eq(REST), anyString(), anyString())).thenReturn(CONSUMER);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(new KafkaRecordDto("orders", 0, 0, 1L,
                "CreateTime", null, "v".getBytes(StandardCharsets.UTF_8), List.of())), List.of());
        ServiceRoleAuthorizer authorizer = mock(ServiceRoleAuthorizer.class);
        when(authorizer.refusal(anyString(), anyString(), anyString(), any(), anyList())).thenReturn(Optional.empty());
        MskStreamingViews mskViews = new MskStreamingViews(
                new MskViewRegistry(AccountAwareStorageBackend.<MskViewBinding>inMemory(ACCOUNT)), msk, karapace,
                restClient, authorizer, Clock.systemUTC());
        backend = mock(BackendSql.class);
        when(backend.permitsCachePublication()).thenReturn(true);
        ExternalMetadataWriter metadata = new ExternalMetadataWriter(mock(GlueService.class), new ObjectMapper());
        KinesisStreamingViews kinesisViews = new KinesisStreamingViews(
                new KinesisViewRegistry(AccountAwareStorageBackend.<KinesisViewBinding>inMemory(ACCOUNT)),
                null, null, null, Clock.systemUTC());
        preparation = new SpectrumQueryPreparation(mock(ExternalCatalogRegistry.class),
                new KinesisSchemaRegistry(AccountAwareStorageBackend.<KinesisSchemaBinding>inMemory(ACCOUNT)),
                mskSchemas, new ExternalFunctionRegistry(AccountAwareStorageBackend.<ExternalFunctionBinding>inMemory(ACCOUNT)),
                mock(ExternalTableMaterializer.class), new ExternalStatementParser(), mock(GlueService.class), metadata,
                new ExternalCatalogLocks(), kinesisViews, mskViews, (accountId, clusterIdentifier) -> null);
    }

    @Test
    void aViewOverATopicIsHandledAndAViewOverAnythingElseIsLeftToPostgres() {
        assertThat(preparation.handlesDdl(CREATE_VIEW, SESSION), is(true));
        assertThat(preparation.handlesDdl("CREATE MATERIALIZED VIEW v AS SELECT 1 FROM public.t", SESSION), is(false));
        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(false));
    }

    @Test
    void createRefreshAndDropRunAsTheirOwnCommands() {
        assertThat(preparation.prepare(CREATE_VIEW, SESSION, backend), is(true));
        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(true));
        assertThat(preparation.handlesDdl("DROP MATERIALIZED VIEW orders_mv", SESSION), is(true));

        assertThat(preparation.prepare(REFRESH, SESSION, backend), is(true));
        verify(backend).execute(argThat(sql -> sql.startsWith("INSERT INTO \"msk\".\"orders\" VALUES (0, 0, ")));
        assertThat(preparation.prepare("DROP MATERIALIZED VIEW orders_mv", SESSION, backend), is(true));

        verify(backend).execute("DROP TABLE \"public\".\"orders_mv\" RESTRICT; DROP TABLE IF EXISTS \"msk\".\"orders\"");
        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(false));
        assertThat(preparation.commandTag(CREATE_VIEW), equalTo("CREATE MATERIALIZED VIEW"));
        assertThat(preparation.commandTag(REFRESH), equalTo("REFRESH MATERIALIZED VIEW"));
        assertThat(preparation.commandTag("DROP MATERIALIZED VIEW orders_mv"), equalTo("DROP MATERIALIZED VIEW"));
    }

    @Test
    void autoRefreshYesAndTransactionBlocksAreRefused() {
        SpectrumSqlException auto = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE MATERIALIZED VIEW v AUTO REFRESH YES AS SELECT * FROM msk.orders", SESSION, backend));
        SpectrumSqlException inTransaction = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE_VIEW, IN_TRANSACTION, backend));

        assertThat(auto.sqlState(), equalTo("0A000"));
        assertThat(auto.getMessage(), containsString("AUTO REFRESH YES"));
        assertThat(inTransaction.sqlState(), equalTo("0A000"));
        verify(backend, never()).execute(anyString());
    }

    @Test
    void aJoinOrAnAggregateInTheViewIsRefused() {
        SpectrumSqlException join = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE MATERIALIZED VIEW v AS SELECT * FROM msk.orders o JOIN public.t ON o.kafka_offset = t.id",
                SESSION, backend));
        SpectrumSqlException aggregate = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE MATERIALIZED VIEW v AS SELECT kafka_partition, count(*) FROM msk.orders GROUP BY kafka_partition",
                SESSION, backend));

        assertThat(join.getMessage(), containsString("JOIN"));
        assertThat(aggregate.getMessage(), containsString("aggregate"));
        verify(backend, never()).execute(anyString());
    }

    @Test
    void aViewReadsExactlyOneTopic() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                "CREATE MATERIALIZED VIEW v AS SELECT * FROM msk.orders WHERE kafka_offset IN (SELECT kafka_offset FROM msk.payments)",
                SESSION, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        assertThat(error.getMessage(), containsString("exactly one topic"));
    }

    @Test
    void refreshInsideATransactionBlockIsRefused() {
        preparation.prepare(CREATE_VIEW, SESSION, backend);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(REFRESH, IN_TRANSACTION, backend));

        assertThat(error.sqlState(), equalTo("25001"));
    }

    @Test
    void dropInsideATransactionBlockIsRefused() {
        preparation.prepare(CREATE_VIEW, SESSION, backend);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP MATERIALIZED VIEW orders_mv", IN_TRANSACTION, backend));

        assertThat(error.sqlState(), equalTo("25001"));
    }

    @Test
    void aPlainReadOfATopicRelationIsStillRefused() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("SELECT * FROM msk.orders", SESSION, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        assertThat(error.getMessage(), containsString("only read through a streaming materialized view"));
    }

    @Test
    void dropSchemaWithoutCascadeDoesNotDropTheViewsThatReadIt() {
        preparation.prepare(CREATE_VIEW, SESSION, backend);

        preparation.prepare("DROP SCHEMA msk", SESSION, backend);

        verify(backend).execute(argThat(sql -> sql.startsWith("DROP SCHEMA \"msk\" RESTRICT")));
        verify(backend, never()).execute(argThat(sql -> sql.startsWith("DROP TABLE IF EXISTS \"public\".\"orders_mv\"")));
    }

    @Test
    void dropSchemaCascadeTakesTheViewsThatReadItWithIt() {
        preparation.prepare(CREATE_VIEW, SESSION, backend);

        preparation.prepare("DROP SCHEMA msk CASCADE", SESSION, backend);

        verify(backend).execute(argThat(sql -> sql.startsWith(
                "DROP TABLE IF EXISTS \"public\".\"orders_mv\" CASCADE; DROP SCHEMA \"msk\" CASCADE")));
        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(false));
        assertThat(mskSchemas.find(ACCOUNT, SESSION.clusterKey(), "dev", "msk").isPresent(), is(false));
    }

    @Test
    void forgettingTheRuntimeForgetsTheViews() {
        preparation.prepare(CREATE_VIEW, SESSION, backend);

        preparation.forgetRuntime(ACCOUNT, SESSION.clusterKey());

        assertThat(preparation.handlesDdl(REFRESH, SESSION), is(false));
    }
}
