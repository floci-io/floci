package io.floci.aws.services.cloudformation;

import io.floci.aws.services.apigatewayv2.ApiGatewayV2Service;
import io.floci.aws.services.apigatewayv2.model.Integration;
import io.floci.aws.services.apigatewayv2.model.Stage;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.FAILING_RESOURCE;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.REGION;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.integration;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.stack;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.stackAction;
import static io.floci.aws.services.cloudformation.ApiGatewayV2StackTestSupport.stage;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings of an AWS::ApiGatewayV2::Integration through a real stack: a setting the template
 * drops on update is removed from the stored integration and from GetIntegration, and a failed
 * update puts the earlier settings back. The stage variables of an AWS::ApiGatewayV2::Stage follow the
 * same rule: dropped on update, they are cleared from the stage.
 */
@QuarkusTest
class CloudFormationApiGatewayV2IntegrationSettingsIntegrationTest {

    private static final String PARAMETERS =
            ",\"RequestParameters\":{\"overwrite:header.x-example\":\"%s\"}";
    private static final String TIMEOUT = ",\"TimeoutInMillis\":15000";
    private static final String SUBTYPE = ",\"IntegrationSubtype\":\"SQS-SendMessage\",\"RequestParameters\":{"
            + "\"QueueUrl\":\"http://localhost/000000000000/q\",\"MessageBody\":\"$request.body\"}";

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
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        Integration created = integration(apiGatewayV2Service, stack(cloudFormationService, stackName));
        assertEquals(Map.of("overwrite:header.x-example", "value-1"), created.getRequestParameters());
        assertEquals(15000, created.getTimeoutInMillis());

