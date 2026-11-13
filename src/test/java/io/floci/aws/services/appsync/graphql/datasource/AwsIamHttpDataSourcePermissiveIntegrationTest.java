package io.floci.aws.services.appsync.graphql.datasource;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.appsync.model.DataSource;
import io.floci.aws.services.appsync.model.DataSourceType;
import io.floci.aws.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(AwsIamHttpDataSourcePermissiveIntegrationTest.PermissiveSignatures.class)
class AwsIamHttpDataSourcePermissiveIntegrationTest {

    private static final String ACCOUNT = "724600001234";
    private static final String API_REGION = "us-east-1";
    private static final String TARGET_REGION = "us-west-2";

    public static class PermissiveSignatures implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "false", "floci.auth.validate-signatures", "true");
        }
    }

    @Inject
    AppSyncDataSourceInvokers invokers;
    @InjectSpy
    IamService iam;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedRequestsReachTheNativeListenerWithoutAnExistingRoleAndRevokeCredentials(boolean vtl) {
        String roleName = "http-permissive-" + UUID.randomUUID();
        assertTrue(iam.findRole(ACCOUNT, roleName).isEmpty());
        DataSource source = new DataSource();
        source.setType(DataSourceType.HTTP);
        source.setDataSourceArn("arn:aws:appsync:" + API_REGION + ":" + ACCOUNT + ":apis/example/datasources/Http");
        source.setServiceRoleArn("arn:aws:iam::" + ACCOUNT + ":role/" + roleName);
        source.setHttpConfig(Map.of("endpoint", "https://scheduler." + TARGET_REGION + ".amazonaws.com",
                "authorizationConfig", Map.of("authorizationType", "AWS_IAM", "awsIamConfig",
                        Map.of("signingRegion", TARGET_REGION, "signingServiceName", "scheduler"))));
        Map<String, Object> request = Map.of("method", "GET", "resourcePath", "/schedules/" + roleName);
        clearInvocations(iam);

        Map<?, ?> result = (Map<?, ?>) RequestScopes.callAs(ACCOUNT, API_REGION,
                () -> vtl ? invokers.invokeVtl(source, request, API_REGION) : invokers.invoke(source, request, API_REGION));

        assertEquals(404, result.get("statusCode"));
        assertTrue(result.get("body").toString().contains("ResourceNotFoundException"));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(iam).registerSessionForAccount(eq(ACCOUNT), key.capture(), anyString(), anyString(),
                eq(source.getServiceRoleArn()), any(Instant.class), isNull());
        assertFalse(iam.isKnownAccessKey(key.getValue()));
    }
}
