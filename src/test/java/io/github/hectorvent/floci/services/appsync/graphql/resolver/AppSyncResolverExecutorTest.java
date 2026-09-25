package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncVtlEngine;
import io.github.hectorvent.floci.services.appsync.graphql.datasource.AppSyncDataSourceInvoker;
import io.github.hectorvent.floci.services.appsync.graphql.datasource.AppSyncDataSourceInvokers;
import io.github.hectorvent.floci.services.appsync.graphql.js.AppSyncJsRuntime;
import io.github.hectorvent.floci.services.appsync.graphql.js.JsEvaluation;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.appsync.model.FunctionConfiguration;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.ResolverKind;
import io.github.hectorvent.floci.services.appsync.model.ResolverRuntimeName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pipeline mechanics, with a scripted JS runtime in place of the sidecar: the order the handlers
 * run in, what each one sees of the last, and which of AppSync's three ways of stopping applies.
 */
class AppSyncResolverExecutorTest {

    private static final String API_ID = "api1";

    private final AppSyncService appSync = mock(AppSyncService.class);
    private final ScriptedJsRuntime jsRuntime = new ScriptedJsRuntime();
    private final RecordingInvoker invoker = new RecordingInvoker(DataSourceType.RELATIONAL_DATABASE);
    private final RecordingInvoker noneInvoker = new RecordingInvoker(DataSourceType.NONE);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppSyncResolverExecutor executor = new AppSyncResolverExecutor(appSync, jsRuntime,
            new AppSyncDataSourceInvokers(List.of(invoker, noneInvoker)), vtlEngine(), objectMapper);

