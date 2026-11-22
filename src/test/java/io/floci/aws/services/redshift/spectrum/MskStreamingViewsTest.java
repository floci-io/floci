package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.kafka.KafkaConsumerHandle;
import io.floci.aws.core.common.kafka.KafkaHeaderDto;
import io.floci.aws.core.common.kafka.KafkaOffsetDto;
import io.floci.aws.core.common.kafka.KafkaPartitionOffsets;
import io.floci.aws.core.common.kafka.KafkaRecordDto;
import io.floci.aws.core.common.kafka.KafkaRestClient;
import io.floci.aws.core.common.kafka.KafkaRestClientException;
import io.floci.aws.core.common.kafka.KafkaTopicNotFoundException;
import io.floci.aws.core.common.kafka.KafkaTopicPartition;
import io.floci.aws.core.common.kafka.KarapaceManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.ServiceRoleAuthorizer.Permission;
import io.floci.aws.services.msk.MskService;
import io.floci.aws.services.msk.model.ClusterState;
import io.floci.aws.services.msk.model.MskCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MskStreamingViewsTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final String CLUSTER_ARN = "arn:aws:kafka:us-east-1:000000000000:cluster/orders/abc-123";
    private static final String BOOTSTRAP = "localhost:19092";
    private static final URI REST = URI.create("http://localhost:18082/");
    private static final KafkaConsumerHandle CONSUMER =
            new KafkaConsumerHandle("rest-consumer-1", URI.create("http://localhost:18082/consumers/g/instances/rest-consumer-1"));
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), false);
    private static final String SELECT = "SELECT kafka_partition, kafka_offset, from_varbyte(kafka_value, 'utf-8') AS v "
            + "FROM msk.\"orders\"";
    private static final MskSchemaBinding SCHEMA = new MskSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "msk", ROLE,
            "IAM", CLUSTER_ARN);
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

    private MskViewRegistry registry;
    private MskService msk;
    private KarapaceManager karapace;
    private KafkaRestClient restClient;
    private ServiceRoleAuthorizer authorizer;
    private BackendSql backend;
    private MskStreamingViews views;
    private MskCluster cluster;

    @BeforeEach
    void setUp() {
        registry = new MskViewRegistry(AccountAwareStorageBackend.<MskViewBinding>inMemory(ACCOUNT));
        msk = mock(MskService.class);
        cluster = new MskCluster(CLUSTER_ARN, "orders", "3.6.0");
        cluster.setState(ClusterState.ACTIVE);
        cluster.setBootstrapBrokers(BOOTSTRAP);
        when(msk.getClusterForAccount(ACCOUNT, CLUSTER_ARN)).thenReturn(Optional.of(cluster));
        karapace = mock(KarapaceManager.class);
        when(karapace.ensureStarted(BOOTSTRAP)).thenReturn(REST);
        restClient = mock(KafkaRestClient.class);
        when(restClient.createConsumer(eq(REST), anyString(), anyString())).thenReturn(CONSUMER);
        authorizer = mock(ServiceRoleAuthorizer.class);
        when(authorizer.refusal(anyString(), anyString(), anyString(), any(), anyList())).thenReturn(Optional.empty());
        backend = mock(BackendSql.class);
        views = new MskStreamingViews(registry, msk, karapace, restClient, authorizer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExternalStatement.CreateStreamingView createStatement() {
        return new ExternalStatement.CreateStreamingView(null, "orders_mv", false, SELECT);
    }

    private MskViewBinding createView() {
        when(restClient.listPartitions(REST, "orders")).thenReturn(List.of(0));
        views.create(createStatement(), "orders", SCHEMA, SESSION, backend);
        return registry.find(ACCOUNT, SESSION.clusterKey(), "dev", MskViewBinding.nameOf("public", "orders_mv"))
                .orElseThrow();
    }

    private MskViewBinding stored() {
        return registry.find(ACCOUNT, SESSION.clusterKey(), "dev", MskViewBinding.nameOf("public", "orders_mv"))
                .orElseThrow();
    }

    private void partitionEnds(long... ends) {
        List<Integer> partitions = new ArrayList<>();
        for (int partition = 0; partition < ends.length; partition++) {
            partitions.add(partition);
            when(restClient.partitionOffsets(REST, "orders", partition))
                    .thenReturn(new KafkaPartitionOffsets(0, ends[partition]));
        }
        when(restClient.listPartitions(REST, "orders")).thenReturn(partitions);
    }

    private static KafkaRecordDto record(int partition, long offset, String value) {
        return new KafkaRecordDto("orders", partition, offset, 1_700_000_000_000L + offset, "CreateTime",
                ("key-" + offset).getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8), List.of());
    }

    @Test
    void createBuildsTheViewTableAndTheStagingTableWithTheEightKafkaColumnsAndBindsTheView() {
        createView();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(backend).execute(sql.capture());
        assertThat(sql.getValue(), startsWith("CREATE TABLE IF NOT EXISTS \"msk\".\"orders\" (kafka_partition bigint, "
                + "kafka_offset bigint, kafka_timestamp_type char(1), kafka_timestamp timestamp, "
                + "kafka_key pg_catalog.varbyte, kafka_value pg_catalog.varbyte, kafka_headers pg_catalog.super, "
                + "refresh_time timestamp); "));
        assertThat(sql.getValue(), containsString("CREATE TABLE \"public\".\"orders_mv\" AS " + SELECT + " WITH NO DATA"));
        MskViewBinding binding = stored();
        assertThat(binding.topicSchema(), equalTo("msk"));
        assertThat(binding.topicName(), equalTo("orders"));
        assertThat(binding.checkpoints().isEmpty(), is(true));
    }

    @Test
    void createRefusesAViewNameThatIsTaken() {
        createView();

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.create(createStatement(), "orders", SCHEMA, SESSION, backend));

        assertThat(error.sqlState(), equalTo("42P07"));
    }

    @Test
    void createFailsForAClusterThatDoesNotExistWithoutTouchingTheBackend() {
        when(msk.getClusterForAccount(ACCOUNT, CLUSTER_ARN)).thenReturn(Optional.empty());

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.create(createStatement(), "orders", SCHEMA, SESSION, backend));

        assertThat(error.sqlState(), equalTo("42704"));
        verifyNoInteractions(backend, restClient);
    }

    @Test
    void createFailsForAClusterWithoutARunningBroker() {
        cluster.setState(ClusterState.CREATING);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.create(createStatement(), "orders", SCHEMA, SESSION, backend));

        assertThat(error.sqlState(), equalTo("58030"));
        assertThat(error.getMessage(), containsString("no running broker"));
        verifyNoInteractions(backend, karapace);
    }

    @Test
    void createFailsForAClusterThatHasNoBootstrapBrokers() {
        cluster.setBootstrapBrokers(null);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.create(createStatement(), "orders", SCHEMA, SESSION, backend));

        assertThat(error.sqlState(), equalTo("58030"));
        verifyNoInteractions(backend, karapace);
    }

    @Test
    void createFailsWhenTheKafkaRestBridgeCannotStart() {
        when(karapace.ensureStarted(BOOTSTRAP)).thenThrow(new IllegalStateException("docker is not running"));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.create(createStatement(), "orders", SCHEMA, SESSION, backend));

        assertThat(error.sqlState(), equalTo("58030"));
        assertThat(error.getMessage(), containsString("docker is not running"));
        verifyNoInteractions(backend);
    }

    @Test
    void createFailsForAMissingTopicAsAnUndefinedRelation() {
        when(restClient.listPartitions(REST, "orders")).thenThrow(new KafkaTopicNotFoundException("orders",
                new KafkaRestClientException("GET", "topics/orders/partitions", 404, "{\"error_code\":40401}")));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.create(createStatement(), "orders", SCHEMA, SESSION, backend));

        assertThat(error.sqlState(), equalTo("42P01"));
        verifyNoInteractions(backend);
    }

    @Test
    void createFailsWhenTheBrokerCannotBeReachedAndLeavesNoBinding() {
        when(restClient.listPartitions(REST, "orders")).thenThrow(new UncheckedIOException(new IOException("refused")));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.create(createStatement(), "orders", SCHEMA, SESSION, backend));

        assertThat(error.sqlState(), equalTo("58030"));
        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev"), hasSize(0));
        verifyNoInteractions(backend);
    }

    @Test
    void theFirstRefreshReadsEveryPartitionFromTheBeginningThroughManualAssignment() {
        createView();
        partitionEnds(2, 1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(
                List.of(record(0, 0, "a"), record(0, 1, "b"), record(1, 0, "c")), List.of());

        long loaded = views.refresh(stored(), SCHEMA, backend);

        assertThat(loaded, equalTo(3L));
        verify(restClient).assign(CONSUMER, List.of(new KafkaTopicPartition("orders", 0),
                new KafkaTopicPartition("orders", 1)));
        verify(restClient).seekToBeginning(CONSUMER, List.of(new KafkaTopicPartition("orders", 0),
                new KafkaTopicPartition("orders", 1)));
        verify(restClient).seek(CONSUMER, List.of());
        verify(restClient, never()).subscribe(any(), anyList());
        verify(restClient).close(CONSUMER);
        assertThat(stored().checkpoints(), equalTo(Map.of("0", 1L, "1", 0L)));
    }

    @Test
    void aRefreshLoadsTheBatchIntoTheStagingTableRunsTheViewQueryAndEmptiesTheStagingTable() {
        createView();
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());

        views.refresh(stored(), SCHEMA, backend);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(backend, org.mockito.Mockito.times(2)).execute(sql.capture());
        String load = sql.getAllValues().get(1);
        assertThat(load, startsWith("INSERT INTO \"msk\".\"orders\" VALUES (0, 0, E'C', "
                + "TIMESTAMP '2023-11-14 22:13:20.000000', decode('6b65792d30', 'hex')::pg_catalog.varbyte, "
                + "decode('61', 'hex')::pg_catalog.varbyte, E'{}'::jsonb::pg_catalog.super, "
                + "TIMESTAMP '2026-01-02 03:04:05.000000'); "));
        assertThat(load, containsString("; INSERT INTO \"public\".\"orders_mv\" " + SELECT + "; TRUNCATE \"msk\".\"orders\""));
    }

    @Test
    void aLaterRefreshResumesEachPartitionAfterItsCheckpointAndSeeksThere() {
        createView();
        partitionEnds(2, 1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(
                List.of(record(0, 0, "a"), record(0, 1, "b"), record(1, 0, "c")), List.of());
        views.refresh(stored(), SCHEMA, backend);
        partitionEnds(4, 1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 2, "d"), record(0, 3, "e")), List.of());

        long loaded = views.refresh(stored(), SCHEMA, backend);

        assertThat(loaded, equalTo(2L));
        verify(restClient).seek(CONSUMER, List.of(new KafkaOffsetDto("orders", 0, 2)));
        assertThat(stored().checkpoints(), equalTo(Map.of("0", 3L, "1", 0L)));
    }

    @Test
    void aRefreshOfATopicThatGainedNothingOpensNoConsumerAndLoadsNothing() {
        createView();
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());
        views.refresh(stored(), SCHEMA, backend);
        org.mockito.Mockito.clearInvocations(restClient, backend);

        long loaded = views.refresh(stored(), SCHEMA, backend);

        assertThat(loaded, equalTo(0L));
        verify(restClient, never()).createConsumer(any(), anyString(), anyString());
        verify(backend, never()).execute(anyString());
    }

    @Test
    void aTruncateOfTheViewDoesNotRewindTheCheckpoints() {
        createView();
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());
        views.refresh(stored(), SCHEMA, backend);

        assertThat(views.refresh(stored(), SCHEMA, backend), equalTo(0L));
        assertThat(stored().checkpoints(), equalTo(Map.of("0", 0L)));
    }

    @Test
    void aBrokerThatIsDownFailsTheRefreshAndLeavesTheCheckpointsAndTheBackendAlone() {
        createView();
        when(restClient.listPartitions(REST, "orders")).thenThrow(new UncheckedIOException(new IOException("refused")));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.refresh(stored(), SCHEMA, backend));

        assertThat(error.sqlState(), equalTo("58030"));
        assertThat(error.getMessage(), containsString(CLUSTER_ARN));
        assertThat(stored().checkpoints().isEmpty(), is(true));
        verify(restClient, never()).createConsumer(any(), anyString(), anyString());
    }

    @Test
    void aProxyErrorWhilePollingFailsTheRefreshAndClosesTheConsumer() {
        createView();
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenThrow(
                new KafkaRestClientException("GET", "records", 500, "{\"error_code\":50002}"));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.refresh(stored(), SCHEMA, backend));

        assertThat(error.sqlState(), equalTo("58030"));
        assertThat(stored().checkpoints().isEmpty(), is(true));
        verify(restClient).close(CONSUMER);
    }

    @Test
    void aFailedBatchStatementKeepsTheCheckpointsBehindTheDataAndClosesTheConsumer() {
        createView();
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());
        doThrow(new SpectrumReadException("42703", "column does not exist")).when(backend)
                .execute(argThat(sql -> sql.startsWith("INSERT INTO")));

        assertThrows(SpectrumReadException.class, () -> views.refresh(stored(), SCHEMA, backend));

        assertThat(stored().checkpoints().isEmpty(), is(true));
        verify(restClient).close(CONSUMER);
    }

    @Test
    void aCloseFailureDoesNotHideTheRefreshOutcome() {
        createView();
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());
        doThrow(new UncheckedIOException(new IOException("gone"))).when(restClient).close(CONSUMER);

        assertThat(views.refresh(stored(), SCHEMA, backend), equalTo(1L));
    }

    @Test
    void aRefreshStopsAfterTheTopicStopsReturningRecordsEvenIfAnEndOffsetWasNotReached() {
        createView();
        partitionEnds(5);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());

        assertThat(views.refresh(stored(), SCHEMA, backend), equalTo(1L));

        verify(restClient, org.mockito.Mockito.times(1 + MskStreamingViews.MAX_IDLE_POLLS)).poll(eq(CONSUMER), any());
        assertThat(stored().checkpoints(), equalTo(Map.of("0", 0L)));
    }

    @Test
    void recordsOfAnUnassignedPartitionOrAtAnOffsetAlreadyLoadedAreIgnored() {
        createView();
        partitionEnds(2);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(
                List.of(record(0, 0, "a"), record(7, 0, "stray"), record(0, 1, "b")), List.of());
        views.refresh(stored(), SCHEMA, backend);
        partitionEnds(3);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 1, "again"), record(0, 2, "c")), List.of());

        assertThat(views.refresh(stored(), SCHEMA, backend), equalTo(1L));
        assertThat(stored().checkpoints(), equalTo(Map.of("0", 2L)));
    }

    @Test
    void recordsAreSplitIntoStatementsOfBoundedSizeAndEachBatchMovesTheCheckpoint() {
        createView();
        partitionEnds(3);
        byte[] large = new byte[3 * 1024 * 1024];
        List<KafkaRecordDto> polled = new ArrayList<>();
        for (int offset = 0; offset < 3; offset++) {
            polled.add(new KafkaRecordDto("orders", 0, offset, 1L, "CreateTime", null, large, List.of()));
        }
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(polled, List.of());

        assertThat(views.refresh(stored(), SCHEMA, backend), equalTo(3L));

        verify(backend, org.mockito.Mockito.times(4)).execute(anyString());
        assertThat(stored().checkpoints(), equalTo(Map.of("0", 2L)));
    }

    @Test
    void aRecordWithoutATimestampOrKeyIsLoadedWithUnknownTypeAndNulls() {
        createView();
        partitionEnds(1);
        KafkaRecordDto record = new KafkaRecordDto("orders", 0, 0, -1, "CreateTime", null,
                "v".getBytes(StandardCharsets.UTF_8), List.of(new KafkaHeaderDto("trace", null)));
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record), List.of());

        views.refresh(stored(), SCHEMA, backend);

        verify(backend).execute(argThat(sql -> sql.contains("VALUES (0, 0, E'U', NULL::timestamp, "
                + "NULL::pg_catalog.varbyte, decode('76', 'hex')::pg_catalog.varbyte, "
                + "E'{\"trace\":null}'::jsonb::pg_catalog.super, ")));
    }

    @Test
    void headersBecomeAJsonObjectOfNameToTextAndARepeatedNameKeepsItsLastValue() {
        String json = MskStreamingViews.headersJson(List.of(
                new KafkaHeaderDto("a", "1".getBytes(StandardCharsets.UTF_8)),
                new KafkaHeaderDto("b", "café".getBytes(StandardCharsets.UTF_8)),
                new KafkaHeaderDto("a", "2".getBytes(StandardCharsets.UTF_8))));

        assertThat(json, equalTo("{\"a\":\"2\",\"b\":\"café\"}"));
        assertThat(MskStreamingViews.headersJson(null), equalTo("{}"));
    }

    @Test
    void eachViewReadsThroughItsOwnConsumerGroupApartFromPipesAndLambda() {
        MskViewBinding first = createView();
        MskViewBinding second = new MskViewBinding(ACCOUNT, SESSION.clusterKey(), "dev", "public", "other_mv", "msk",
                "orders", SELECT, Map.of());

        String group = MskStreamingViews.groupOf(first);

        assertThat(group, startsWith("floci-redshift-msk-"));
        assertThat(group, not(startsWith("floci-pipes-")));
        assertThat(MskStreamingViews.groupOf(second), not(equalTo(group)));
        assertThat(MskStreamingViews.groupOf(first), equalTo(group));
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());
        views.refresh(first, SCHEMA, backend);
        verify(restClient).createConsumer(REST, group, "earliest");
    }

    @Test
    void iamAuthenticationChecksTheKafkaActionsOnTheClusterTopicAndConsumerGroup() {
        MskViewBinding view = createView();
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());

        views.refresh(view, SCHEMA, backend);

        String group = MskStreamingViews.groupOf(view);
        String topicArn = "arn:aws:kafka:us-east-1:000000000000:topic/orders/abc-123/orders";
        String groupArn = "arn:aws:kafka:us-east-1:000000000000:group/orders/abc-123/" + group;
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Permission>> permissions = ArgumentCaptor.forClass(List.class);
        verify(authorizer).refusal(eq(ROLE), eq(ACCOUNT), eq("redshift.amazonaws.com"), isNull(),
                permissions.capture());
        assertThat(permissions.getValue(), hasSize(6));
        assertThat(permissions.getValue(), hasItem(new Permission("kafka:GetBootstrapBrokers", CLUSTER_ARN)));
        assertThat(permissions.getValue(), hasItem(new Permission("kafka-cluster:Connect", CLUSTER_ARN)));
        assertThat(permissions.getValue(), hasItem(new Permission("kafka-cluster:DescribeTopic", topicArn)));
        assertThat(permissions.getValue(), hasItem(new Permission("kafka-cluster:ReadData", topicArn)));
        assertThat(permissions.getValue(), hasItem(new Permission("kafka-cluster:AlterGroup", groupArn)));
        assertThat(permissions.getValue(), hasItem(new Permission("kafka-cluster:DescribeGroup", groupArn)));
    }

    @Test
    void unauthenticatedAccessChecksOnlyTheBootstrapBrokersAction() {
        MskSchemaBinding open = new MskSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "msk", ROLE, "NONE",
                CLUSTER_ARN);
        MskViewBinding view = createView();
        partitionEnds(1);
        when(restClient.poll(eq(CONSUMER), any())).thenReturn(List.of(record(0, 0, "a")), List.of());

        views.refresh(view, open, backend);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Permission>> permissions = ArgumentCaptor.forClass(List.class);
        verify(authorizer).refusal(anyString(), anyString(), anyString(), any(), permissions.capture());
        assertThat(permissions.getValue(), equalTo(List.of(new Permission("kafka:GetBootstrapBrokers", CLUSTER_ARN))));
    }

    @Test
    void aRefusedRoleFailsTheRefreshBeforeAnyBrokerOrBackendCall() {
        MskViewBinding view = createView();
        org.mockito.Mockito.clearInvocations(backend, restClient, karapace);
        when(authorizer.refusal(anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(Optional.of("role is not authorized to kafka-cluster:ReadData"));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.refresh(view, SCHEMA, backend));

        assertThat(error.sqlState(), equalTo("42501"));
        verifyNoInteractions(backend, restClient, karapace);
    }

    @Test
    void refreshingAViewThatWasDroppedMeanwhileFailsAsAMissingRelation() {
        MskViewBinding view = createView();
        views.unbind(view);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.refresh(view, SCHEMA, backend));

        assertThat(error.sqlState(), equalTo("42P01"));
    }

    @Test
    void dropRemovesTheViewTableAndTheStagingTableOnceNoOtherViewReadsTheTopic() {
        MskViewBinding view = createView();
        org.mockito.Mockito.clearInvocations(backend);

        views.drop(view, false, false, backend);

        verify(backend).execute("DROP TABLE \"public\".\"orders_mv\" RESTRICT; DROP TABLE IF EXISTS \"msk\".\"orders\"");
        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev"), hasSize(0));
    }

    @Test
    void dropKeepsTheStagingTableWhileAnotherViewStillReadsTheTopic() {
        MskViewBinding view = createView();
        registry.bind(new MskViewBinding(ACCOUNT, SESSION.clusterKey(), "dev", "public", "other_mv", "msk", "orders",
                SELECT, Map.of()));
        org.mockito.Mockito.clearInvocations(backend);

        views.drop(view, false, true, backend);

        verify(backend).execute("DROP TABLE \"public\".\"orders_mv\" CASCADE");
        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev"), hasSize(1));
    }

    @Test
    void dropOfAMissingViewIsAnErrorUnlessIfExistsIsGiven() {
        MskViewBinding view = createView();
        views.unbind(view);
        org.mockito.Mockito.clearInvocations(backend);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> views.drop(view, false, false, backend));
        views.drop(view, true, false, backend);

        assertThat(error.sqlState(), equalTo("42P01"));
        verify(backend, never()).execute(anyString());
    }

    @Test
    void unbindIfCurrentLeavesAViewOfTheSameNameOverAnotherTopicAlone() {
        MskViewBinding captured = createView();
        views.unbind(captured);
        MskViewBinding replacement = new MskViewBinding(ACCOUNT, SESSION.clusterKey(), "dev", "public", "orders_mv",
                "msk", "payments", SELECT, Map.of());
        registry.bind(replacement);

        views.unbindIfCurrent(captured);

        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev"), equalTo(List.of(replacement)));
    }

    @Test
    void forgetClusterRemovesItsViews() {
        createView();

        views.forgetCluster(ACCOUNT, SESSION.clusterKey());

        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev"), hasSize(0));
    }
}
