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
            var result = engine.evaluate(null, defaultCtx());
            assertEquals("", result.output());
            assertNull(result.error());
        }

        @Test
        void a2_emptyTemplate() {
            var result = engine.evaluate("", defaultCtx());
            assertEquals("", result.output());
            assertNull(result.error());
        }

        @Test
        void a3_plainText() {
            var result = engine.evaluate("hello", defaultCtx());
            assertEquals("hello", result.output());
        }

        @Test
        void a4_jsonTemplate() {
            var result = engine.evaluate("{\"key\": \"value\"}", defaultCtx());
            assertEquals("{\"key\": \"value\"}", result.output());
        }
    }

    @Nested
    class CategoryB_ContextVariables {

        @Test
        void b1_contextArguments() {
            var ctx = ctxWith(b -> b.arguments(Map.of("id", "123")));
            var result = engine.evaluate("$context.arguments.id", ctx);
            assertEquals("123", result.output());
        }

        @Test
        void b2_ctxArgumentsAlias() {
            var ctx = ctxWith(b -> b.arguments(Map.of("id", "123")));
            var result = engine.evaluate("$ctx.arguments.id", ctx);
            assertEquals("123", result.output());
        }

        @Test
        void b3_argsShortcut() {
            var ctx = ctxWith(b -> b.arguments(Map.of("id", "123")));
            var result = engine.evaluate("$args.id", ctx);
            assertEquals("123", result.output());
        }

        @Test
        void b4_contextSource() {
            var ctx = ctxWith(b -> b.source(Map.of("name", "Alice")));
            var result = engine.evaluate("$context.source.name", ctx);
            assertEquals("Alice", result.output());
        }

        @Test
        void b5_sourceAlias() {
            var ctx = ctxWith(b -> b.source(Map.of("name", "Alice")));
            var result = engine.evaluate("$source.name", ctx);
            assertEquals("Alice", result.output());
        }

        @Test
        void b6_contextSourceEmptyForTopLevel() {
            var ctx = ctxWith(b -> b.source(Map.of()));
            var result = engine.evaluate("$util.toJson($context.source)", ctx);
            assertEquals("{}", result.output());
        }

        @Test
        void b7_contextResult() {
            var ctx = ctxWith(b -> b.result(Map.of("id", 1)));
            var result = engine.evaluate("$util.toJson($context.result)", ctx);
            assertEquals("{\"id\":1}", result.output());
        }

        @Test
        void b8_contextStash() {
            var stash = new HashMap<String, Object>();
            var ctx = ctxWith(b -> b.stash(stash));
            var result = engine.evaluate("$util.qr($stash.put(\"x\",\"y\"))$stash.get(\"x\")", ctx);
            assertEquals("y", result.output());
        }

        @Test
        void b9_contextPrevResult() {
            var ctx = ctxWith(b -> b.prev(Map.of("result", Map.of("id", 1))));
            var result = engine.evaluate("$util.toJson($ctx.prev.result)", ctx);
            assertEquals("{\"id\":1}", result.output());
        }

        @Test
        void b10_contextInfoFieldName() {
            var ctx = ctxWith(b -> b.info(Map.of("fieldName", "getUser")));
            var result = engine.evaluate("$context.info.fieldName", ctx);
            assertEquals("getUser", result.output());
        }

        @Test
        void b11_contextInfoParentTypeName() {
            var ctx = ctxWith(b -> b.info(Map.of("parentTypeName", "Query")));
            var result = engine.evaluate("$context.info.parentTypeName", ctx);
            assertEquals("Query", result.output());
        }

        @Test
        void b12_contextInfoSelectionSetList() {
            var ctx = ctxWith(b -> b.info(Map.of("selectionSetList", List.of("id", "name"))));
            var result = engine.evaluate("$util.toJson($context.info.selectionSetList)", ctx);
            assertEquals("[\"id\",\"name\"]", result.output());
        }

        @Test
        void b13_contextInfoVariables() {
            var ctx = ctxWith(b -> b.info(Map.of("variables", Map.of("id", "1"))));
            var result = engine.evaluate("$util.toJson($context.info.variables)", ctx);
            assertEquals("{\"id\":\"1\"}", result.output());
        }

        @Test
        void b14_contextIdentity() {
            var ctx = ctxWith(b -> b.identity(Map.of("username", "alice")));
            var result = engine.evaluate("$context.identity.username", ctx);
            assertEquals("alice", result.output());
        }

        @Test
        void b15_contextRequestHeaders() {
            var ctx = ctxWith(b -> b.request(Map.of("headers", Map.of("custom", "val"))));
            var result = engine.evaluate("$context.request.headers.custom", ctx);
            assertEquals("val", result.output());
        }

        @Test
        void b16_contextRequestDomainName() {
            var ctx = ctxWith(b -> b.request(Map.of("domainName", "api.example.com")));
            var result = engine.evaluate("$context.request.domainName", ctx);
            assertEquals("api.example.com", result.output());
        }
    }

    @Nested
    class CategoryC_UtilMethods {

        @Test
        void c1_utilTimeNowISO8601() {
            var result = engine.evaluate("$util.time.nowISO8601()", defaultCtx());
            assertNotNull(result.output());
            assertTrue(result.output().toString().contains("T"));
        }

        @Test
        void c2_utilDynamoDBJson() {
            var result = engine.evaluate("$util.dynamodb.toDynamoDBJson(\"hello\")", defaultCtx());
            assertEquals("{\"S\":\"hello\"}", result.output());
        }

        @Test
        void c3_utilDynamoDBWithArgs() {
            var ctx = ctxWith(b -> b.arguments(Map.of("id", "1")));
            var result = engine.evaluate("$util.dynamodb.toDynamoDBJson($args.id)", ctx);
            assertEquals("{\"S\":\"1\"}", result.output());
        }

        @Test
        void c4_utilAutoId() {
            var result = engine.evaluate("$util.autoId()", defaultCtx());
            assertNotNull(result.output());
            assertTrue(result.output().toString().length() > 0);
        }

        @Test
        void c5_utilToJson() {
            var ctx = ctxWith(b -> b.arguments(Map.of("id", "1")));
            var result = engine.evaluate("$util.toJson($args)", ctx);
            assertEquals("{\"id\":\"1\"}", result.output());
        }

        @Test
        void c6_utilParseJson() {
            var result = engine.evaluate("#set($m=$util.parseJson('{\"a\":1}'))$m.a", defaultCtx());
            assertEquals("1", result.output());
        }

        @Test
        void c7_utilMatches() {
            var result = engine.evaluate("#if($util.matches(\"a*b\",\"aaab\"))yes#end", defaultCtx());
            assertEquals("yes", result.output());
        }

        @Test
        void c8_utilIsNull() {
            var result = engine.evaluate("#if($util.isNull(null))yes#end", defaultCtx());
            assertEquals("yes", result.output());
        }

        @Test
        void c9_utilIsNullOrEmpty() {
            var result = engine.evaluate("#if($util.isNullOrEmpty(\"\"))yes#end", defaultCtx());
            assertEquals("yes", result.output());
        }

        @Test
        void c10_utilTypeOf() {
            var result = engine.evaluate("$util.typeOf(\"hello\")", defaultCtx());
            assertEquals("String", result.output());
        }

        @Test
        void c11_utilEscapeJavaScript() {
            var result = engine.evaluate("$util.escapeJavaScript(\"it's\")", defaultCtx());
            assertEquals("it\\'s", result.output());
        }

        @Test
        void c12_utilUrlEncode() {
            var result = engine.evaluate("$util.urlEncode(\"hello world\")", defaultCtx());
            assertEquals("hello+world", result.output());
        }

        @Test
        void c13_utilBase64Encode() {
            var result = engine.evaluate(
                    "#set($s = \"hello\")$util.base64Encode($s.getBytes())", defaultCtx());
            assertEquals("aGVsbG8=", result.output());
        }

        @Test
        void c14_utilStrToUpper() {
            var result = engine.evaluate("$util.str.toUpper(\"hello\")", defaultCtx());
            assertEquals("HELLO", result.output());
        }

        @Test
        void c15_utilMathRoundNum() {
            var result = engine.evaluate("$util.math.roundNum(3.7)", defaultCtx());
            assertEquals("4", result.output());
        }

        @Test
        void c18_utilQrSuppressesOutput() {
            var stash = new HashMap<String, Object>();
            var ctx = ctxWith(b -> b.stash(stash));
            var result = engine.evaluate("$util.qr($stash.put(\"k\",\"v\"))", ctx);
            assertEquals("", result.output());
        }

        @Test
        void c19_utilQuietSuppressesOutput() {
            var stash = new HashMap<String, Object>();
            var ctx = ctxWith(b -> b.stash(stash));
            var result = engine.evaluate("$util.quiet($stash.put(\"k\",\"v\"))", ctx);
            assertEquals("", result.output());
        }

        @Test
        void c20_utilDefaultIfNull() {
            var result = engine.evaluate("$util.defaultIfNull(null, \"default\")", defaultCtx());
            assertEquals("default", result.output());
        }

        @Test
        void c16_utilListCopyAndRetainAll() {
            var result = engine.evaluate(
                    "#set($list = [\"a\",\"b\",\"c\",\"d\"])" +
                    "#set($keep = [\"a\",\"c\"])" +
                    "$util.toJson($util.list.copyAndRetainAll($list, $keep))",
                    defaultCtx());
            assertEquals("[\"a\",\"c\"]", result.output());
        }

        @Test
        void c17_utilMapCopyAndRemoveAllKeys() {
            var result = engine.evaluate(
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
            var result = engine.evaluate("#set($t = $util.transform)$util.typeOf($t)", defaultCtx());
            assertEquals("Object", result.output());
        }
    }

    @Nested
    class CategoryD_ErrorHandling {

        @Test
        void d1_utilErrorWithMessage() {
            var result = engine.evaluate("$util.error(\"fail\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("fail", result.error().getMessage());
            assertEquals("Unknown", result.error().getErrorType());
        }

        @Test
        void d2_utilErrorWithType() {
            var result = engine.evaluate("$util.error(\"fail\", \"CustomError\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("CustomError", result.error().getErrorType());
        }

        @Test
        void d3_utilErrorWithData() {
            var result = engine.evaluate("$util.error(\"fail\", \"E\", {\"key\":\"val\"})", defaultCtx());
            assertTrue(result.hasError());
            assertNotNull(result.error().getData());
        }

        @Test
        void d4_utilErrorFullForm() {
            var result = engine.evaluate(
                    "$util.error(\"fail\", \"E\", {\"key\":\"val\"}, {\"info\":\"data\"})",
                    defaultCtx());
            assertTrue(result.hasError());
            assertNotNull(result.error().getErrorInfo());
        }

        @Test
        void d5_utilUnauthorized() {
            var result = engine.evaluate("$util.unauthorized()", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("Not Authorized", result.error().getMessage());
            assertEquals("Unauthorized", result.error().getErrorType());
        }

        @Test
        void d6_utilValidateTrue() {
            var result = engine.evaluate("$util.validate(true, \"msg\")", defaultCtx());
            assertFalse(result.hasError());
        }

        @Test
        void d7_utilValidateFalse() {
            var result = engine.evaluate("$util.validate(false, \"msg\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("CustomTemplateException", result.error().getErrorType());
        }

        @Test
        void d8_errorHaltsEvaluation() {
            var result = engine.evaluate("$util.error(\"stop\")#set($x=\"after\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals("", result.output());
        }

        @Test
        void d9_errorInConditional() {
            var result = engine.evaluate("#if(true)$util.error(\"fail\")#end", defaultCtx());
            assertTrue(result.hasError());
        }
    }

    @Nested
    class CategoryE_AppendError {

        @Test
        void e1_appendErrorDoesNotHalt() {
            var result = engine.evaluate("$util.appendError(\"warn\")after", defaultCtx());
            assertFalse(result.hasError());
            assertTrue(result.output().toString().contains("after"));
            assertEquals(1, result.appendedErrors().size());
            assertEquals("warn", result.appendedErrors().get(0).get("message"));
        }

        @Test
        void e1_appendErrorDoesNotPopulateContextError() {
            var ctx = defaultCtx();
            var result = engine.evaluate("$util.appendError(\"warn\")$context.error", ctx);
            assertFalse(result.hasError());
            assertNull(ctx.getContextMap().get("error"));
        }

        @Test
        void e2_appendErrorWithType() {
            var result = engine.evaluate("$util.appendError(\"warn\", \"Type\")", defaultCtx());
            assertEquals(1, result.appendedErrors().size());
            assertEquals("Type", result.appendedErrors().get(0).get("type"));
        }

        @Test
        void e3_appendErrorWithData() {
            var result = engine.evaluate("$util.appendError(\"w\", \"T\", {\"k\":\"v\"})", defaultCtx());
            assertEquals(1, result.appendedErrors().size());
            assertNotNull(result.appendedErrors().get(0).get("data"));
        }

        @Test
        void e4_multipleAppendError() {
            var result = engine.evaluate(
                    "$util.appendError(\"e1\")$util.appendError(\"e2\")", defaultCtx());
            assertEquals(2, result.appendedErrors().size());
            assertEquals("e1", result.appendedErrors().get(0).get("message"));
            assertEquals("e2", result.appendedErrors().get(1).get("message"));
        }

        @Test
        void e5_appendErrorPlusError() {
            var result = engine.evaluate(
                    "$util.appendError(\"warn\")$util.error(\"fatal\")", defaultCtx());
            assertTrue(result.hasError());
            assertEquals(1, result.appendedErrors().size());
            assertEquals("warn", result.appendedErrors().get(0).get("message"));
            assertEquals("fatal", result.error().getMessage());
        }

        @Test
        void e6_appendErrorWithErrorInfo() {
            var result = engine.evaluate(
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
            var result = engine.evaluate("#return({\"id\":\"123\"})", defaultCtx());
            assertFalse(result.hasError());
            assertNotNull(result.output());
        }

        @Test
        void f2_returnWithoutValue() {
            var result = engine.evaluate("#return", defaultCtx());
            assertFalse(result.hasError());
            assertNull(result.output());
        }

        @Test
        void f3_returnHaltsEvaluation() {
            var result = engine.evaluate("before#return(\"mid\")after", defaultCtx());
            assertFalse(result.hasError());
            assertEquals("mid", result.output().toString());
        }

        @Test
        void f4_returnInIf() {
            var result = engine.evaluate("#if(true)#return(\"early\")#end", defaultCtx());
            assertFalse(result.hasError());
            assertEquals("early", result.output());
        }

        @Test
        void f5_returnInForeach() {
            var result = engine.evaluate(
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
            var ctx = ctxWith(b -> b.authType("API Key Authorization"));
            var result = engine.evaluate("$util.authType()", ctx);
            assertEquals("API Key Authorization", result.output());
        }

        @Test
        void g2_iamAuth() {
            var ctx = ctxWith(b -> b.authType("IAM Authorization"));
            var result = engine.evaluate("$util.authType()", ctx);
            assertEquals("IAM Authorization", result.output());
        }

        @Test
        void g3_cognitoAuth() {
            var ctx = ctxWith(b -> b.authType("User Pool Authorization"));
            var result = engine.evaluate("$util.authType()", ctx);
            assertEquals("User Pool Authorization", result.output());
        }

        @Test
        void g4_oidcAuth() {
            var ctx = ctxWith(b -> b.authType("Open ID Connect Authorization"));
            var result = engine.evaluate("$util.authType()", ctx);
            assertEquals("Open ID Connect Authorization", result.output());
        }

        @Test
        void g5_noAuthType() {
            var result = engine.evaluate("$util.authType()", defaultCtx());
            assertEquals("$util.authType()", result.output());
        }
    }

    @Nested
    class CategoryH_VelocityDirectives {

        @Test
        void h1_setDirective() {
            var result = engine.evaluate("#set($x = \"hello\")$x", defaultCtx());
            assertEquals("hello", result.output());
        }

        @Test
        void h2_ifElse() {
            var result = engine.evaluate("#if(true)yes#{else}no#end", defaultCtx());
            assertEquals("yes", result.output());
        }

        @Test
        void h3_foreach() {
            var result = engine.evaluate("#foreach($i in [1,2,3])$i #end", defaultCtx());
            assertEquals("1 2 3 ", result.output());
        }

        @Test
        void h4_foreachHasNext() {
            var result = engine.evaluate(
                    "#foreach($i in [1,2])$i$foreach.hasNext#end", defaultCtx());
            assertEquals("1true2false", result.output());
        }

        @Test
        void h5_nullReference() {
            var result = engine.evaluate("$context.nonexistent", defaultCtx());
            assertEquals("$context.nonexistent", result.output());
        }

        @Test
        void h5b_silentReferenceOnNull() {
            var result = engine.evaluate("$!context.error", defaultCtx());
            assertEquals("", result.output());
        }

        @Test
        void h5c_silentReferenceOnNonexistent() {
            var result = engine.evaluate("$!context.nonexistent", defaultCtx());
            assertEquals("", result.output());
        }

        @Test
        void h6_mapPut() {
            var result = engine.evaluate(
                    "#set($m={})$util.qr($m.put(\"k\",\"v\"))$m.k", defaultCtx());
            assertEquals("v", result.output());
        }

        @Test
        void h7_nestedMaps() {
            var result = engine.evaluate(
                    "#set($m={\"a\":{\"b\":\"c\"}})$m.a.b", defaultCtx());
            assertEquals("c", result.output());
        }
    }

    @Nested
    class CategoryI_PipelineSimulation {

        @Test
        void i1_stashPersistsAcrossEvaluations() {
            var stash = new HashMap<String, Object>();
            var ctx1 = ctxWith(b -> b.stash(stash));
            engine.evaluate("$util.qr($stash.put(\"x\",\"y\"))", ctx1);

            var ctx2 = ctxWith(b -> b.stash(stash));
            var result = engine.evaluate("$stash.get(\"x\")", ctx2);
            assertEquals("y", result.output());
        }

        @Test
        void i2_prevResultCarriesBetweenEvaluations() {
            var ctx1 = ctxWith(b -> b.result("output1"));
            var result1 = engine.evaluate("$util.toJson($context.result)", ctx1);

            var ctx2 = ctxWith(b -> b.prev(Map.of("result", "output1")));
            var result2 = engine.evaluate("$ctx.prev.result", ctx2);
            assertEquals("output1", result2.output());
        }

        @Test
        void i3_stashIsolationBetweenPipelines() {
            var stash1 = new HashMap<String, Object>();
            var stash2 = new HashMap<String, Object>();

            var ctx1 = ctxWith(b -> b.stash(stash1));
            engine.evaluate("$util.qr($stash.put(\"key\",\"val1\"))", ctx1);

            var ctx2 = ctxWith(b -> b.stash(stash2));
            engine.evaluate("$util.qr($stash.put(\"key\",\"val2\"))", ctx2);

            assertEquals("val1", stash1.get("key"));
            assertEquals("val2", stash2.get("key"));
            assertNotSame(stash1, stash2);
        }

        @Test
        void i4_stashPersistsBetweenRequestResponse() {
            var stash = new HashMap<String, Object>();

            var reqCtx = ctxWith(b -> b.stash(stash));
            engine.evaluate("$util.qr($stash.put(\"key\",\"val\"))", reqCtx);

            var resCtx = ctxWith(b -> b.stash(stash).result("data"));
            var result = engine.evaluate("$stash.get(\"key\")", resCtx);
            assertEquals("val", result.output());
        }
    }

    @Nested
    class CategoryJ_EdgeCases {

        @Test
        void j1_unicodeInTemplate() {
            var result = engine.evaluate("{\"name\": \"日本語\"}", defaultCtx());
            assertEquals("{\"name\": \"日本語\"}", result.output());
        }

        @Test
        void j3_specialCharactersInArgs() {
            var ctx = ctxWith(b -> b.arguments(Map.of("name", "O'Brien")));
            var result = engine.evaluate("$args.name", ctx);
            assertEquals("O'Brien", result.output());
        }

        @Test
        void j4_deeplyNestedContext() {
            var ctx = ctxWith(b -> b.arguments(Map.of("a", Map.of("b", Map.of("c", Map.of("d", "deep"))))));
            var result = engine.evaluate("$context.arguments.a.b.c.d", ctx);
            assertEquals("deep", result.output());
        }

        @Test
        void j7_contextErrorInitiallyNull() {
            var result = engine.evaluate("$context.error", defaultCtx());
            assertEquals("$context.error", result.output());
        }

        @Test
        void j8_contextErrorNotPopulatedByAppendError() {
            var ctx = defaultCtx();
            var result = engine.evaluate(
                    "$util.appendError(\"msg\", \"Type\")$context.error", ctx);
            assertNull(ctx.getContextMap().get("error"));
        }

        @Test
        void j9_multiValueHeaders() {
            var ctx = ctxWith(b -> b.request(
                    Map.of("headers", Map.of("custom", List.of("val1", "val2")))));
            var result = engine.evaluate("$context.request.headers.custom[0]", ctx);
            assertEquals("val1", result.output());
        }

        @Test
        void j10_contextResultNullInRequestTemplate() {
            var ctx = defaultCtx();
            var result = engine.evaluate("$context.result", ctx);
            assertEquals("$context.result", result.output());
        }

        @Test
        void j11_prevDefaultIsNull() {
            var result = engine.evaluate("$util.toJson($ctx.prev)", defaultCtx());
            assertEquals("null", result.output());
        }

        @Test
        void j11b_prevUndefinedRendersAsLiteral() {
            var result = engine.evaluate("$ctx.prev", defaultCtx());
            assertEquals("$ctx.prev", result.output());
        }

        @Test
        void j11c_prevResultUndefinedRendersAsLiteral() {
            var result = engine.evaluate("$ctx.prev.result", defaultCtx());
            assertEquals("$ctx.prev.result", result.output());
        }

        @Test
        void j2_largeTemplateWithLoops() {
            var sb = new StringBuilder();
            sb.append("#set($total = 0)");
            sb.append("#foreach($i in [1,2,3,4,5])");
            sb.append("#set($total = $total + $i)");
            sb.append("#end");
            sb.append("$total");
            var result = engine.evaluate(sb.toString(), defaultCtx());
            assertEquals("15", result.output());
        }

        @Test
        void j5_toJsonExcludesSelectionSetFields() {
            var info = Map.<String, Object>of(
                    "fieldName", "getPost",
                    "parentTypeName", "Query",
                    "variables", Map.of("id", "1"),
                    "selectionSetList", List.of("id", "title"),
                    "selectionSetGraphQL", "{ id title }"
            );
            var ctx = ctxWith(b -> b.info(info));
            var result = engine.evaluate("$util.toJson($context.info)", ctx);
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
}
