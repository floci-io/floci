package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.PostgresWire;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    private static final Set<String> DISALLOWED_LEADING_KEYWORDS = Set.of(
            "begin", "start", "commit", "end", "rollback", "abort", "savepoint", "release", "prepare", "do", "call");
    private static final Set<String> VOLATILE_ARGUMENT_FUNCTIONS = Set.of(
            "nextval", "setval", "random", "clock_timestamp", "statement_timestamp", "timeofday",
            "gen_random_uuid", "uuid_generate_v4", "txid_current", "pg_current_xact_id");

    static final String CURRENT_USER_SQL = "SELECT current_user";
    static final String CREATE_PENDING_TABLE_SQL = "CREATE TEMP TABLE IF NOT EXISTS pg_temp._floci_udf_pending ("
            + "fn_key text NOT NULL, args_json text NOT NULL, PRIMARY KEY (fn_key, args_json))";
    static final String CREATE_CACHE_TABLE_SQL = "CREATE TEMP TABLE IF NOT EXISTS pg_temp._floci_udf_cache ("
            + "fn_key text NOT NULL, args_json text NOT NULL, result_text text, PRIMARY KEY (fn_key, args_json))";
    static final String CREATE_CACHE_GUARD_FN_SQL = "CREATE OR REPLACE FUNCTION pg_temp._floci_udf_cache_guard() "
            + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            + "RAISE EXCEPTION 'External function cache is read-only' USING ERRCODE = '42501'; END $$";
    static final String CREATE_CACHE_GUARD_TRIGGER_SQL = "DO $$ BEGIN "
            + "IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t "
            + "JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid "
            + "WHERE c.oid = 'pg_temp._floci_udf_cache'::regclass AND t.tgname = '_floci_udf_cache_readonly') THEN "
            + "CREATE TRIGGER _floci_udf_cache_readonly BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE "
            + "ON pg_temp._floci_udf_cache FOR EACH STATEMENT EXECUTE FUNCTION pg_temp._floci_udf_cache_guard(); "
            + "END IF; END $$";
    static final String DISABLE_CACHE_GUARD_SQL =
            "ALTER TABLE pg_temp._floci_udf_cache DISABLE TRIGGER _floci_udf_cache_readonly";
    static final String ENABLE_CACHE_GUARD_SQL =
            "ALTER TABLE pg_temp._floci_udf_cache ENABLE TRIGGER _floci_udf_cache_readonly";
    static final String TRUNCATE_CACHE_SQL = "TRUNCATE pg_temp._floci_udf_cache";
    static final String SET_COLLECT_MODE_SQL = "SELECT pg_catalog.set_config('floci.udf_mode', 'collect', true)";
    static final String SET_EXECUTE_MODE_SQL = "SELECT pg_catalog.set_config('floci.udf_mode', 'execute', false)";
    static final String RESET_MODE_SQL = "SELECT pg_catalog.set_config('floci.udf_mode', '', false)";
    static final String RESET_CACHE_SQL = "DO $$ BEGIN "
            + "IF pg_catalog.to_regclass('pg_temp._floci_udf_cache') IS NOT NULL THEN "
            + "ALTER TABLE pg_temp._floci_udf_cache DISABLE TRIGGER _floci_udf_cache_readonly; "
            + "TRUNCATE pg_temp._floci_udf_cache; "
            + "ALTER TABLE pg_temp._floci_udf_cache ENABLE TRIGGER _floci_udf_cache_readonly; "
            + "END IF; END $$";
    static final String READ_PENDING_SQL = "SELECT fn_key, args_json FROM pg_temp._floci_udf_pending "
            + "ORDER BY fn_key, args_json";

    private final ExternalFunctionRegistry functions;
    private final LambdaUdfInvoker invoker;
    private final AtomicLong nextQueryId = new AtomicLong();

    @Inject
    public LambdaUdfEvaluator(ExternalFunctionRegistry functions, LambdaUdfInvoker invoker) {
        this.functions = Objects.requireNonNull(functions, "functions must not be null");
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
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

    /**
     * Whether {@code sql} contains a function call matching any registered external function in the session's DB,
     * respecting explicit schema qualification and argument arity.
     */
    public boolean referencesExternalFunction(String sql, SpectrumSession session) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        List<ExternalFunctionBinding> registered = functions.list(
                session.accountId(), session.clusterKey(), session.databaseName());
        if (registered.isEmpty()) {
            return false;
        }
        List<SpectrumSqlLexer.Token> tokens = SpectrumSqlLexer.tokenize(sql).tokens();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            SpectrumSqlLexer.Token token = tokens.get(i);
            if ((token.kind() == SpectrumSqlLexer.Kind.WORD || token.kind() == SpectrumSqlLexer.Kind.QUOTED_WORD)
                    && tokens.get(i + 1).kind() == SpectrumSqlLexer.Kind.LPAREN) {
                String qualifiedSchema = qualifiedSchemaBefore(tokens, i);
                int arity = argumentCount(tokens, i + 1);
                if (matchesRegistered(registered, qualifiedSchema, token.text(), arity)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String qualifiedSchemaBefore(List<SpectrumSqlLexer.Token> tokens, int nameIndex) {
        if (nameIndex < 2) {
            return null;
        }
        SpectrumSqlLexer.Token dot = tokens.get(nameIndex - 1);
        SpectrumSqlLexer.Token schema = tokens.get(nameIndex - 2);
        if (dot.kind() == SpectrumSqlLexer.Kind.OP && ".".equals(dot.text())
                && (schema.kind() == SpectrumSqlLexer.Kind.WORD
                || schema.kind() == SpectrumSqlLexer.Kind.QUOTED_WORD)) {
            return schema.text();
        }
        return null;
    }

    /** Counts top-level arguments in the parenthesized call starting at {@code lparenIndex}, or -1 if unknown. */
    private static int argumentCount(List<SpectrumSqlLexer.Token> tokens, int lparenIndex) {
        if (lparenIndex + 1 >= tokens.size()) {
            return -1;
        }
        if (tokens.get(lparenIndex + 1).kind() == SpectrumSqlLexer.Kind.RPAREN) {
            return 0;
        }
        int depth = 1;
        int commas = 0;
        for (int i = lparenIndex + 1; i < tokens.size(); i++) {
            SpectrumSqlLexer.Token token = tokens.get(i);
            if (token.kind() == SpectrumSqlLexer.Kind.LPAREN) {
                depth++;
            } else if (token.kind() == SpectrumSqlLexer.Kind.RPAREN) {
                depth--;
                if (depth == 0) {
                    return commas + 1;
                }
            } else if (depth == 1) {
                if (token.kind() == SpectrumSqlLexer.Kind.COMMA) {
                    commas++;
                } else if (token.isWord("variadic")) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static boolean matchesRegistered(List<ExternalFunctionBinding> registered, String qualifiedSchema,
                                             String functionName, int arity) {
        for (ExternalFunctionBinding binding : registered) {
            if (!binding.functionName().equals(functionName)) {
                continue;
            }
            if (qualifiedSchema != null && !binding.schemaName().equals(qualifiedSchema)) {
                continue;
            }
            if (arity >= 0 && binding.argumentTypes().size() != arity) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Collects every external function call triggered by {@code sql}, invokes Lambda in batches, populates the
     * session cache table, and enables execute mode. Returns {@code false} without doing work when
     * {@code parameters} is null (Extended Query {@code Parse} phase) or when {@code sql} references no registered
     * external function.
     */
    public boolean prepare(String sql, SpectrumSession session, BackendSql backend, List<String> parameters,
                           String clusterRegion) {
        return prepare(sql, session, backend, parameters, null, clusterRegion);
    }

    public boolean prepare(String sql, SpectrumSession session, BackendSql backend, List<String> parameters,
                           BackendSql.RawParameters rawParameters, String clusterRegion) {
        if ((parameters == null && rawParameters == null) || !referencesExternalFunction(sql, session)) {
            return false;
        }
        validateCollectableSql(sql);

        backend.execute(CREATE_PENDING_TABLE_SQL);
        backend.execute(CREATE_CACHE_TABLE_SQL);
        backend.execute(CREATE_CACHE_GUARD_FN_SQL);
        backend.execute(CREATE_CACHE_GUARD_TRIGGER_SQL);
        truncateCache(backend);

        String user = backend.queryString(CURRENT_USER_SQL).orElse(DEFAULT_USER);
        long queryId = nextQueryId.incrementAndGet();

        if (backend.usesSubtransactionCollect()) {
            BackendSql.RawParameters effectiveRaw = rawParameters != null ? rawParameters
                    : (parameters == null || parameters.isEmpty() ? null : textRawParameters(parameters));
            prepareWithSubtransaction(sql, session, backend, effectiveRaw, clusterRegion, user, queryId);
            return true;
        }

        boolean useSavepoint = session.inTransaction();
        List<String> textParams = parameters == null ? List.of() : parameters;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<List<String>> pending = collectRound(sql, textParams, backend, useSavepoint);
            if (pending.isEmpty()) {
                backend.execute(SET_EXECUTE_MODE_SQL);
                return true;
            }
            invokeAndCache(pending, session, backend, clusterRegion, user, queryId);
        }
        throw new SpectrumSqlException("54001",
                "External function evaluation exceeded maximum nesting depth (" + MAX_ROUNDS + ")");
    }

    /**
     * Clears execute mode and truncates the session UDF cache so later statements that reach a stub indirectly
     * cannot observe results cached for an earlier statement.
     */
    public void resetState(BackendSql backend) {
        backend.execute(RESET_MODE_SQL);
        backend.execute(RESET_CACHE_SQL);
    }

    private void prepareWithSubtransaction(String sql, SpectrumSession session, BackendSql backend,
                                           BackendSql.RawParameters rawParameters, String clusterRegion,
                                           String user, long queryId) {
        SubtransactionCollector collector = SubtransactionCollector.create(sql, rawParameters, backend);
        RuntimeException primaryFailure = null;
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<List<String>> pending = collector.collect(backend);
                if (pending.isEmpty()) {
                    backend.execute(SET_EXECUTE_MODE_SQL);
                    return;
                }
                invokeAndCache(pending, session, backend, clusterRegion, user, queryId);
            }
            throw new SpectrumSqlException("54001",
                    "External function evaluation exceeded maximum nesting depth (" + MAX_ROUNDS + ")");
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            collector.drop(backend, primaryFailure);
        }
    }

    private void invokeAndCache(List<List<String>> pending, SpectrumSession session, BackendSql backend,
                                String clusterRegion, String user, long queryId) {
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

    private static void validateCollectableSql(String sql) {
        List<SpectrumSqlLexer.Token> tokens = SpectrumSqlLexer.tokenize(sql).tokens();
        if (tokens.isEmpty()) {
            return;
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind() == SpectrumSqlLexer.Kind.SEMICOLON && i + 1 < tokens.size()) {
                throw new SpectrumSqlException("0A000",
                        "External function calls are not supported in multi-statement queries");
            }
        }
        SpectrumSqlLexer.Token first = tokens.get(0);
        if (first.kind() == SpectrumSqlLexer.Kind.WORD && DISALLOWED_LEADING_KEYWORDS.contains(first.text())) {
            throw new SpectrumSqlException("0A000",
                    "External function calls are not supported with transaction control or procedural blocks");
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            SpectrumSqlLexer.Token token = tokens.get(i);
            if ((token.kind() == SpectrumSqlLexer.Kind.WORD || token.kind() == SpectrumSqlLexer.Kind.QUOTED_WORD)
                    && tokens.get(i + 1).kind() == SpectrumSqlLexer.Kind.LPAREN) {
                String lower = token.text().toLowerCase(Locale.ROOT);
                if (VOLATILE_ARGUMENT_FUNCTIONS.contains(lower)) {
                    throw new SpectrumSqlException("0A000",
                            "External function calls cannot be combined with volatile expression \"" + lower + "()\"");
                }
            }
        }
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

    private static void truncateCache(BackendSql backend) {
        backend.execute(DISABLE_CACHE_GUARD_SQL);
        RuntimeException primaryFailure = null;
        try {
            backend.execute(TRUNCATE_CACHE_SQL);
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            reEnableCacheGuard(backend, primaryFailure);
        }
    }

    private static void storeResults(BackendSql backend, String fnKey, List<String> argsJsonList,
                                     List<String> results) {
        String quotedKey = PostgresWire.quoteLiteral(fnKey);
        backend.execute(DISABLE_CACHE_GUARD_SQL);
        RuntimeException primaryFailure = null;
        try {
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
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            reEnableCacheGuard(backend, primaryFailure);
        }
    }

    private static void reEnableCacheGuard(BackendSql backend, RuntimeException primaryFailure) {
        try {
            backend.execute(ENABLE_CACHE_GUARD_SQL);
        } catch (RuntimeException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
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

    private static List<List<String>> decodePendingJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<List<String>> rows = new ArrayList<>(root.size());
            for (JsonNode entry : root) {
                if (entry != null && entry.isArray() && entry.size() >= 2) {
                    rows.add(List.of(entry.get(0).asText(), entry.get(1).asText()));
                }
            }
            return rows;
        } catch (IOException exception) {
            throw new SpectrumSqlException("XX000", "Unable to decode collected UDF rows: "
                    + exception.getMessage());
        }
    }

    private static BackendSql.RawParameters textRawParameters(List<String> parameters) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeShort(0);
            out.writeShort(parameters.size());
            for (String value : parameters) {
                if (value == null) {
                    out.writeInt(-1);
                } else {
                    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(utf8.length);
                    out.write(utf8);
                }
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return new BackendSql.RawParameters(List.of(), parameters.size(), bytes.toByteArray());
    }

    /**
     * Temporary PL/pgSQL function that runs a statement inside an internal subtransaction, captures pending UDF calls
     * into a local variable, and rolls back the subtransaction via a sentinel exception. Works in Extended Query
     * autocommit mode where {@code SAVEPOINT} is rejected with {@code 25P01}.
     */
    private static final class SubtransactionCollector {
        private final String fnName;
        private final List<Integer> resolvedOids;
        private final BackendSql.RawParameters boundParameters;
        private final String callSql;

        private SubtransactionCollector(String fnName, List<Integer> resolvedOids,
                                        BackendSql.RawParameters boundParameters, String callSql) {
            this.fnName = fnName;
            this.resolvedOids = resolvedOids;
            this.boundParameters = boundParameters;
            this.callSql = callSql;
        }

        private static SubtransactionCollector create(String sql, BackendSql.RawParameters rawParameters,
                                                      BackendSql backend) {
            String id = UUID.randomUUID().toString().replace("-", "");
            String fnName = "_floci_udf_collect_" + id;
            String sentinel = "_floci_udf_done_" + id;
            String ddlTag = "$floci_ddl_" + id + "$";
            String bodyTag = "$floci_body_" + id + "$";
            String sqlTag = "$floci_sql_" + id + "$";

            int paramCount = rawParameters == null ? 0 : rawParameters.count();
            List<Integer> resolvedOids = paramCount == 0 ? List.of()
                    : backend.resolveParameterTypeOids(sql, rawParameters.typeOids(), paramCount);
            if (paramCount > 0 && resolvedOids.size() != paramCount) {
                throw new SpectrumSqlException("XX000",
                        "Unable to resolve parameter types for external function collect pass");
            }

            String usingClause = paramCount == 0 ? ""
                    : " USING " + IntStream.rangeClosed(1, paramCount)
                    .mapToObj(i -> "p" + i)
                    .collect(Collectors.joining(", "));
            String body = "DECLARE v_pending text; BEGIN BEGIN "
                    + "PERFORM pg_catalog.set_config('floci.udf_mode', 'collect', true); "
                    + "EXECUTE " + sqlTag + sql + sqlTag + usingClause + "; "
                    + "SELECT pg_catalog.json_agg(pg_catalog.json_build_array(fn_key, args_json) "
                    + "ORDER BY fn_key, args_json)::text INTO v_pending FROM pg_temp._floci_udf_pending; "
                    + "RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = " + PostgresWire.quoteLiteral(sentinel) + "; "
                    + "EXCEPTION WHEN SQLSTATE 'P0001' THEN "
                    + "IF SQLERRM <> " + PostgresWire.quoteLiteral(sentinel) + " THEN RAISE; END IF; "
                    + "END; RETURN COALESCE(v_pending, '[]'); END;";

            String createSql;
            String callSql;
            BackendSql.RawParameters boundParameters;
            if (paramCount == 0) {
                createSql = "DO " + ddlTag + " BEGIN EXECUTE 'CREATE FUNCTION pg_temp." + fnName
                        + "() RETURNS text LANGUAGE plpgsql AS ' || pg_catalog.quote_literal("
                        + bodyTag + body + bodyTag + "); END " + ddlTag;
                callSql = "SELECT pg_temp." + fnName + "()";
                boundParameters = null;
            } else {
                String paramDeclExpr = IntStream.range(0, paramCount)
                        .mapToObj(i -> "'p" + (i + 1) + " ' || pg_catalog.format_type(" + resolvedOids.get(i) + ", -1)")
                        .collect(Collectors.joining(" || ', ' || "));
                createSql = "DO " + ddlTag + " BEGIN EXECUTE 'CREATE FUNCTION pg_temp." + fnName
                        + "(' || " + paramDeclExpr
                        + " || ') RETURNS text LANGUAGE plpgsql AS ' || pg_catalog.quote_literal("
                        + bodyTag + body + bodyTag + "); END " + ddlTag;
                String placeholders = IntStream.rangeClosed(1, paramCount)
                        .mapToObj(i -> "$" + i)
                        .collect(Collectors.joining(", "));
                callSql = "SELECT pg_temp." + fnName + "(" + placeholders + ")";
                boundParameters = new BackendSql.RawParameters(resolvedOids, paramCount, rawParameters.bindSection());
            }

            backend.execute(createSql);
            return new SubtransactionCollector(fnName, resolvedOids, boundParameters, callSql);
        }

        private List<List<String>> collect(BackendSql backend) {
            String json = boundParameters == null
                    ? backend.queryString(callSql).orElse("[]")
                    : backend.queryString(callSql, boundParameters).orElse("[]");
            return decodePendingJson(json);
        }

        private void drop(BackendSql backend, RuntimeException primaryFailure) {
            if (primaryFailure != null) {
                return;
            }
            String id = UUID.randomUUID().toString().replace("-", "");
            String ddlTag = "$floci_drop_" + id + "$";
            String dropSql;
            if (resolvedOids.isEmpty()) {
                dropSql = "DO " + ddlTag + " BEGIN EXECUTE 'DROP FUNCTION IF EXISTS pg_temp." + fnName
                        + "()'; END " + ddlTag;
            } else {
                String argTypesExpr = resolvedOids.stream()
                        .map(oid -> "pg_catalog.format_type(" + oid + ", -1)")
                        .collect(Collectors.joining(" || ', ' || "));
                dropSql = "DO " + ddlTag + " BEGIN EXECUTE 'DROP FUNCTION IF EXISTS pg_temp." + fnName
                        + "(' || " + argTypesExpr + " || ')'; END " + ddlTag;
            }
            backend.execute(dropSql);
        }
    }
}
