package io.floci.aws.services.appsync.graphql.datasource;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.appsync.model.DataSource;
import io.floci.aws.services.appsync.model.DataSourceType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(HttpDataSourceInvokerIntegrationTest.Enforced.class)
class HttpDataSourceInvokerIntegrationTest {

    public static class Enforced implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }

    @Inject
    AppSyncDataSourceInvokers invokers;
    @InjectMock
    HttpDataSourceTransport transport;

    private DataSource source;

    @BeforeEach
    void setUp() {
        reset(transport);
        source = new DataSource();
        source.setName("PublicHttp");
        source.setType(DataSourceType.HTTP);
        source.setDataSourceArn("arn:aws:appsync:us-east-1:000000000000:apis/example/datasources/PublicHttp");
        source.setHttpConfig(Map.of("endpoint", "https://public.example/base"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jsAndVtlExposeTheHttpResponseWithoutRequiringAnUnsignedSourceRole(boolean vtl) {
        Map<String, Object> response = Map.of("statusCode", 409, "headers", Map.of("content-type", "text/plain"),
                "body", "conflict");
        when(transport.send(any())).thenReturn(response);
        Map<String, Object> request = Map.of("version", "2018-05-29", "method", "POST", "resourcePath", "/items",
                "params", Map.of("query", Map.of("name", "a b"), "headers", Map.of("Content-Type", "text/plain"),
                        "body", "request"));

        Object result = vtl ? invokers.invokeVtl(source, request, "us-east-1")
                : invokers.invoke(source, request, "us-east-1");

        assertEquals(response, result);
        ArgumentCaptor<HttpDataSourceRequest> sent = ArgumentCaptor.forClass(HttpDataSourceRequest.class);
        verify(transport).send(sent.capture());
        assertEquals(URI.create("https://public.example/base/items?name=a%20b"), sent.getValue().uri());
        assertEquals("POST", sent.getValue().method());
        assertEquals("request", new String(sent.getValue().body(), StandardCharsets.UTF_8));
    }

    @Test
    void malformedRequestsFailBeforeNetworkAccess() {
        AwsException error = assertThrows(AwsException.class,
                () -> invokers.invoke(source, Map.of("method", "GET", "resourcePath", "//other.example/path"), "us-east-1"));
        assertEquals("MappingTemplate", error.getErrorCode());
        verifyNoInteractions(transport);
    }

    @Test
    void unsupportedAuthorizationIsNeverSentUnsigned() {
        source.setHttpConfig(Map.of("endpoint", "https://public.example", "authorizationConfig",
                Map.of("authorizationType", "UNSUPPORTED")));
        AwsException error = assertThrows(AwsException.class,
                () -> invokers.invoke(source, Map.of("method", "GET", "resourcePath", "/"), "us-east-1"));
        assertEquals("MappingTemplate", error.getErrorCode());
        verifyNoInteractions(transport);
    }
}
