package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaUrlInvocationControllerTest {

    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:100000000012:function:my-function";

    private LambdaUrlInvocationController newController(LambdaService lambdaService) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("100000000012");
        return new LambdaUrlInvocationController(
                lambdaService, regionResolver, new ObjectMapper(), new RequestContext());
    }

    private HttpHeaders headersWith(String contentType) {
        HttpHeaders headers = mock(HttpHeaders.class);
        MultivaluedHashMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        if (contentType != null) {
            requestHeaders.putSingle("content-type", contentType);
        }
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);
        when(headers.getHeaderString(HttpHeaders.CONTENT_TYPE)).thenReturn(contentType);
        return headers;
    }

    private UriInfo uriInfoFor(String uri) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(uri));
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uriInfo;
    }

    @Test
    void aliasUrlInvokesOwningAccountAliasArn() {
        String accountId = "100000000012";
        String region = "ap-south-1";
        String aliasArn = "arn:aws:lambda:" + region + ":" + accountId
                + ":function:account-function:live";
        LambdaAlias alias = new LambdaAlias();
        alias.setFunctionName("account-function");
        alias.setName("live");
        alias.setAliasArn(aliasArn);

        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.getTargetByUrlId("url-id")).thenReturn(alias);
        InvokeResult invokeResult = new InvokeResult();
        invokeResult.setStatusCode(200);
        invokeResult.setPayload("{\"statusCode\":200,\"body\":\"ok\"}"
                .getBytes(StandardCharsets.UTF_8));
        when(lambdaService.invokeArn(eq(aliasArn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(invokeResult);

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(accountId);
        RequestContext requestContext = new RequestContext();
        LambdaUrlInvocationController controller = new LambdaUrlInvocationController(
                lambdaService, regionResolver, new ObjectMapper(), requestContext);

        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/lambda-url/url-id/"));
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());

        Response response = controller.handleGet("url-id", "", headers, uriInfo);

        assertEquals(200, response.getStatus());
        assertEquals(accountId, requestContext.getAccountId());
        assertEquals(region, requestContext.getRegion());
        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(aliasArn), event.capture(), eq(InvocationType.RequestResponse));
        assertTrue(new String(event.getValue(), StandardCharsets.UTF_8)
                .contains("\"accountId\":\"" + accountId + "\""));
    }

    @Test
    void binaryRequestBodyIsBase64EncodedInEvent() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("my-function");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("100000000012");

        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.getTargetByUrlId("url-id")).thenReturn(fn);
        InvokeResult invokeResult = new InvokeResult();
        invokeResult.setStatusCode(200);
        invokeResult.setPayload("{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(invokeResult);

        LambdaUrlInvocationController controller = newController(lambdaService);
        byte[] binaryBody = new byte[] {0x00, (byte) 0xff, (byte) 0x80, 0x0a};

        controller.handlePost("url-id", "", headersWith("application/octet-stream"),
                uriInfoFor("http://localhost/lambda-url/url-id/"), binaryBody);

        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(FUNCTION_ARN), event.capture(), eq(InvocationType.RequestResponse));
        JsonNode eventNode = readTree(event.getValue());

        assertTrue(eventNode.get("isBase64Encoded").asBoolean());
        assertEquals(Base64.getEncoder().encodeToString(binaryBody), eventNode.get("body").asText());
    }

    @Test
    void octetStreamBodyIsBase64EncodedEvenWhenValidUtf8() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("my-function");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("100000000012");

        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.getTargetByUrlId("url-id")).thenReturn(fn);
        InvokeResult invokeResult = new InvokeResult();
        invokeResult.setStatusCode(200);
        invokeResult.setPayload("{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(invokeResult);

        LambdaUrlInvocationController controller = newController(lambdaService);
        byte[] textLookingBody = "hello world".getBytes(StandardCharsets.UTF_8);

        controller.handlePost("url-id", "", headersWith("application/octet-stream"),
                uriInfoFor("http://localhost/lambda-url/url-id/"), textLookingBody);

        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(FUNCTION_ARN), event.capture(), eq(InvocationType.RequestResponse));
        JsonNode eventNode = readTree(event.getValue());

        assertTrue(eventNode.get("isBase64Encoded").asBoolean());
        assertEquals(Base64.getEncoder().encodeToString(textLookingBody), eventNode.get("body").asText());
    }

    /** Media types are case-insensitive, so a mixed-case Content-Type must still classify as text. */
    @Test
    void mixedCaseJsonContentTypeIsStillPassedThroughAsText() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("my-function");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("100000000012");

        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.getTargetByUrlId("url-id")).thenReturn(fn);
        InvokeResult invokeResult = new InvokeResult();
        invokeResult.setStatusCode(200);
        invokeResult.setPayload("{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(invokeResult);

        LambdaUrlInvocationController controller = newController(lambdaService);
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        controller.handlePost("url-id", "", headersWith("Application/JSON"),
                uriInfoFor("http://localhost/lambda-url/url-id/"), body);

        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(FUNCTION_ARN), event.capture(), eq(InvocationType.RequestResponse));
        JsonNode eventNode = readTree(event.getValue());

        assertFalse(eventNode.get("isBase64Encoded").asBoolean());
        assertEquals(new String(body, StandardCharsets.UTF_8), eventNode.get("body").asText());
    }

    @Test
    void textRequestBodyIsPassedThroughUndecoded() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("my-function");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("100000000012");

        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.getTargetByUrlId("url-id")).thenReturn(fn);
        InvokeResult invokeResult = new InvokeResult();
        invokeResult.setStatusCode(200);
        invokeResult.setPayload("{\"statusCode\":200,\"body\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(invokeResult);

        LambdaUrlInvocationController controller = newController(lambdaService);

        controller.handlePost("url-id", "", headersWith("application/json"),
                uriInfoFor("http://localhost/lambda-url/url-id/"), "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(FUNCTION_ARN), event.capture(), eq(InvocationType.RequestResponse));
        JsonNode eventNode = readTree(event.getValue());

        assertFalse(eventNode.get("isBase64Encoded").asBoolean());
        assertEquals("{\"hello\":\"world\"}", eventNode.get("body").asText());
    }

    @Test
    void responseCookiesArrayBecomesMultipleSetCookieHeaders() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("my-function");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("100000000012");

        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.getTargetByUrlId("url-id")).thenReturn(fn);
        InvokeResult invokeResult = new InvokeResult();
        invokeResult.setStatusCode(200);
        invokeResult.setPayload(("{\"statusCode\":200,\"body\":\"ok\","
                + "\"cookies\":[\"a=1; Path=/\",\"b=2; Max-Age=60\"]}").getBytes(StandardCharsets.UTF_8));
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(invokeResult);

        LambdaUrlInvocationController controller = newController(lambdaService);

        Response response = controller.handleGet("url-id", "", headersWith(null),
                uriInfoFor("http://localhost/lambda-url/url-id/"));

        List<Object> setCookieHeaders = response.getHeaders().get("Set-Cookie");
        assertEquals(2, setCookieHeaders.size());
        assertTrue(setCookieHeaders.contains("a=1; Path=/"));
        assertTrue(setCookieHeaders.contains("b=2; Max-Age=60"));
    }

    @Test
    void unhandledFunctionErrorMapsToBadGateway() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("my-function");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("100000000012");

        LambdaService lambdaService = mock(LambdaService.class);
        when(lambdaService.getTargetByUrlId("url-id")).thenReturn(fn);
        InvokeResult invokeResult = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"boom\",\"errorType\":\"RuntimeException\"}".getBytes(StandardCharsets.UTF_8),
                null, "req-1");
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(invokeResult);

        LambdaUrlInvocationController controller = newController(lambdaService);

        Response response = controller.handleGet("url-id", "", headersWith(null),
                uriInfoFor("http://localhost/lambda-url/url-id/"));

        assertEquals(502, response.getStatus());
        assertNull(response.getEntity());
    }

    private JsonNode readTree(byte[] json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
