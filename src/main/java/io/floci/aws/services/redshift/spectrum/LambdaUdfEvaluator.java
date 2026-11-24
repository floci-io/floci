package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.PostgresWire;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Evaluates scalar Lambda UDF calls for a statement by running collect passes against plpgsql stubs in PostgreSQL,
 * batch-invoking Lambda through {@link LambdaUdfInvoker}, caching results in a per-session temp table, and then
 * switching the stubs into execute mode for the real statement.
 */
@ApplicationScoped
public class LambdaUdfEvaluator {
    static final int MAX_ROUNDS = 16;
    private static final int CACHE_INSERT_BATCH_SIZE = 200;
    private static final String DEFAULT_USER = "admin";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String CURRENT_USER_SQL = "SELECT current_user";
    static final String CREATE_PENDING_TABLE_SQL = "CREATE TEMP TABLE IF NOT EXISTS pg_temp._floci_udf_pending ("
            + "fn_key text NOT NULL, args_json text NOT NULL, PRIMARY KEY (fn_key, args_json))";
    static final String CREATE_CACHE_TABLE_SQL = "CREATE TEMP TABLE IF NOT EXISTS pg_temp._floci_udf_cache ("
            + "fn_key text NOT NULL, args_json text NOT NULL, result_text text, PRIMARY KEY (fn_key, args_json))";
    static final String TRUNCATE_CACHE_SQL = "TRUNCATE pg_temp._floci_udf_cache";
    static final String SET_COLLECT_MODE_SQL = "SELECT pg_catalog.set_config('floci.udf_mode', 'collect', true)";
    static final String SET_EXECUTE_MODE_SQL = "SELECT pg_catalog.set_config('floci.udf_mode', 'execute', false)";
    static final String READ_PENDING_SQL = "SELECT fn_key, args_json FROM pg_temp._floci_udf_pending "
            + "ORDER BY fn_key, args_json";

    private final ExternalFunctionRegistry functions;
    private final LambdaUdfInvoker invoker;
    private final AtomicLong nextQueryId = new AtomicLong();

    @Inject
    public LambdaUdfEvaluator(ExternalFunctionRegistry functions, LambdaUdfInvoker invoker) {
        this.functions = functions;
        this.invoker = invoker;
    }

    /**
     * Builds the plpgsql stub DDL for {@code CREATE [OR REPLACE] EXTERNAL FUNCTION}. The stub is always created as
     * {@code VOLATILE} in PostgreSQL so it may insert into the temp pending table even when the Redshift UDF was
     * declared {@code STABLE}.
     */
    static String stubSql(ExternalStatement.CreateFunction function, String schema, boolean replace) {
        String key = ExternalFunctionBinding.nameOf(schema, function.functionName(), function.argumentTypes());
        String argsExpr = function.argumentTypes().isEmpty()
                ? "'[]'"
                : "pg_catalog.json_build_array("
                + IntStream.rangeClosed(1, function.argumentTypes().size())
                .mapToObj(i -> "$" + i + "::text")
                .collect(Collectors.joining(", "))
                + ")::text";
        String qualifiedName = schema + "." + function.functionName();
        // The message is an argument, not the format string, so a % in a quoted name is not read as a placeholder.
        String body = "DECLARE "
                + "v_mode text := pg_catalog.current_setting('floci.udf_mode', true); "
                + "v_key text := " + PostgresWire.quoteLiteral(key) + "; "
                + "v_args text := " + argsExpr + "; "
                + "v_result text; "
                + "BEGIN "
                + "IF v_mode = 'collect' THEN "
                + "SELECT c.result_text INTO v_result FROM pg_temp._floci_udf_cache c "
                + "WHERE c.fn_key = v_key AND c.args_json = v_args; "
                + "IF FOUND THEN RETURN v_result::" + function.returnType() + "; END IF; "
                + "INSERT INTO pg_temp._floci_udf_pending (fn_key, args_json) VALUES (v_key, v_args) "
                + "ON CONFLICT DO NOTHING; "
                + "RETURN NULL; "
                + "ELSIF v_mode = 'execute' THEN "
                + "SELECT c.result_text INTO v_result FROM pg_temp._floci_udf_cache c "
                + "WHERE c.fn_key = v_key AND c.args_json = v_args; "
                + "IF FOUND THEN RETURN v_result::" + function.returnType() + "; END IF; "
                + "RAISE EXCEPTION '%', "
                + PostgresWire.quoteLiteral("External function " + qualifiedName
                + " was not prepared for these arguments")
                + " USING ERRCODE = 'XX000'; "
                + "ELSE "
                + "RAISE EXCEPTION '%', "
                + PostgresWire.quoteLiteral("External function " + qualifiedName + " cannot be called directly")
                + " USING ERRCODE = '0A000'; "
                + "END IF; "
                + "END";
        return "CREATE " + (replace ? "OR REPLACE " : "") + "FUNCTION "
                + signature(schema, function.functionName(), function.argumentTypes())
                + " RETURNS " + function.returnType() + " LANGUAGE plpgsql VOLATILE AS "
                + PostgresWire.quoteLiteral(body);
    }

    static String signature(String schema, String functionName, List<String> argumentTypes) {
        return ExternalTableMaterializer.quote(schema) + "." + ExternalTableMaterializer.quote(functionName)
                + "(" + String.join(", ", argumentTypes) + ")";
    }

