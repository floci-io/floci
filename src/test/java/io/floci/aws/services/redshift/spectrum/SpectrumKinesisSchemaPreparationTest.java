package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.glue.GlueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class SpectrumKinesisSchemaPreparationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), false);
    private static final String CREATE = "CREATE EXTERNAL SCHEMA kds FROM KINESIS IAM_ROLE '" + ROLE + "'";
    private ExternalCatalogRegistry glueSchemas;
    private KinesisSchemaRegistry kinesisSchemas;
    private ExternalTableMaterializer materializer;
    private BackendSql backend;
    private SpectrumQueryPreparation preparation;

    @BeforeEach
    void setUp() {
        glueSchemas = mock(ExternalCatalogRegistry.class);
        kinesisSchemas = new KinesisSchemaRegistry(AccountAwareStorageBackend.<KinesisSchemaBinding>inMemory(ACCOUNT));
        materializer = mock(ExternalTableMaterializer.class);
        backend = mock(BackendSql.class);
        when(backend.permitsCachePublication()).thenReturn(true);
        ExternalMetadataWriter metadata = new ExternalMetadataWriter(mock(GlueService.class), new ObjectMapper());
        preparation = new SpectrumQueryPreparation(glueSchemas, kinesisSchemas, materializer,
                new ExternalStatementParser(), mock(GlueService.class), metadata, new ExternalCatalogLocks(),
                (accountId, clusterIdentifier) -> null);
    }

    private Optional<KinesisSchemaBinding> bound(String schema) {
        return kinesisSchemas.find(ACCOUNT, SESSION.clusterKey(), "dev", schema);
    }

    @Test
    void createsTheLocalSchemaListsItAsKindNineAndRemembersTheBinding() {
        assertThat(preparation.handlesDdl(CREATE, SESSION), equalTo(true));
        assertThat(preparation.prepare(CREATE, SESSION, backend), equalTo(true));

        verify(backend).execute("CREATE SCHEMA \"kds\"");
        verify(backend).execute(argThat(sql -> sql.contains("refresh_external_catalog(E'kds'")
                && sql.contains("\"eskind\":9")));
        KinesisSchemaBinding binding = bound("kds").orElseThrow();
        assertThat(binding.iamRoleArn(), equalTo(ROLE));
        assertThat(binding.region(), equalTo(null));
    }

    @Test
    void keepsTheRegionTheStatementNames() {
        preparation.prepare("CREATE EXTERNAL SCHEMA kds FROM KINESIS REGION 'us-west-2' IAM_ROLE '" + ROLE + "'",
                SESSION, backend);

        assertThat(bound("kds").orElseThrow().region(), equalTo("us-west-2"));
    }

    @Test
    void doesNotBindUntilTheStatementCommits() {
        when(backend.permitsCachePublication()).thenReturn(false);

        preparation.prepare(CREATE, SESSION, backend);
        assertThat(bound("kds").isPresent(), equalTo(false));

        preparation.finishCycle(backend, true);
        assertThat(bound("kds").isPresent(), equalTo(true));
    }

    @Test
    void forgetsAPendingSchemaWhenTheTransactionRollsBack() {
        when(backend.permitsCachePublication()).thenReturn(false);

        preparation.prepare(CREATE, SESSION, backend);
        preparation.finishCycle(backend);
        preparation.finishCycle(backend, true);

        assertThat(bound("kds").isPresent(), equalTo(false));
    }

    @Test
    void rejectsADuplicateSchemaUnlessItIsIfNotExists() {
        preparation.prepare(CREATE, SESSION, backend);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE, SESSION, backend));
        assertThat(error.sqlState(), equalTo("42P06"));

        assertThat(preparation.prepare("CREATE EXTERNAL SCHEMA IF NOT EXISTS kds FROM KINESIS IAM_ROLE '" + ROLE + "'",
                SESSION, backend), equalTo(true));
    }

    @Test
    void rejectsANameThatAGlueSchemaAlreadyUses() {
        when(glueSchemas.find(ACCOUNT, SESSION.clusterKey(), "dev", "kds")).thenReturn(Optional.of(
                new ExternalSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "kds", "lake", ROLE)));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE, SESSION, backend));

        assertThat(error.sqlState(), equalTo("42P06"));
        verifyNoInteractions(backend);
    }

    @Test
    void refusesToCreateTheSchemaInsideATransaction() {
        SpectrumSession transaction = new SpectrumSession(ACCOUNT, SESSION.clusterKey(), "dev", List.of(), true);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE, transaction, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        verifyNoInteractions(backend);
        assertThat(bound("kds").isPresent(), equalTo(false));
    }

    @Test
    void removesTheLocalSchemaWhenItsMetadataCannotBeWritten() {
        doThrow(new SpectrumReadException("XX000", "metadata write failed")).when(backend)
                .execute(argThat(sql -> sql.contains("refresh_external_catalog")));

        assertThrows(SpectrumReadException.class, () -> preparation.prepare(CREATE, SESSION, backend));

        verify(backend).execute("DROP SCHEMA \"kds\" CASCADE");
        assertThat(bound("kds").isPresent(), equalTo(false));
    }

    @Test
    void dropsTheSchemaItsMetadataAndItsBinding() {
        preparation.prepare(CREATE, SESSION, backend);
        String drop = "DROP SCHEMA kds";

        assertThat(preparation.handlesDdl(drop, SESSION), equalTo(true));
        assertThat(preparation.prepare(drop, SESSION, backend), equalTo(true));

        verify(backend).execute(argThat(sql -> sql.startsWith("DROP SCHEMA \"kds\" RESTRICT; ")
                && sql.contains("purge_external_schema(E'kds')")));
        assertThat(bound("kds").isPresent(), equalTo(false));
    }

    @Test
    void dropsWithCascadeWhenAsked() {
        preparation.prepare(CREATE, SESSION, backend);

        preparation.prepare("DROP SCHEMA IF EXISTS kds CASCADE", SESSION, backend);

        verify(backend).execute(argThat(sql -> sql.startsWith("DROP SCHEMA IF EXISTS \"kds\" CASCADE; ")));
    }

    @Test
    void leavesTheDropOfAnUnknownSchemaToPostgreSql() {
        assertThat(preparation.handlesDdl("DROP SCHEMA other", SESSION), equalTo(false));
    }

    @Test
    void refusesToDropTheSchemaInsideATransaction() {
        preparation.prepare(CREATE, SESSION, backend);
        SpectrumSession transaction = new SpectrumSession(ACCOUNT, SESSION.clusterKey(), "dev", List.of(), true);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP SCHEMA kds", transaction, backend));

        assertThat(error.sqlState(), equalTo("25001"));
        assertThat(bound("kds").isPresent(), equalTo(true));
    }

    @Test
    void refusesToReadAStreamOutsideAMaterializedView() {
        preparation.prepare(CREATE, SESSION, backend);
        String read = "SELECT * FROM kds.my_stream";

        assertThat(preparation.referencesExternal(read, SESSION), equalTo(true));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(read, SESSION, backend));

        assertThat(error.sqlState(), equalTo("0A000"));
        assertThat(error.getMessage(), containsString("kds.my_stream"));
        assertThat(error.getMessage(), containsString("streaming materialized view"));
        verify(materializer, never()).ensureCurrent(any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesEveryStatementThatNamesARelationOfTheSchemaAsASource() {
        preparation.prepare(CREATE, SESSION, backend);

        for (String sql : List.of("INSERT INTO kds.derived VALUES (1)", "UPDATE kds.derived SET id = 2",
                "SELECT * FROM kds.a JOIN public.b ON true")) {
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                    () -> preparation.prepare(sql, SESSION, backend));
            assertThat(sql, error.sqlState(), equalTo("0A000"));
            assertThat(sql, error.getMessage(), containsString("Kinesis external schema"));
        }
    }

    @Test
    void refusesStatementsThatWouldPutARelationIntoTheSchemaOrDropItAmongOthers() {
        preparation.prepare(CREATE, SESSION, backend);

        for (String sql : List.of("CREATE TABLE kds.t (id int)", "ALTER TABLE t SET SCHEMA kds",
                "DROP SCHEMA other, kds")) {
            assertThat(sql, preparation.referencesExternal(sql, SESSION), equalTo(true));
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                    () -> preparation.prepare(sql, SESSION, backend));
            assertThat(sql, error.sqlState(), equalTo("0A000"));
        }
        assertThat(bound("kds").isPresent(), equalTo(true));
    }

    @Test
    void leavesAnUnboundLocalSchemaAloneUnderIfNotExists() {
        doThrow(new SpectrumReadException("42P06", "schema \"kds\" already exists")).when(backend)
                .execute("CREATE SCHEMA \"kds\"");

        assertThat(preparation.prepare("CREATE EXTERNAL SCHEMA IF NOT EXISTS kds FROM KINESIS IAM_ROLE '" + ROLE + "'",
                SESSION, backend), equalTo(true));

        assertThat(bound("kds").isPresent(), equalTo(false));
        SpectrumReadException error = assertThrows(SpectrumReadException.class, () -> preparation.prepare(CREATE, SESSION, backend));
        assertThat(error.sqlState(), equalTo("42P06"));
    }

    @Test
    void rejectsAnEmptyRoleWithASyntaxError() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("CREATE EXTERNAL SCHEMA kds FROM KINESIS IAM_ROLE ''", SESSION, backend));

        assertThat(error.sqlState(), equalTo("42601"));
    }

    @Test
    void aGlueSchemaCannotTakeTheNameOfAKinesisSchemaAndLeavesGlueUntouched() {
        GlueService glue = mock(GlueService.class);
        SpectrumQueryPreparation withGlue = new SpectrumQueryPreparation(glueSchemas, kinesisSchemas, materializer,
                new ExternalStatementParser(), glue, new ExternalMetadataWriter(glue, new ObjectMapper()),
                new ExternalCatalogLocks(), (accountId, clusterIdentifier) -> null);
        preparation.prepare(CREATE, SESSION, backend);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> withGlue.prepare(
                "CREATE EXTERNAL SCHEMA kds FROM DATA CATALOG DATABASE 'lake' IAM_ROLE '" + ROLE
                        + "' CREATE EXTERNAL DATABASE IF NOT EXISTS", SESSION, backend));

        assertThat(error.sqlState(), equalTo("42P06"));
        verifyNoInteractions(glue);
        assertThat(withGlue.prepare("CREATE EXTERNAL SCHEMA IF NOT EXISTS kds FROM DATA CATALOG DATABASE 'lake' "
                + "IAM_ROLE '" + ROLE + "'", SESSION, backend), equalTo(true));
        verifyNoInteractions(glue);
    }

    @Test
    void aDropDoesNotUnbindASchemaThatWasRecreatedMeanwhile() {
        preparation.prepare(CREATE, SESSION, backend);
        KinesisSchemaBinding newer = new KinesisSchemaBinding(ACCOUNT, SESSION.clusterKey(), "dev", "kds", null,
                "arn:aws:iam::000000000000:role/Other");
        doAnswer(invocation -> {
            kinesisSchemas.unbind(ACCOUNT, SESSION.clusterKey(), "dev", "kds");
            kinesisSchemas.bind(newer);
            return null;
        }).when(backend).execute(argThat(sql -> sql.startsWith("DROP SCHEMA \"kds\"")));

        preparation.prepare("DROP SCHEMA kds", SESSION, backend);

        assertThat(bound("kds").orElseThrow(), equalTo(newer));
    }

    @Test
    void leavesQueriesThatDoNotTouchAStreamSchemaAlone() {
        preparation.prepare(CREATE, SESSION, backend);

        assertThat(preparation.referencesExternal("SELECT * FROM public.orders", SESSION), equalTo(false));
    }

    @Test
    void forgetsTheSchemasOfARemovedCluster() {
        preparation.prepare(CREATE, SESSION, backend);

        preparation.forgetRuntime(ACCOUNT, SESSION.clusterKey());

        assertThat(bound("kds").isPresent(), equalTo(false));
    }
}
