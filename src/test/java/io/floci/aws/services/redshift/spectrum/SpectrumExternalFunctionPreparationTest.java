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
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpectrumExternalFunctionPreparationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), false);
    private static final String CREATE = "CREATE EXTERNAL FUNCTION exfunc_sum(INT, INT) RETURNS INT VOLATILE "
            + "LAMBDA 'lambda_sum' IAM_ROLE '" + ROLE + "'";

    private ExternalFunctionRegistry functions;
    private BackendSql backend;
    private SpectrumQueryPreparation preparation;

    @BeforeEach
    void setUp() {
        functions = new ExternalFunctionRegistry(AccountAwareStorageBackend.<ExternalFunctionBinding>inMemory(ACCOUNT));
        backend = mock(BackendSql.class);
        when(backend.queryString(SpectrumQueryPreparation.CURRENT_SCHEMA_SQL)).thenReturn(Optional.of("public"));
        GlueService glue = mock(GlueService.class);
        KinesisStreamingViews views = new KinesisStreamingViews(
                new KinesisViewRegistry(AccountAwareStorageBackend.<KinesisViewBinding>inMemory(ACCOUNT)),
                null, null, null, Clock.systemUTC());
        preparation = new SpectrumQueryPreparation(mock(ExternalCatalogRegistry.class),
                new KinesisSchemaRegistry(AccountAwareStorageBackend.<KinesisSchemaBinding>inMemory(ACCOUNT)),
                new MskSchemaRegistry(AccountAwareStorageBackend.<MskSchemaBinding>inMemory(ACCOUNT)), functions,
                mock(ExternalTableMaterializer.class), new ExternalStatementParser(), glue,
                new ExternalMetadataWriter(glue, new ObjectMapper()), new ExternalCatalogLocks(), views,
                (accountId, clusterIdentifier) -> null);
    }

    private Optional<ExternalFunctionBinding> bound(String schema, String name, List<String> args) {
        return functions.findFunction(SESSION, schema, name, args);
    }

    @Test
    void createsAStubThatEvaluatesThroughTheSessionTablesAndRemembersTheDeclaration() {
        assertThat(preparation.handlesDdl(CREATE, SESSION), equalTo(true));
        assertThat(preparation.commandTag(CREATE), equalTo("CREATE FUNCTION"));
        assertThat(preparation.prepare(CREATE, SESSION, backend), equalTo(true));

        verify(backend).execute(argThat(sql -> sql.startsWith("CREATE FUNCTION \"public\".\"exfunc_sum\"(integer, integer) "
                + "RETURNS integer LANGUAGE plpgsql VOLATILE AS E'DECLARE ")
                && sql.contains("pg_temp._floci_udf_pending")
                && sql.contains("pg_temp._floci_udf_cache")
                && sql.contains("USING ERRCODE = ''0A000''")));
        ExternalFunctionBinding binding = bound("public", "exfunc_sum", List.of("integer", "integer")).orElseThrow();
        assertThat(binding.lambdaFunction(), equalTo("lambda_sum"));
        assertThat(binding.iamRoleArn(), equalTo(ROLE));
        assertThat(binding.returnType(), equalTo("integer"));
        assertThat(binding.volatility(), equalTo("VOLATILE"));
        assertThat(binding.maxBatchSizeBytes(), equalTo(5L * 1024 * 1024));
    }

    @Test
    void createsInTheSchemaTheNameQualifiesWith() {
        preparation.prepare("CREATE OR REPLACE EXTERNAL FUNCTION \"Sales\".f_now() RETURNS VARCHAR(8) STABLE "
                + "LAMBDA 'now' IAM_ROLE '" + ROLE + "'", SESSION, backend);

        verify(backend).execute(argThat(sql -> sql.startsWith("CREATE FUNCTION \"Sales\".\"f_now\"() "
                + "RETURNS character varying(8) LANGUAGE plpgsql VOLATILE")));
        ExternalFunctionBinding binding = bound("Sales", "f_now", List.of()).orElseThrow();
        assertThat(binding.volatility(), equalTo("STABLE"));
    }

    @Test
    void orReplaceOnlyReplacesAFunctionTheRegistryMade() {
        String replace = CREATE.replace("CREATE EXTERNAL", "CREATE OR REPLACE EXTERNAL");

        preparation.prepare(replace, SESSION, backend);
        preparation.prepare(replace, SESSION, backend);

        verify(backend, times(1)).execute(argThat(sql -> sql.startsWith("CREATE FUNCTION")));
        verify(backend, times(1)).execute(argThat(sql -> sql.startsWith("CREATE OR REPLACE FUNCTION")));
    }

    @Test
    void anOrdinaryFunctionOfTheSignatureIsNeverOverwrittenByAStub() {
        doThrow(new SpectrumReadException("42723", "function already exists")).when(backend)
                .execute(argThat(sql -> sql.startsWith("CREATE FUNCTION")));

        SpectrumReadException error = assertThrows(SpectrumReadException.class, () -> preparation.prepare(
                CREATE.replace("CREATE EXTERNAL", "CREATE OR REPLACE EXTERNAL"), SESSION, backend));

        assertThat(error.sqlState(), equalTo("42723"));
        verify(backend, never()).execute(argThat(sql -> sql.startsWith("CREATE OR REPLACE")));
        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(false));
    }

    @Test
    void aPercentSignInAQuotedNameIsNotAPlaceholderOfTheStubsMessage() {
        preparation.prepare("CREATE EXTERNAL FUNCTION \"pct%fn\"(INT) RETURNS INT STABLE LAMBDA 'f' IAM_ROLE '"
                + ROLE + "'", SESSION, backend);

        verify(backend).execute(argThat(sql -> sql.contains("RAISE EXCEPTION ''%'', E''External function public.pct%fn ")));
    }

    @Test
    void doesNotBindWhenTheBackendRefusesTheStub() {
        doThrow(new SpectrumReadException("42723", "function already exists")).when(backend)
                .execute(argThat(sql -> sql.startsWith("CREATE FUNCTION")));

        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> preparation.prepare(CREATE, SESSION, backend));

        assertThat(error.sqlState(), equalTo("42723"));
        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(false));
    }

    @Test
    void replacingAFunctionKeepsOneBindingWithTheNewDeclaration() {
        preparation.prepare(CREATE, SESSION, backend);

        preparation.prepare(CREATE.replace("CREATE EXTERNAL", "CREATE OR REPLACE EXTERNAL")
                .replace("lambda_sum", "lambda_sum_v2"), SESSION, backend);

        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).orElseThrow().lambdaFunction(),
                equalTo("lambda_sum_v2"));
        assertThat(functions.list(ACCOUNT, SESSION.clusterKey(), "dev").size(), equalTo(1));
    }

    @Test
    void refusesToRunInsideATransactionBlock() {
        SpectrumSession transaction = new SpectrumSession(ACCOUNT, SESSION.clusterKey(), "dev", List.of(), true);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE, transaction, backend));

        assertThat(error.sqlState(), equalTo("25001"));
        verifyNoInteractions(backend);
    }

    @Test
    void dropsTheStubAndTheBinding() {
        preparation.prepare(CREATE, SESSION, backend);
        String drop = "DROP FUNCTION exfunc_sum(INT, INT)";

        assertThat(preparation.handlesDdl(drop, SESSION), equalTo(true));
        assertThat(preparation.commandTag(drop), equalTo("DROP FUNCTION"));
        assertThat(preparation.prepare(drop, SESSION, backend), equalTo(true));

        verify(backend).execute("DROP FUNCTION \"public\".\"exfunc_sum\"(integer, integer) RESTRICT");
        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(false));
    }

    @Test
    void passesCascadeAndIfExistsToTheBackend() {
        preparation.prepare(CREATE, SESSION, backend);

        preparation.prepare("DROP FUNCTION IF EXISTS public.exfunc_sum(integer, integer) CASCADE", SESSION, backend);

        verify(backend).execute("DROP FUNCTION IF EXISTS \"public\".\"exfunc_sum\"(integer, integer) CASCADE");
    }

    @Test
    void leavesADropOfAnUntrackedFunctionToTheBackend() {
        preparation.prepare(CREATE, SESSION, backend);

        assertThat(preparation.handlesDdl("DROP FUNCTION exfunc_sum(BIGINT)", SESSION), equalTo(false));
        assertThat(preparation.handlesDdl("DROP FUNCTION other.exfunc_sum(INT, INT)", SESSION), equalTo(false));
        assertThat(preparation.prepare("DROP FUNCTION exfunc_sum(BIGINT)", SESSION, backend), equalTo(false));
        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(true));
    }

    @Test
    void keepsTheBindingWhenTheBackendRefusesTheDrop() {
        preparation.prepare(CREATE, SESSION, backend);
        doThrow(new SpectrumReadException("42501", "must be owner of function")).when(backend)
                .execute(argThat(sql -> sql.startsWith("DROP FUNCTION")));

        assertThrows(SpectrumReadException.class,
                () -> preparation.prepare("DROP FUNCTION exfunc_sum(INT, INT)", SESSION, backend));

        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(true));
    }

    @Test
    void forgetsABindingWhoseStubIsAlreadyGone() {
        preparation.prepare(CREATE, SESSION, backend);
        doThrow(new SpectrumReadException("42883", "function does not exist")).when(backend)
                .execute(argThat(sql -> sql.startsWith("DROP FUNCTION")));

        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> preparation.prepare("DROP FUNCTION exfunc_sum(INT, INT)", SESSION, backend));

        assertThat(error.sqlState(), equalTo("42883"));
        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(false));
    }

    @Test
    void dropRefusesToRunInsideATransactionBlock() {
        preparation.prepare(CREATE, SESSION, backend);
        SpectrumSession transaction = new SpectrumSession(ACCOUNT, SESSION.clusterKey(), "dev", List.of(), true);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP FUNCTION exfunc_sum(INT, INT)", transaction, backend));

        assertThat(error.sqlState(), equalTo("25001"));
        verify(backend, never()).execute(argThat(sql -> sql.startsWith("DROP FUNCTION")));
    }

    @Test
    void forgetRuntimeDropsTheClustersFunctions() {
        preparation.prepare(CREATE, SESSION, backend);

        preparation.forgetRuntime(ACCOUNT, SESSION.clusterKey());

        assertThat(functions.list(ACCOUNT, SESSION.clusterKey(), "dev").isEmpty(), equalTo(true));
    }

    @Test
    void theStubNamesTheFunctionInItsError() {
        preparation.prepare(CREATE, SESSION, backend);

        verify(backend).execute(argThat(sql -> sql.contains("public.exfunc_sum")
                && sql.contains("cannot be called directly")));
    }

    @Test
    void createsUnqualifiedFunctionInTheCurrentSchemaReportedByTheBackend() {
        when(backend.queryString(SpectrumQueryPreparation.CURRENT_SCHEMA_SQL)).thenReturn(Optional.of("analytics"));

        assertThat(preparation.prepare(CREATE, SESSION, backend), equalTo(true));

        verify(backend).execute(argThat(sql -> sql.startsWith("CREATE FUNCTION \"analytics\".\"exfunc_sum\"(integer, integer) ")
                && sql.contains("External function analytics.exfunc_sum")));
        assertThat(bound("analytics", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(true));
        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(false));
    }

    @Test
    void refusesUnqualifiedCreateWhenNoSchemaIsSelected() {
        when(backend.queryString(SpectrumQueryPreparation.CURRENT_SCHEMA_SQL)).thenReturn(Optional.empty());

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(CREATE, SESSION, backend));

        assertThat(error.sqlState(), equalTo("3F000"));
        verify(backend, never()).execute(argThat(sql -> sql.startsWith("CREATE FUNCTION")));
    }

    @Test
    void qualifiedCreateDoesNotAskTheBackendForCurrentSchema() {
        preparation.prepare("CREATE EXTERNAL FUNCTION \"Sales\".f_now() RETURNS VARCHAR(8) STABLE "
                + "LAMBDA 'now' IAM_ROLE '" + ROLE + "'", SESSION, backend);

        verify(backend, never()).queryString(SpectrumQueryPreparation.CURRENT_SCHEMA_SQL);
    }

    @Test
    void unqualifiedDropResolvesFunctionAlongSearchPath() {
        when(backend.queryString(SpectrumQueryPreparation.CURRENT_SCHEMA_SQL)).thenReturn(Optional.of("analytics"));
        preparation.prepare(CREATE, SESSION, backend);

        String resolveSql = SpectrumQueryPreparation.resolveFunctionSchemaSql("exfunc_sum", List.of("integer", "integer"));
        when(backend.queryString(SpectrumQueryPreparation.CURRENT_SCHEMA_SQL)).thenReturn(Optional.of("other"));
        when(backend.queryString(resolveSql)).thenReturn(Optional.of("analytics"));

        String drop = "DROP FUNCTION exfunc_sum(INT, INT)";
        assertThat(preparation.needsBackendForDdl(drop, SESSION), equalTo(true));
        assertThat(preparation.handlesDdl(drop, SESSION, backend), equalTo(true));
        assertThat(preparation.prepare(drop, SESSION, backend), equalTo(true));

        verify(backend).execute("DROP FUNCTION \"analytics\".\"exfunc_sum\"(integer, integer) RESTRICT");
        assertThat(bound("analytics", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(false));
    }

    @Test
    void unqualifiedDropLeavesOrdinaryFunctionInEarlierSearchPathSchemaToBackend() {
        preparation.prepare(CREATE, SESSION, backend);

        String resolveSql = SpectrumQueryPreparation.resolveFunctionSchemaSql("exfunc_sum", List.of("integer", "integer"));
        when(backend.queryString(resolveSql)).thenReturn(Optional.of("analytics"));

        String drop = "DROP FUNCTION exfunc_sum(INT, INT)";
        assertThat(preparation.handlesDdl(drop, SESSION, backend), equalTo(false));
        assertThat(preparation.prepare(drop, SESSION, backend), equalTo(false));

        verify(backend, never()).execute(argThat(sql -> sql.startsWith("DROP FUNCTION")));
        assertThat(bound("public", "exfunc_sum", List.of("integer", "integer")).isPresent(), equalTo(true));
    }
}
