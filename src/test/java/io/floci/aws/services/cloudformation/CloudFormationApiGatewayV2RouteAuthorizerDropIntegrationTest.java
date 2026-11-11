package io.floci.aws.services.cloudformation;

import io.floci.aws.services.apigatewayv2.ApiGatewayV2Service;
import io.floci.aws.services.apigatewayv2.model.Authorizer;
import io.floci.aws.services.apigatewayv2.model.Route;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.REGION;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.authorizer;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.route;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.stack;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.stackAction;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings of an AWS::ApiGatewayV2::Route and an AWS::ApiGatewayV2::Authorizer through a real
 * stack: a setting the template drops on update reads back through GetRoute and GetAuthorizer as it
 * does on a create without it, a setting set through the API on a member the template never declared
 * survives an unrelated update, and a changed value is applied. The members AWS requires stay.
 */
@QuarkusTest
class CloudFormationApiGatewayV2RouteAuthorizerDropIntegrationTest {

    private static final String LAMBDA_URI = "arn:aws:lambda:us-east-1:000000000000:function:auth";
    private static final String ROUTE_SETTINGS =
            ",\"AuthorizationType\":\"CUSTOM\",\"AuthorizerId\":{\"Ref\":\"Authorizer\"},"
                    + "\"Target\":\"integrations/int-1\"";
    private static final String AUTHORIZER_SETTINGS =
            ",\"AuthorizerResultTtlInSeconds\":300,\"EnableSimpleResponses\":true";

