package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class VtlTemplateEngineTest {

    @Inject
    VtlTemplateEngine engine;

    private VtlTemplateEngine.VtlContext ctx(String body) {
        return new VtlTemplateEngine.VtlContext(
                body,
                Map.of("Content-Type", "application/json", "Authorization", "Bearer xyz"),
                Map.of("limit", "10"),
                Map.of("proxy", "users/123"),
                "prod",
                "POST",
                "/users",
                "req-123",
                "000000000000",
                Map.of(),
                Map.of()
        );
    }

    @Test
    void passthrough_nullTemplate() {
        String result = engine.evaluate(null, ctx("{\"key\":\"value\"}")).body();
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void passthrough_emptyTemplate() {
        String result = engine.evaluate("", ctx("{\"key\":\"value\"}")).body();
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void inputBody() {
        String result = engine.evaluate("$input.body()", ctx("{\"key\":\"value\"}")).body();
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void inputJson_root() {
        String result = engine.evaluate("$input.json('$')", ctx("{\"name\":\"Alice\",\"age\":30}")).body();
        // Should return the full body as JSON
        assertTrue(result.contains("Alice"));
        assertTrue(result.contains("30"));
    }

    @Test
    void inputJson_nested() {
        String result = engine.evaluate("$input.json('$.name')", ctx("{\"name\":\"Bob\"}")).body();
        assertEquals("\"Bob\"", result);
    }

    @Test
    void inputPath() {
        String result = engine.evaluate("$input.path('$.name')", ctx("{\"name\":\"Carol\"}")).body();
        assertEquals("Carol", result);
    }

    @Test
    void inputParams() {
        String result = engine.evaluate("$input.params().querystring.limit", ctx("{}")).body();
        assertEquals("10", result);
    }

    @Test
    void utilEscapeJavaScript_singleQuotes() {
        String body = "{\"msg\":\"hello 'world'\"}";
        String result = engine.evaluate("$util.escapeJavaScript($input.body())", ctx(body)).body();
        assertTrue(result.contains("\\'world\\'"), "Single quotes should be escaped: " + result);
    }

    @Test
    void utilEscapeJavaScript_doubleQuotes() {
        String result = engine.evaluate("$util.escapeJavaScript('he said \"hi\"')", ctx("{}")).body();
        assertEquals("he said \\\"hi\\\"", result);
    }

    @Test
    void utilEscapeJavaScript_forwardSlash() {
        String result = engine.evaluate("$util.escapeJavaScript('a/b/c')", ctx("{}")).body();
        assertEquals("a\\/b\\/c", result);
    }

    @Test
    void utilEscapeJavaScript_controlChars() {
        // Test backspace, form feed
        var util = new VtlTemplateEngine.UtilVariable(new ObjectMapper());
        String result = util.escapeJavaScript("a\bb\fc");
        assertEquals("a\\bb\\fc", result);
    }

    @Test
    void utilEscapeJavaScript_unicode() {
        var util = new VtlTemplateEngine.UtilVariable(new ObjectMapper());
        String result = util.escapeJavaScript("café");
        assertEquals("caf\\u00e9", result);
    }

    @Test
    void utilEscapeJavaScript_backslash() {
        var util = new VtlTemplateEngine.UtilVariable(new ObjectMapper());
        String result = util.escapeJavaScript("a\\b");
        assertEquals("a\\\\b", result);
    }

    @Test
    void utilEscapeJavaScript_newlineTabCr() {
        var util = new VtlTemplateEngine.UtilVariable(new ObjectMapper());
        String result = util.escapeJavaScript("a\nb\tc\rd");
        assertEquals("a\\nb\\tc\\rd", result);
    }

    @Test
    void utilUrlEncodeDecode() {
        String result = engine.evaluate("$util.urlEncode('hello world')", ctx("{}")).body();
        assertEquals("hello+world", result);

        String decoded = engine.evaluate("$util.urlDecode('hello+world')", ctx("{}")).body();
        assertEquals("hello world", decoded);
    }

    @Test
    void utilBase64EncodeDecode() {
        String encoded = engine.evaluate("$util.base64Encode('test data')", ctx("{}")).body();
        assertEquals("dGVzdCBkYXRh", encoded);

        String decoded = engine.evaluate("$util.base64Decode('dGVzdCBkYXRh')", ctx("{}")).body();
        assertEquals("test data", decoded);
    }

    @Test
    void contextVariables() {
        String result = engine.evaluate(
                "$context.stage:$context.httpMethod:$context.resourcePath:$context.requestId",
                ctx("{}")).body();
        assertEquals("prod:POST:/users:req-123", result);
    }

    @Test
    void contextIdentity() {
        String result = engine.evaluate("$context.identity.sourceIp", ctx("{}")).body();
        assertEquals("127.0.0.1", result);
    }

    @Test
    void contextAuthorizer() {
        VtlTemplateEngine.VtlContext authorizerCtx = new VtlTemplateEngine.VtlContext(
                "{}", Map.of(), Map.of(), Map.of(), "prod", "POST", "/users",
                "req-123", "000000000000", Map.of(),
                Map.of(
                        "principalId", "test-user",
                        "key_id", "KEY-123",
                        "numberKey", "1",
                        "booleanKey", "true",
                        "identity", "{\"tenant\":\"t-9\"}"));
        String template = "#set($identity = $util.parseJson($context.authorizer.identity))"
                + "#set($numberIsString = $context.authorizer.numberKey == \"1\")"
                + "#set($booleanIsString = $context.authorizer.booleanKey == \"true\")"
                + "$context.authorizer.principalId|$context.authorizer.key_id|$identity.tenant"
                + "|$numberIsString|$booleanIsString";

        assertEquals("test-user|KEY-123|t-9|true|true", engine.evaluate(template, authorizerCtx).body());
    }

    @Test
    void contextErrorForGatewayResponses() {
        VtlTemplateEngine.VtlContext errorCtx = new VtlTemplateEngine.VtlContext(
                null, Map.of(), Map.of(), Map.of(), "prod", "GET", "/users",
                "req-123", "000000000000", Map.of(), null,
                Map.of("path", "/prod/users",
                        "error", Map.of("message", "Missing Authentication Token",
                                "messageString", "\"Missing Authentication Token\"",
                                "responseType", "MISSING_AUTHENTICATION_TOKEN",
                                "validationErrorString", "")));
        String template = "{\"message\":$context.error.messageString,"
                + "\"raw\":\"$context.error.message\",\"type\":\"$context.error.responseType\","
                + "\"path\":\"$context.path\",\"stage\":\"$context.stage\"}";

        assertEquals("{\"message\":\"Missing Authentication Token\",\"raw\":\"Missing Authentication Token\","
                + "\"type\":\"MISSING_AUTHENTICATION_TOKEN\",\"path\":\"/prod/users\",\"stage\":\"prod\"}",
                engine.evaluate(template, errorCtx).body());
        // Outside a gateway response $context.error is simply absent.
        assertEquals("$context.error", engine.evaluate("$context.error", ctx("{}")).body());
    }

    @Test
    void stageVariables() {
        VtlTemplateEngine.VtlContext svCtx = new VtlTemplateEngine.VtlContext(
                "{}", Map.of(), Map.of(), Map.of(), "prod", "GET", "/",
                "req-1", "000000000000", Map.of("tableName", "my-table"), Map.of());
        String result = engine.evaluate("$stageVariables.tableName", svCtx).body();
        assertEquals("my-table", result);
    }

    @Test
    void sfnRequestTemplate() {
        String template = """
                {"stateMachineArn": "arn:aws:states:us-east-1:000:sm:test", "input": "$util.escapeJavaScript($input.json('$'))"}""";
        String body = "{\"id\":\"123\",\"message\":\"hello\"}";
        String result = engine.evaluate(template, ctx(body)).body();

        assertTrue(result.contains("arn:aws:states:us-east-1:000:sm:test"));
        assertTrue(result.contains("\\\"id\\\""));
        assertTrue(result.contains("\\\"123\\\""));

        // Verify it's valid JSON when we unescape the input field
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result));
    }

    @Test
    void velocityDirectives_set() {
        String template = """
                #set($body = $input.path('$'))
                {"value": "$body.name"}""";
        String result = engine.evaluate(template, ctx("{\"name\":\"Dave\"}")).body().trim();
        assertTrue(result.contains("Dave"));
    }

    @Test
    void inputJson_arrayIndex() {
        String body = "{\"items\":[{\"id\":\"first\"},{\"id\":\"second\"}]}";
        String result = engine.evaluate("$input.json('$.items[0].id')", ctx(body)).body();
        assertEquals("\"first\"", result);
    }

    @Test
    void inputJson_arrayIndexNested() {
        String body = "{\"data\":[[[\"deep\"]]]}";
        String result = engine.evaluate("$input.json('$.data[0][0][0]')", ctx(body)).body();
        assertEquals("\"deep\"", result);
    }

    @Test
    void inputPath_arrayIndex() {
        String body = "{\"users\":[{\"name\":\"Eve\"},{\"name\":\"Frank\"}]}";
        String result = engine.evaluate("$input.path('$.users[1].name')", ctx(body)).body();
        assertEquals("Frank", result);
    }

    @Test
    void nullBody() {
        String result = engine.evaluate("$input.body()", ctx(null)).body();
        assertEquals("", result);
    }

    // ──────────── Velocity directives: #foreach ────────────

    @Test
    void foreach_iterateArray() {
        String body = "{\"names\":[\"Alice\",\"Bob\",\"Carol\"]}";
        String template = "#set($names = $input.path('$.names'))\n"
                + "[#foreach($name in $names)\"$name\"#if($foreach.hasNext),#end#end]";
        String result = engine.evaluate(template, ctx(body)).body().trim();
        assertEquals("[\"Alice\",\"Bob\",\"Carol\"]", result);
    }

    @Test
    void foreach_buildJsonArray() {
        // Common APIGW pattern: transform a list of items
        String body = "{\"items\":[{\"id\":\"1\",\"val\":\"a\"},{\"id\":\"2\",\"val\":\"b\"}]}";
        String template = "#set($items = $input.path('$.items'))\n"
                + "{\"results\": [#foreach($item in $items)"
                + "{\"key\": \"$item.id\"}#if($foreach.hasNext),#end"
                + "#end]}";
        String result = engine.evaluate(template, ctx(body)).body().trim();
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result));
        var node = assertDoesNotThrow(() -> new ObjectMapper().readTree(result));
        assertEquals(2, node.path("results").size());
        assertEquals("1", node.path("results").get(0).path("key").asText());
    }

    // ──────────── Velocity directives: #if / #else ────────────

    @Test
    void if_conditionalOutput() {
        String body = "{\"type\":\"premium\"}";
        String template = "#set($type = $input.path('$.type'))\n"
                + "#if($type == 'premium')PREMIUM#else STANDARD#end";
        String result = engine.evaluate(template, ctx(body)).body().trim();
        assertEquals("PREMIUM", result);
    }

    @Test
    void if_elseBranch() {
        String body = "{\"type\":\"basic\"}";
        String template = "#set($type = $input.path('$.type'))\n"
                + "#if($type == 'premium')PREMIUM#else STANDARD#end";
        String result = engine.evaluate(template, ctx(body)).body().trim();
        assertEquals("STANDARD", result);
    }

    @Test
    void if_nullCheck() {
        // Common pattern: check if a field exists
        String body = "{\"name\":\"test\"}";
        String template = "#set($desc = $input.path('$.description'))\n"
                + "#if($desc && $desc != '')HAS_DESC#else NO_DESC#end";
        String result = engine.evaluate(template, ctx(body)).body().trim();
        assertEquals("NO_DESC", result);
    }

    // ──────────── Complex real-world template patterns ────────────

    @Test
    void realWorld_sqsSendMessage() {
        // Common pattern: APIGW → SQS SendMessage with body as message
        String body = "{\"orderId\":\"123\",\"amount\":99.95}";
        String template = """
                {"QueueUrl": "http://localhost:4566/000000000000/my-queue", "MessageBody": "$util.escapeJavaScript($input.json('$'))"}""";
        String result = engine.evaluate(template, ctx(body)).body();
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result));
        var node = assertDoesNotThrow(() -> new ObjectMapper().readTree(result));
        assertEquals("http://localhost:4566/000000000000/my-queue", node.path("QueueUrl").asText());
        // MessageBody should be the escaped JSON string
        assertTrue(node.path("MessageBody").asText().contains("orderId"));
    }

    @Test
    void realWorld_dynamoDbQueryWithParams() {
        // Pattern: use query string params in a DynamoDB query
        var queryCtx = new VtlTemplateEngine.VtlContext(
                "{}", Map.of(), Map.of("userId", "user-42", "status", "active"),
                Map.of(), "prod", "GET", "/items",
                "req-456", "000000000000", Map.of("tableName", "orders"), Map.of());
        String template = """
                {"TableName": "$stageVariables.tableName", "KeyConditionExpression": "pk = :pk", "ExpressionAttributeValues": {":pk": {"S": "$input.params().querystring.userId"}}}""";
        String result = engine.evaluate(template, queryCtx).body();
        var node = assertDoesNotThrow(() -> new ObjectMapper().readTree(result));
        assertEquals("orders", node.path("TableName").asText());
        assertEquals("user-42", node.path("ExpressionAttributeValues").path(":pk").path("S").asText());
    }

    // ──────────── Multi-value params and headers ────────────

    @Test
    void inputParams_headerAccess() {
        String result = engine.evaluate("$input.params().header.Authorization", ctx("{}")).body();
        assertEquals("Bearer xyz", result);
    }

    @Test
    void inputParams_pathAccess() {
        String result = engine.evaluate("$input.params().path.proxy", ctx("{}")).body();
        assertEquals("users/123", result);
    }

    @Test
    void inputParams_allTypes() {
        // Access all three param types in one template
        String template = "$input.params().querystring.limit|$input.params().path.proxy|$input.params().header.get('Content-Type')";
        String result = engine.evaluate(template, ctx("{}")).body();
        assertEquals("10|users/123|application/json", result);
    }

    @Test
    void inputParams_headerKeySet() {
        String template = "#foreach($header in $input.params().header.keySet())"
                + "$header=$input.params().header.get($header)#if($foreach.hasNext),#end#end";
        String result = engine.evaluate(template, ctx("{}")).body();

        assertEquals(Set.of("Content-Type=application/json", "Authorization=Bearer xyz"),
                Set.of(result.split(",")));
    }

    // ──────────── $util.parseJson in templates ────────────

    // ──────────── $input.params('name') shorthand ────────────

    @Test
    void inputParams_shorthand_querystring() {
        String result = engine.evaluate("$input.params('limit')", ctx("{}")).body();
        assertEquals("10", result);
    }

    @Test
    void inputParams_shorthand_path() {
        String result = engine.evaluate("$input.params('proxy')", ctx("{}")).body();
        assertEquals("users/123", result);
    }

    @Test
    void inputParams_shorthand_header() {
        String result = engine.evaluate("$input.params('Authorization')", ctx("{}")).body();
        assertEquals("Bearer xyz", result);
    }

    @Test
    void inputParams_shorthand_notFound() {
        String result = engine.evaluate("$input.params('nonexistent')", ctx("{}")).body();
        assertEquals("", result);
    }

    @Test
    void inputParams_shorthand_pathPrecedence() {
        // AWS searches path, query string, and headers in that order.
        VtlTemplateEngine.VtlContext overlapCtx = new VtlTemplateEngine.VtlContext(
                "{}", Map.of("shared", "header-val"),
                Map.of("shared", "query-val"),
                Map.of("shared", "path-val"),
                "prod", "GET", "/", "req-1", "000000000000", Map.of(), Map.of());
        String result = engine.evaluate("$input.params('shared')", overlapCtx).body();
        assertEquals("path-val", result);
    }

    @Test
    void inputParams_shorthand_preservesAnEmptyPathValue() {
        VtlTemplateEngine.VtlContext context = new VtlTemplateEngine.VtlContext(
                "{}", Map.of("id", "header-id"), Map.of("id", "query-id"), Map.of("id", ""),
                "prod", "GET", "/items/{id}", "req-123", "000000000000", Map.of(), Map.of());

        assertEquals("", engine.evaluate("$input.params('id')", context).body());
    }

    @Test
    void inputParams_shorthand_fallsBackToQueryThenHeaderThenEmpty() {
        VtlTemplateEngine.VtlContext context = new VtlTemplateEngine.VtlContext(
                "{}", Map.of("id", "header-id", "empty", "header-empty", "headerOnly", "header-value"),
                Map.of("id", "query-id", "empty", ""), null,
                "prod", "GET", "/items", "req-123", "000000000000", Map.of(), Map.of());

        assertEquals("query-id||header-value|", engine.evaluate(
                "$input.params('id')|$input.params('empty')|$input.params('headerOnly')|$input.params('missing')",
                context).body());
    }

    @Test
    void utilParseJson_navigateResult() {
        String template = "#set($parsed = $util.parseJson('{\"a\":{\"b\":\"deep\"}}'))\n$parsed.a.b";
        String result = engine.evaluate(template, ctx("{}")).body().trim();
        assertEquals("deep", result);
    }

    @Test
    void utilParseJson_withArray() {
        String template = "#set($parsed = $util.parseJson('[1,2,3]'))\n$parsed.size()";
        String result = engine.evaluate(template, ctx("{}")).body().trim();
        assertEquals("3", result);
    }

    // ──────────── $context.responseOverride ────────────

    @Test
    void responseOverride_status() {
        String template = "#set($context.responseOverride.status = 404)\nnot found";
        VtlTemplateEngine.EvaluateResult result = engine.evaluate(template, ctx("{}"));
        assertEquals(404, result.statusOverride());
        assertTrue(result.body().contains("not found"));
    }

    @Test
    void responseOverride_header() {
        String template = "#set($context.responseOverride.header[\"Content-Type\"] = \"application/problem+json\")\nerror";
        VtlTemplateEngine.EvaluateResult result = engine.evaluate(template, ctx("{}"));
        assertEquals("application/problem+json", result.headerOverrides().get("Content-Type"));
    }

    @Test
    void responseOverride_statusAndHeader() {
        String template = """
                #set($context.responseOverride.status = 500)
                #set($context.responseOverride.header["X-Error"] = "internal")
                body""";
        VtlTemplateEngine.EvaluateResult result = engine.evaluate(template, ctx("{}"));
        assertEquals(500, result.statusOverride());
        assertEquals("internal", result.headerOverrides().get("X-Error"));
    }

    @Test
    void responseOverride_notSet_returnsNulls() {
        VtlTemplateEngine.EvaluateResult result = engine.evaluate("hello", ctx("{}"));
        assertNull(result.statusOverride());
        assertTrue(result.headerOverrides().isEmpty());
    }
}
