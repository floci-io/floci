package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.glue.GlueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpectrumMskSchemaPreparationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final String CLUSTER = "arn:aws:kafka:us-east-1:000000000000:cluster/orders/abc-123";
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), false);
    private static final String CREATE = "CREATE EXTERNAL SCHEMA msk FROM MSK IAM_ROLE '" + ROLE
            + "' AUTHENTICATION iam CLUSTER_ARN '" + CLUSTER + "'";

    private ExternalCatalogRegistry glueSchemas;
    private KinesisSchemaRegistry kinesisSchemas;
    private MskSchemaRegistry mskSchemas;
    private ExternalTableMaterializer materializer;
    private BackendSql backend;
    private SpectrumQueryPreparation preparation;

    @BeforeEach
    void setUp() {
        glueSchemas = mock(ExternalCatalogRegistry.class);
        kinesisSchemas = new KinesisSchemaRegistry(AccountAwareStorageBackend.<KinesisSchemaBinding>inMemory(ACCOUNT));
        mskSchemas = new MskSchemaRegistry(AccountAwareStorageBackend.<MskSchemaBinding>inMemory(ACCOUNT));
        materializer = mock(ExternalTableMaterializer.class);
        backend = mock(BackendSql.class);
        when(backend.permitsCachePublication()).thenReturn(true);
        ExternalMetadataWriter metadata = new ExternalMetadataWriter(mock(GlueService.class), new ObjectMapper());
        KinesisStreamingViews views = new KinesisStreamingViews(
                new KinesisViewRegistry(AccountAwareStorageBackend.<KinesisViewBinding>inMemory(ACCOUNT)),
                null, null, null, Clock.systemUTC());
        preparation = new SpectrumQueryPreparation(glueSchemas, kinesisSchemas, mskSchemas, materializer,
                new ExternalStatementParser(), mock(GlueService.class), metadata, new ExternalCatalogLocks(),
                views, (accountId, clusterIdentifier) -> null);
    }

    private Optional<MskSchemaBinding> bound(String schema) {
        return mskSchemas.find(ACCOUNT, SESSION.clusterKey(), "dev", schema);
    }

    @Test
    void createsTheLocalSchemaListsItAsKindTenAndRemembersTheBinding() {
        assertThat(preparation.handlesDdl(CREATE, SESSION), equalTo(true));
        assertThat(preparation.commandTag(CREATE), equalTo("CREATE EXTERNAL"));
        assertThat(preparation.prepare(CREATE, SESSION, backend), equalTo(true));

        verify(backend).execute("CREATE SCHEMA \"msk\"");
        verify(backend).execute(argThat(sql -> sql.contains("refresh_external_catalog(E'msk'")
                && sql.contains("\"eskind\":10") && sql.contains(CLUSTER)));
        MskSchemaBinding binding = bound("msk").orElseThrow();
        assertThat(binding.iamRoleArn(), equalTo(ROLE));
        assertThat(binding.authentication(), equalTo("IAM"));
        assertThat(binding.clusterArn(), equalTo(CLUSTER));
        assertThat(binding.partition(), equalTo(null));
    }

    @Test
    void keepsThePartitionAndAuthModeTheStatementNames() {
        preparation.prepare("CREATE EXTERNAL SCHEMA msk FROM MSK PARTITION 'aws-us-gov' IAM_ROLE '" + ROLE
                + "' AUTHENTICATION none CLUSTER_ARN '" + CLUSTER + "'", SESSION, backend);

        MskSchemaBinding binding = bound("msk").orElseThrow();
        assertThat(binding.partition(), equalTo("aws-us-gov"));
        assertThat(binding.authentication(), equalTo("NONE"));
    }

    @Test
    void doesNotBindUntilTheStatementCommits() {
        when(backend.permitsCachePublication()).thenReturn(false);

        preparation.prepare(CREATE, SESSION, backend);
        assertThat(bound("msk").isPresent(), equalTo(false));

        preparation.finishCycle(backend, true);
        assertThat(bound("msk").isPresent(), equalTo(true));
    }

    @Test
    void forgetsAPendingSchemaWhenTheTransactionRollsBack() {
        when(backend.permitsCachePublication()).thenReturn(false);

        preparation.prepare(CREATE, SESSION, backend);
        preparation.finishCycle(backend);
        preparation.finishCycle(backend, true);

        assertThat(bound("msk").isPresent(), equalTo(false));
    }

    @Test
    void rejectsADuplicateSchemaUnlessItIsIfNotExists() {
        preparation.prepare(CREATE, SESSION, backend);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE, SESSION, backend));
        assertThat(error.sqlState(), equalTo("42P06"));

        assertThat(preparation.prepare("CREATE EXTERNAL SCHEMA IF NOT EXISTS msk FROM MSK IAM_ROLE '" + ROLE
                + "' AUTHENTICATION iam CLUSTER_ARN '" + CLUSTER + "'", SESSION, backend), equalTo(true));
    }

    @Test
    void rejectsANameThatAGlueOrKinesisSchemaAlreadyUses() {
        when(glueSchemas.find(ACCOUNT, SESSION.clusterKey(), "dev", "msk")).thenReturn(Optional.of(
                new ExternalSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "msk", "lake", ROLE)));

        SpectrumSqlException glueCollision = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE, SESSION, backend));
        assertThat(glueCollision.sqlState(), equalTo("42P06"));
        verifyNoInteractions(backend);

        kinesisSchemas.bind(new KinesisSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "kds", null, ROLE));
        SpectrumSqlException kinesisCollision = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("CREATE EXTERNAL SCHEMA kds FROM MSK IAM_ROLE '" + ROLE
                        + "' AUTHENTICATION iam CLUSTER_ARN '" + CLUSTER + "'", SESSION, backend));
        assertThat(kinesisCollision.sqlState(), equalTo("42P06"));
        verifyNoInteractions(backend);
    }

    @Test
    void glueAndKinesisSchemasCannotTakeTheNameOfAnMskSchema() {
        GlueService glue = mock(GlueService.class);
        KinesisStreamingViews views = new KinesisStreamingViews(
                new KinesisViewRegistry(AccountAwareStorageBackend.<KinesisViewBinding>inMemory(ACCOUNT)),
                null, null, null, Clock.systemUTC());
        SpectrumQueryPreparation withGlue = new SpectrumQueryPreparation(glueSchemas, kinesisSchemas, mskSchemas,
                materializer, new ExternalStatementParser(), glue, new ExternalMetadataWriter(glue, new ObjectMapper()),
                new ExternalCatalogLocks(), views, (accountId, clusterIdentifier) -> null);
        preparation.prepare(CREATE, SESSION, backend);

        SpectrumSqlException glueError = assertThrows(SpectrumSqlException.class, () -> withGlue.prepare(
                "CREATE EXTERNAL SCHEMA msk FROM DATA CATALOG DATABASE 'lake' IAM_ROLE '" + ROLE
                        + "' CREATE EXTERNAL DATABASE IF NOT EXISTS", SESSION, backend));
        assertThat(glueError.sqlState(), equalTo("42P06"));
        verifyNoInteractions(glue);

        assertThat(withGlue.prepare("CREATE EXTERNAL SCHEMA IF NOT EXISTS msk FROM DATA CATALOG DATABASE 'lake' "
                + "IAM_ROLE '" + ROLE + "'", SESSION, backend), equalTo(true));
        verifyNoInteractions(glue);

        SpectrumSqlException kinesisError = assertThrows(SpectrumSqlException.class, () -> withGlue.prepare(
                "CREATE EXTERNAL SCHEMA msk FROM KINESIS IAM_ROLE '" + ROLE + "'", SESSION, backend));
        assertThat(kinesisError.sqlState(), equalTo("42P06"));
    }

    @Test
    void refusesToCreateTheSchemaInsideATransaction() {
        SpectrumSession transaction = new SpectrumSession(ACCOUNT, SESSION.clusterKey(), "dev", List.of(), true);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE, transaction, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        verifyNoInteractions(backend);
        assertThat(bound("msk").isPresent(), equalTo(false));
    }

    @Test
    void removesTheLocalSchemaWhenItsMetadataCannotBeWritten() {
        doThrow(new SpectrumReadException("XX000", "metadata write failed")).when(backend)
                .execute(argThat(sql -> sql.contains("refresh_external_catalog")));

        assertThrows(SpectrumReadException.class, () -> preparation.prepare(CREATE, SESSION, backend));

        verify(backend).execute("DROP SCHEMA \"msk\" CASCADE");
        assertThat(bound("msk").isPresent(), equalTo(false));
    }

    @Test
    void dropsTheSchemaItsMetadataAndItsBinding() {
        preparation.prepare(CREATE, SESSION, backend);
        String drop = "DROP SCHEMA msk";

        assertThat(preparation.handlesDdl(drop, SESSION), equalTo(true));
        assertThat(preparation.prepare(drop, SESSION, backend), equalTo(true));

        verify(backend).execute(argThat(sql -> sql.startsWith("DROP SCHEMA \"msk\" RESTRICT; ")
                && sql.contains("purge_external_schema(E'msk')")));
        assertThat(bound("msk").isPresent(), equalTo(false));
    }

    @Test
    void dropsWithCascadeWhenAsked() {
        preparation.prepare(CREATE, SESSION, backend);

        preparation.prepare("DROP SCHEMA IF EXISTS msk CASCADE", SESSION, backend);

        verify(backend).execute(argThat(sql -> sql.startsWith("DROP SCHEMA IF EXISTS \"msk\" CASCADE; ")));
    }

    @Test
    void refusesToDropTheSchemaInsideATransaction() {
        preparation.prepare(CREATE, SESSION, backend);
        SpectrumSession transaction = new SpectrumSession(ACCOUNT, SESSION.clusterKey(), "dev", List.of(), true);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP SCHEMA msk", transaction, backend));

        assertThat(error.sqlState(), equalTo("25001"));
        assertThat(bound("msk").isPresent(), equalTo(true));
    }

    @Test
    void keepsTheBindingWhenDropFailsTheSchemaOwnerCheck() {
        preparation.prepare(CREATE, SESSION, backend);
        doThrow(new SpectrumReadException("42501", "Must be owner of external schema")).when(backend)
                .execute(argThat(sql -> sql.startsWith("DO ")));

        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> preparation.prepare("DROP SCHEMA msk", SESSION, backend));

        assertThat(error.sqlState(), equalTo("42501"));
        assertThat(bound("msk").isPresent(), equalTo(true));
    }

    @Test
    void refusesCreateExternalTableAndStreamingMaterializedViewsOverAnMskSchema() {
        preparation.prepare(CREATE, SESSION, backend);
        String createTable = "CREATE EXTERNAL TABLE msk.orders (id INT) STORED AS TEXTFILE LOCATION 's3://bucket/orders/'";
        assertThat(preparation.handlesDdl(createTable, SESSION), equalTo(true));
        SpectrumSqlException tableError = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(createTable, SESSION, backend));
        assertThat(tableError.sqlState(), equalTo("0A000"));
        assertThat(tableError.getMessage(), containsString("CREATE EXTERNAL TABLE"));

        String createMv = "CREATE MATERIALIZED VIEW mv AS SELECT * FROM msk.orders";
        assertThat(preparation.handlesDdl(createMv, SESSION), equalTo(true));
        SpectrumSqlException mvError = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(createMv, SESSION, backend));
        assertThat(mvError.sqlState(), equalTo("0A000"));
        assertThat(mvError.getMessage(), containsString("Streaming materialized views over MSK"));
    }

    @Test
    void refusesAMixedKinesisAndMskMaterializedViewBeforeTouchingKinesisOrTheBackend() {
        preparation.prepare(CREATE, SESSION, backend);
        kinesisSchemas.bind(new KinesisSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "kds", null, ROLE));
        String mixed = "CREATE MATERIALIZED VIEW mv AS SELECT * FROM kds.events WHERE EXISTS (SELECT 1 FROM msk.orders)";

        assertThat(preparation.handlesDdl(mixed, SESSION), equalTo(true));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(mixed, SESSION, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        assertThat(error.getMessage(), containsString("Streaming materialized views over MSK"));
        verify(backend, never()).execute(argThat(sql -> sql.contains("CREATE TABLE")));
    }

    @Test
    void refusesRenamingAnMskSchemaAndLeavesItsBindingIntact() {
        preparation.prepare(CREATE, SESSION, backend);
        String rename = "ALTER SCHEMA msk RENAME TO other";

        assertThat(preparation.referencesExternal(rename, SESSION), equalTo(true));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(rename, SESSION, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        verify(backend, never()).execute(rename);
        assertThat(bound("msk").isPresent(), equalTo(true));
    }

    @Test
    void refusesToReadATopicOutsideAMaterializedView() {
        preparation.prepare(CREATE, SESSION, backend);
        String read = "SELECT * FROM msk.orders";

        assertThat(preparation.referencesExternal(read, SESSION), equalTo(true));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(read, SESSION, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        assertThat(error.getMessage(), containsString("msk.orders"));
        assertThat(error.getMessage(), containsString("MSK external schema"));
        verify(materializer, never()).ensureCurrent(any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesEveryStatementThatNamesARelationOfTheSchemaAsASourceOrWriteTarget() {
        preparation.prepare(CREATE, SESSION, backend);

        for (String sql : List.of("INSERT INTO msk.orders VALUES (1)", "UPDATE msk.orders SET id = 2",
                "SELECT * FROM msk.orders JOIN public.b ON true", "CREATE TABLE msk.t (id int)",
                "ALTER TABLE t SET SCHEMA msk", "DROP SCHEMA other, msk")) {
            assertThat(sql, preparation.referencesExternal(sql, SESSION), equalTo(true));
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                    () -> preparation.prepare(sql, SESSION, backend));
            assertThat(sql, error.sqlState(), equalTo("0A000"));
        }
        assertThat(bound("msk").isPresent(), equalTo(true));
    }

    @Test
    void leavesAnUnboundLocalSchemaAloneUnderIfNotExists() {
        doThrow(new SpectrumReadException("42P06", "schema \"msk\" already exists")).when(backend)
                .execute("CREATE SCHEMA \"msk\"");

        assertThat(preparation.prepare("CREATE EXTERNAL SCHEMA IF NOT EXISTS msk FROM MSK IAM_ROLE '" + ROLE
                + "' AUTHENTICATION iam CLUSTER_ARN '" + CLUSTER + "'", SESSION, backend), equalTo(true));

        assertThat(bound("msk").isPresent(), equalTo(false));
        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> preparation.prepare(CREATE, SESSION, backend));
        assertThat(error.sqlState(), equalTo("42P06"));
    }

    @Test
    void aDropDoesNotUnbindASchemaThatWasRecreatedMeanwhile() {
        preparation.prepare(CREATE, SESSION, backend);
        MskSchemaBinding newer = new MskSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "msk", null,
                "arn:aws:iam::000000000000:role/Other", "NONE", CLUSTER);
        doAnswer(invocation -> {
            mskSchemas.unbind(ACCOUNT, SESSION.clusterKey(), "dev", "msk");
            mskSchemas.bind(newer);
            return null;
        }).when(backend).execute(argThat(sql -> sql.startsWith("DROP SCHEMA \"msk\"")));

        preparation.prepare("DROP SCHEMA msk", SESSION, backend);

        assertThat(bound("msk").orElseThrow(), equalTo(newer));
    }

    @Test
    void forgetsTheSchemasOfARemovedCluster() {
        preparation.prepare(CREATE, SESSION, backend);

        preparation.forgetRuntime(ACCOUNT, SESSION.clusterKey());

        assertThat(bound("msk").isPresent(), equalTo(false));
    }
}
