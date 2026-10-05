package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.apigatewayv2.model.VpcLink;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ApiGatewayV2CfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiGatewayV2CfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String API_ID = "api-123";
    private final ObjectMapper mapper = new ObjectMapper();
    private ApiGatewayV2Service apiGatewayV2Service;
    private CfnResourceDispatcher provisioner;

    @BeforeEach
    void setUp() {
        apiGatewayV2Service = mock(ApiGatewayV2Service.class);
        provisioner = CfnProvisionerFixture.builder()
                .apiGatewayV2(apiGatewayV2Service)
                .objectMapper(mapper)
                .provisioners(new ApiGatewayV2CfnProvisioner(apiGatewayV2Service, mock(S3Service.class), mapper))
                .build();

        Api api = new Api();
        api.setApiId(API_ID);
        api.setApiEndpoint("https://" + API_ID + ".execute-api.localhost");
        when(apiGatewayV2Service.createApi(eq(REGION), anyMap())).thenReturn(api);
        when(apiGatewayV2Service.updateApi(eq(REGION), eq(API_ID), anyMap())).thenReturn(api);
    }

    @Test
    void publishesApiIdEndpointAndExecuteApiArn() throws Exception {
        StackResource api = provision(propertiesWithoutBody(), null, Map.of());

        assertEquals("CREATE_COMPLETE", api.getStatus());
        assertEquals(API_ID, api.getAttributes().get("ApiId"));
        assertEquals("https://" + API_ID + ".execute-api.localhost", api.getAttributes().get("ApiEndpoint"));
        assertEquals("arn:aws:execute-api:us-east-1:000000000000:api-123",
                api.getAttributes().get("ExecuteApiArn"));
    }

    @Test
    void authorizerWithNonNumericTtlIsRejected() throws Exception {
        StackResource authorizer = provisioner.provision("MyAuth", "AWS::ApiGatewayV2::Authorizer",
                mapper.readTree("""
                        {"ApiId": "api-123",
                         "IdentitySource": ["$request.header.Authorization"],
                         "AuthorizerResultTtlInSeconds": "not-a-number"}
                        """),
                engine(), REGION, "000000000000", "test-stack", null, Map.of());

        assertEquals("CREATE_FAILED", authorizer.getStatus());
        assertTrue(authorizer.getStatusReason().contains("AuthorizerResultTtlInSeconds must be an integer"),
                authorizer.getStatusReason());
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
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldRoute, List.of());
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
                List.of("replacement-route"));
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
    void carriesAReplacementCleanupRecordOntoTheRestoredResourceForAnyType() {
        StackResource previous = new StackResource();
        previous.setLogicalId("Subnet");
        previous.setResourceType("AWS::EC2::Subnet");
        previous.setPhysicalId("subnet-prior");
        previous.setAttributes(new java.util.HashMap<>());
        StackResource attempted = new StackResource();
        attempted.setLogicalId("Subnet");
        attempted.setResourceType("AWS::EC2::Subnet");
        attempted.setPhysicalId("subnet-replacement");
        attempted.setAttributes(new java.util.HashMap<>(Map.of(
                "__FlociReplacementCleanup",
                "{\"region\":\"us-east-1\",\"displaced\":[{\"physicalId\":\"subnet-replacement\",\"resourceType\":\"AWS::EC2::Subnet\",\"region\":\"us-east-1\",\"retainable\":false,\"cleanupAttempts\":1}]}")));

        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        String record = previous.getAttributes().get("__FlociReplacementCleanup");
        assertTrue(record != null && record.contains("subnet-replacement") && record.contains("\"cleanupAttempts\":1"),
                "the orphan the attempt could not remove is owed on the restored resource: " + record);
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
                        && List.of("orders/read").equals(request.get("authorizationScopes"))));
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
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldRoute, List.of());
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
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldOne, List.of());
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldTwo, List.of());
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
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldOne, List.of());
        verify(apiGatewayV2Service).restoreRoute(REGION, API_ID, oldTwo, List.of());
    }

    @Test
    void vpcLinkCreatesTheLinkAndPublishesItsId() throws Exception {
        when(apiGatewayV2Service.createVpcLink(eq(REGION), anyMap())).thenReturn(vpcLink("vl-1", List.of("subnet-a")));

        StackResource link = provisionVpcLink("""
                {"Name":"my-link","SubnetIds":["subnet-a"],"SecurityGroupIds":["sg-1"],"Tags":{"Env":"test"}}
                """, null, Map.of());

        assertEquals("CREATE_COMPLETE", link.getStatus());
        assertEquals("vl-1", link.getPhysicalId());
        assertEquals("vl-1", link.getAttributes().get("VpcLinkId"));
        verify(apiGatewayV2Service).createVpcLink(eq(REGION), argThat(req -> "my-link".equals(req.get("name"))
                && List.of("subnet-a").equals(req.get("subnetIds"))
                && List.of("sg-1").equals(req.get("securityGroupIds"))
                && Map.of("Env", "test").equals(req.get("tags"))));
    }

    @Test
    void vpcLinkNameChangeUpdatesTheLinkInPlace() throws Exception {
        VpcLink prior = vpcLink("vl-1", List.of("subnet-a"));
        prior.setName("old-name");
        when(apiGatewayV2Service.getVpcLink(REGION, "vl-1")).thenReturn(prior);

        StackResource link = provisionVpcLink("""
                {"Name":"new-name","SubnetIds":["subnet-a"]}
                """, "vl-1", Map.of("VpcLinkId", "vl-1"));

        assertEquals("vl-1", link.getPhysicalId());
        verify(apiGatewayV2Service).updateVpcLink(REGION, "vl-1", Map.of("name", "new-name"));
        verify(apiGatewayV2Service, never()).createVpcLink(anyString(), anyMap());
        assertFalse(provisioner.hasReplacementUpdate(link));
    }

    @Test
    void vpcLinkSubnetChangeReplacesTheLink() throws Exception {
        when(apiGatewayV2Service.getVpcLink(REGION, "vl-1")).thenReturn(vpcLink("vl-1", List.of("subnet-a")));
        when(apiGatewayV2Service.createVpcLink(eq(REGION), anyMap())).thenReturn(vpcLink("vl-2", List.of("subnet-b")));

        StackResource link = provisionVpcLink("""
                {"Name":"my-link","SubnetIds":["subnet-b"]}
                """, "vl-1", Map.of("VpcLinkId", "vl-1"));

        assertEquals("vl-2", link.getPhysicalId());
        assertEquals("vl-2", link.getAttributes().get("VpcLinkId"));
        verify(apiGatewayV2Service, never()).updateVpcLink(anyString(), anyString(), anyMap());
        assertTrue(provisioner.hasReplacementUpdate(link), "the displaced link is deleted after the update");
    }

    @Test
    void vpcLinkWithoutSubnetIdsFailsBeforeCallingTheService() throws Exception {
        StackResource link = provisionVpcLink("""
                {"Name":"my-link"}
                """, null, Map.of());

        assertEquals("CREATE_FAILED", link.getStatus());
        assertTrue(link.getStatusReason().contains("requires SubnetIds"), link.getStatusReason());
        verify(apiGatewayV2Service, never()).createVpcLink(anyString(), anyMap());
    }

    @Test
    void rollbackOfAnInPlaceVpcLinkUpdateRestoresNameAndTags() throws Exception {
        VpcLink prior = vpcLink("vl-1", List.of("subnet-a"));
        prior.setName("old-name");
        prior.setTags(new HashMap<>(Map.of("Env", "old")));
        when(apiGatewayV2Service.getVpcLink(REGION, "vl-1")).thenReturn(prior);
        when(apiGatewayV2Service.getTags(anyString())).thenReturn(Map.of("Env", "new"));
        StackResource link = provisionVpcLink("""
                {"Name":"new-name","SubnetIds":["subnet-a"],"Tags":{"Env":"new"}}
                """, "vl-1", Map.of("VpcLinkId", "vl-1"));

        assertTrue(provisioner.rollbackUpdate(link));

        verify(apiGatewayV2Service).updateVpcLink(REGION, "vl-1", Map.of("name", "old-name"));
        verify(apiGatewayV2Service).tagResource("arn:aws:apigateway:us-east-1::/vpclinks/vl-1", Map.of("Env", "old"));
        assertFalse(link.getAttributes().containsKey("__FlociVpcLinkUpdateSnapshot"));
    }

    @Test
    void integrationPassesItsVpcLinkConnection() throws Exception {
        Integration integration = new Integration();
        integration.setIntegrationId("int-1");
        when(apiGatewayV2Service.createIntegration(eq(REGION), eq(API_ID), anyMap())).thenReturn(integration);

        provisioner.provision("Integration", "AWS::ApiGatewayV2::Integration", mapper.readTree("""
                        {"ApiId":"api-123","IntegrationType":"HTTP_PROXY",
                         "IntegrationUri":"arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/alb/1/2",
                         "ConnectionType":"VPC_LINK","ConnectionId":"vl-1"}
                        """),
                engine(), REGION, "000000000000", "test-stack", null, Map.of());

        verify(apiGatewayV2Service).createIntegration(eq(REGION), eq(API_ID),
                argThat(req -> "VPC_LINK".equals(req.get("connectionType")) && "vl-1".equals(req.get("connectionId"))));
    }

    @Test
    void failedInPlaceVpcLinkUpdateRestoresTagsAndSendsOnlyChangedTags() throws Exception {
        String arn = "arn:aws:apigateway:us-east-1::/vpclinks/vl-1";
        VpcLink prior = vpcLink("vl-1", List.of("subnet-a"));
        prior.setName("old-name");
        prior.setTags(new HashMap<>(Map.of("floci:override-id", "vl-1", "Env", "old")));
        when(apiGatewayV2Service.getVpcLink(REGION, "vl-1")).thenReturn(prior);
        when(apiGatewayV2Service.getTags(arn)).thenReturn(
                Map.of("floci:override-id", "vl-1", "Env", "old"),
                Map.of("floci:override-id", "vl-1", "Env", "new"));
        when(apiGatewayV2Service.updateVpcLink(REGION, "vl-1", Map.of("name", "new-name")))
                .thenThrow(new AwsException("BadRequestException", "rename failed", 400));

        StackResource link = provisionVpcLink("""
                {"Name":"new-name","SubnetIds":["subnet-a"],"Tags":{"floci:override-id":"vl-1","Env":"new"}}
                """, "vl-1", Map.of("VpcLinkId", "vl-1"));

        assertTrue(link.getStatus().endsWith("_FAILED"), link.getStatus());
        verify(apiGatewayV2Service).tagResource(arn, Map.of("Env", "new"));
        verify(apiGatewayV2Service).tagResource(arn, Map.of("Env", "old"));
        verify(apiGatewayV2Service).updateVpcLink(REGION, "vl-1", Map.of("name", "old-name"));
        assertFalse(link.getAttributes().containsKey("__FlociUpdateRollbackFailure"));
        assertFalse(link.getAttributes().containsKey("__FlociVpcLinkUpdateSnapshot"));
    }

    @Test
    void integrationUpdateWithoutConnectionResetsItToInternet() throws Exception {
        stubIntegrationUpdate(priorVpcLinkIntegration());

        StackResource integration = provisionIntegration("""
                {"ApiId":"api-123","IntegrationType":"HTTP_PROXY","IntegrationUri":"http://backend"}
                """);

        assertTrue(integration.getStatus().endsWith("_COMPLETE"), integration.getStatus());
        verify(apiGatewayV2Service).replaceIntegrationConnection(REGION, API_ID, "int-1", "INTERNET", null);
    }

    @Test
    void rollbackOfAnIntegrationUpdateRestoresItsConnection() throws Exception {
        stubIntegrationUpdate(priorVpcLinkIntegration());
        StackResource integration = provisionIntegration("""
                {"ApiId":"api-123","IntegrationType":"HTTP_PROXY","IntegrationUri":"http://backend",
                 "ConnectionType":"VPC_LINK","ConnectionId":"vl-2"}
                """);

        assertTrue(provisioner.rollbackUpdate(integration));

        verify(apiGatewayV2Service).replaceIntegrationConnection(REGION, API_ID, "int-1", "VPC_LINK", "vl-1");
        assertFalse(integration.getAttributes().containsKey("__FlociIntegrationConnectionSnapshot"));
    }

    @Test
    void rollbackOfAnIntegrationUpdateWithOtherChangesRestoresConnectionButReportsFailure() throws Exception {
        stubIntegrationUpdate(priorVpcLinkIntegration());
        StackResource integration = provisionIntegration("""
                {"ApiId":"api-123","IntegrationType":"HTTP_PROXY","IntegrationUri":"http://other-backend",
                 "ConnectionType":"VPC_LINK","ConnectionId":"vl-2"}
                """);

        assertFalse(provisioner.rollbackUpdate(integration), "the IntegrationUri change has no rollback");

        verify(apiGatewayV2Service).replaceIntegrationConnection(REGION, API_ID, "int-1", "VPC_LINK", "vl-1");
    }

    @Test
    void failedIntegrationUpdateRestoresItsConnection() throws Exception {
        stubIntegrationUpdate(priorVpcLinkIntegration());
        when(apiGatewayV2Service.updateIntegration(eq(REGION), eq(API_ID), eq("int-1"), anyMap()))
                .thenThrow(new AwsException("NotFoundException", "Integration not found", 404));

        StackResource integration = provisionIntegration("""
                {"ApiId":"api-123","IntegrationType":"HTTP_PROXY","IntegrationUri":"http://backend",
                 "ConnectionType":"VPC_LINK","ConnectionId":"vl-2"}
                """);

        assertTrue(integration.getStatus().endsWith("_FAILED"), integration.getStatus());
        InOrder order = inOrder(apiGatewayV2Service);
        order.verify(apiGatewayV2Service).replaceIntegrationConnection(REGION, API_ID, "int-1", "VPC_LINK", "vl-2");
        order.verify(apiGatewayV2Service).updateIntegration(eq(REGION), eq(API_ID), eq("int-1"), anyMap());
        order.verify(apiGatewayV2Service).replaceIntegrationConnection(REGION, API_ID, "int-1", "VPC_LINK", "vl-1");
        assertFalse(integration.getAttributes().containsKey("__FlociUpdateRollbackFailure"));
        assertFalse(integration.getAttributes().containsKey("__FlociIntegrationConnectionSnapshot"));
    }

    private static Integration priorVpcLinkIntegration() {
        Integration prior = new Integration();
        prior.setIntegrationId("int-1");
        prior.setIntegrationType("HTTP_PROXY");
        prior.setIntegrationUri("http://backend");
        prior.setPayloadFormatVersion("2.0");
        prior.setConnectionType("VPC_LINK");
        prior.setConnectionId("vl-1");
        return prior;
    }

    private void stubIntegrationUpdate(Integration prior) {
        when(apiGatewayV2Service.getIntegration(REGION, API_ID, "int-1")).thenReturn(prior);
        when(apiGatewayV2Service.updateIntegration(eq(REGION), eq(API_ID), eq("int-1"), anyMap())).thenReturn(prior);
        when(apiGatewayV2Service.replaceIntegrationConnection(eq(REGION), eq(API_ID), eq("int-1"), anyString(), any()))
                .thenReturn(prior);
    }

    private StackResource provisionIntegration(String properties) throws Exception {
        return provisioner.provision("Integration", "AWS::ApiGatewayV2::Integration", mapper.readTree(properties),
                engine(), REGION, "000000000000", "test-stack", "int-1", Map.of("IntegrationId", "int-1"));
    }

    private StackResource provision(JsonNode properties, String existingPhysicalId,
                                    Map<String, String> existingAttributes) {
        return provisioner.provision("HttpApi", "AWS::ApiGatewayV2::Api", properties, engine(), REGION,
                "000000000000", "test-stack", existingPhysicalId, existingAttributes);
    }

    private StackResource provisionVpcLink(String properties, String existingPhysicalId,
                                           Map<String, String> existingAttributes) throws Exception {
        return provisioner.provision("VpcLink", "AWS::ApiGatewayV2::VpcLink", mapper.readTree(properties),
                engine(), REGION, "000000000000", "test-stack", existingPhysicalId, existingAttributes);
    }

    private static VpcLink vpcLink(String id, List<String> subnetIds) {
        VpcLink link = new VpcLink();
        link.setVpcLinkId(id);
        link.setName("my-link");
        link.setSubnetIds(subnetIds);
        return link;
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
