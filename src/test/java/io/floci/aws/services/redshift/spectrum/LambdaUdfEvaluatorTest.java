package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.storage.AccountAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LambdaUdfEvaluatorTest {
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER = ACCOUNT + ":c";
    private static final String ROLE = "arn:aws:iam::000000000000:role/UdfRole";
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, CLUSTER, "dev", List.of(), false);
    private static final ExternalFunctionBinding SUM = new ExternalFunctionBinding(
            ACCOUNT, CLUSTER, "dev", "public", "exfunc_sum", List.of("integer", "integer"), "integer",
            "VOLATILE", "lambda_sum", ROLE, 20_000, Integer.MAX_VALUE, 5L * 1024 * 1024);
    private static final ExternalFunctionBinding DOUBLE = new ExternalFunctionBinding(
            ACCOUNT, CLUSTER, "dev", "public", "exfunc_double", List.of("integer"), "integer",
            "STABLE", "lambda_double", ROLE, 20_000, Integer.MAX_VALUE, 5L * 1024 * 1024);

    private ExternalFunctionRegistry functions;
    private LambdaUdfInvoker invoker;
    private BackendSql backend;
    private LambdaUdfEvaluator evaluator;

    @BeforeEach
    void setUp() {
        functions = new ExternalFunctionRegistry(AccountAwareStorageBackend.<ExternalFunctionBinding>inMemory(ACCOUNT));
        functions.replace(SUM);
        invoker = mock(LambdaUdfInvoker.class);
        backend = mock(BackendSql.class);
        when(backend.queryString(LambdaUdfEvaluator.CURRENT_USER_SQL)).thenReturn(Optional.of("alice"));
        evaluator = new LambdaUdfEvaluator(functions, invoker);
    }

    @Test
    void detectsExternalFunctionCallsAndIgnoresLiteralsCommentsAndColumnNames() {
        assertThat(evaluator.referencesExternalFunction("SELECT exfunc_sum(1, 2)", SESSION), equalTo(true));
        assertThat(evaluator.referencesExternalFunction("SELECT public.EXFUNC_SUM(a, b) FROM t", SESSION), equalTo(true));
        assertThat(evaluator.referencesExternalFunction("SELECT 'exfunc_sum(1, 2)'", SESSION), equalTo(false));
        assertThat(evaluator.referencesExternalFunction("SELECT 1 -- exfunc_sum(1, 2)", SESSION), equalTo(false));
        assertThat(evaluator.referencesExternalFunction("SELECT exfunc_sum FROM t", SESSION), equalTo(false));
        assertThat(evaluator.referencesExternalFunction("SELECT other_fn(1, 2)", SESSION), equalTo(false));
    }

    @Test
    void skipsPreparationWhenParametersAreNullOrNoFunctionIsReferenced() {
        evaluator.prepare("SELECT exfunc_sum($1, $2)", SESSION, backend, null, "us-east-1");
        evaluator.prepare("SELECT 1", SESSION, backend, List.of(), "us-east-1");

        verifyNoInteractions(backend, invoker);
    }

    @Test
    void collectsInvokesCachesAndEnablesExecuteModeInAutoCommit() {
        String key = SUM.bindingName();
        when(backend.queryRows(LambdaUdfEvaluator.READ_PENDING_SQL))
                .thenReturn(List.of(List.of(key, "[\"1\",\"2\"]"), List.of(key, "[null,\"5\"]")))
                .thenReturn(List.of());
        when(invoker.invoke(eq(SUM), eq(SESSION), eq("us-east-1"), eq("alice"), anyLong(),
                eq(List.of(List.of("1", "2"), Arrays.asList(null, "5")))))
                .thenReturn(Arrays.asList("3", null));

        evaluator.prepare("SELECT exfunc_sum(a, b) FROM t", SESSION, backend, List.of(), "us-east-1");

        InOrder order = inOrder(backend);
        order.verify(backend).execute(LambdaUdfEvaluator.CREATE_PENDING_TABLE_SQL);
        order.verify(backend).execute(LambdaUdfEvaluator.CREATE_CACHE_TABLE_SQL);
        order.verify(backend).execute(LambdaUdfEvaluator.TRUNCATE_CACHE_SQL);
        order.verify(backend).execute("BEGIN");
        order.verify(backend).execute(LambdaUdfEvaluator.SET_COLLECT_MODE_SQL);
        order.verify(backend).execute("SELECT exfunc_sum(a, b) FROM t", List.of());
        order.verify(backend).queryRows(LambdaUdfEvaluator.READ_PENDING_SQL);
        order.verify(backend).execute("ROLLBACK");
        order.verify(backend).execute(argThat(sql -> sql.startsWith("INSERT INTO pg_temp._floci_udf_cache")
                && sql.contains("E'3'") && sql.contains(", NULL)")));
        order.verify(backend).execute("BEGIN");
        order.verify(backend).execute(LambdaUdfEvaluator.SET_COLLECT_MODE_SQL);
        order.verify(backend).execute("SELECT exfunc_sum(a, b) FROM t", List.of());
        order.verify(backend).queryRows(LambdaUdfEvaluator.READ_PENDING_SQL);
        order.verify(backend).execute("ROLLBACK");
        order.verify(backend).execute(LambdaUdfEvaluator.SET_EXECUTE_MODE_SQL);
    }

    @Test
    void runsMultipleCollectRoundsForNestedCalls() {
        functions.replace(DOUBLE);
        String sumKey = SUM.bindingName();
        String doubleKey = DOUBLE.bindingName();
        when(backend.queryRows(LambdaUdfEvaluator.READ_PENDING_SQL))
                .thenReturn(List.of(List.of(sumKey, "[\"2\",\"3\"]")))
                .thenReturn(List.of(List.of(doubleKey, "[\"5\"]")))
                .thenReturn(List.of());
        when(invoker.invoke(eq(SUM), eq(SESSION), eq("us-east-1"), eq("alice"), anyLong(),
                eq(List.of(List.of("2", "3")))))
                .thenReturn(List.of("5"));
        when(invoker.invoke(eq(DOUBLE), eq(SESSION), eq("us-east-1"), eq("alice"), anyLong(),
                eq(List.of(List.of("5")))))
                .thenReturn(List.of("10"));

        evaluator.prepare("SELECT exfunc_double(exfunc_sum(2, 3))", SESSION, backend, List.of(), "us-east-1");

        verify(invoker, times(1)).invoke(eq(SUM), any(), anyString(), anyString(), anyLong(), anyList());
        verify(invoker, times(1)).invoke(eq(DOUBLE), any(), anyString(), anyString(), anyLong(), anyList());
        verify(backend).execute(LambdaUdfEvaluator.SET_EXECUTE_MODE_SQL);
    }

    @Test
    void usesSavepointInsideExplicitTransactionOrImplicitExtendedQueryTransaction() {
        SpectrumSession inTx = new SpectrumSession(ACCOUNT, CLUSTER, "dev", List.of(), true);
        when(backend.queryRows(LambdaUdfEvaluator.READ_PENDING_SQL)).thenReturn(List.of());

        evaluator.prepare("SELECT exfunc_sum(1, 2)", inTx, backend, List.of(), "us-east-1");

        InOrder txOrder = inOrder(backend);
        txOrder.verify(backend).execute("SAVEPOINT _floci_udf_collect");
        txOrder.verify(backend).execute("ROLLBACK TO SAVEPOINT _floci_udf_collect");
        txOrder.verify(backend).execute("RELEASE SAVEPOINT _floci_udf_collect");
        verify(backend, never()).execute("BEGIN");
        verify(backend, never()).execute("ROLLBACK");

        BackendSql extended = mock(BackendSql.class);
        when(extended.inImplicitTransaction()).thenReturn(true);
        when(extended.queryString(LambdaUdfEvaluator.CURRENT_USER_SQL)).thenReturn(Optional.of("alice"));
        when(extended.queryRows(LambdaUdfEvaluator.READ_PENDING_SQL)).thenReturn(List.of());

        evaluator.prepare("SELECT exfunc_sum($1, $2)", SESSION, extended, List.of("10", "20"), "us-east-1");

        verify(extended).execute("SAVEPOINT _floci_udf_collect");
        verify(extended).execute("SELECT exfunc_sum($1, $2)", List.of("10", "20"));
        verify(extended).execute("ROLLBACK TO SAVEPOINT _floci_udf_collect");
        verify(extended).execute("RELEASE SAVEPOINT _floci_udf_collect");
    }

    @Test
    void rollsBackCollectTransactionWhenCollectQueryFails() {
        doThrow(new SpectrumReadException("42P01", "relation \"missing\" does not exist"))
                .when(backend).execute("SELECT exfunc_sum(a, b) FROM missing", List.of());

        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> evaluator.prepare("SELECT exfunc_sum(a, b) FROM missing", SESSION, backend, List.of(), "us-east-1"));

        assertThat(error.sqlState(), equalTo("42P01"));
        verify(backend).execute("BEGIN");
        verify(backend).execute("ROLLBACK");
        verify(backend, never()).execute(LambdaUdfEvaluator.SET_EXECUTE_MODE_SQL);
    }

    @Test
    void stopsAfterMaximumNestingRounds() {
        String key = SUM.bindingName();
        when(backend.queryRows(LambdaUdfEvaluator.READ_PENDING_SQL))
                .thenReturn(List.of(List.of(key, "[\"1\",\"2\"]")));
        when(invoker.invoke(eq(SUM), any(), anyString(), anyString(), anyLong(), anyList()))
                .thenReturn(List.of("3"));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> evaluator.prepare("SELECT exfunc_sum(1, 2)", SESSION, backend, List.of(), "us-east-1"));

        assertThat(error.sqlState(), equalTo("54001"));
        assertThat(error.getMessage(), containsString(String.valueOf(LambdaUdfEvaluator.MAX_ROUNDS)));
    }
}
