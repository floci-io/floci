package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaUrlInvocationControllerTest {

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
}
