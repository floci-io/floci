package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.apigatewayv2.ApiGatewayV2Service;
import io.floci.aws.services.apigatewayv2.ApiGatewayV2ServiceFixture;
import io.floci.aws.services.apigatewayv2.model.Authorizer;
import io.floci.aws.services.apigatewayv2.model.Route;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.ApiGatewayV2CfnProvisioner;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static io.floci.aws.services.cloudformation.CfnProvisionerFixture.engine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * What an in-place update leaves on an AWS::ApiGatewayV2::Route and an AWS::ApiGatewayV2::Authorizer
 * when the template drops a setting, over the real service and the real provisioner. A setting the
 * previous template declared and the new one drops reads back as it does on a create without it. A
 * setting the template never declared is left alone, so a value set through the API survives.
 * The Route {@code RouteKey} and the Authorizer {@code Name}, {@code AuthorizerType},
 * {@code AuthorizerUri}, {@code AuthorizerPayloadFormatVersion} and {@code JwtConfiguration} are
 * required by AWS for the resource or its type, so a drop keeps them.
 */
class ApiGatewayV2RouteAuthorizerDropTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROUTE_BASE = "\"ApiId\":\"%s\",\"RouteKey\":\"GET /items\"";
    private static final String LAMBDA_URI = "arn:aws:lambda:us-east-1:000000000000:function:auth";
    private static final String LAMBDA_SETTINGS =
            "\"AuthorizerUri\":\"" + LAMBDA_URI + "\",\"AuthorizerPayloadFormatVersion\":\"2.0\"";
    private static final String JWT_CONFIGURATION =
            ",\"JwtConfiguration\":{\"Audience\":[\"aud\"],\"Issuer\":\"https://issuer.example\"}";

    private static final String AUTHORIZER_BASE =
            "\"ApiId\":\"%s\",\"Name\":\"auth\",\"AuthorizerType\":\"REQUEST\"," + LAMBDA_SETTINGS;

    private final ObjectMapper mapper = new ObjectMapper();
    private ApiGatewayV2Service service;
    private CfnResourceDispatcher provisioner;
    private String apiId;

    @BeforeEach
    void setUp() {
        service = ApiGatewayV2ServiceFixture.service(new InMemoryStorageFactory(ACCOUNT), REGION, ACCOUNT);
        provisioner = CfnProvisionerFixture.builder()
                .apiGatewayV2(service)
                .objectMapper(mapper)
                .provisioners(new ApiGatewayV2CfnProvisioner(service, mock(S3Service.class), mapper))
                .build();
        apiId = service.createApi(REGION, Map.of("name", "drop-api", "protocolType", "HTTP")).getApiId();
    }

    // ── Route ────────────────────────────────────────────────────────────────

    @Test
    void routeTargetDroppedFromTheTemplateReadsBackAbsent() throws Exception {
        StackResource created = createRoute(",\"Target\":\"integrations/int-1\"");
        assertEquals("integrations/int-1", route(created).getTarget());

        updateRoute(created, ",\"Target\":\"integrations/int-1\"", "");

        assertNull(route(created).getTarget());
    }

    @Test
    void routeTargetMadeNoValueByRefReadsBackAbsent() throws Exception {
        StackResource created = createRoute(",\"Target\":\"integrations/int-1\"");

        updateRoute(created, ",\"Target\":\"integrations/int-1\"", ",\"Target\":{\"Ref\":\"AWS::NoValue\"}");

        assertNull(route(created).getTarget());
    }

    @Test
    void routeTargetMadeNoValueByAWholeFnIfReadsBackAbsent() throws Exception {
        StackResource created = createRoute(",\"Target\":\"integrations/int-1\"");

        updateRoute(created, ",\"Target\":\"integrations/int-1\"",
                ",\"Target\":{\"Fn::If\":[\"Off\",{\"Ref\":\"AWS::NoValue\"},\"integrations/int-1\"]}");

        assertNull(route(created).getTarget());
    }

    @Test
    void routeAuthorizerIdDroppedFromTheTemplateReadsBackAbsent() throws Exception {
        String authorizerId = createAuthorizerId();
        String declared = ",\"AuthorizationType\":\"NONE\",\"AuthorizerId\":\"" + authorizerId + "\"";
        StackResource created = createRoute(declared);
        assertEquals(authorizerId, route(created).getAuthorizerId());

        updateRoute(created, declared, ",\"AuthorizationType\":\"NONE\"");

        assertNull(route(created).getAuthorizerId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"JWT", "CUSTOM"})
    void routeAuthorizerIdDroppedWhileTheRouteStaysProtectedIsKeptAndTargetIsStillCleared(String type)
            throws Exception {
        String authorizerId = createAuthorizerId();
        String declared = ",\"AuthorizationType\":\"" + type + "\",\"AuthorizerId\":\"" + authorizerId
                + "\",\"Target\":\"integrations/int-1\"";
        StackResource created = createRoute(declared);

        updateRoute(created, declared, ",\"AuthorizationType\":\"" + type + "\"");

        Route route = route(created);
        assertEquals(type, route.getAuthorizationType());
        assertEquals(authorizerId, route.getAuthorizerId());
        assertNull(route.getTarget());
    }

    @Test
    void routeAuthorizerIdDroppedWhileTheTypeBecomesJwtIsKept() throws Exception {
        String authorizerId = createAuthorizerId();
        String before = ",\"AuthorizationType\":\"AWS_IAM\",\"AuthorizerId\":\"" + authorizerId + "\"";
        StackResource created = createRoute(before);

        updateRoute(created, before, ",\"AuthorizationType\":\"JWT\"");

        Route route = route(created);
        assertEquals("JWT", route.getAuthorizationType());
        assertEquals(authorizerId, route.getAuthorizerId());
    }

    @ParameterizedTest
    @ValueSource(strings = {",\"AuthorizationType\":\"NONE\"", ",\"AuthorizationType\":\"AWS_IAM\"", ""})
    void routeAuthorizerIdDroppedWhileTheRouteBecomesOpenOrIamIsCleared(String after) throws Exception {
        String authorizerId = createAuthorizerId();
        String before = ",\"AuthorizationType\":\"JWT\",\"AuthorizerId\":\"" + authorizerId + "\"";
        StackResource created = createRoute(before);

        updateRoute(created, before, after);

        assertNull(route(created).getAuthorizerId());
    }

    @Test
    void routeAuthorizationScopesDroppedFromTheTemplateReadBackAsAnEmptyList() throws Exception {
        String declared = ",\"AuthorizationType\":\"JWT\",\"AuthorizationScopes\":[\"read\",\"write\"]";
        StackResource created = createRoute(declared);
        assertEquals(List.of("read", "write"), route(created).getAuthorizationScopes());

        updateRoute(created, declared, ",\"AuthorizationType\":\"JWT\"");

        assertEquals(List.of(), route(created).getAuthorizationScopes());
    }

    @Test
    void routeAuthorizationTypeDroppedFromTheTemplateReadsBackNone() throws Exception {
        String declared = ",\"AuthorizationType\":\"AWS_IAM\"";
        StackResource created = createRoute(declared);
        assertEquals("AWS_IAM", route(created).getAuthorizationType());

        updateRoute(created, declared, "");

        assertEquals("NONE", route(created).getAuthorizationType());
    }

    @Test
    void routeKeyDroppedFromTheTemplateIsKept() throws Exception {
        StackResource created = createRoute("");

        provisioner.provision("Route", "AWS::ApiGatewayV2::Route",
                mapper.readTree("{\"ApiId\":\"" + apiId + "\"}"), engine(mapper, REGION, Map.of()), REGION, ACCOUNT, "test-stack",
                created.getPhysicalId(), created.getAttributes(), event -> { },
                mapper.readTree("{" + ROUTE_BASE.formatted(apiId) + "}"));

        assertEquals("GET /items", route(created).getRouteKey());
    }

    @Test
    void routeTargetAndAuthorizerIdSetOutsideTheStackSurviveAnUnrelatedUpdate() throws Exception {
        StackResource created = createRoute("");
        String authorizerId = createAuthorizerId();
        service.updateRoute(REGION, apiId, created.getPhysicalId(),
                Map.of("target", "integrations/outside", "authorizerId", authorizerId));

        updateRoute(created, "", ",\"AuthorizationType\":\"AWS_IAM\"");

        Route route = route(created);
        assertEquals("AWS_IAM", route.getAuthorizationType());
        assertEquals("integrations/outside", route.getTarget());
        assertEquals(authorizerId, route.getAuthorizerId());
    }

    @Test
    void changedRouteTargetAndAuthorizerIdStillApply() throws Exception {
        String first = createAuthorizerId();
        String second = createAuthorizerId();
        String before = ",\"AuthorizationType\":\"CUSTOM\",\"AuthorizerId\":\"" + first
                + "\",\"Target\":\"integrations/int-1\"";
        StackResource created = createRoute(before);

        updateRoute(created, before, ",\"AuthorizationType\":\"CUSTOM\",\"AuthorizerId\":\"" + second
                + "\",\"Target\":\"integrations/int-2\"");

        Route route = route(created);
        assertEquals("integrations/int-2", route.getTarget());
        assertEquals(second, route.getAuthorizerId());
    }

    @Test
    void changingTheAuthorizationTypeToNoneTogetherWithDroppingTheAuthorizerIdLeavesNoAuthorizer() throws Exception {
        String authorizerId = createAuthorizerId();
        String declared = ",\"AuthorizationType\":\"JWT\",\"AuthorizerId\":\"" + authorizerId
                + "\",\"AuthorizationScopes\":[\"read\"]";
        StackResource created = createRoute(declared);

        updateRoute(created, declared, "");

        Route route = route(created);
        assertEquals("NONE", route.getAuthorizationType());
        assertNull(route.getAuthorizerId());
        assertEquals(List.of(), route.getAuthorizationScopes());
    }

    @Test
    void aDeclaredEmptyTargetReadsBackAsACreateWithAnEmptyTargetDoes() throws Exception {
        StackResource declaredOnCreate = provisioner.provision("Other", "AWS::ApiGatewayV2::Route",
                mapper.readTree("{\"ApiId\":\"" + apiId + "\",\"RouteKey\":\"GET /other\",\"Target\":\"\"}"),
                engine(mapper, REGION, Map.of()), REGION, ACCOUNT, "test-stack", null, Map.of());
        assertEquals("", route(declaredOnCreate).getTarget());

        StackResource created = createRoute(",\"Target\":\"integrations/int-1\"");
        updateRoute(created, ",\"Target\":\"integrations/int-1\"", ",\"Target\":\"\"");

        assertEquals("", route(created).getTarget());
    }

    @Test
    void droppingTheApiIdFromARouteFailsTheUpdateAndLeavesTheRouteAsItWas() throws Exception {
        StackResource created = createRoute(",\"Target\":\"integrations/int-1\"");

        StackResource result = provisioner.provision("Route", "AWS::ApiGatewayV2::Route",
                mapper.readTree("{\"RouteKey\":\"GET /items\"}"), engine(mapper, REGION, Map.of()), REGION, ACCOUNT,
                "test-stack", created.getPhysicalId(), created.getAttributes(), event -> { },
                mapper.readTree("{" + ROUTE_BASE.formatted(apiId) + ",\"Target\":\"integrations/int-1\"}"));

        assertTrue(result.getStatus().endsWith("_FAILED"), result.getStatus());
        assertEquals("Route not found", result.getStatusReason());
        assertEquals(created.getPhysicalId(), result.getPhysicalId());
        assertEquals("integrations/int-1", route(created).getTarget());
    }

    // ── Authorizer ───────────────────────────────────────────────────────────

    @Test
    void authorizerResultTtlDroppedFromTheTemplateReadsBackAbsent() throws Exception {
        String declared = ",\"AuthorizerResultTtlInSeconds\":300";
        StackResource created = createAuthorizer(declared);
        assertEquals(300, authorizer(created).getAuthorizerResultTtlInSeconds());

        updateAuthorizer(created, declared, "");

        assertNull(authorizer(created).getAuthorizerResultTtlInSeconds());
    }

    @Test
    void authorizerResultTtlMadeNoValueByAWholeFnIfReadsBackAbsent() throws Exception {
        String declared = ",\"AuthorizerResultTtlInSeconds\":300";
        StackResource created = createAuthorizer(declared);

        updateAuthorizer(created, declared,
                ",\"AuthorizerResultTtlInSeconds\":{\"Fn::If\":[\"Off\",{\"Ref\":\"AWS::NoValue\"},300]}");

        assertNull(authorizer(created).getAuthorizerResultTtlInSeconds());
    }

    @Test
    void authorizerEnableSimpleResponsesDroppedFromTheTemplateReadsBackAbsent() throws Exception {
        String declared = ",\"EnableSimpleResponses\":true";
        StackResource created = createAuthorizer(declared);
        assertEquals(Boolean.TRUE, authorizer(created).getEnableSimpleResponses());

        updateAuthorizer(created, declared, "");

        assertNull(authorizer(created).getEnableSimpleResponses());
    }

    @Test
    void authorizerEnableSimpleResponsesMadeNoValueByRefReadsBackAbsent() throws Exception {
        String declared = ",\"EnableSimpleResponses\":true";
        StackResource created = createAuthorizer(declared);

        updateAuthorizer(created, declared, ",\"EnableSimpleResponses\":{\"Ref\":\"AWS::NoValue\"}");

        assertNull(authorizer(created).getEnableSimpleResponses());
    }

    @Test
    void authorizerIdentitySourceDroppedFromTheTemplateReadsBackAsAnEmptyList() throws Exception {
        String declared = ",\"IdentitySource\":[\"$request.header.Authorization\"]";
        StackResource created = createAuthorizer(declared);
        assertEquals(List.of("$request.header.Authorization"), authorizer(created).getIdentitySource());

        updateAuthorizer(created, declared, "");

        assertEquals(List.of(), authorizer(created).getIdentitySource());
    }

    @Test
    void authorizerNameAndTypeDroppedFromTheTemplateAreKept() throws Exception {
        StackResource created = createAuthorizer("");

        updateAuthorizerFrom(created, "{" + AUTHORIZER_BASE.formatted(apiId) + "}",
                "{\"ApiId\":\"" + apiId + "\"," + LAMBDA_SETTINGS + "}");

        Authorizer authorizer = authorizer(created);
        assertEquals("auth", authorizer.getName());
        assertEquals("REQUEST", authorizer.getAuthorizerType());
    }

    @Test
    void authorizerUriAndPayloadFormatVersionDroppedFromTheTemplateReadBackAbsent() throws Exception {
        StackResource created = createAuthorizer("");
        assertEquals(LAMBDA_URI, authorizer(created).getAuthorizerUri());

        updateAuthorizerFrom(created, "{" + AUTHORIZER_BASE.formatted(apiId) + "}",
                "{\"ApiId\":\"" + apiId + "\",\"Name\":\"auth\",\"AuthorizerType\":\"REQUEST\"}");

        Authorizer authorizer = authorizer(created);
        assertNull(authorizer.getAuthorizerUri());
        assertNull(authorizer.getAuthorizerPayloadFormatVersion());
    }

    @Test
    void authorizerJwtConfigurationDroppedFromTheTemplateReadsBackAbsent() throws Exception {
        StackResource created = createJwtAuthorizer(JWT_CONFIGURATION);
        assertEquals("https://issuer.example", authorizer(created).getJwtConfiguration().issuer());

        updateAuthorizerFrom(created, jwtAuthorizer(JWT_CONFIGURATION), jwtAuthorizer(""));

        assertNull(authorizer(created).getJwtConfiguration());
    }

    @Test
    void changingAJwtAuthorizerToRequestWithoutJwtConfigurationLeavesNoJwtConfiguration() throws Exception {
        StackResource created = createJwtAuthorizer(JWT_CONFIGURATION);

        updateAuthorizerFrom(created, jwtAuthorizer(JWT_CONFIGURATION),
                "{\"ApiId\":\"" + apiId + "\",\"Name\":\"jwt\",\"AuthorizerType\":\"REQUEST\","
                        + LAMBDA_SETTINGS + "}");

        Authorizer authorizer = authorizer(created);
        assertEquals("REQUEST", authorizer.getAuthorizerType());
        assertNull(authorizer.getJwtConfiguration());
        assertEquals(LAMBDA_URI, authorizer.getAuthorizerUri());
    }

    @Test
    void changingARequestAuthorizerToJwtWithoutUriAndPayloadVersionLeavesNeither() throws Exception {
        StackResource created = createAuthorizer("");

        updateAuthorizerFrom(created, "{" + AUTHORIZER_BASE.formatted(apiId) + "}", jwtAuthorizer(JWT_CONFIGURATION));

        Authorizer authorizer = authorizer(created);
        assertEquals("JWT", authorizer.getAuthorizerType());
        assertNull(authorizer.getAuthorizerUri());
        assertNull(authorizer.getAuthorizerPayloadFormatVersion());
        assertEquals("https://issuer.example", authorizer.getJwtConfiguration().issuer());
    }

    @Test
    void droppingTheApiIdFromAnAuthorizerFailsTheUpdateAndLeavesTheAuthorizerAsItWas() throws Exception {
        StackResource created = createAuthorizer(",\"AuthorizerResultTtlInSeconds\":300");

        StackResource result = updateAuthorizerFrom(created,
                "{" + AUTHORIZER_BASE.formatted(apiId) + ",\"AuthorizerResultTtlInSeconds\":300}",
                "{\"Name\":\"auth\",\"AuthorizerType\":\"REQUEST\"}");

        assertTrue(result.getStatus().endsWith("_FAILED"), result.getStatus());
        assertEquals("Authorizer not found", result.getStatusReason());
        assertEquals(created.getPhysicalId(), result.getPhysicalId());
        assertEquals(300, authorizer(created).getAuthorizerResultTtlInSeconds());
    }

    @Test
    void authorizerSettingsSetOutsideTheStackSurviveAnUnrelatedUpdate() throws Exception {
        StackResource created = createAuthorizer("");
        service.updateAuthorizer(REGION, apiId, created.getPhysicalId(),
                Map.of("authorizerResultTtlInSeconds", 120, "enableSimpleResponses", true));

        updateAuthorizer(created, "", ",\"IdentitySource\":[\"$request.header.X-Key\"]");

        Authorizer authorizer = authorizer(created);
        assertEquals(List.of("$request.header.X-Key"), authorizer.getIdentitySource());
        assertEquals(120, authorizer.getAuthorizerResultTtlInSeconds());
        assertEquals(Boolean.TRUE, authorizer.getEnableSimpleResponses());
    }

    @Test
    void changedAuthorizerResultTtlAndEnableSimpleResponsesStillApply() throws Exception {
        String before = ",\"AuthorizerResultTtlInSeconds\":300,\"EnableSimpleResponses\":true";
        StackResource created = createAuthorizer(before);

        updateAuthorizer(created, before, ",\"AuthorizerResultTtlInSeconds\":60,\"EnableSimpleResponses\":false");

        Authorizer authorizer = authorizer(created);
        assertEquals(60, authorizer.getAuthorizerResultTtlInSeconds());
        assertEquals(Boolean.FALSE, authorizer.getEnableSimpleResponses());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A route with the base properties plus {@code extra}, a JSON fragment that starts with a comma or is empty. */
    private StackResource createRoute(String extra) throws Exception {
        return provisioner.provision("Route", "AWS::ApiGatewayV2::Route",
                mapper.readTree("{" + ROUTE_BASE.formatted(apiId) + extra + "}"), engine(mapper, REGION, Map.of()), REGION, ACCOUNT,
                "test-stack", null, Map.of());
    }

    /** Updates the route from the template with {@code previousExtra} to the one with {@code currentExtra}. */
    private void updateRoute(StackResource created, String previousExtra, String currentExtra) throws Exception {
        CloudFormationTemplateEngine engine = engine(mapper, REGION, Map.of("Off", true));
        // CloudFormationService omits NoValue before any provisioner runs, so the provisioner never sees it.
        provisioner.provision("Route", "AWS::ApiGatewayV2::Route",
                engine.omitNoValue(mapper.readTree("{" + ROUTE_BASE.formatted(apiId) + currentExtra + "}")), engine,
                REGION, ACCOUNT, "test-stack", created.getPhysicalId(), created.getAttributes(), event -> { },
                mapper.readTree("{" + ROUTE_BASE.formatted(apiId) + previousExtra + "}"));
    }

    private StackResource createAuthorizer(String extra) throws Exception {
        return provisioner.provision("Authorizer", "AWS::ApiGatewayV2::Authorizer",
                mapper.readTree("{" + AUTHORIZER_BASE.formatted(apiId) + extra + "}"), engine(mapper, REGION, Map.of()), REGION, ACCOUNT,
                "test-stack", null, Map.of());
    }

    private void updateAuthorizer(StackResource created, String previousExtra, String currentExtra)
            throws Exception {
        updateAuthorizerFrom(created, "{" + AUTHORIZER_BASE.formatted(apiId) + previousExtra + "}",
                "{" + AUTHORIZER_BASE.formatted(apiId) + currentExtra + "}");
    }

    /** Updates the authorizer from one full property object to another; NoValue is omitted as the service does. */
    private StackResource updateAuthorizerFrom(StackResource created, String previous, String current)
            throws Exception {
        CloudFormationTemplateEngine engine = engine(mapper, REGION, Map.of("Off", true));
        return provisioner.provision("Authorizer", "AWS::ApiGatewayV2::Authorizer",
                engine.omitNoValue(mapper.readTree(current)), engine, REGION, ACCOUNT, "test-stack",
                created.getPhysicalId(), created.getAttributes(), event -> { }, mapper.readTree(previous));
    }

    private String jwtAuthorizer(String jwtConfiguration) {
        return "{\"ApiId\":\"" + apiId + "\",\"Name\":\"jwt\",\"AuthorizerType\":\"JWT\","
                + "\"IdentitySource\":[\"$request.header.Authorization\"]" + jwtConfiguration + "}";
    }

    private StackResource createJwtAuthorizer(String jwtConfiguration) throws Exception {
        return provisioner.provision("Authorizer", "AWS::ApiGatewayV2::Authorizer",
                mapper.readTree(jwtAuthorizer(jwtConfiguration)), engine(mapper, REGION, Map.of()), REGION, ACCOUNT,
                "test-stack", null, Map.of());
    }

    private String createAuthorizerId() throws Exception {
        return createAuthorizer("").getPhysicalId();
    }

    private Route route(StackResource resource) {
        return service.getRoute(REGION, apiId, resource.getPhysicalId());
    }

    private Authorizer authorizer(StackResource resource) {
        return service.getAuthorizer(REGION, apiId, resource.getPhysicalId());
    }
}
