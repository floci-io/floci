package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Deployment;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiGatewayExportServiceTest {
    private static final String REGION = "us-east-1";
    private AccountAwareStorageBackend<Deployment> deployments;
    private ApiGatewayService service;

    @BeforeEach
    void setup() {
        deployments = AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                "apigateway-deployments.json".equals(invocation.getArgument(1))
                        ? deployments : AccountAwareStorageBackend.inMemory("000000000000"));
        service = new ApiGatewayService(factory, mock(EmulatorConfig.class), mock(TlsCertificateManager.class),
                new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void legacyDeploymentRequiresRedeploymentRatherThanExportingLiveMethods() {
        String apiId = service.createRestApi(REGION, Map.of("name", "legacy")).getId();
        deployments.put(REGION + "::" + apiId + "::old", new Deployment("old", "legacy", 1));
        service.createStage(REGION, apiId, Map.of("stageName", "dev", "deploymentId", "old"));
        AwsException error = assertThrows(AwsException.class,
                () -> service.exportRestApi(REGION, apiId, "dev", "oas30", null));
        assertTrue(error.getMessage().contains("Redeploy"));
        service.createDeployment(REGION, apiId, Map.of("stageName", "dev"));
        assertNotNull(service.exportRestApi(REGION, apiId, "dev", "oas30", null));
    }

    @Test
    void generatedSecurityNamesDoNotOverwriteAuthorizers() {
        String apiId = service.createRestApi(REGION, Map.of("name", "scheme-collision")).getId();
        String rootId = service.getResources(REGION, apiId).get(0).getId();
        Authorizer authorizer = service.createAuthorizer(REGION, apiId, Map.of("name", "sigv4", "type", "TOKEN",
                "authorizerUri", "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations"));
        service.putMethod(REGION, apiId, rootId, "GET", Map.of("authorizationType", "CUSTOM", "authorizerId", authorizer.getId()));
        service.putMethod(REGION, apiId, rootId, "POST", Map.of("authorizationType", "AWS_IAM"));
        service.createDeployment(REGION, apiId, Map.of("stageName", "dev"));
        JsonNode exported = service.exportRestApi(REGION, apiId, "dev", "oas30", "apigateway");
        assertTrue(exported.at("/components/securitySchemes/sigv4").has("x-amazon-apigateway-authorizer"));
        assertEquals("awsSigv4", exported.at("/components/securitySchemes/sigv4_1/x-amazon-apigateway-authtype").asText());
        assertTrue(exported.at("/paths/~1/get/security/0").has("sigv4"));
        assertTrue(exported.at("/paths/~1/post/security/0").has("sigv4_1"));
    }
    @ParameterizedTest
    @CsvSource({"TOKEN,oas30", "TOKEN,swagger", "COGNITO_USER_POOLS,oas30", "COGNITO_USER_POOLS,swagger"})
    void incompleteAuthorizerExportCannotBeImportedAsApiKey(String kind, String format) {
        String apiId = protectedApi(kind);
        JsonNode document = service.exportRestApi(REGION, apiId, "dev", format, null);
        String schemes = "swagger".equals(format) ? "securityDefinitions" : "components/securitySchemes";
        assertEquals("TOKEN".equals(kind) ? "custom" : "cognito_user_pools",
                document.at("/" + schemes + "/Protected/x-amazon-apigateway-authtype").asText());
        assertFalse(document.at("/" + schemes + "/Protected").has("x-amazon-apigateway-authorizer"));
        int before = service.getRestApis(REGION).size();
        AwsException rejected = assertThrows(AwsException.class,
                () -> service.importRestApi(REGION, document.toString()));
        assertTrue(rejected.getMessage().contains("authorizer definition"));
        assertEquals(before, service.getRestApis(REGION).size());
        assertThrows(AwsException.class,
                () -> service.putRestApi(REGION, apiId, "overwrite", document.toString()));
        MethodConfig original = service.getResources(REGION, apiId).get(0).getResourceMethods().get("GET");
        assertEquals("TOKEN".equals(kind) ? "CUSTOM" : kind, original.getAuthorizationType());
        assertFalse(original.isApiKeyRequired());
    }

    @ParameterizedTest
    @CsvSource({"TOKEN,oas30,authorizers", "TOKEN,swagger,apigateway",
            "COGNITO_USER_POOLS,oas30,apigateway", "COGNITO_USER_POOLS,swagger,authorizers"})
    void fullAuthorizerExportPreservesAuthorizationAndScopes(String kind, String format, String extensions) {
        String apiId = protectedApi(kind);
        JsonNode document = service.exportRestApi(REGION, apiId, "dev", format, extensions);
        String restoredId = service.importRestApi(REGION, document.toString()).getId();
        MethodConfig method = service.getResources(REGION, restoredId).get(0).getResourceMethods().get("GET");
        assertEquals("TOKEN".equals(kind) ? "CUSTOM" : kind, method.getAuthorizationType());
        assertFalse(method.isApiKeyRequired());
        assertEquals("TOKEN".equals(kind) ? List.of() : List.of("widgets/read", "widgets/write"), method.getAuthorizationScopes());
        Authorizer original = service.getAuthorizers(REGION, apiId).get(0);
        Authorizer restored = service.getAuthorizer(REGION, restoredId, method.getAuthorizerId());
        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getProviderARNs(), restored.getProviderARNs());
        assertEquals(original.getAuthorizerUri(), restored.getAuthorizerUri());
        assertEquals(original.getIdentitySource(), restored.getIdentitySource());
    }

    @ParameterizedTest
    @CsvSource(value = {"oas30|", "swagger|", "oas30|4[0-9]{2}", "swagger|4[0-9]{2}",
            "oas30|default", "swagger|default"}, delimiter = '|')
    void duplicateResponsePatternsFailExplicitly(String format, String configuredPattern) {
        String apiId = service.createRestApi(REGION, Map.of("name", "duplicate-responses")).getId();
        String rootId = service.getResources(REGION, apiId).get(0).getId();
        service.putMethod(REGION, apiId, rootId, "GET", Map.of("authorizationType", "NONE"));
        service.putIntegration(REGION, apiId, rootId, "GET", Map.of("type", "MOCK"));
        String pattern = configuredPattern == null ? "" : configuredPattern;
        service.putIntegrationResponse(REGION, apiId, rootId, "GET", "200",
                Map.of("selectionPattern", pattern, "responseTemplates", Map.of("application/json", "first")));
        service.putIntegrationResponse(REGION, apiId, rootId, "GET", "400",
                Map.of("selectionPattern", pattern, "responseTemplates", Map.of("application/json", "second")));
        service.createDeployment(REGION, apiId, Map.of("stageName", "dev"));
        AwsException error = assertThrows(AwsException.class,
                () -> service.exportRestApi(REGION, apiId, "dev", format, "integrations"));
        assertTrue(error.getMessage().contains("selection pattern"));
        assertEquals(2, service.getIntegration(REGION, apiId, rootId, "GET").getIntegrationResponses().size());
    }

    @ParameterizedTest
    @CsvSource({"CUSTOM,oas30", "COGNITO_USER_POOLS,swagger"})
    void missingStoredAuthorizerCannotProduceAnUnprotectedExport(String authorization, String format) {
        String apiId = service.createRestApi(REGION, Map.of("name", "missing-authorizer")).getId();
        String rootId = service.getResources(REGION, apiId).get(0).getId();
        service.putMethod(REGION, apiId, rootId, "GET", Map.of("authorizationType", authorization));
        service.createDeployment(REGION, apiId, Map.of("stageName", "dev"));
        AwsException error = assertThrows(AwsException.class,
                () -> service.exportRestApi(REGION, apiId, "dev", format, "authorizers"));
        assertTrue(error.getMessage().contains("Missing authorizer for protected method"));
    }

    @ParameterizedTest
    @CsvSource({"TOKEN,oas30", "TOKEN,swagger", "COGNITO_USER_POOLS,oas30", "COGNITO_USER_POOLS,swagger"})
    void unusedAuthorizerDoesNotBlockDefaultExportImportOrOverwrite(String kind, String format) {
        String apiId = protectedApi(kind);
        String rootId = service.getResources(REGION, apiId).get(0).getId();
        service.putMethod(REGION, apiId, rootId, "GET", Map.of("authorizationType", "NONE"));
        service.createDeployment(REGION, apiId, Map.of("stageName", "dev"));
        JsonNode document = service.exportRestApi(REGION, apiId, "dev", format, null);
        String restoredId = service.importRestApi(REGION, document.toString()).getId();
        MethodConfig restored = service.getResources(REGION, restoredId).get(0).getResourceMethods().get("GET");
        assertEquals("NONE", restored.getAuthorizationType());
        assertFalse(restored.isApiKeyRequired());
        service.putRestApi(REGION, apiId, "overwrite", document.toString());
        assertEquals("NONE", service.getResources(REGION, apiId).get(0).getResourceMethods().get("GET").getAuthorizationType());
    }

    @ParameterizedTest
    @CsvSource({"get,false", "get,true", "x-amazon-apigateway-any-method,false", "x-amazon-apigateway-any-method,true"})
    void incompleteAuthorizerCheckUsesEffectiveOperationSecurity(String verb, boolean overrideSecurity) {
        String document = """
                {
                  "openapi": "3.0.1", "info": {"title": "user-definition", "version": "1"},
                  "components": {"securitySchemes": {"Unused": {
                    "type": "apiKey", "in": "header", "name": "Authorization",
                    "x-amazon-apigateway-authtype": "custom"
                  }}},
                  "security": [{"Unused": []}],
                  "paths": {"/": {"%s": {
                    %s
                    "responses": {"200": {"description": "OK"}}
                  }}}
                }
                """.formatted(verb, overrideSecurity ? "\"security\": []," : "");
        if (overrideSecurity) {
            String apiId = service.importRestApi(REGION, document).getId();
            MethodConfig method = service.getResources(REGION, apiId).get(0).getResourceMethods()
                    .get("get".equals(verb) ? "GET" : "ANY");
            assertEquals("NONE", method.getAuthorizationType());
            assertFalse(method.isApiKeyRequired());
        } else {
            AwsException rejected = assertThrows(AwsException.class, () -> service.importRestApi(REGION, document));
            assertTrue(rejected.getMessage().contains("authorizer definition"));
        }
    }

    private String protectedApi(String kind) {
        String apiId = service.createRestApi(REGION, Map.of("name", "protected-export")).getId();
        String rootId = service.getResources(REGION, apiId).get(0).getId();
        Map<String, Object> request = new HashMap<>();
        request.put("name", "Protected");
        request.put("type", kind);
        request.put("identitySource", "method.request.header.Authorization");
        if ("TOKEN".equals(kind)) {
            request.put("authorizerUri", "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations");
        } else {
            request.put("providerARNs", List.of("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_fixture"));
        }
        Authorizer authorizer = service.createAuthorizer(REGION, apiId, request);
        service.putMethod(REGION, apiId, rootId, "GET", Map.of("authorizationType", "TOKEN".equals(kind) ? "CUSTOM" : kind,
                "authorizerId", authorizer.getId(), "authorizationScopes", "TOKEN".equals(kind)
                        ? List.of() : List.of("widgets/read", "widgets/write")));
        service.createDeployment(REGION, apiId, Map.of("stageName", "dev"));
        return apiId;
    }

}