    @Inject
    ApiGatewayV2Service apiGatewayV2Service;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void routeSettingsTheTemplateDropsReadBackAsOnACreateWithoutThem() {
        String stackName = "route-drop-stack";
        stackAction("CreateStack", stackName, template(ROUTE_SETTINGS, ""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        Stack created = stack(cloudFormationService, stackName);
        Route route = route(apiGatewayV2Service, created);
        assertEquals("integrations/int-1", route.getTarget());
        assertEquals(created.getResources().get("Authorizer").getPhysicalId(), route.getAuthorizerId());

        stackAction("UpdateStack", stackName, template("", ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        Route dropped = route(apiGatewayV2Service, updated);
        assertNull(dropped.getTarget());
        assertNull(dropped.getAuthorizerId());
        assertEquals("NONE", dropped.getAuthorizationType());
        assertEquals("GET /items", dropped.getRouteKey());
        getRouteResponse(updated)
                .body("target", nullValue())
                .body("authorizerId", nullValue())
                .body("authorizationType", equalTo("NONE"))
                .body("routeKey", equalTo("GET /items"));
    }

    @Test
    void routeSettingsMadeNoValueByAConditionReadBackAsOnACreateWithoutThem() {
        String stackName = "route-novalue-stack";
        stackAction("CreateStack", stackName, template(ROUTE_SETTINGS, ""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        Stack created = stack(cloudFormationService, stackName);

        stackAction("UpdateStack", stackName, template(
                ",\"AuthorizationType\":\"CUSTOM\","
                        + "\"AuthorizerId\":{\"Ref\":\"AWS::NoValue\"},"
                        + "\"Target\":{\"Fn::If\":[\"Off\",{\"Ref\":\"AWS::NoValue\"},\"integrations/int-1\"]}", ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        Route route = route(apiGatewayV2Service, updated);
        assertNull(route.getTarget());
        assertEquals(created.getResources().get("Authorizer").getPhysicalId(), route.getAuthorizerId());
        getRouteResponse(updated).body("target", nullValue())
                .body("authorizerId", equalTo(created.getResources().get("Authorizer").getPhysicalId()));
    }

    @Test
    void authorizationScopesTheTemplateDropsReadBackEmpty() {
        String stackName = "route-scopes-drop-stack";
        stackAction("CreateStack", stackName,
                template(",\"AuthorizationType\":\"JWT\",\"AuthorizerId\":{\"Ref\":\"Authorizer\"},"
                        + "\"AuthorizationScopes\":[\"read\"]", ""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        assertEquals(List.of("read"), route(apiGatewayV2Service, stack(cloudFormationService, stackName))
                .getAuthorizationScopes());

        stackAction("UpdateStack", stackName,
                template(",\"AuthorizationType\":\"JWT\",\"AuthorizerId\":{\"Ref\":\"Authorizer\"}", ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        List<String> scopes = route(apiGatewayV2Service, stack(cloudFormationService, stackName))
                .getAuthorizationScopes();
        assertTrue(scopes == null || scopes.isEmpty(), "expected no scopes, got " + scopes);
    }

    @Test
    void routeTargetAndAuthorizerIdSetOutsideTheStackSurviveAnUnrelatedUpdate() {
        String stackName = "route-outside-stack";
        stackAction("CreateStack", stackName, template("", ""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        Stack created = stack(cloudFormationService, stackName);
        String authorizerId = created.getResources().get("Authorizer").getPhysicalId();
        apiGatewayV2Service.updateRoute(REGION, created.getResources().get("HttpApi").getPhysicalId(),
                created.getResources().get("Route").getPhysicalId(),
                Map.of("target", "integrations/outside", "authorizerId", authorizerId));

        stackAction("UpdateStack", stackName, template(",\"AuthorizationType\":\"AWS_IAM\"", ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        Route route = route(apiGatewayV2Service, updated);
        assertEquals("AWS_IAM", route.getAuthorizationType());
        assertEquals("integrations/outside", route.getTarget());
        assertEquals(authorizerId, route.getAuthorizerId());
        getRouteResponse(updated)
                .body("target", equalTo("integrations/outside"))
                .body("authorizerId", equalTo(authorizerId));
    }

    @Test
    void aChangedRouteTargetIsApplied() {
        String stackName = "route-changed-stack";
        stackAction("CreateStack", stackName, template(",\"Target\":\"integrations/int-1\"", ""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, template(",\"Target\":\"integrations/int-2\"", ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        assertEquals("integrations/int-2", route(apiGatewayV2Service, updated).getTarget());
        getRouteResponse(updated).body("target", equalTo("integrations/int-2"));
    }

    @Test
    void authorizerSettingsTheTemplateDropsReadBackAsOnACreateWithoutThem() {
        String stackName = "authorizer-drop-stack";
        stackAction("CreateStack", stackName, template("", AUTHORIZER_SETTINGS));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        Authorizer created = authorizer(apiGatewayV2Service, stack(cloudFormationService, stackName));
        assertEquals(300, created.getAuthorizerResultTtlInSeconds());
        assertEquals(Boolean.TRUE, created.getEnableSimpleResponses());

        stackAction("UpdateStack", stackName, template("", ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        Authorizer dropped = authorizer(apiGatewayV2Service, updated);
        assertNull(dropped.getAuthorizerResultTtlInSeconds());
        assertNull(dropped.getEnableSimpleResponses());
        assertEquals("auth", dropped.getName());
        assertEquals("REQUEST", dropped.getAuthorizerType());
        assertEquals(LAMBDA_URI, dropped.getAuthorizerUri());
        assertEquals("2.0", dropped.getAuthorizerPayloadFormatVersion());
        getAuthorizerResponse(updated)
                .body("authorizerResultTtlInSeconds", nullValue())
                .body("enableSimpleResponses", nullValue())
                .body("name", equalTo("auth"))
                .body("authorizerType", equalTo("REQUEST"))
                .body("authorizerUri", equalTo(LAMBDA_URI))
                .body("authorizerPayloadFormatVersion", equalTo("2.0"));
    }

    @Test
    void authorizerSettingsMadeNoValueByAConditionReadBackAsOnACreateWithoutThem() {
        String stackName = "authorizer-novalue-stack";
        stackAction("CreateStack", stackName, template("", AUTHORIZER_SETTINGS));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, template("",
                ",\"AuthorizerResultTtlInSeconds\":{\"Fn::If\":[\"Off\",{\"Ref\":\"AWS::NoValue\"},300]},"
                        + "\"EnableSimpleResponses\":{\"Ref\":\"AWS::NoValue\"}"));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        Authorizer authorizer = authorizer(apiGatewayV2Service, updated);
        assertNull(authorizer.getAuthorizerResultTtlInSeconds());
        assertNull(authorizer.getEnableSimpleResponses());
        getAuthorizerResponse(updated)
                .body("authorizerResultTtlInSeconds", nullValue())
                .body("enableSimpleResponses", nullValue());
    }

    @Test
    void authorizerIdentitySourceTheTemplateDropsReadsBackEmpty() {
        String stackName = "authorizer-identity-drop-stack";
        stackAction("CreateStack", stackName,
                template("", ",\"IdentitySource\":[\"$request.header.Authorization\"]"));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        assertEquals(List.of("$request.header.Authorization"),
                authorizer(apiGatewayV2Service, stack(cloudFormationService, stackName)).getIdentitySource());

        stackAction("UpdateStack", stackName, template("", ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        List<String> source = authorizer(apiGatewayV2Service, stack(cloudFormationService, stackName))
                .getIdentitySource();
        assertTrue(source == null || source.isEmpty(), "expected no identity source, got " + source);
    }

    @Test
    void authorizerSettingsSetOutsideTheStackSurviveAnUnrelatedUpdate() {
        String stackName = "authorizer-outside-stack";
        stackAction("CreateStack", stackName, template("", ""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        Stack created = stack(cloudFormationService, stackName);
        apiGatewayV2Service.updateAuthorizer(REGION, created.getResources().get("HttpApi").getPhysicalId(),
                created.getResources().get("Authorizer").getPhysicalId(),
                Map.of("authorizerResultTtlInSeconds", 120, "enableSimpleResponses", true));

        stackAction("UpdateStack", stackName,
                template("", ",\"IdentitySource\":[\"$request.header.X-Key\"]"));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        Authorizer authorizer = authorizer(apiGatewayV2Service, updated);
        assertEquals(List.of("$request.header.X-Key"), authorizer.getIdentitySource());
        assertEquals(120, authorizer.getAuthorizerResultTtlInSeconds());
        assertEquals(Boolean.TRUE, authorizer.getEnableSimpleResponses());
        getAuthorizerResponse(updated)
                .body("authorizerResultTtlInSeconds", equalTo(120))
                .body("enableSimpleResponses", equalTo(true));
    }

    @Test
    void changedAuthorizerSettingsAreApplied() {
        String stackName = "authorizer-changed-stack";
        stackAction("CreateStack", stackName, template("", AUTHORIZER_SETTINGS));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName,
                template("", ",\"AuthorizerResultTtlInSeconds\":60,\"EnableSimpleResponses\":false"));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        Authorizer authorizer = authorizer(apiGatewayV2Service, updated);
        assertEquals(60, authorizer.getAuthorizerResultTtlInSeconds());
        assertEquals(Boolean.FALSE, authorizer.getEnableSimpleResponses());
        getAuthorizerResponse(updated)
                .body("authorizerResultTtlInSeconds", equalTo(60))
                .body("enableSimpleResponses", equalTo(false));
    }

    private ValidatableResponse getRouteResponse(Stack stack) {
        String apiId = stack.getResources().get("HttpApi").getPhysicalId();
        String routeId = stack.getResources().get("Route").getPhysicalId();
        return given().when().get("/v2/apis/" + apiId + "/routes/" + routeId)
                .then().statusCode(200);
    }

    private ValidatableResponse getAuthorizerResponse(Stack stack) {
        String apiId = stack.getResources().get("HttpApi").getPhysicalId();
        String authorizerId = stack.getResources().get("Authorizer").getPhysicalId();
        return given().when().get("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then().statusCode(200);
    }

    /**
     * An HTTP API, a REQUEST authorizer and a route, with no container involved. {@code routeSettings}
     * and {@code authorizerSettings} add properties to the route and the authorizer, as JSON fragments
     * that start with a comma.
     */
    private static String template(String routeSettings, String authorizerSettings) {
        return """
                {"Conditions":{"Off":{"Fn::Equals":["a","a"]}},
                 "Resources":{
                  "HttpApi":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"route-authorizer-drop-api","ProtocolType":"HTTP"}},
                  "Authorizer":{"Type":"AWS::ApiGatewayV2::Authorizer","Properties":{
                    "ApiId":{"Ref":"HttpApi"},"Name":"auth","AuthorizerType":"REQUEST",
                    "AuthorizerUri":"%s","AuthorizerPayloadFormatVersion":"2.0"%s}},
                  "Route":{"Type":"AWS::ApiGatewayV2::Route","Properties":{
                    "ApiId":{"Ref":"HttpApi"},"RouteKey":"GET /items"%s}}}}
                """.formatted(LAMBDA_URI, authorizerSettings, routeSettings);
    }
}
