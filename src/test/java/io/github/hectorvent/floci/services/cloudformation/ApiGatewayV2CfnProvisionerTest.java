package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiGatewayV2CfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String API_ID = "api-123";
    private final ObjectMapper mapper = new ObjectMapper();
    private ApiGatewayV2Service apiGatewayV2Service;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        apiGatewayV2Service = mock(ApiGatewayV2Service.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null, null, null, null, null, null,
                null, apiGatewayV2Service, null, null, null, null, mapper,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                new CloudFormationResourceRegistry(java.util.List.of()), null);

        Api api = new Api();
        api.setApiId(API_ID);
        api.setApiEndpoint("https://" + API_ID + ".execute-api.localhost");
        when(apiGatewayV2Service.createApi(eq(REGION), anyMap())).thenReturn(api);
        when(apiGatewayV2Service.updateApi(eq(REGION), eq(API_ID), anyMap())).thenReturn(api);
    }

    @Test
    void restoresExistingRoutesAndCleansPartialReplacementWhenRouteCreationFails() throws Exception {
        Route oldRoute = route("old-route", "GET /before");
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = invocation.getArgument(2);
            return switch ((String) request.get("routeKey")) {
                case "GET /before" -> oldRoute;
                case "GET /first" -> route("partial-route", "GET /first");
                case "GET /second" -> throw new AwsException("InternalFailure", "simulated route failure", 500);
                default -> throw new AssertionError("Unexpected route key: " + request.get("routeKey"));
            };
        });
        when(apiGatewayV2Service.getRoute(REGION, API_ID, "old-route")).thenReturn(oldRoute);

        StackResource original = provision(body("""
                {"paths":{"/before":{"get":{}}}}
                """), null, Map.of());

        StackResource replacement = provision(body("""
                {"paths":{"/first":{"get":{}},"/second":{"get":{}}}}
                """), original.getPhysicalId(), original.getAttributes());

        assertEquals("CREATE_COMPLETE", original.getStatus());
        assertEquals("CREATE_FAILED", replacement.getStatus());
        assertEquals("old-route", replacement.getAttributes().get("__FlociApiGatewayV2BodyRouteIds"));
        verify(apiGatewayV2Service).deleteRoute(REGION, API_ID, "old-route");
        verify(apiGatewayV2Service).deleteRoute(REGION, API_ID, "partial-route");
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldRoute, java.util.List.of());
    }

    @Test
    void retainsPartialRoutesWhenMaterializationCleanupFails() throws Exception {
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = invocation.getArgument(2);
            return switch ((String) request.get("routeKey")) {
                case "GET /first" -> route("partial-route", "GET /first");
                case "GET /second" -> throw new AwsException("InternalFailure", "simulated route failure", 500);
                default -> throw new AssertionError("Unexpected route key: " + request.get("routeKey"));
            };
        });
        doAnswer(invocation -> {
            throw new AwsException("InternalFailure", "simulated cleanup failure", 500);
        }).when(apiGatewayV2Service).deleteRoute(REGION, API_ID, "partial-route");

        StackResource failed = provision(body("""
                {"paths":{"/first":{"get":{}},"/second":{"get":{}}}}
                """), null, Map.of());

        assertEquals("CREATE_FAILED", failed.getStatus());
        assertEquals("partial-route", failed.getAttributes().get("__FlociApiGatewayV2BodyRouteIds"));
    }

    @Test
    void restoresExistingRouteWhenReplacementCleanupKeepsAConflictingRoute() throws Exception {
        Route oldRoute = route("old-route", "GET /same");
        Route replacementRoute = route("replacement-route", "GET /same");
        AtomicInteger sameRouteCreations = new AtomicInteger();
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = invocation.getArgument(2);
            return switch ((String) request.get("routeKey")) {
                case "GET /same" -> sameRouteCreations.getAndIncrement() == 0 ? oldRoute : replacementRoute;
                case "GET /second" -> throw new AwsException("InternalFailure", "simulated route failure", 500);
                default -> throw new AssertionError("Unexpected route key: " + request.get("routeKey"));
            };
        });
        when(apiGatewayV2Service.getRoute(REGION, API_ID, "old-route")).thenReturn(oldRoute);
        doAnswer(invocation -> {
            if ("replacement-route".equals(invocation.getArgument(2))) {
                throw new AwsException("InternalFailure", "simulated cleanup failure", 500);
            }
            return null;
        }).when(apiGatewayV2Service).deleteRoute(eq(REGION), eq(API_ID), anyString());

        StackResource original = provision(body("""
                {"paths":{"/same":{"get":{}}}}
                """), null, Map.of());
        StackResource replacement = provision(body("""
                {"paths":{"/same":{"get":{}},"/second":{"get":{}}}}
                """), original.getPhysicalId(), original.getAttributes());

        assertEquals("CREATE_COMPLETE", original.getStatus());
        assertEquals("CREATE_FAILED", replacement.getStatus());
        assertEquals("old-route,replacement-route",
                replacement.getAttributes().get("__FlociApiGatewayV2BodyRouteIds"));
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldRoute,
                java.util.List.of("replacement-route"));
    }

    @Test
    void mergesFailedUpdateRouteAndIntegrationOwnershipIntoCommittedMetadata() {
        StackResource previous = new StackResource();
        previous.setResourceType("AWS::ApiGatewayV2::Api");
        previous.getAttributes().put("__FlociApiGatewayV2BodyRouteIds", "old-route");
        previous.getAttributes().put("__FlociApiGatewayV2BodyIntegrationIds", "old-integration");
        previous.getAttributes().put("__FlociApiGatewayV2BodyAuthorizerIds", "old-authorizer");

        StackResource attempted = new StackResource();
        attempted.setResourceType("AWS::ApiGatewayV2::Api");
        attempted.getAttributes().put("__FlociApiGatewayV2BodyRouteIds",
                "old-route,surviving-route");
        attempted.getAttributes().put("__FlociApiGatewayV2BodyIntegrationIds",
                "old-integration,surviving-integration");
        attempted.getAttributes().put("__FlociApiGatewayV2BodyAuthorizerIds",
                "old-authorizer,surviving-authorizer");

        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        assertEquals("old-route,surviving-route",
                previous.getAttributes().get("__FlociApiGatewayV2BodyRouteIds"));
        assertEquals("old-integration,surviving-integration",
                previous.getAttributes().get("__FlociApiGatewayV2BodyIntegrationIds"));
        assertEquals("old-authorizer,surviving-authorizer",
                previous.getAttributes().get("__FlociApiGatewayV2BodyAuthorizerIds"));
    }

    @Test
    void materializesInheritedJwtSecurityAndHonorsOperationOptOut() throws Exception {
        Authorizer authorizer = new Authorizer();
        authorizer.setAuthorizerId("body-authorizer");
        when(apiGatewayV2Service.createAuthorizer(eq(REGION), eq(API_ID), anyMap()))
                .thenReturn(authorizer);
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = invocation.getArgument(2);
            return route("route-" + request.get("routeKey"), (String) request.get("routeKey"));
        });

        StackResource resource = provision(body("""
                {
                  "components":{"securitySchemes":{"JwtAuth":{
                    "type":"oauth2",
                    "x-amazon-apigateway-authorizer":{
                      "type":"jwt",
                      "identitySource":"$request.header.Authorization",
                      "jwtConfiguration":{
                        "issuer":"https://issuer.example.com",
                        "audience":["client-id"]
                      }
                    }
                  }}},
                  "security":[{"JwtAuth":["orders/read"]}],
                  "paths":{
                    "/protected":{"get":{}},
                    "/public":{"get":{"security":[]}}
                  }
                }
                """), null, Map.of());

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals("body-authorizer",
                resource.getAttributes().get("__FlociApiGatewayV2BodyAuthorizerIds"));
        verify(apiGatewayV2Service).createAuthorizer(eq(REGION), eq(API_ID), argThat(request ->
                "JwtAuth".equals(request.get("name"))
                        && "JWT".equals(request.get("authorizerType"))
                        && "$request.header.Authorization".equals(request.get("identitySource"))));
        verify(apiGatewayV2Service).createRoute(eq(REGION), eq(API_ID), argThat(request ->
                "GET /protected".equals(request.get("routeKey"))
                        && "JWT".equals(request.get("authorizationType"))
                        && "body-authorizer".equals(request.get("authorizerId"))
                        && java.util.List.of("orders/read").equals(request.get("authorizationScopes"))));
        verify(apiGatewayV2Service).createRoute(eq(REGION), eq(API_ID), argThat(request ->
                "GET /public".equals(request.get("routeKey"))
                        && "NONE".equals(request.get("authorizationType"))
                        && !request.containsKey("authorizerId")));
    }

    @Test
    void rejectsProtectedOperationWhenSecuritySchemeCannotBeResolved() throws Exception {
        StackResource resource = provision(body("""
                {
                  "security":[{"MissingAuthorizer":[]}],
                  "paths":{"/protected":{"get":{}}}
                }
                """), null, Map.of());

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("Protected operation GET /protected references unsupported security scheme 'MissingAuthorizer'",
                resource.getStatusReason());
        verify(apiGatewayV2Service, never()).createRoute(eq(REGION), eq(API_ID), anyMap());
    }

    @Test
    void rejectsAndSecurityRequirementInsteadOfApplyingOnlyOneScheme() throws Exception {
        Authorizer first = new Authorizer();
        first.setAuthorizerId("first-authorizer");
        Authorizer second = new Authorizer();
        second.setAuthorizerId("second-authorizer");
        when(apiGatewayV2Service.createAuthorizer(eq(REGION), eq(API_ID), anyMap()))
                .thenReturn(first, second);

        StackResource resource = provision(body("""
                {
                  "components":{"securitySchemes":{
                    "First":{"x-amazon-apigateway-authorizer":{
                      "type":"jwt","jwtConfiguration":{"issuer":"https://first.example.com"}
                    }},
                    "Second":{"x-amazon-apigateway-authorizer":{
                      "type":"jwt","jwtConfiguration":{"issuer":"https://second.example.com"}
                    }}
                  }},
                  "security":[{"First":[],"Second":[]}],
                  "paths":{"/protected":{"get":{}}}
                }
                """), null, Map.of());

        assertEquals("CREATE_FAILED", resource.getStatus());
        verify(apiGatewayV2Service, never()).createRoute(eq(REGION), eq(API_ID), anyMap());
        verify(apiGatewayV2Service).deleteAuthorizer(REGION, API_ID, "first-authorizer");
        verify(apiGatewayV2Service).deleteAuthorizer(REGION, API_ID, "second-authorizer");
    }

    @Test
    void rejectsUnrepresentableSecurityOrAlternativesInsteadOfChoosingOne() throws Exception {
        StackResource resource = provision(body("""
                {
                  "security":[{"First":[]},{"Second":[]}],
                  "paths":{"/protected":{"get":{}}}
                }
                """), null, Map.of());

        assertEquals("CREATE_FAILED", resource.getStatus());
        verify(apiGatewayV2Service, never()).createRoute(eq(REGION), eq(API_ID), anyMap());
    }

    @Test
    void honorsAnonymousAlternativeInSecurityOrList() throws Exception {
        Authorizer authorizer = new Authorizer();
        authorizer.setAuthorizerId("optional-authorizer");
        when(apiGatewayV2Service.createAuthorizer(eq(REGION), eq(API_ID), anyMap()))
                .thenReturn(authorizer);
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap()))
                .thenReturn(route("optional-route", "GET /optional"));

        StackResource resource = provision(body("""
                {
                  "components":{"securitySchemes":{"JwtAuth":{
                    "x-amazon-apigateway-authorizer":{
                      "type":"jwt","jwtConfiguration":{"issuer":"https://issuer.example.com"}
                    }
                  }}},
                  "security":[{"JwtAuth":[]},{}],
                  "paths":{"/optional":{"get":{}}}
                }
                """), null, Map.of());

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        verify(apiGatewayV2Service).createRoute(eq(REGION), eq(API_ID), argThat(request ->
                "GET /optional".equals(request.get("routeKey"))
                        && "NONE".equals(request.get("authorizationType"))));
    }

    @Test
    void materializesRequestAuthorizerSecurityAsCustomAuthorization() throws Exception {
        Authorizer authorizer = new Authorizer();
        authorizer.setAuthorizerId("request-authorizer");
        when(apiGatewayV2Service.createAuthorizer(eq(REGION), eq(API_ID), anyMap()))
                .thenReturn(authorizer);
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap()))
                .thenReturn(route("protected-route", "POST /protected"));

        StackResource resource = provision(body("""
                {
                  "components":{"securitySchemes":{"RequestAuth":{
                    "type":"apiKey",
                    "x-amazon-apigateway-authorizer":{
                      "type":"request",
                      "identitySource":["$request.header.Authorization"],
                      "authorizerUri":"arn:aws:apigateway:us-east-1:lambda:path/functions/auth/invocations",
                      "authorizerPayloadFormatVersion":"2.0",
                      "enableSimpleResponses":true
                    }
                  }}},
                  "paths":{"/protected":{"post":{"security":[{"RequestAuth":[]}]}}}
                }
                """), null, Map.of());

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        verify(apiGatewayV2Service).createAuthorizer(eq(REGION), eq(API_ID), argThat(request ->
                "REQUEST".equals(request.get("authorizerType"))
                        && Boolean.TRUE.equals(request.get("enableSimpleResponses"))));
        verify(apiGatewayV2Service).createRoute(eq(REGION), eq(API_ID), argThat(request ->
                "POST /protected".equals(request.get("routeKey"))
                        && "CUSTOM".equals(request.get("authorizationType"))
                        && "request-authorizer".equals(request.get("authorizerId"))));
    }

    @Test
    void restoresBodyAuthorizerWhenSecuredRouteReplacementFails() throws Exception {
        Authorizer oldAuthorizer = new Authorizer();
        oldAuthorizer.setAuthorizerId("old-authorizer");
        Authorizer replacementAuthorizer = new Authorizer();
        replacementAuthorizer.setAuthorizerId("replacement-authorizer");
        when(apiGatewayV2Service.createAuthorizer(eq(REGION), eq(API_ID), anyMap()))
                .thenReturn(oldAuthorizer, replacementAuthorizer);

        Route oldRoute = route("old-route", "GET /protected");
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap()))
                .thenReturn(oldRoute)
                .thenThrow(new AwsException("InternalFailure", "simulated route failure", 500));
        when(apiGatewayV2Service.getRoute(REGION, API_ID, "old-route")).thenReturn(oldRoute);
        when(apiGatewayV2Service.getAuthorizer(REGION, API_ID, "old-authorizer"))
                .thenReturn(oldAuthorizer);

        String securedBody = """
                {
                  "components":{"securitySchemes":{"JwtAuth":{
                    "x-amazon-apigateway-authorizer":{
                      "type":"jwt",
                      "jwtConfiguration":{"issuer":"https://issuer.example.com"}
                    }
                  }}},
                  "security":[{"JwtAuth":[]}],
                  "paths":{"/protected":{"get":{}}}
                }
                """;
        StackResource original = provision(body(securedBody), null, Map.of());
        StackResource replacement = provision(body(securedBody), original.getPhysicalId(),
                original.getAttributes());

        assertEquals("CREATE_COMPLETE", original.getStatus());
        assertEquals("CREATE_FAILED", replacement.getStatus());
        assertEquals("old-authorizer",
                replacement.getAttributes().get("__FlociApiGatewayV2BodyAuthorizerIds"));
        verify(apiGatewayV2Service).deleteAuthorizer(REGION, API_ID, "old-authorizer");
        verify(apiGatewayV2Service).deleteAuthorizer(REGION, API_ID, "replacement-authorizer");
        verify(apiGatewayV2Service).restoreAuthorizer(REGION, API_ID, oldAuthorizer);
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldRoute, java.util.List.of());
    }

    @Test
    void rejectsSigV4SecurityUntilHttpApiIamEnforcementIsSupported() throws Exception {
        StackResource resource = provision(body("""
                {
                  "components":{"securitySchemes":{"SigV4":{
                    "type":"apiKey",
                    "x-amazon-apigateway-authtype":"awsSigv4"
                  }}},
                  "security":[{"SigV4":[]}],
                  "paths":{"/iam":{"get":{}}}
                }
                """), null, Map.of());

        assertEquals("CREATE_FAILED", resource.getStatus());
        verify(apiGatewayV2Service, never()).createAuthorizer(eq(REGION), eq(API_ID), anyMap());
        verify(apiGatewayV2Service, never()).createRoute(eq(REGION), eq(API_ID), anyMap());
    }

    @Test
    void restoresExistingRoutesWhenOldRouteDeletionFails() throws Exception {
        Route oldOne = route("old-one", "GET /before-one");
        Route oldTwo = route("old-two", "GET /before-two");
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = invocation.getArgument(2);
            return switch ((String) request.get("routeKey")) {
                case "GET /before-one" -> oldOne;
                case "GET /before-two" -> oldTwo;
                case "GET /after" -> route("replacement-route", "GET /after");
                default -> throw new AssertionError("Unexpected route key: " + request.get("routeKey"));
            };
        });
        when(apiGatewayV2Service.getRoute(REGION, API_ID, "old-one")).thenReturn(oldOne);
        when(apiGatewayV2Service.getRoute(REGION, API_ID, "old-two")).thenReturn(oldTwo);
        doAnswer(invocation -> {
            if ("old-two".equals(invocation.getArgument(2))) {
                throw new AwsException("InternalFailure", "simulated delete failure", 500);
            }
            return null;
        }).when(apiGatewayV2Service).deleteRoute(eq(REGION), eq(API_ID), anyString());

        StackResource original = provision(body("""
                {"paths":{"/before-one":{"get":{}},"/before-two":{"get":{}}}}
                """), null, Map.of());

        StackResource replacement = provision(body("""
                {"paths":{"/after":{"get":{}}}}
                """), original.getPhysicalId(), original.getAttributes());

        assertEquals("CREATE_COMPLETE", original.getStatus());
        assertEquals("CREATE_FAILED", replacement.getStatus());
        assertEquals("old-one,old-two", replacement.getAttributes().get("__FlociApiGatewayV2BodyRouteIds"));
        verify(apiGatewayV2Service, never()).createRoute(eq(REGION), eq(API_ID),
                argThat(request -> "GET /after".equals(request.get("routeKey"))));
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldOne, java.util.List.of());
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldTwo, java.util.List.of());
    }

    @Test
    void restoresExistingRoutesWhenDefinitionRemovalFails() throws Exception {
        Route oldOne = route("old-one", "GET /before-one");
        Route oldTwo = route("old-two", "GET /before-two");
        when(apiGatewayV2Service.createRoute(eq(REGION), eq(API_ID), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = invocation.getArgument(2);
            return switch ((String) request.get("routeKey")) {
                case "GET /before-one" -> oldOne;
                case "GET /before-two" -> oldTwo;
                default -> throw new AssertionError("Unexpected route key: " + request.get("routeKey"));
            };
        });
        when(apiGatewayV2Service.getRoute(REGION, API_ID, "old-one")).thenReturn(oldOne);
        when(apiGatewayV2Service.getRoute(REGION, API_ID, "old-two")).thenReturn(oldTwo);
        doAnswer(invocation -> {
            if ("old-two".equals(invocation.getArgument(2))) {
                throw new AwsException("InternalFailure", "simulated delete failure", 500);
            }
            return null;
        }).when(apiGatewayV2Service).deleteRoute(eq(REGION), eq(API_ID), anyString());

        StackResource original = provision(body("""
                {"paths":{"/before-one":{"get":{}},"/before-two":{"get":{}}}}
                """), null, Map.of());

        StackResource removal = provision(propertiesWithoutBody(), original.getPhysicalId(),
                original.getAttributes());

        assertEquals("CREATE_COMPLETE", original.getStatus());
        assertEquals("CREATE_FAILED", removal.getStatus());
        assertEquals("old-one,old-two", removal.getAttributes().get("__FlociApiGatewayV2BodyRouteIds"));
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldOne, java.util.List.of());
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldTwo, java.util.List.of());
    }

    private StackResource provision(JsonNode properties, String existingPhysicalId,
                                    Map<String, String> existingAttributes) {
        return provisioner.provision("HttpApi", "AWS::ApiGatewayV2::Api", properties, engine(), REGION,
                "000000000000", "test-stack", existingPhysicalId, existingAttributes);
    }

    private JsonNode body(String body) throws Exception {
        return mapper.readTree("""
                {"Name":"test-api","ProtocolType":"HTTP","Body":%s}
                """.formatted(body));
    }

    private JsonNode propertiesWithoutBody() throws Exception {
        return mapper.readTree("""
                {"Name":"test-api","ProtocolType":"HTTP"}
                """);
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", REGION, "test-stack", "stack/id",
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private static Route route(String id, String routeKey) {
        Route route = new Route();
        route.setRouteId(id);
        route.setRouteKey(routeKey);
        return route;
    }
}
