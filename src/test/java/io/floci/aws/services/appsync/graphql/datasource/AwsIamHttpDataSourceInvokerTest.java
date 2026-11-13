package io.floci.aws.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceRegistry;
import io.floci.aws.services.appsync.model.DataSource;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.quarkus.vertx.http.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AwsIamHttpDataSourceInvokerTest {

    private static final String ACCOUNT = "724600001234";
    private static final String REGION = "us-east-1";
    private final IamService iam = mock(IamService.class);
    private final ServiceRoleAuthorizer authorizer = mock(ServiceRoleAuthorizer.class);
    private final ServiceRegistry services = mock(ServiceRegistry.class);
    private final HttpServer server = mock(HttpServer.class);
    private final HttpDataSourceTransport transport = mock(HttpDataSourceTransport.class);
    private DataSource source;
    private HttpDataSourceRequest request;
    private AwsIamHttpDataSourceInvoker invoker;

    @BeforeEach
    void setUp() {
        when(services.isServiceEnabled("scheduler")).thenReturn(true);
        when(server.getLocalBaseUri()).thenReturn(URI.create("http://localhost:4566"));
        when(authorizer.refusal(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        source = new DataSource();
        source.setDataSourceArn("arn:aws:appsync:" + REGION + ":" + ACCOUNT + ":apis/example/datasources/Http");
        source.setServiceRoleArn("arn:aws:iam::" + ACCOUNT + ":role/appsync/Resolver");
        source.setHttpConfig(Map.of("endpoint", "https://scheduler.us-west-2.amazonaws.com", "authorizationConfig",
                Map.of("authorizationType", "AWS_IAM", "awsIamConfig",
                        Map.of("signingRegion", "us-west-2", "signingServiceName", "scheduler"))));
        request = HttpDataSourceRequest.parse(source, Map.of("method", "GET", "resourcePath", "/schedules/example"),
                new ObjectMapper());
        invoker = new AwsIamHttpDataSourceInvoker(iam, authorizer, services, new RegionResolver(REGION, ACCOUNT),
                server, transport);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transportFailureOrTimeoutAlwaysRevokesTheIssuedSession(boolean timeout) {
        RuntimeException failure = timeout ? new AwsException("HttpException", "HTTP deadline exceeded", 500)
                : new IllegalStateException("connection failed");
        when(transport.send(any())).thenThrow(failure);
        AwsException error = assertThrows(AwsException.class, () -> invoker.invoke(source, request, REGION));
        assertEquals("HttpException", error.getErrorCode());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(iam).registerSessionForAccount(eq(ACCOUNT), key.capture(), anyString(), anyString(),
                eq(source.getServiceRoleArn()), any(Instant.class), isNull());
        verify(iam).unregisterSession(ACCOUNT, key.getValue());
    }

    @Test
    void signedRequestUsesTheNativeListenerAndRevokesItsCredentials() {
        when(transport.send(any())).thenReturn(Map.of("statusCode", 200, "headers", Map.of(), "body", "ok"));
        assertEquals(200, invoker.invoke(source, request, REGION).get("statusCode"));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(iam).registerSessionForAccount(eq(ACCOUNT), key.capture(), anyString(), anyString(),
                eq(source.getServiceRoleArn()), any(Instant.class), isNull());
        verify(iam).unregisterSession(ACCOUNT, key.getValue());
        ArgumentCaptor<HttpDataSourceRequest> outgoing = ArgumentCaptor.forClass(HttpDataSourceRequest.class);
        verify(transport).send(outgoing.capture());
        assertEquals(URI.create("http://localhost:4566/schedules/example"), outgoing.getValue().uri());
    }

    @Test
    void aDisabledServiceCannotReceiveASignedRequest() {
        when(services.isServiceEnabled("scheduler")).thenReturn(false);
        assertRefusedBeforeCredentials("ServiceNotAvailableException");
    }

    @Test
    void aMissingOrUntrustedRoleCannotIssueCredentials() {
        when(authorizer.refusal(any(), any(), any(), any(), any())).thenReturn(Optional.of("Role cannot be assumed"));
        assertRefusedBeforeCredentials("AccessDeniedException");
    }

    private void assertRefusedBeforeCredentials(String code) {
        assertEquals(code, assertThrows(AwsException.class, () -> invoker.invoke(source, request, REGION)).getErrorCode());
        verify(iam, never()).registerSessionForAccount(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        verifyNoInteractions(transport);
    }
}
