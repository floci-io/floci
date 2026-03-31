package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
                Map.of()
        );
    }

    @Test
    void passthrough_nullTemplate() {
        String result = engine.evaluate(null, ctx("{\"key\":\"value\"}"));
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void passthrough_emptyTemplate() {
        String result = engine.evaluate("", ctx("{\"key\":\"value\"}"));
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void inputBody() {
        String result = engine.evaluate("$input.body()", ctx("{\"key\":\"value\"}"));
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    void inputJson_root() {
        String result = engine.evaluate("$input.json('$')", ctx("{\"name\":\"Alice\",\"age\":30}"));
        // Should return the full body as JSON
        assertTrue(result.contains("Alice"));
        assertTrue(result.contains("30"));
    }

    @Test
    void inputJson_nested() {
        String result = engine.evaluate("$input.json('$.name')", ctx("{\"name\":\"Bob\"}"));
        assertEquals("\"Bob\"", result);
    }

    @Test
    void inputPath() {
        String result = engine.evaluate("$input.path('$.name')", ctx("{\"name\":\"Carol\"}"));
        assertEquals("Carol", result);
    }

    @Test
    void inputParams() {
        String result = engine.evaluate("$input.params().querystring.limit", ctx("{}"));
        assertEquals("10", result);
    }

    @Test
    void utilEscapeJavaScript_singleQuotes() {
        String body = "{\"msg\":\"hello 'world'\"}";
        String result = engine.evaluate("$util.escapeJavaScript($input.body())", ctx(body));
        assertTrue(result.contains("\\'world\\'"), "Single quotes should be escaped: " + result);
    }

    @Test
    void utilEscapeJavaScript_doubleQuotes() {
        String result = engine.evaluate("$util.escapeJavaScript('he said \"hi\"')", ctx("{}"));
        assertEquals("he said \\\"hi\\\"", result);
    }

    @Test
    void utilEscapeJavaScript_forwardSlash() {
        String result = engine.evaluate("$util.escapeJavaScript('a/b/c')", ctx("{}"));
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
        String result = engine.evaluate("$util.urlEncode('hello world')", ctx("{}"));
        assertEquals("hello+world", result);

        String decoded = engine.evaluate("$util.urlDecode('hello+world')", ctx("{}"));
        assertEquals("hello world", decoded);
    }

    @Test
    void utilBase64EncodeDecode() {
        String encoded = engine.evaluate("$util.base64Encode('test data')", ctx("{}"));
        assertEquals("dGVzdCBkYXRh", encoded);

        String decoded = engine.evaluate("$util.base64Decode('dGVzdCBkYXRh')", ctx("{}"));
        assertEquals("test data", decoded);
    }

    @Test
    void contextVariables() {
        String result = engine.evaluate(
                "$context.stage:$context.httpMethod:$context.resourcePath:$context.requestId",
                ctx("{}"));
        assertEquals("prod:POST:/users:req-123", result);
    }

    @Test
    void contextIdentity() {
        String result = engine.evaluate("$context.identity.sourceIp", ctx("{}"));
        assertEquals("127.0.0.1", result);
    }

    @Test
    void stageVariables() {
        VtlTemplateEngine.VtlContext svCtx = new VtlTemplateEngine.VtlContext(
                "{}", Map.of(), Map.of(), Map.of(), "prod", "GET", "/",
                "req-1", "000000000000", Map.of("tableName", "my-table"));
        String result = engine.evaluate("$stageVariables.tableName", svCtx);
        assertEquals("my-table", result);
    }

    @Test
    void sfnRequestTemplate() {
        String template = """
                {"stateMachineArn": "arn:aws:states:us-east-1:000:sm:test", "input": "$util.escapeJavaScript($input.json('$'))"}""";
        String body = "{\"id\":\"123\",\"message\":\"hello\"}";
        String result = engine.evaluate(template, ctx(body));

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
        String result = engine.evaluate(template, ctx("{\"name\":\"Dave\"}")).trim();
        assertTrue(result.contains("Dave"));
    }

    @Test
    void inputJson_arrayIndex() {
        String body = "{\"items\":[{\"id\":\"first\"},{\"id\":\"second\"}]}";
        String result = engine.evaluate("$input.json('$.items[0].id')", ctx(body));
        assertEquals("\"first\"", result);
    }

    @Test
    void inputJson_arrayIndexNested() {
        String body = "{\"data\":[[[\"deep\"]]]}";
        String result = engine.evaluate("$input.json('$.data[0][0][0]')", ctx(body));
        assertEquals("\"deep\"", result);
    }

    @Test
    void inputPath_arrayIndex() {
        String body = "{\"users\":[{\"name\":\"Eve\"},{\"name\":\"Frank\"}]}";
        String result = engine.evaluate("$input.path('$.users[1].name')", ctx(body));
        assertEquals("Frank", result);
    }

    @Test
    void nullBody() {
        String result = engine.evaluate("$input.body()", ctx(null));
        assertEquals("", result);
    }

    // ──────────── Velocity directives: #foreach ────────────

    @Test
    void foreach_iterateArray() {
        String body = "{\"names\":[\"Alice\",\"Bob\",\"Carol\"]}";
        String template = "#set($names = $input.path('$.names'))\n"
                + "[#foreach($name in $names)\"$name\"#if($foreach.hasNext),#end#end]";
        String result = engine.evaluate(template, ctx(body)).trim();
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
        String result = engine.evaluate(template, ctx(body)).trim();
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
        String result = engine.evaluate(template, ctx(body)).trim();
        assertEquals("PREMIUM", result);
    }

    @Test
    void if_elseBranch() {
        String body = "{\"type\":\"basic\"}";
        String template = "#set($type = $input.path('$.type'))\n"
                + "#if($type == 'premium')PREMIUM#else STANDARD#end";
        String result = engine.evaluate(template, ctx(body)).trim();
        assertEquals("STANDARD", result);
    }

    @Test
    void if_nullCheck() {
        // Common pattern: check if a field exists
        String body = "{\"name\":\"test\"}";
        String template = "#set($desc = $input.path('$.description'))\n"
                + "#if($desc && $desc != '')HAS_DESC#else NO_DESC#end";
        String result = engine.evaluate(template, ctx(body)).trim();
        assertEquals("NO_DESC", result);
    }

    // ──────────── Complex real-world template patterns ────────────

    @Test
    void realWorld_sqsSendMessage() {
        // Common pattern: APIGW → SQS SendMessage with body as message
        String body = "{\"orderId\":\"123\",\"amount\":99.95}";
        String template = """
                {"QueueUrl": "http://localhost:4566/000000000000/my-queue", "MessageBody": "$util.escapeJavaScript($input.json('$'))"}""";
        String result = engine.evaluate(template, ctx(body));
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
                "req-456", "000000000000", Map.of("tableName", "orders"));
        String template = """
                {"TableName": "$stageVariables.tableName", "KeyConditionExpression": "pk = :pk", "ExpressionAttributeValues": {":pk": {"S": "$input.params().querystring.userId"}}}""";
        String result = engine.evaluate(template, queryCtx);
        var node = assertDoesNotThrow(() -> new ObjectMapper().readTree(result));
        assertEquals("orders", node.path("TableName").asText());
        assertEquals("user-42", node.path("ExpressionAttributeValues").path(":pk").path("S").asText());
    }

    // ──────────── Multi-value params and headers ────────────

    @Test
    void inputParams_headerAccess() {
        String result = engine.evaluate("$input.params().header.Authorization", ctx("{}"));
        assertEquals("Bearer xyz", result);
    }

    @Test
    void inputParams_pathAccess() {
        String result = engine.evaluate("$input.params().path.proxy", ctx("{}"));
        assertEquals("users/123", result);
    }

    @Test
    void inputParams_allTypes() {
        // Access all three param types in one template
        String template = "$input.params().querystring.limit|$input.params().path.proxy|$input.params().header.get('Content-Type')";
        String result = engine.evaluate(template, ctx("{}"));
        assertTrue(result.startsWith("10|users/123|"), "Should contain all param types: " + result);
    }

    // ──────────── $util.parseJson in templates ────────────

    // ──────────── $input.params('name') shorthand ────────────

    @Test
    void inputParams_shorthand_querystring() {
        String result = engine.evaluate("$input.params('limit')", ctx("{}"));
        assertEquals("10", result);
    }

    @Test
    void inputParams_shorthand_path() {
        String result = engine.evaluate("$input.params('proxy')", ctx("{}"));
        assertEquals("users/123", result);
    }

    @Test
    void inputParams_shorthand_header() {
        String result = engine.evaluate("$input.params('Authorization')", ctx("{}"));
        assertEquals("Bearer xyz", result);
    }

    @Test
    void inputParams_shorthand_notFound() {
        String result = engine.evaluate("$input.params('nonexistent')", ctx("{}"));
        assertEquals("", result);
    }

    @Test
    void inputParams_shorthand_querystringPrecedence() {
        // querystring should take precedence over path and header
        VtlTemplateEngine.VtlContext overlapCtx = new VtlTemplateEngine.VtlContext(
                "{}", Map.of("shared", "header-val"),
                Map.of("shared", "query-val"),
                Map.of("shared", "path-val"),
                "prod", "GET", "/", "req-1", "000000000000", Map.of());
        String result = engine.evaluate("$input.params('shared')", overlapCtx);
        assertEquals("query-val", result);
    }

    @Test
    void utilParseJson_navigateResult() {
        String template = "#set($parsed = $util.parseJson('{\"a\":{\"b\":\"deep\"}}'))\n$parsed.a.b";
        String result = engine.evaluate(template, ctx("{}")).trim();
        assertEquals("deep", result);
    }

    @Test
    void utilParseJson_withArray() {
        String template = "#set($parsed = $util.parseJson('[1,2,3]'))\n$parsed.size()";
        String result = engine.evaluate(template, ctx("{}")).trim();
        assertEquals("3", result);
    }
}