    /** Whether {@code sql} contains a function call matching any registered external function in the session's DB. */
    public boolean referencesExternalFunction(String sql, SpectrumSession session) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        List<ExternalFunctionBinding> registered = functions.list(
                session.accountId(), session.clusterKey(), session.databaseName());
        if (registered.isEmpty()) {
            return false;
        }
        Set<String> names = new HashSet<>(registered.size());
        for (ExternalFunctionBinding binding : registered) {
            names.add(binding.functionName());
        }
        List<SpectrumSqlLexer.Token> tokens = SpectrumSqlLexer.tokenize(sql).tokens();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            SpectrumSqlLexer.Token token = tokens.get(i);
            if ((token.kind() == SpectrumSqlLexer.Kind.WORD || token.kind() == SpectrumSqlLexer.Kind.QUOTED_WORD)
                    && tokens.get(i + 1).kind() == SpectrumSqlLexer.Kind.LPAREN
                    && names.contains(token.text())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects every external function call triggered by {@code sql}, invokes Lambda in batches, populates the
     * session cache table, and enables execute mode. Returns without doing work when {@code parameters} is null
     * (Extended Query {@code Parse} phase) or when {@code sql} references no registered external function.
     */
    public void prepare(String sql, SpectrumSession session, BackendSql backend, List<String> parameters,
                        String clusterRegion) {
        if (parameters == null || !referencesExternalFunction(sql, session)) {
            return;
        }
        backend.execute(CREATE_PENDING_TABLE_SQL);
        backend.execute(CREATE_CACHE_TABLE_SQL);
        backend.execute(TRUNCATE_CACHE_SQL);

        String user = backend.queryString(CURRENT_USER_SQL).orElse(DEFAULT_USER);
        long queryId = nextQueryId.incrementAndGet();
        boolean useSavepoint = session.inTransaction() || backend.inImplicitTransaction();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<List<String>> pending = collectRound(sql, parameters, backend, useSavepoint);
            if (pending.isEmpty()) {
                backend.execute(SET_EXECUTE_MODE_SQL);
                return;
            }
            Map<String, List<String>> byFunction = new LinkedHashMap<>();
            for (List<String> row : pending) {
                if (row.size() >= 2 && row.get(0) != null && row.get(1) != null) {
                    byFunction.computeIfAbsent(row.get(0), ignored -> new ArrayList<>()).add(row.get(1));
                }
            }
            for (Map.Entry<String, List<String>> entry : byFunction.entrySet()) {
                String fnKey = entry.getKey();
                List<String> argsJsonList = entry.getValue();
                ExternalFunctionBinding binding = functions.find(
                                session.accountId(), session.clusterKey(), session.databaseName(), fnKey)
                        .orElseThrow(() -> new SpectrumSqlException("42883",
                                "External function binding \"" + fnKey + "\" does not exist"));
                List<List<String>> rows = new ArrayList<>(argsJsonList.size());
                for (String argsJson : argsJsonList) {
                    rows.add(decodeArgs(argsJson));
                }
                List<String> results = invoker.invoke(binding, session, clusterRegion, user, queryId, rows);
                storeResults(backend, fnKey, argsJsonList, results);
            }
        }
        throw new SpectrumSqlException("54001",
                "External function evaluation exceeded maximum nesting depth (" + MAX_ROUNDS + ")");
    }

    private List<List<String>> collectRound(String sql, List<String> parameters, BackendSql backend,
                                            boolean useSavepoint) {
        if (useSavepoint) {
            backend.execute("SAVEPOINT _floci_udf_collect");
        } else {
            backend.execute("BEGIN");
        }
        List<List<String>> pending;
        try {
            backend.execute(SET_COLLECT_MODE_SQL);
            backend.execute(sql, parameters);
            pending = backend.queryRows(READ_PENDING_SQL);
        } catch (RuntimeException failure) {
            rollbackCollect(backend, useSavepoint, failure);
            throw failure;
        }
        rollbackCollect(backend, useSavepoint, null);
        return pending;
    }

    private static void rollbackCollect(BackendSql backend, boolean useSavepoint, RuntimeException primaryFailure) {
        try {
            if (useSavepoint) {
                backend.execute("ROLLBACK TO SAVEPOINT _floci_udf_collect");
                backend.execute("RELEASE SAVEPOINT _floci_udf_collect");
            } else {
                backend.execute("ROLLBACK");
            }
        } catch (RuntimeException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private static void storeResults(BackendSql backend, String fnKey, List<String> argsJsonList,
                                     List<String> results) {
        String quotedKey = PostgresWire.quoteLiteral(fnKey);
        for (int start = 0; start < argsJsonList.size(); start += CACHE_INSERT_BATCH_SIZE) {
            int end = Math.min(start + CACHE_INSERT_BATCH_SIZE, argsJsonList.size());
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO pg_temp._floci_udf_cache (fn_key, args_json, result_text) VALUES ");
            for (int i = start; i < end; i++) {
                if (i > start) {
                    sql.append(", ");
                }
                String result = results.get(i);
                sql.append('(')
                        .append(quotedKey).append(", ")
                        .append(PostgresWire.quoteLiteral(argsJsonList.get(i))).append(", ")
                        .append(result == null ? "NULL" : PostgresWire.quoteLiteral(result))
                        .append(')');
            }
            sql.append(" ON CONFLICT (fn_key, args_json) DO UPDATE SET result_text = EXCLUDED.result_text");
            backend.execute(sql.toString());
        }
    }

    private static List<String> decodeArgs(String argsJson) {
        try {
            JsonNode node = JSON.readTree(argsJson);
            if (node == null || !node.isArray()) {
                throw new SpectrumSqlException("XX000", "Invalid collected UDF argument tuple: " + argsJson);
            }
            List<String> values = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                values.add(element == null || element.isNull() ? null : element.asText());
            }
            return values;
        } catch (IOException exception) {
            throw new SpectrumSqlException("XX000", "Unable to decode collected UDF arguments: "
                    + exception.getMessage());
        }
    }
}