        stackAction("UpdateStack", stackName, template(TIMEOUT, ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

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
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stack withoutTimeout = stack(cloudFormationService, stackName);
        assertEquals(0, integration(apiGatewayV2Service, withoutTimeout).getTimeoutInMillis());
        getIntegrationResponse(withoutTimeout).body("timeoutInMillis", nullValue());
    }

    @Test
    void aTimeoutThatBecomesNoValueIsRemoved() {
        String stackName = "integration-settings-novalue-stack";
        stackAction("CreateStack", stackName, template(TIMEOUT, ""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        assertEquals(15000, integration(apiGatewayV2Service, stack(cloudFormationService, stackName)).getTimeoutInMillis());

        stackAction("UpdateStack", stackName, template(
                ",\"TimeoutInMillis\":{\"Fn::If\":[\"Off\",{\"Ref\":\"AWS::NoValue\"},15000]}", ""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        assertEquals(0, integration(apiGatewayV2Service, stack(cloudFormationService, stackName)).getTimeoutInMillis());
    }

    @Test
    void failedUpdateRestoresTheEarlierRequestParameters() {
        String stackName = "integration-settings-rollback-stack";
        stackAction("CreateStack", stackName, template(PARAMETERS.formatted("original") + TIMEOUT, ""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, template(PARAMETERS.formatted("changed"), FAILING_RESOURCE));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        Stack rolledBack = stack(cloudFormationService, stackName);
        Integration integration = integration(apiGatewayV2Service, rolledBack);
        assertEquals(Map.of("overwrite:header.x-example", "original"), integration.getRequestParameters());
        assertEquals(15000, integration.getTimeoutInMillis());
        getIntegrationResponse(rolledBack)
                .body("requestParameters.'overwrite:header.x-example'", equalTo("original"));
    }

    @Test
    void failedUpdateRemovesTheSubtypeItAdded() {
        String stackName = "integration-subtype-rollback-stack";
        stackAction("CreateStack", stackName, awsProxy(template("", "")));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, awsProxy(template(SUBTYPE, FAILING_RESOURCE)));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        Integration integration = integration(apiGatewayV2Service, stack(cloudFormationService, stackName));
        assertNull(integration.getIntegrationSubtype());
        assertNull(integration.getRequestParameters());
    }

    @Test
    void stageVariablesTheTemplateDropsAreClearedFromTheStage() {
        String stackName = "stage-variables-drop-stack";
        stackAction("CreateStack", stackName, stageTemplate(",\"StageVariables\":{\"color\":\"blue\"}"));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        Stack created = stack(cloudFormationService, stackName);
        assertEquals(Map.of("color", "blue"), stage(apiGatewayV2Service, created).getStageVariables());
        getStageResponse(created).body("stageVariables.color", equalTo("blue"));

        stackAction("UpdateStack", stackName,
                stageTemplate(",\"StageVariables\":{\"color\":\"green\",\"size\":\"2\"}"));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");
        assertEquals(Map.of("color", "green", "size", "2"),
                stage(apiGatewayV2Service, stack(cloudFormationService, stackName)).getStageVariables());

        stackAction("UpdateStack", stackName, stageTemplate(""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");
        Stack dropped = stack(cloudFormationService, stackName);
        assertNoStageVariables(stage(apiGatewayV2Service, dropped));
        getStageResponse(dropped).body("stageVariables", anyOf(nullValue(), anEmptyMap()));
    }

    @Test
    void stageVariablesMadeNoValueByAConditionAreClearedFromTheStage() {
        String stackName = "stage-variables-novalue-stack";
        stackAction("CreateStack", stackName, stageTemplate(",\"StageVariables\":{\"color\":\"red\"}"));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        assertEquals(Map.of("color", "red"),
                stage(apiGatewayV2Service, stack(cloudFormationService, stackName)).getStageVariables());

        stackAction("UpdateStack", stackName, stageTemplate(
                ",\"StageVariables\":{\"Fn::If\":[\"Off\",{\"Ref\":\"AWS::NoValue\"},{\"color\":\"red\"}]}"));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");
        assertNoStageVariables(stage(apiGatewayV2Service, stack(cloudFormationService, stackName)));
    }

    @Test
    void emptyStageVariablesDeclaredByTheTemplateStayEmpty() {
        String stackName = "stage-variables-empty-stack";
        stackAction("CreateStack", stackName, stageTemplate(",\"StageVariables\":{\"color\":\"blue\"}"));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, stageTemplate(",\"StageVariables\":{}"));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        assertNoStageVariables(stage(apiGatewayV2Service, stack(cloudFormationService, stackName)));
    }

    @Test
    void stageVariablesSetOutsideTheStackSurviveAnUpdateThatNeverDeclaredThem() {
        String stackName = "stage-variables-outside-stack";
        stackAction("CreateStack", stackName, stageTemplate(""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        Stack created = stack(cloudFormationService, stackName);
        apiGatewayV2Service.updateStage(REGION, created.getResources().get("HttpApi").getPhysicalId(),
                created.getResources().get("Stage").getPhysicalId(),
                Map.of("stageVariables", Map.of("outside", "kept")));

        stackAction("UpdateStack", stackName, stageTemplate("").replace("\"AutoDeploy\":false", "\"AutoDeploy\":true"));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");

        Stage stage = stage(apiGatewayV2Service, stack(cloudFormationService, stackName));
        assertTrue(stage.isAutoDeploy());
        assertEquals(Map.of("outside", "kept"), stage.getStageVariables());
    }

    private static void assertNoStageVariables(Stage stage) {
        assertTrue(stage.getStageVariables() == null || stage.getStageVariables().isEmpty(),
                "expected no stage variables, got " + stage.getStageVariables());
    }

    private ValidatableResponse getStageResponse(Stack stack) {
        String apiId = stack.getResources().get("HttpApi").getPhysicalId();
        String stageName = stack.getResources().get("Stage").getPhysicalId();
        return given().when().get("/v2/apis/" + apiId + "/stages/" + stageName)
                .then().statusCode(200);
    }

    /** An HTTP API and one stage; {@code stageSettings} adds stage properties, starting with a comma. */
    private static String stageTemplate(String stageSettings) {
        return """
                {"Conditions":{"Off":{"Fn::Equals":["a","a"]}},
                 "Resources":{
                  "HttpApi":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"stage-variables-api","ProtocolType":"HTTP"}},
                  "Stage":{"Type":"AWS::ApiGatewayV2::Stage","Properties":{
                    "ApiId":{"Ref":"HttpApi"},"StageName":"prod","AutoDeploy":false%s}}}}
                """.formatted(stageSettings);
    }

    private ValidatableResponse getIntegrationResponse(Stack stack) {
        String apiId = stack.getResources().get("HttpApi").getPhysicalId();
        String integrationId = stack.getResources().get("Integration").getPhysicalId();
        return given().when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200);
    }

    private static String awsProxy(String template) {
        return template.replace("\"IntegrationType\":\"HTTP_PROXY\"", "\"IntegrationType\":\"AWS_PROXY\"");
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
