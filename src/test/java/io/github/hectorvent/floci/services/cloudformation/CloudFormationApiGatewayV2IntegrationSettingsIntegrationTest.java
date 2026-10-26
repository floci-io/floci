package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.FAILING_RESOURCE;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.REGION;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.integration;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.stack;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.stackAction;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.waitForStackStatus;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The settings of an AWS::ApiGatewayV2::Integration through a real stack: a setting the template
 * drops on update is removed from the stored integration and from GetIntegration, and a failed
 * update puts the earlier settings back.
 */
@QuarkusTest
class CloudFormationApiGatewayV2IntegrationSettingsIntegrationTest {

    private static final String PARAMETERS =
            ",\"RequestParameters\":{\"overwrite:header.x-example\":\"%s\"}";
    private static final String TIMEOUT = ",\"TimeoutInMillis\":15000";

    @Inject
    ApiGatewayV2Service apiGatewayV2Service;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void removingSettingsFromTheTemplateRemovesThemFromTheIntegration() {
        String stackName = "integration-settings-removal-stack";
        stackAction("CreateStack", stackName, template(PARAMETERS.formatted("value-1") + TIMEOUT, ""));
        waitForStackStatus(cloudFormationService, stackName, "CREATE_COMPLETE");
        Integration created = integration(apiGatewayV2Service, stack(cloudFormationService, stackName));
        assertEquals(Map.of("overwrite:header.x-example", "value-1"), created.getRequestParameters());
        assertEquals(15000, created.getTimeoutInMillis());

        stackAction("UpdateStack", stackName, template(TIMEOUT, ""));
        waitForStackStatus(cloudFormationService, stackName, "UPDATE_COMPLETE");

        Stack updated = stack(cloudFormationService, stackName);
        Integration integration = integration(apiGatewayV2Service, updated);
        assertNull(integration.getRequestParameters());
        assertEquals(15000, integration.getTimeoutInMillis());
        assertEquals("ANY", integration.getIntegrationMethod());
        assertEquals("http://backend.example", integration.getIntegrationUri());
        getIntegrationResponse(updated)
                .body("requestParameters", nullValue())
                .body("timeoutInMillis", equalTo(15000));

        stackAction("UpdateStack", stackName, template("", ""));
        waitForStackStatus(cloudFormationService, stackName, "UPDATE_COMPLETE");

        Stack withoutTimeout = stack(cloudFormationService, stackName);
        assertEquals(0, integration(apiGatewayV2Service, withoutTimeout).getTimeoutInMillis());
        getIntegrationResponse(withoutTimeout).body("timeoutInMillis", nullValue());
    }

    @Test
    void aTimeoutThatBecomesNoValueIsRemoved() {
        String stackName = "integration-settings-novalue-stack";
        stackAction("CreateStack", stackName, template(TIMEOUT, ""));
        waitForStackStatus(cloudFormationService, stackName, "CREATE_COMPLETE");
        assertEquals(15000, integration(apiGatewayV2Service, stack(cloudFormationService, stackName)).getTimeoutInMillis());

        stackAction("UpdateStack", stackName, template(
                ",\"TimeoutInMillis\":{\"Fn::If\":[\"Off\",{\"Ref\":\"AWS::NoValue\"},15000]}", ""));
        waitForStackStatus(cloudFormationService, stackName, "UPDATE_COMPLETE");

        assertEquals(0, integration(apiGatewayV2Service, stack(cloudFormationService, stackName)).getTimeoutInMillis());
    }

    @Test
    void failedUpdateRestoresTheEarlierRequestParameters() {
        String stackName = "integration-settings-rollback-stack";
        stackAction("CreateStack", stackName, template(PARAMETERS.formatted("original") + TIMEOUT, ""));
        waitForStackStatus(cloudFormationService, stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, template(PARAMETERS.formatted("changed"), FAILING_RESOURCE));
        waitForStackStatus(cloudFormationService, stackName, "UPDATE_ROLLBACK_COMPLETE");

        Stack rolledBack = stack(cloudFormationService, stackName);
        Integration integration = integration(apiGatewayV2Service, rolledBack);
        assertEquals(Map.of("overwrite:header.x-example", "original"), integration.getRequestParameters());
        assertEquals(15000, integration.getTimeoutInMillis());
        getIntegrationResponse(rolledBack)
                .body("requestParameters.'overwrite:header.x-example'", equalTo("original"));
    }

    private ValidatableResponse getIntegrationResponse(Stack stack) {
        String apiId = stack.getResources().get("HttpApi").getPhysicalId();
        String integrationId = stack.getResources().get("Integration").getPhysicalId();
        return given().when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200);
    }

    /**
     * An HTTP API and an HTTP_PROXY integration to a plain URL, so no container starts.
     * {@code settings} adds integration properties and {@code extraResources} adds resources, both
     * as JSON fragments that start with a comma.
     */
    private static String template(String settings, String extraResources) {
        return """
                {"Conditions":{"Off":{"Fn::Equals":["a","a"]}},
                 "Resources":{
                  "HttpApi":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"integration-settings-api","ProtocolType":"HTTP"}},
                  "Integration":{"Type":"AWS::ApiGatewayV2::Integration","Properties":{
                    "ApiId":{"Ref":"HttpApi"},"IntegrationType":"HTTP_PROXY","IntegrationMethod":"ANY",
                    "IntegrationUri":"http://backend.example","PayloadFormatVersion":"1.0"%s}}%s}}
                """.formatted(settings, extraResources);
    }
}
