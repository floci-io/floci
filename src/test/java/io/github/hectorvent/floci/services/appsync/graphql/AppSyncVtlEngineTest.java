package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appsync.graphql.util.AppSyncUtil;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AppSyncVtlEngineTest {

    @Inject
    AppSyncVtlEngine engine;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AppSyncVtlContext defaultCtx() {
        return new AppSyncVtlContext(
                Map.of(), Map.of(), Map.of(),
                Map.of("headers", Map.of()),
                Map.of(), null, null, null,
                null, objectMapper
        );
    }

    private AppSyncVtlContext ctxWith(java.util.function.Consumer<AppSyncVtlContext.Builder> customizer) {
        AppSyncVtlContext.Builder builder = AppSyncVtlContext.builder(objectMapper);
        customizer.accept(builder);
        return builder.build();
    }

    @Nested
    class CategoryA_BasicEvaluation {

        @Test
        void a1_nullTemplate() {
            AppSyncVtlResult result = engine.evaluate(null, defaultCtx());
            assertEquals("", result.output());
            assertNull(result.error());
        }

        @Test
        void a2_emptyTemplate() {
            AppSyncVtlResult result = engine.evaluate("", defaultCtx());
            assertEquals("", result.output());
            assertNull(result.error());
        }

        @Test
        void a3_plainText() {
            AppSyncVtlResult result = engine.evaluate("hello", defaultCtx());
            assertEquals("hello", result.output());
        }

        @Test
        void a4_jsonTemplate() {
            AppSyncVtlResult result = engine.evaluate("{\"key\": \"value\"}", defaultCtx());
            assertEquals("{\"key\": \"value\"}", result.output());
        }
    }

    @Nested
    class CategoryB_ContextVariables {

        @Test
        void b1_contextArguments() {
            AppSyncVtlContext ctx = ctxWith(b -> b.arguments(Map.of("id", "123")));
            AppSyncVtlResult result = engine.evaluate("$context.arguments.id", ctx);
            assertEquals("123", result.output());
        }

        @Test
        void b2_ctxArgumentsAlias() {
            AppSyncVtlContext ctx = ctxWith(b -> b.arguments(Map.of("id", "123")));
            AppSyncVtlResult result = engine.evaluate("$ctx.arguments.id", ctx);
            assertEquals("123", result.output());
        }

        @Test
        void b2b_ctxArgsAlias() {
            AppSyncVtlContext ctx = ctxWith(builder -> builder.arguments(Map.of("id", "123")));
            AppSyncVtlResult result = engine.evaluate("$ctx.args.id", ctx);
            assertEquals("123", result.output());
        }

        @Test
        void b3_argsShortcut() {
            AppSyncVtlContext ctx = ctxWith(b -> b.arguments(Map.of("id", "123")));
            AppSyncVtlResult result = engine.evaluate("$args.id", ctx);
            assertEquals("123", result.output());
        }

        @Test
        void b4_contextSource() {
            AppSyncVtlContext ctx = ctxWith(b -> b.source(Map.of("name", "Alice")));
            AppSyncVtlResult result = engine.evaluate("$context.source.name", ctx);
            assertEquals("Alice", result.output());
        }

        @Test
        void b5_sourceAlias() {
            AppSyncVtlContext ctx = ctxWith(b -> b.source(Map.of("name", "Alice")));
            AppSyncVtlResult result = engine.evaluate("$source.name", ctx);
            assertEquals("Alice", result.output());
        }

        @Test
        void b6_contextSourceNullForTopLevel() {
            AppSyncVtlContext ctx = ctxWith(b -> b.source(null));
            AppSyncVtlResult result = engine.evaluate("$util.toJson($context.source)", ctx);
            assertEquals("null", result.output());
        }

        @Test
        void b7_contextResult() {
            AppSyncVtlContext ctx = ctxWith(b -> b.result(Map.of("id", 1)));
            AppSyncVtlResult result = engine.evaluate("$util.toJson($context.result)", ctx);
            assertEquals("{\"id\":1}", result.output());
        }

        @Test
        void b8_contextStash() {
            HashMap<String, Object> stash = new HashMap<>();
            AppSyncVtlContext ctx = ctxWith(b -> b.stash(stash));
            AppSyncVtlResult result = engine.evaluate("$util.qr($stash.put(\"x\",\"y\"))$stash.get(\"x\")", ctx);
            assertEquals("y", result.output());
        }

        @Test
        void b9_contextPrevResult() {
            AppSyncVtlContext ctx = ctxWith(b -> b.prev(Map.of("result", Map.of("id", 1))));
            AppSyncVtlResult result = engine.evaluate("$util.toJson($ctx.prev.result)", ctx);
            assertEquals("{\"id\":1}", result.output());
        }

        @Test
        void b10_contextInfoFieldName() {
            AppSyncVtlContext ctx = ctxWith(b -> b.info(Map.of("fieldName", "getUser")));
            AppSyncVtlResult result = engine.evaluate("$context.info.fieldName", ctx);
            assertEquals("getUser", result.output());
        }

        @Test
        void b11_contextInfoParentTypeName() {
            AppSyncVtlContext ctx = ctxWith(b -> b.info(Map.of("parentTypeName", "Query")));
            AppSyncVtlResult result = engine.evaluate("$context.info.parentTypeName", ctx);
            assertEquals("Query", result.output());
        }

        @Test
        void b12_contextInfoSelectionSetList() {
            AppSyncVtlContext ctx = ctxWith(b -> b.info(Map.of("selectionSetList", List.of("id", "name"))));
            AppSyncVtlResult result = engine.evaluate("$util.toJson($context.info.selectionSetList)", ctx);
            assertEquals("[\"id\",\"name\"]", result.output());
        }

        @Test
        void b13_contextInfoVariables() {
            AppSyncVtlContext ctx = ctxWith(b -> b.info(Map.of("variables", Map.of("id", "1"))));
            AppSyncVtlResult result = engine.evaluate("$util.toJson($context.info.variables)", ctx);
            assertEquals("{\"id\":\"1\"}", result.output());
        }

        @Test
        void b14_contextIdentity() {
            AppSyncVtlContext ctx = ctxWith(b -> b.identity(Map.of("username", "alice")));
            AppSyncVtlResult result = engine.evaluate("$context.identity.username", ctx);
            assertEquals("alice", result.output());
        }

        @Test
        void b15_contextRequestHeaders() {
            AppSyncVtlContext ctx = ctxWith(b -> b.request(Map.of("headers", Map.of("custom", "val"))));
            AppSyncVtlResult result = engine.evaluate("$context.request.headers.custom", ctx);
            assertEquals("val", result.output());
        }

        @Test
        void b16_contextRequestDomainName() {
            AppSyncVtlContext ctx = ctxWith(b -> b.request(Map.of("domainName", "api.example.com")));
            AppSyncVtlResult result = engine.evaluate("$context.request.domainName", ctx);
            assertEquals("api.example.com", result.output());
        }
    }

    @Nested
    class CategoryC_UtilMethods {

        @Test
        void c1_utilTimeNowISO8601() {
            AppSyncVtlResult result = engine.evaluate("$util.time.nowISO8601()", defaultCtx());
            assertNotNull(result.output());
            assertTrue(result.output().toString().contains("T"));
        }

        @Test
        void c2_utilDynamoDBJson() {
            AppSyncVtlResult result = engine.evaluate("$util.dynamodb.toDynamoDBJson(\"hello\")", defaultCtx());
            assertEquals("{\"S\":\"hello\"}", result.output());
        }

        @Test
        void c3_utilDynamoDBWithArgs() {
            AppSyncVtlContext ctx = ctxWith(b -> b.arguments(Map.of("id", "1")));
            AppSyncVtlResult result = engine.evaluate("$util.dynamodb.toDynamoDBJson($args.id)", ctx);
            assertEquals("{\"S\":\"1\"}", result.output());
        }

        @Test
        void c4_utilAutoId() {
            AppSyncVtlResult result = engine.evaluate("$util.autoId()", defaultCtx());
            assertNotNull(result.output());
            assertTrue(result.output().toString().length() > 0);
        }

        @Test
        void c5_utilToJson() {
            AppSyncVtlContext ctx = ctxWith(b -> b.arguments(Map.of("id", "1")));
            AppSyncVtlResult result = engine.evaluate("$util.toJson($args)", ctx);
            assertEquals("{\"id\":\"1\"}", result.output());
        }

        @Test
        void c6_utilParseJson() {
            AppSyncVtlResult result = engine.evaluate("#set($m=$util.parseJson('{\"a\":1}'))$m.a", defaultCtx());
            assertEquals("1", result.output());
        }

        @Test
        void c7_utilMatches() {
            AppSyncVtlResult result = engine.evaluate("#if($util.matches(\"a*b\",\"aaab\"))yes#end", defaultCtx());
            assertEquals("yes", result.output());
        }

        @Test
        void c8_utilIsNull() {
            AppSyncVtlResult result = engine.evaluate("#if($util.isNull(null))yes#end", defaultCtx());
            assertEquals("yes", result.output());
        }

        @Test
        void c9_utilIsNullOrEmpty() {
            AppSyncVtlResult result = engine.evaluate("#if($util.isNullOrEmpty(\"\"))yes#end", defaultCtx());
            assertEquals("yes", result.output());
        }

        @Test
        void c10_utilTypeOf() {
            AppSyncVtlResult result = engine.evaluate("$util.typeOf(\"hello\")", defaultCtx());
            assertEquals("String", result.output());
        }

        @Test
        void c11_utilEscapeJavaScript() {
            AppSyncVtlResult result = engine.evaluate("$util.escapeJavaScript(\"it's\")", defaultCtx());
            assertEquals("it\\'s", result.output());
        }

        @Test
        void c12_utilUrlEncode() {
            AppSyncVtlResult result = engine.evaluate("$util.urlEncode(\"hello world\")", defaultCtx());
            assertEquals("hello+world", result.output());
        }

        @Test
        void c13_utilBase64Encode() {
            AppSyncVtlResult result = engine.evaluate(
                    "#set($s = \"hello\")$util.base64Encode($s.getBytes())", defaultCtx());
            assertEquals("aGVsbG8=", result.output());
        }

        @Test
        void c14_utilStrToUpper() {
            AppSyncVtlResult result = engine.evaluate("$util.str.toUpper(\"hello\")", defaultCtx());
            assertEquals("HELLO", result.output());
        }

        @Test
        void c15_utilMathRoundNum() {
            AppSyncVtlResult result = engine.evaluate("$util.math.roundNum(3.7)", defaultCtx());
            assertEquals("4", result.output());
        }

        @Test
        void c18_utilQrSuppressesOutput() {
            HashMap<String, Object> stash = new HashMap<>();
            AppSyncVtlContext ctx = ctxWith(b -> b.stash(stash));
            AppSyncVtlResult result = engine.evaluate("$util.qr($stash.put(\"k\",\"v\"))", ctx);
            assertEquals("", result.output());
        }

        @Test
        void c19_utilQuietSuppressesOutput() {
            HashMap<String, Object> stash = new HashMap<>();
            AppSyncVtlContext ctx = ctxWith(b -> b.stash(stash));
            AppSyncVtlResult result = engine.evaluate("$util.quiet($stash.put(\"k\",\"v\"))", ctx);
            assertEquals("", result.output());
        }

        @Test
        void c20_utilDefaultIfNull() {
            AppSyncVtlResult result = engine.evaluate("$util.defaultIfNull(null, \"default\")", defaultCtx());
            assertEquals("default", result.output());
        }

        @Test
        void c16_utilListCopyAndRetainAll() {
            AppSyncVtlResult result = engine.evaluate(
                    "#set($list = [\"a\",\"b\",\"c\",\"d\"])" +
                    "#set($keep = [\"a\",\"c\"])" +
                    "$util.toJson($util.list.copyAndRetainAll($list, $keep))",
                    defaultCtx());
            assertEquals("[\"a\",\"c\"]", result.output());
        }

        @Test
        void c17_utilMapCopyAndRemoveAllKeys() {
            AppSyncVtlResult result = engine.evaluate(
                    "#set($map = {})" +
                    "$util.qr($map.put(\"a\", 1))" +
                    "$util.qr($map.put(\"b\", 2))" +
                    "$util.qr($map.put(\"c\", 3))" +
                    "#set($keys = [\"b\"])" +
                    "$util.toJson($util.map.copyAndRemoveAllKeys($map, $keys))",
                    defaultCtx());
            assertEquals("{\"a\":1,\"c\":3}", result.output());
        }

        @Test
        void c21_utilTransformAccessible() {
            AppSyncVtlResult result = engine.evaluate("#set($t = $util.transform)$util.typeOf($t)", defaultCtx());
            assertEquals("Object", result.output());
        }
    }

    @Nested
    class CategoryD_ErrorHandling {

        @Test
        void d1_utilErrorWithMessage() {
            AppSyncVtlResult result = engine.evaluate("$util.error(\"fail\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("fail", result.error().getMessage());
            assertEquals("Unknown", result.error().getErrorType());
        }

        @Test
        void d2_utilErrorWithType() {
            AppSyncVtlResult result = engine.evaluate("$util.error(\"fail\", \"CustomError\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("CustomError", result.error().getErrorType());
        }

        @Test
        void d3_utilErrorWithData() {
            AppSyncVtlResult result = engine.evaluate("$util.error(\"fail\", \"E\", {\"key\":\"val\"})", defaultCtx());
            assertTrue(result.hasError());
            assertNotNull(result.error().getData());
        }

        @Test
        void d4_utilErrorFullForm() {
            AppSyncVtlResult result = engine.evaluate(
                    "$util.error(\"fail\", \"E\", {\"key\":\"val\"}, {\"info\":\"data\"})",
                    defaultCtx());
            assertTrue(result.hasError());
            assertNotNull(result.error().getErrorInfo());
        }

        @Test
        void d5_utilUnauthorized() {
            AppSyncVtlResult result = engine.evaluate("$util.unauthorized()", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("Not Authorized", result.error().getMessage());
            assertEquals("Unauthorized", result.error().getErrorType());
        }

        @Test
        void d6_utilValidateTrue() {
            AppSyncVtlResult result = engine.evaluate("$util.validate(true, \"msg\")", defaultCtx());
            assertFalse(result.hasError());
        }

        @Test
        void d7_utilValidateFalse() {
            AppSyncVtlResult result = engine.evaluate("$util.validate(false, \"msg\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("CustomTemplateException", result.error().getErrorType());
        }

        @Test
        void d8_errorHaltsEvaluation() {
            AppSyncVtlResult result = engine.evaluate("$util.error(\"stop\")#set($x=\"after\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("", result.output());
        }

        @Test
        void d9_errorInConditional() {
            AppSyncVtlResult result = engine.evaluate("#if(true)$util.error(\"fail\")#end", defaultCtx());
            assertTrue(result.hasError());
        }
    }

    @Nested
    class CategoryE_AppendError {

        @Test
        void e1_appendErrorDoesNotHalt() {
            AppSyncVtlResult result = engine.evaluate("$util.appendError(\"warn\")after", defaultCtx());
            assertFalse(result.hasError());
            assertTrue(result.output().toString().contains("after"));
            assertEquals(1, result.appendedErrors().size());
            assertEquals("warn", result.appendedErrors().get(0).get("message"));
        }

        @Test
        void e1_appendErrorDoesNotPopulateContextError() {
            AppSyncVtlContext ctx = defaultCtx();
            AppSyncVtlResult result = engine.evaluate("$util.appendError(\"warn\")$context.error", ctx);
            assertFalse(result.hasError());
            assertNull(ctx.getContextMap().get("error"));
        }

        @Test
        void e2_appendErrorWithType() {
            AppSyncVtlResult result = engine.evaluate("$util.appendError(\"warn\", \"Type\")", defaultCtx());
            assertEquals(1, result.appendedErrors().size());
            assertEquals("Type", result.appendedErrors().get(0).get("type"));
        }

        @Test
        void e3_appendErrorWithData() {
            AppSyncVtlResult result = engine.evaluate("$util.appendError(\"w\", \"T\", {\"k\":\"v\"})", defaultCtx());
            assertEquals(1, result.appendedErrors().size());
            assertNotNull(result.appendedErrors().get(0).get("data"));
        }

        @Test
        void e4_multipleAppendError() {
            AppSyncVtlResult result = engine.evaluate(
                    "$util.appendError(\"e1\")$util.appendError(\"e2\")", defaultCtx());
            assertEquals(2, result.appendedErrors().size());
            assertEquals("e1", result.appendedErrors().get(0).get("message"));
            assertEquals("e2", result.appendedErrors().get(1).get("message"));
        }

        @Test
        void e5_appendErrorPlusError() {
            AppSyncVtlResult result = engine.evaluate(
                    "$util.appendError(\"warn\")$util.error(\"fatal\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals(1, result.appendedErrors().size());
            assertEquals("warn", result.appendedErrors().get(0).get("message"));
            assertEquals("fatal", result.error().getMessage());
        }

        @Test
        void e6_appendErrorWithErrorInfo() {
            AppSyncVtlResult result = engine.evaluate(
                    "$util.appendError(\"w\", \"T\", {\"k\":\"v\"}, {\"info\":\"data\"})",
                    defaultCtx());
            assertEquals(1, result.appendedErrors().size());
            assertNotNull(result.appendedErrors().get(0).get("errorInfo"));
            assertEquals("data", ((Map<?, ?>) result.appendedErrors().get(0).get("errorInfo")).get("info"));
        }
    }

    @Nested
    class CategoryF_ReturnDirective {

        @Test
        void f1_returnWithValue() {
            AppSyncVtlResult result = engine.evaluate("#return({\"id\":\"123\"})", defaultCtx());
            assertFalse(result.hasError());
            assertNotNull(result.output());
        }

        @Test
        void f2_returnWithoutValue() {
            AppSyncVtlResult result = engine.evaluate("#return", defaultCtx());
            assertFalse(result.hasError());
            assertNull(result.output());
        }

        @Test
        void f3_returnHaltsEvaluation() {
            AppSyncVtlResult result = engine.evaluate("before#return(\"mid\")after", defaultCtx());
            assertFalse(result.hasError());
            assertEquals("mid", result.output().toString());
        }

        @Test
        void f4_returnInIf() {
            AppSyncVtlResult result = engine.evaluate("#if(true)#return(\"early\")#end", defaultCtx());
            assertFalse(result.hasError());
            assertEquals("early", result.output());
        }

        @Test
        void f5_returnInForeach() {
            AppSyncVtlResult result = engine.evaluate(
                    "#foreach($i in [1,2,3])#if($i==2)#return(\"stop\")#end$i#end",
                    defaultCtx());
            assertFalse(result.hasError());
            assertEquals("stop", result.output().toString());
        }
    }

    @Nested
    class CategoryG_AuthType {

        @Test
        void g1_apiKeyAuth() {
            AppSyncVtlContext ctx = ctxWith(b -> b.authType("API Key Authorization"));
            AppSyncVtlResult result = engine.evaluate("$util.authType()", ctx);
            assertEquals("API Key Authorization", result.output());
        }

        @Test
        void g2_iamAuth() {
            AppSyncVtlContext ctx = ctxWith(b -> b.authType("IAM Authorization"));
            AppSyncVtlResult result = engine.evaluate("$util.authType()", ctx);
            assertEquals("IAM Authorization", result.output());
        }

        @Test
        void g3_cognitoAuth() {
            AppSyncVtlContext ctx = ctxWith(b -> b.authType("User Pool Authorization"));
            AppSyncVtlResult result = engine.evaluate("$util.authType()", ctx);
            assertEquals("User Pool Authorization", result.output());
        }

        @Test
        void g4_oidcAuth() {
            AppSyncVtlContext ctx = ctxWith(b -> b.authType("Open ID Connect Authorization"));
            AppSyncVtlResult result = engine.evaluate("$util.authType()", ctx);
            assertEquals("Open ID Connect Authorization", result.output());
        }

        @Test
        void g5_noAuthType() {
            AppSyncVtlResult result = engine.evaluate("$util.authType()", defaultCtx());
            assertEquals("$util.authType()", result.output());
        }
    }

    @Nested
    class CategoryH_VelocityDirectives {

        @Test
        void h1_setDirective() {
            AppSyncVtlResult result = engine.evaluate("#set($x = \"hello\")$x", defaultCtx());
            assertEquals("hello", result.output());
        }

        @Test
        void h2_ifElse() {
            AppSyncVtlResult result = engine.evaluate("#if(true)yes#{else}no#end", defaultCtx());
            assertEquals("yes", result.output());
        }

        @Test
        void h3_foreach() {
            AppSyncVtlResult result = engine.evaluate("#foreach($i in [1,2,3])$i #end", defaultCtx());
            assertEquals("1 2 3 ", result.output());
        }

        @Test
        void h4_foreachHasNext() {
            AppSyncVtlResult result = engine.evaluate(
                    "#foreach($i in [1,2])$i$foreach.hasNext#end", defaultCtx());
            assertEquals("1true2false", result.output());
        }

        @Test
        void h5_nullReference() {
            AppSyncVtlResult result = engine.evaluate("$context.nonexistent", defaultCtx());
            assertEquals("$context.nonexistent", result.output());
        }

        @Test
        void h5b_silentReferenceOnNull() {
            AppSyncVtlResult result = engine.evaluate("$!context.error", defaultCtx());
            assertEquals("", result.output());
        }

        @Test
        void h5c_silentReferenceOnNonexistent() {
            AppSyncVtlResult result = engine.evaluate("$!context.nonexistent", defaultCtx());
            assertEquals("", result.output());
        }

        @Test
        void h6_mapPut() {
            AppSyncVtlResult result = engine.evaluate(
                    "#set($m={})$util.qr($m.put(\"k\",\"v\"))$m.k", defaultCtx());
            assertEquals("v", result.output());
        }

        @Test
        void h7_nestedMaps() {
            AppSyncVtlResult result = engine.evaluate(
                    "#set($m={\"a\":{\"b\":\"c\"}})$m.a.b", defaultCtx());
            assertEquals("c", result.output());
        }
    }

    @Nested
    class CategoryI_PipelineSimulation {

        @Test
        void i1_stashPersistsAcrossEvaluations() {
            HashMap<String, Object> stash = new HashMap<>();
            AppSyncVtlContext ctx1 = ctxWith(b -> b.stash(stash));
            engine.evaluate("$util.qr($stash.put(\"x\",\"y\"))", ctx1);

            AppSyncVtlContext ctx2 = ctxWith(b -> b.stash(stash));
            AppSyncVtlResult result = engine.evaluate("$stash.get(\"x\")", ctx2);
            assertEquals("y", result.output());
        }

        @Test
        void i2_prevResultCarriesBetweenEvaluations() {
            AppSyncVtlContext ctx1 = ctxWith(b -> b.result("output1"));
            AppSyncVtlResult result1 = engine.evaluate("$util.toJson($context.result)", ctx1);

            AppSyncVtlContext ctx2 = ctxWith(b -> b.prev(Map.of("result", "output1")));
            AppSyncVtlResult result2 = engine.evaluate("$ctx.prev.result", ctx2);
            assertEquals("output1", result2.output());
        }

        @Test
        void i3_stashIsolationBetweenPipelines() {
            HashMap<String, Object> stash1 = new HashMap<>();
            HashMap<String, Object> stash2 = new HashMap<>();

            AppSyncVtlContext ctx1 = ctxWith(b -> b.stash(stash1));
            engine.evaluate("$util.qr($stash.put(\"key\",\"val1\"))", ctx1);

            AppSyncVtlContext ctx2 = ctxWith(b -> b.stash(stash2));
            engine.evaluate("$util.qr($stash.put(\"key\",\"val2\"))", ctx2);

            assertEquals("val1", stash1.get("key"));
            assertEquals("val2", stash2.get("key"));
            assertNotSame(stash1, stash2);
        }

        @Test
        void i4_stashPersistsBetweenRequestResponse() {
            HashMap<String, Object> stash = new HashMap<>();

            AppSyncVtlContext reqCtx = ctxWith(b -> b.stash(stash));
            engine.evaluate("$util.qr($stash.put(\"key\",\"val\"))", reqCtx);

            AppSyncVtlContext resCtx = ctxWith(b -> b.stash(stash).result("data"));
            AppSyncVtlResult result = engine.evaluate("$stash.get(\"key\")", resCtx);
            assertEquals("val", result.output());
        }
    }

    @Nested
    class CategoryJ_EdgeCases {

        @Test
        void j1_unicodeInTemplate() {
            AppSyncVtlResult result = engine.evaluate("{\"name\": \"日本語\"}", defaultCtx());
            assertEquals("{\"name\": \"日本語\"}", result.output());
        }

        @Test
        void j3_specialCharactersInArgs() {
            AppSyncVtlContext ctx = ctxWith(b -> b.arguments(Map.of("name", "O'Brien")));
            AppSyncVtlResult result = engine.evaluate("$args.name", ctx);
            assertEquals("O'Brien", result.output());
        }

        @Test
        void j4_deeplyNestedContext() {
            AppSyncVtlContext ctx = ctxWith(b -> b.arguments(Map.of("a", Map.of("b", Map.of("c", Map.of("d", "deep"))))));
            AppSyncVtlResult result = engine.evaluate("$context.arguments.a.b.c.d", ctx);
            assertEquals("deep", result.output());
        }

        @Test
        void j7_contextErrorInitiallyNull() {
            AppSyncVtlResult result = engine.evaluate("$context.error", defaultCtx());
            assertEquals("$context.error", result.output());
        }

        @Test
        void j8_contextErrorNotPopulatedByAppendError() {
            AppSyncVtlContext ctx = defaultCtx();
            AppSyncVtlResult result = engine.evaluate(
                    "$util.appendError(\"msg\", \"Type\")$context.error", ctx);
            assertNull(ctx.getContextMap().get("error"));
        }

        @Test
        void j9_multiValueHeaders() {
            AppSyncVtlContext ctx = ctxWith(b -> b.request(
                    Map.of("headers", Map.of("custom", List.of("val1", "val2")))));
            AppSyncVtlResult result = engine.evaluate("$context.request.headers.custom[0]", ctx);
            assertEquals("val1", result.output());
        }

        @Test
        void j10_contextResultNullInRequestTemplate() {
            AppSyncVtlContext ctx = defaultCtx();
            AppSyncVtlResult result = engine.evaluate("$context.result", ctx);
            assertEquals("$context.result", result.output());
        }

        @Test
        void j11_prevDefaultIsNull() {
            AppSyncVtlResult result = engine.evaluate("$util.toJson($ctx.prev)", defaultCtx());
            assertEquals("null", result.output());
        }

        @Test
        void j11b_prevUndefinedRendersAsLiteral() {
            AppSyncVtlResult result = engine.evaluate("$ctx.prev", defaultCtx());
            assertEquals("$ctx.prev", result.output());
        }

        @Test
        void j11c_prevResultUndefinedRendersAsLiteral() {
            AppSyncVtlResult result = engine.evaluate("$ctx.prev.result", defaultCtx());
            assertEquals("$ctx.prev.result", result.output());
        }

        @Test
        void j2_largeTemplateWithLoops() {
            StringBuilder sb = new StringBuilder();
            sb.append("#set($total = 0)");
            sb.append("#foreach($i in [1,2,3,4,5])");
            sb.append("#set($total = $total + $i)");
            sb.append("#end");
            sb.append("$total");
            AppSyncVtlResult result = engine.evaluate(sb.toString(), defaultCtx());
            assertEquals("15", result.output());
        }

        @Test
        void j5_toJsonExcludesSelectionSetFields() {
            Map<String, Object> info = Map.of(
                    "fieldName", "getPost",
                    "parentTypeName", "Query",
                    "variables", Map.of("id", "1"),
                    "selectionSetList", List.of("id", "title"),
                    "selectionSetGraphQL", "{ id title }"
            );
            AppSyncVtlContext ctx = ctxWith(b -> b.info(info));
            AppSyncVtlResult result = engine.evaluate("$util.toJson($context.info)", ctx);
            String output = result.output().toString();
            assertTrue(output.contains("fieldName"));
            assertTrue(output.contains("parentTypeName"));
            assertTrue(output.contains("variables"));
            assertFalse(output.contains("selectionSetList"));
            assertFalse(output.contains("selectionSetGraphQL"));
        }

        @Test
        void j6_vtlSyntaxErrorPropagated() {
            assertThrows(Exception.class, () ->
                    engine.evaluate("#if(true)", defaultCtx()));
        }
    }

    // ────────── Sandbox: reflection escapes, and runaway loop/output limits ──────────

    @Nested
    class CategoryK_Sandbox {

        @Test
        void k1_getClassLoaderIsNotReachable() {
            // When SecureUberspector blocks a method call, Velocity (non-strict mode, the default
            // here) renders the literal, unresolved reference text instead of throwing or
            // evaluating it. Getting the raw template text back verbatim (rather than an actual
            // ClassLoader instance's toString) proves the call was blocked.
            String template = "$util.getClass().getClassLoader()";
            AppSyncVtlResult result = engine.evaluate(template, defaultCtx());
            assertEquals(template, result.output(),
                    "expected getClassLoader() to be blocked by SecureUberspector and rendered as "
                            + "an unresolved literal reference, but got: " + result.output());
        }

        @Test
        void k2_classForNameIsNotReachable() {
            AppSyncVtlResult result = engine.evaluate(
                    "$util.getClass().forName('java.lang.System').getName()", defaultCtx());
            assertNotEquals("java.lang.System", result.output(),
                    "expected Class.forName(...) to be blocked by SecureUberspector, but it resolved: "
                            + result.output());
        }

        @Test
        void k3_runtimeClassIsNotLoadableByName() {
            AppSyncVtlResult result = engine.evaluate(
                    "$util.getClass().forName('java.lang.Runtime').getName()", defaultCtx());
            assertNotEquals("java.lang.Runtime", result.output(),
                    "expected Class.forName('java.lang.Runtime') to be blocked, but it resolved: "
                            + result.output());
        }

        @Test
        void k4_processBuilderClassIsNotLoadableByName() {
            AppSyncVtlResult result = engine.evaluate(
                    "$util.getClass().forName('java.lang.ProcessBuilder').getName()", defaultCtx());
            assertNotEquals("java.lang.ProcessBuilder", result.output(),
                    "expected Class.forName('java.lang.ProcessBuilder') to be blocked, but it resolved: "
                            + result.output());
        }

        @Test
        void k5_getClassStillPermitsGetName() {
            // getName() on a Class receiver must remain permitted (SecureUberspector's one
            // exception), so ordinary reflection-free VTL idioms relying on it keep working.
            AppSyncVtlResult result = engine.evaluate("$util.getClass().getName()", defaultCtx());
            assertTrue(result.output().toString().contains("AppSyncUtil"),
                    "expected getName() on Class to still work, got: " + result.output());
        }

        @Test
        void k6_foreachLoopCountIsBounded() {
            // 50,000 requested iterations, each writing a single character. With no cap this
            // renders 50,000 characters; the configured default
            // (AppSyncServiceConfig.vtlMaxLoops = 10000) must cap the loop well before that.
            String template = "#foreach($i in [1..50000])x#end";
            AppSyncVtlResult result = engine.evaluate(template, defaultCtx());
            assertEquals(10000, result.output().toString().length(),
                    "expected #foreach to be capped at the configured vtlMaxLoops, but rendered "
                            + result.output().toString().length() + " characters");
        }

        @Test
        void k7_outputSizeIsBounded() {
            // 21 doublings of a 2-character seed produce roughly 4 million characters, comfortably
            // over the default 1,048,576-character output cap, in only 21 loop iterations (well
            // under the 10,000-iteration loop cap), so this exercises the output limit
            // specifically.
            String template = "#set($s = \"xy\")#foreach($i in [1..21])#set($s = \"$s$s\")#end$s";
            assertThrows(RuntimeException.class, () -> engine.evaluate(template, defaultCtx()),
                    "expected output exceeding the configured vtlMaxOutputChars to throw");
        }
    }
}