    private static AppSyncVtlEngine vtlEngine() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().appsync().vtlMaxLoops()).thenReturn(10_000);
        when(config.services().appsync().vtlMaxOutputChars()).thenReturn(1_048_576);
        when(config.services().appsync().vtlTimeoutMillis()).thenReturn(5_000L);
        return new AppSyncVtlEngine(config);
    }

    /** A JS runtime whose answers are supplied per (code, handler) by the test. */
    private static final class ScriptedJsRuntime implements AppSyncJsRuntime {
        private final Map<String, BiFunction<String, Map<String, Object>, JsEvaluation>> scripts =
                new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final List<Map<String, Object>> contexts = new ArrayList<>();

        void script(String code, String handler,
                    BiFunction<String, Map<String, Object>, JsEvaluation> answer) {
            scripts.put(code + "#" + handler, answer);
        }

        @Override
        public JsEvaluation evaluate(String code, String handler, Map<String, Object> context) {
            calls.add(code + "#" + handler);
            contexts.add(context);
            var script = scripts.get(code + "#" + handler);
            if (script == null) {
                // Nothing scripted stands for a module that does not export this handler, which is
                // how a request-only function behaves.
                return new JsEvaluation(null, Map.of(), false, List.of(), null, true);
            }
            return script.apply(handler, context);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /** Records what reached the data source and answers with whatever the test set. */
    private static final class RecordingInvoker implements AppSyncDataSourceInvoker {
        private final DataSourceType type;
        private final List<Object> requests = new ArrayList<>();
        private Object answer = Map.of();
        private AwsException failure;

        private RecordingInvoker(DataSourceType type) {
            this.type = type;
        }

        @Override
        public DataSourceType type() {
            return type;
        }

        @Override
        public Object invoke(DataSource dataSource, Object request, String region) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            if (type == DataSourceType.NONE) {
                if (request instanceof Map<?, ?> map && map.containsKey("payload")) {
                    return map.get("payload");
                }
                return request;
            }
            return answer;
        }
    }

    private static JsEvaluation ok(Object result) {
        return new JsEvaluation(result, Map.of(), false, List.of(), null, false);
    }

    private static JsEvaluation ok(Object result, Map<String, Object> stash) {
        return new JsEvaluation(result, stash, false, List.of(), null, false);
    }

    private DataSource dataSource(String name) {
        return dataSource(name, DataSourceType.RELATIONAL_DATABASE);
    }

    private DataSource dataSource(String name, DataSourceType type) {
        DataSource ds = new DataSource();
        ds.setName(name);
        ds.setType(type);
        ds.setDataSourceArn("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID + "/datasources/" + name);
        return ds;
    }

    private Resolver resolver(ResolverKind kind, String code) {
        Resolver resolver = new Resolver();
        resolver.setApiId(API_ID);
        resolver.setTypeName("Query");
        resolver.setFieldName("getMessages");
        resolver.setKind(kind);
        resolver.setCode(code);
        return resolver;
    }

    private FunctionConfiguration function(String id, String name, String dataSourceName, String code) {
        FunctionConfiguration fn = new FunctionConfiguration();
        fn.setFunctionId(id);
        fn.setName(name);
        fn.setDataSourceName(dataSourceName);
        fn.setCode(code);
        return fn;
    }

    private ResolverInvocation invocation(Map<String, Object> arguments) {
        return invocation(arguments, null);
    }

    private ResolverInvocation invocation(Map<String, Object> arguments, Object source) {
        return new ResolverInvocation(API_ID, "Query", "getThing", arguments, source,
                null, null, null, null, null);
    }

    @Test
    void unitResolverRunsRequestThenDataSourceThenResponse() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        when(appSync.getEnvironmentVariables(API_ID)).thenReturn(Map.of("QUOTA", "10"));
        Resolver resolver = resolver(ResolverKind.UNIT, "unit-code");
        resolver.setDataSourceName("accountDB");
        jsRuntime.script("unit-code", "request", (h, ctx) -> ok(Map.of("statement", "select 1")));
        jsRuntime.script("unit-code", "response", (h, ctx) -> ok(List.of(Map.of("id", 1))));
        invoker.answer = Map.of("sqlStatementResults", List.of());

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(List.of("unit-code#request", "unit-code#response"), jsRuntime.calls);
        assertEquals(List.of(Map.of("statement", "select 1")), invoker.requests);
        assertEquals(List.of(Map.of("id", 1)), result.data());
        assertTrue(result.errors().isEmpty());
        // The response handler sees the data source's answer as ctx.result.
        assertEquals(Map.of("sqlStatementResults", List.of()), jsRuntime.contexts.get(1).get("result"));
        assertEquals(Map.of("QUOTA", "10"), jsRuntime.contexts.get(0).get("env"));
    }

    @Test
    void pipelineRunsBeforeEachFunctionThenAfter() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        when(appSync.getFunction(API_ID, "fn1")).thenReturn(function("fn1", "one", "accountDB", "fn1-code"));
        when(appSync.getFunction(API_ID, "fn2")).thenReturn(function("fn2", "two", "accountDB", "fn2-code"));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("fn1", "fn2")));
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok(Map.of(), Map.of("orgNo", "556")));
        jsRuntime.script("fn1-code", "request", (h, ctx) -> ok(Map.of("statement", "one")));
        jsRuntime.script("fn1-code", "response", (h, ctx) -> ok("first"));
        jsRuntime.script("fn2-code", "request", (h, ctx) -> ok(Map.of("statement", "two")));
        jsRuntime.script("fn2-code", "response", (h, ctx) -> ok("second"));
        jsRuntime.script("pipeline-code", "response", (h, ctx) -> ok(ctx.get("result")));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(List.of("pipeline-code#request", "fn1-code#request", "fn1-code#response",
                "fn2-code#request", "fn2-code#response", "pipeline-code#response"), jsRuntime.calls);
        // The after step is handed the last function's result as ctx.result.
        assertEquals("second", result.data());
    }

    @Test
    void pipelineThreadsStashAndPreviousResultThroughEveryStage() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        when(appSync.getFunction(API_ID, "fn1")).thenReturn(function("fn1", "one", "accountDB", "fn1-code"));
        when(appSync.getFunction(API_ID, "fn2")).thenReturn(function("fn2", "two", "accountDB", "fn2-code"));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("fn1", "fn2")));
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok("before-result", Map.of("orgNo", "556")));
        jsRuntime.script("fn1-code", "request", (h, ctx) -> ok(Map.of()));
        jsRuntime.script("fn1-code", "response", (h, ctx) -> ok("fn1-result", Map.of("orgNo", "556", "seen", true)));
        jsRuntime.script("fn2-code", "request", (h, ctx) -> ok(Map.of()));
        jsRuntime.script("fn2-code", "response", (h, ctx) -> ok("fn2-result"));
        jsRuntime.script("pipeline-code", "response", (h, ctx) -> ok(ctx.get("result")));

        executor.execute(resolver, invocation(Map.of()));

        // fn1's request sees the before step's return as ctx.prev.result and its stash.
        assertEquals(Map.of("result", "before-result"), jsRuntime.contexts.get(1).get("prev"));
        assertEquals(Map.of("orgNo", "556"), jsRuntime.contexts.get(1).get("stash"));
        // fn2 sees fn1's response, and the stash fn1 added to.
        assertEquals(Map.of("result", "fn1-result"), jsRuntime.contexts.get(3).get("prev"));
        assertEquals(Map.of("orgNo", "556", "seen", true), jsRuntime.contexts.get(3).get("stash"));
    }

    @Test
    void earlyReturnInTheBeforeStepSkipsTheFunctionsAndTheAfterStep() {
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("missing")));
        jsRuntime.script("pipeline-code", "request", (h, ctx) ->
                new JsEvaluation(Map.of("cached", true), Map.of(), true, List.of(), null, false));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(List.of("pipeline-code#request"), jsRuntime.calls);
        assertTrue(invoker.requests.isEmpty(), "runtime.earlyReturn must not reach the data source");
        assertEquals(Map.of("cached", true), result.data());
    }

    @Test
    void utilErrorFailsTheFieldWithItsOwnErrorType() {
        Resolver resolver = resolver(ResolverKind.UNIT, "unit-code");
        resolver.setDataSourceName("accountDB");
        jsRuntime.script("unit-code", "request", (h, ctx) -> new JsEvaluation(null, Map.of(), false,
                List.of(), new JsEvaluation.JsError("orgNo is required", "BadRequest",
                Map.of("field", "orgNo"), null), false));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertNull(result.data());
        assertTrue(invoker.requests.isEmpty(), "util.error must stop before the data source");
        assertEquals(1, result.errors().size());
        AppSyncResolverError error = result.errors().get(0);
        assertEquals("orgNo is required", error.message());
        // Clients switch on errorType, so it has to survive into the response extensions.
        assertEquals("BadRequest", error.errorType());
        assertEquals(Map.of("field", "orgNo"), error.data());
    }

    @Test
    void appendedErrorsAreReturnedBesideTheData() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        Resolver resolver = resolver(ResolverKind.UNIT, "unit-code");
        resolver.setDataSourceName("accountDB");
        jsRuntime.script("unit-code", "request", (h, ctx) -> ok(Map.of()));
        jsRuntime.script("unit-code", "response", (h, ctx) -> new JsEvaluation(List.of("partial"),
                Map.of(), false,
                List.of(new JsEvaluation.JsError("one row was dropped", "Partial", null, null)),
                null, false));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        // util.appendError is the one that does not stop anything: data and errors together.
        assertEquals(List.of("partial"), result.data());
        assertEquals(1, result.errors().size());
        assertEquals("one row was dropped", result.errors().get(0).message());
    }

    @Test
    void aFunctionWithNoResponseHandlerPassesTheDataSourceResultThrough() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        when(appSync.getFunction(API_ID, "fn1")).thenReturn(function("fn1", "one", "accountDB", "fn1-code"));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("fn1")));
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok(null));
        jsRuntime.script("fn1-code", "request", (h, ctx) -> ok(Map.of("statement", "select 1")));
        // No fn1-code#response script: the module exports none.
        jsRuntime.script("pipeline-code", "response", (h, ctx) -> ok(ctx.get("result")));
        invoker.answer = List.of(Map.of("id", 7));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(List.of(Map.of("id", 7)), result.data());
    }

    @Test
    void aMissingPipelineFunctionFailsTheFieldByName() {
        when(appSync.getFunction(API_ID, "gone"))
                .thenThrow(new AwsException("NotFoundException", "Function not found: gone", 404));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("gone")));
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok(null));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).message().contains("gone"),
                result.errors().get(0).message());
    }

    // ── ctx.error ────────────────────────────────────────────────────────────

    @Test
    void aDataSourceErrorReachesTheResponseHandlerAsCtxError() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        Resolver resolver = resolver(ResolverKind.UNIT, "unit-code");
        resolver.setDataSourceName("accountDB");
        invoker.failure = new AwsException("DatabaseErrorException",
                "ERROR: operator does not exist: timestamp >= character varying", 400);
        jsRuntime.script("unit-code", "request", (h, ctx) -> ok(Map.of("statement", "select 1")));
        jsRuntime.script("unit-code", "response", (h, ctx) -> ok(Map.of("handled", true)));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        // AppSync's shape, which a resolver forwards as util.error(ctx.error.message, ctx.error.type).
        Map<?, ?> ctxError = (Map<?, ?>) jsRuntime.contexts.get(1).get("error");
        assertEquals("ERROR: operator does not exist: timestamp >= character varying",
                ctxError.get("message"));
        assertEquals("DatabaseErrorException", ctxError.get("type"));
        assertNull(jsRuntime.contexts.get(1).get("result"), "a failed call has no result");
        // The handler returned a value and did not re-raise, so on AWS the error is suppressed.
        assertEquals(Map.of("handled", true), result.data());
        assertTrue(result.errors().isEmpty(),
                "an error the response handler chose not to re-raise is suppressed");
    }

    @Test
    void aResponseHandlerThatReRaisesCtxErrorFailsTheField() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        Resolver resolver = resolver(ResolverKind.UNIT, "unit-code");
        resolver.setDataSourceName("accountDB");
        invoker.failure = new AwsException("DatabaseErrorException", "relation does not exist", 400);
        jsRuntime.script("unit-code", "request", (h, ctx) -> ok(Map.of()));
        // What `if (ctx.error) return util.error('Error getting messages', ctx.error.type, …)` does.
        jsRuntime.script("unit-code", "response", (h, ctx) -> new JsEvaluation(null, Map.of(), false,
                List.of(), new JsEvaluation.JsError("Error getting messages", "DatabaseErrorException",
                "relation does not exist", null), false));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertNull(result.data());
        assertEquals(1, result.errors().size());
        AppSyncResolverError error = result.errors().get(0);
        assertEquals("Error getting messages", error.message());
        assertEquals("DatabaseErrorException", error.errorType());
    }

    @Test
    void aDataSourceErrorWithNoResponseHandlerFailsTheField() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        Resolver resolver = resolver(ResolverKind.UNIT, "unit-code");
        resolver.setDataSourceName("accountDB");
        invoker.failure = new AwsException("DatabaseErrorException", "connection refused", 500);
        jsRuntime.script("unit-code", "request", (h, ctx) -> ok(Map.of()));
        // No response script: the module exports none, so nothing can decide to suppress.

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(1, result.errors().size());
        assertEquals("connection refused", result.errors().get(0).message());
        assertEquals("DatabaseErrorException",
                result.errors().get(0).errorType());
    }

    @Test
    void aFunctionThatSuppressesItsErrorLetsThePipelineCarryOn() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        when(appSync.getFunction(API_ID, "fn1")).thenReturn(function("fn1", "one", "accountDB", "fn1-code"));
        when(appSync.getFunction(API_ID, "fn2")).thenReturn(function("fn2", "two", null, "fn2-code"));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("fn1", "fn2")));
        invoker.failure = new AwsException("DatabaseErrorException", "deadlock detected", 400);
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok(null));
        jsRuntime.script("fn1-code", "request", (h, ctx) -> ok(Map.of()));
        // Swallows the failure and substitutes an empty page, as a resolver may choose to.
        jsRuntime.script("fn1-code", "response", (h, ctx) -> ok(List.of()));
        jsRuntime.script("fn2-code", "request", (h, ctx) -> ok(Map.of()));
        jsRuntime.script("fn2-code", "response", (h, ctx) -> ok(Map.of("items", ctx.get("result"))));
        jsRuntime.script("pipeline-code", "response", (h, ctx) -> ok(ctx.get("result")));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        // fn2 ran, and saw fn1's substituted value as ctx.prev.result.
        assertEquals(List.of("pipeline-code#request", "fn1-code#request", "fn1-code#response",
                "fn2-code#request", "fn2-code#response", "pipeline-code#response"), jsRuntime.calls);
        assertEquals(Map.of("result", List.of()), jsRuntime.contexts.get(3).get("prev"));
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void aFunctionThatReRaisesStopsThePipeline() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        when(appSync.getFunction(API_ID, "fn1")).thenReturn(function("fn1", "one", "accountDB", "fn1-code"));
        when(appSync.getFunction(API_ID, "fn2")).thenReturn(function("fn2", "two", null, "fn2-code"));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("fn1", "fn2")));
        invoker.failure = new AwsException("DatabaseErrorException", "deadlock detected", 400);
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok(null));
        jsRuntime.script("fn1-code", "request", (h, ctx) -> ok(Map.of()));
        jsRuntime.script("fn1-code", "response", (h, ctx) -> new JsEvaluation(null, Map.of(), false,
                List.of(), new JsEvaluation.JsError("query failed", "DatabaseErrorException", null, null),
                false));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        // fn2 and the after step never run.
        assertEquals(List.of("pipeline-code#request", "fn1-code#request", "fn1-code#response"),
                jsRuntime.calls);
        assertEquals(1, result.errors().size());
        assertEquals("query failed", result.errors().get(0).message());
    }

    @Test
    void aSuccessfulCallCarriesNoCtxError() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        Resolver resolver = resolver(ResolverKind.UNIT, "unit-code");
        resolver.setDataSourceName("accountDB");
        jsRuntime.script("unit-code", "request", (h, ctx) -> ok(Map.of()));
        jsRuntime.script("unit-code", "response", (h, ctx) -> ok("fine"));

        executor.execute(resolver, invocation(Map.of()));

        // Absent, not null: `if (ctx.error)` must be false, and the request handler never sees one.
        assertFalse(jsRuntime.contexts.get(0).containsKey("error"));
        assertFalse(jsRuntime.contexts.get(1).containsKey("error"));
    }

    // ── VTL UNIT resolvers over NONE ─────────────────────────────────────────

    @Test
    void jsNoneRequestWithVtlVersionMemberRemainsTheRequestObject() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        Resolver resolver = resolver(ResolverKind.UNIT, "unit-code");
        resolver.setDataSourceName("local");
        Map<String, Object> request = Map.of("version", "2018-05-29");
        jsRuntime.script("unit-code", "request", (h, ctx) -> ok(request));
        jsRuntime.script("unit-code", "response", (h, ctx) -> ok(ctx.get("result")));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertTrue(result.errors().isEmpty());
        assertEquals(request, result.data());
        assertEquals(List.of(request), noneInvoker.requests);
    }

    @Test
    void vtlUnitResolverUsesArgumentsSourceStashAndResult() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("local");
        resolver.setRequestMappingTemplate("""
                #set($discard = $ctx.stash.put("prefix", $ctx.source.prefix))
                {"version":"2018-05-29","payload":{
                  "id":$util.toJson($ctx.args.id),
                  "source":$util.toJson($ctx.source.prefix)
                }}
                """);
        resolver.setResponseMappingTemplate("""
                {"id":$util.toJson($ctx.result.id),
                 "source":$util.toJson($ctx.result.source),
                 "stash":$util.toJson($ctx.stash.prefix)}
                """);

        ResolverOutcome result = executor.execute(resolver,
                invocation(Map.of("id", "42"), Map.of("prefix", "parent")));

        assertTrue(result.errors().isEmpty());
        assertEquals(Map.of("id", "42", "source", "parent", "stash", "parent"), result.data());
        assertEquals(1, noneInvoker.requests.size());
        assertEquals("2018-05-29", ((Map<?, ?>) noneInvoker.requests.get(0)).get("version"));
    }

    @Test
    void vtlReturnHandsTheValueDirectlyToTheField() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("local");
        resolver.setRequestMappingTemplate("""
                {"version":"2018-05-29","payload":{"id":$util.toJson($ctx.args.id)}}
                """);
        resolver.setResponseMappingTemplate("#return($ctx.result)");

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of("id", "42")));

        assertEquals(Map.of("id", "42"), result.data());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void vtlReturnFromRequestSkipsTheDataSourceAndResponseTemplate() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("local");
        resolver.setRequestMappingTemplate("#return({\"short\":\"circuit\"})");
        resolver.setResponseMappingTemplate("$util.error(\"response should not run\")");

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(Map.of("short", "circuit"), result.data());
        assertTrue(result.errors().isEmpty());
        assertTrue(noneInvoker.requests.isEmpty());
    }

    @Test
    void vtlNoneRequestWithoutPayloadProducesNullResult() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("local");
        resolver.setRequestMappingTemplate("{\"version\":\"2018-05-29\"}");
        resolver.setResponseMappingTemplate("$util.toJson($ctx.result)");

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertNull(result.data());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void vtlNoneRequestRejectsUnsupportedMembers() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("local");
        resolver.setRequestMappingTemplate("{\"version\":\"2018-05-29\",\"operation\":\"GetItem\"}");
        resolver.setResponseMappingTemplate("$util.toJson($ctx.result)");

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertNull(result.data());
        assertEquals(1, result.errors().size());
        assertEquals("MappingTemplate", result.errors().get(0).errorType());
        assertTrue(noneInvoker.requests.isEmpty());
    }

    @Test
    void vtlAppendErrorKeepsTheResponseData() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("local");
        resolver.setRequestMappingTemplate("""
                {"version":"2018-05-29","payload":{"id":"1"}}
                """);
        resolver.setResponseMappingTemplate("""
                $util.appendError("partial", "Partial", {"id":"1"})
                $util.toJson($ctx.result)
                """);

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(Map.of("id", "1"), result.data());
        assertEquals(1, result.appendedErrors().size());
        assertEquals("partial", result.appendedErrors().get(0).message());
        assertEquals("Partial", result.appendedErrors().get(0).errorType());
        assertEquals(Map.of("id", "1"), result.appendedErrors().get(0).data());
    }

    @Test
    void vtlUtilErrorFailsTheFieldWithTheChosenDetails() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("local");
        resolver.setRequestMappingTemplate("""
                {"version":"2018-05-29","payload":{"id":"1"}}
                """);
        resolver.setResponseMappingTemplate(
                "$util.error(\"denied\", \"Denied\", {\"id\":\"1\"}, {\"retry\":false})");

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertNull(result.data());
        assertEquals(1, result.errors().size());
        assertEquals("denied", result.errors().get(0).message());
        assertEquals("Denied", result.errors().get(0).errorType());
        assertEquals(Map.of("id", "1"), result.errors().get(0).data());
        assertEquals(Map.of("retry", false), result.errors().get(0).errorInfo());
    }

    @Test
    void vtlResponseCanInspectAndSuppressADataSourceError() {
        when(appSync.getDataSource(API_ID, "local")).thenReturn(dataSource("local", DataSourceType.NONE));
        noneInvoker.failure = new AwsException("LocalFailure", "none failed", 500);
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("local");
        resolver.setRequestMappingTemplate("{\"version\":\"2018-05-29\"}");
        resolver.setResponseMappingTemplate("$util.toJson({\"handled\":$ctx.error.type})");

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(Map.of("handled", "LocalFailure"), result.data());
        assertTrue(result.errors().isEmpty());
        noneInvoker.failure = null;
    }

    @Test
    void vtlRequestReturnCannotBypassNonNoneDataSourceValidation() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setDataSourceName("accountDB");
        resolver.setRequestMappingTemplate("#return({\"short\":\"circuit\"})");
        resolver.setResponseMappingTemplate("$util.toJson($ctx.result)");

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(1, result.errors().size());
        assertEquals("UnsupportedOperation", result.errors().get(0).errorType());
        assertTrue(result.errors().get(0).message().contains("VTL"));
        assertTrue(invoker.requests.isEmpty());
    }

    @Test
    void vtlRequestReturnCannotBypassMissingDataSourceValidation() {
        Resolver resolver = resolver(ResolverKind.UNIT, null);
        resolver.setRequestMappingTemplate("#return({\"short\":\"circuit\"})");
        resolver.setResponseMappingTemplate("$util.toJson($ctx.result)");

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertNull(result.data());
        assertEquals(1, result.errors().size());
        assertEquals("UnsupportedOperation", result.errors().get(0).errorType());
        assertTrue(noneInvoker.requests.isEmpty());
    }

    @Test
    void aLaterVtlFunctionIsRejectedBeforeAnyPipelineStageRuns() {
        when(appSync.getFunction(API_ID, "fn1"))
                .thenReturn(function("fn1", "one", "accountDB", "fn1-code"));
        when(appSync.getFunction(API_ID, "fn2")).thenReturn(vtlFunction("fn2"));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("fn1", "fn2")));
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok(null));
        jsRuntime.script("fn1-code", "request", (h, ctx) -> ok(Map.of()));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).message().contains("VTL"),
                result.errors().get(0).message());
        assertEquals("UnsupportedOperation", result.errors().get(0).errorType());
        assertEquals(List.of("pipeline-code#request"), jsRuntime.calls);
        assertTrue(invoker.requests.isEmpty());
    }

    @Test
    void anExplicitVtlRuntimeIsRefusedEvenWithCode() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        Resolver resolver = resolver(ResolverKind.UNIT, "$util.toJson($ctx.args)");
        resolver.setDataSourceName("accountDB");
        Resolver.ResolverRuntime runtime = new Resolver.ResolverRuntime();
        runtime.setName(ResolverRuntimeName.VTL);
        resolver.setRuntime(runtime);

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).message().contains("VTL"));
    }

    @Test
    void aStageWithNeitherCodeNorTemplatesStillPassesThrough() {
        when(appSync.getDataSource(API_ID, "accountDB")).thenReturn(dataSource("accountDB"));
        when(appSync.getFunction(API_ID, "fn1")).thenReturn(function("fn1", "one", "accountDB", null));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("fn1")));
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok(null));
        jsRuntime.script("pipeline-code", "response", (h, ctx) -> ok(ctx.get("result")));
        invoker.answer = List.of(Map.of("id", 1));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        // No code and no templates is not VTL: there is simply nothing to run.
        assertTrue(result.errors().isEmpty());
        assertEquals(List.of(Map.of("id", 1)), result.data());
    }

    @Test
    void aControlPlaneFailureLookingUpAFunctionIsNotReportedAsMissing() {
        when(appSync.getFunction(API_ID, "fn1"))
                .thenThrow(new AwsException("ConcurrentModificationException",
                        "Schema is being modified", 409));
        Resolver resolver = resolver(ResolverKind.PIPELINE, "pipeline-code");
        resolver.setPipelineConfig(Map.of("functions", List.of("fn1")));
        jsRuntime.script("pipeline-code", "request", (h, ctx) -> ok(null));

        ResolverOutcome result = executor.execute(resolver, invocation(Map.of()));

        // Reporting a 409 as "function does not exist" hides the real cause.
        assertEquals(1, result.errors().size());
        assertEquals("Schema is being modified", result.errors().get(0).message());
        assertEquals("ConcurrentModificationException",
                result.errors().get(0).errorType());
    }

    private FunctionConfiguration vtlFunction(String id) {
        FunctionConfiguration fn = new FunctionConfiguration();
        fn.setFunctionId(id);
        fn.setName("vtl-" + id);
        fn.setDataSourceName("accountDB");
        fn.setRequestMappingTemplate("{\"version\":\"2018-05-29\"}");
        fn.setResponseMappingTemplate("$util.toJson($ctx.result)");
        return fn;
    }

    @Test
    void findResolverPropagatesAFailureThatIsNotAMissingResolver() {
        when(appSync.getResolver(anyString(), anyString(), anyString()))
                .thenThrow(new AwsException("ConcurrentModificationException",
                        "Schema is being modified", 409));

        // Treating this as "no resolver" would make the field read as unresolved mid-deploy and
        // quietly answer null.
        assertThrows(AwsException.class, () -> executor.findResolver(API_ID, "Query", "getMessages"));
    }

    @Test
    void findResolverAnswersNullWhenTheFieldHasNone() {
        when(appSync.getResolver(anyString(), anyString(), anyString()))
                .thenThrow(new AwsException("NotFoundException", "Resolver not found", 404));

        // Null is what tells the data fetcher to fall back to reading the field off its source.
        assertNull(executor.findResolver(API_ID, "Query", "unresolved"));
    }
}
