package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Stack updates of an {@code AWS::ApiGateway::UsagePlan} that a later resource fails, so the update
 * rolls back. Asserts that the plan's name, description, stages with their method throttles,
 * throttle, quota and tags come back as they were, that a plan the update created in place of one
 * deleted out of band is deleted again, and that a plan whose failed update could not be undone at
 * once is put back by ContinueUpdateRollback.
 */
@QuarkusTest
class ApiGatewayUsagePlanCfnRollbackIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";

    /** One plan with the given properties, optionally followed by more resources. */
    private static final String PLAN_TEMPLATE = """
        {
          "Resources": {
            "Plan": {
              "Type": "AWS::ApiGateway::UsagePlan",
              "Properties": {%s}
            }%s
          },
          "Outputs": {"PlanRef": {"Value": {"Ref": "Plan"}}}
        }
        """;

    /** A resource that fails after the one it depends on, so the update rolls back. */
    private static final String FAILING_RESOURCE = """
        ,
            "BadSecret": {
              "Type": "AWS::SecretsManager::Secret",
              "DependsOn": "%s",
              "Properties": {
                "Name": "%s",
                "SecretString": "explicit",
                "GenerateSecretString": {"PasswordLength": 32}
              }
            }""";

    @InjectSpy
    ApiGatewayService apiGatewayService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aFailedUpdatePutsThePlansQuotaThrottleAndNameBack() {
        String stack = "apigw-usageplan-cfn-rollback-it";
        String original = """
            "UsagePlanName": "apigw-usageplan-rollback-original",
            "ApiStages": [{"ApiId": "rollbackapi", "Stage": "v1",
                           "Throttle": {"/items/GET": {"BurstLimit": 3, "RateLimit": 1}}}],
            "Throttle": {"BurstLimit": 10, "RateLimit": 5},
            "Quota": {"Limit": 1000, "Period": "MONTH"},
            "Tags": [{"Key": "stack", "Value": "v1"}]""";
        cloudFormation(stack, "CreateStack", PLAN_TEMPLATE.formatted(original, ""));
        String planId = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "PlanRef");

        // Every value changes, the description is added, before the secret fails the update.
        String changed = """
            "UsagePlanName": "apigw-usageplan-rollback-changed",
            "Description": "changed",
            "ApiStages": [{"ApiId": "rollbackapi", "Stage": "v2"}],
            "Throttle": {"BurstLimit": 20, "RateLimit": 8},
            "Quota": {"Limit": 50, "Offset": 1, "Period": "WEEK"},
            "Tags": [{"Key": "stack", "Value": "v2"}]""";
        cloudFormation(stack, "UpdateStack",
                PLAN_TEMPLATE.formatted(changed, FAILING_RESOURCE.formatted("Plan", stack)));
        assertEquals(planId, outputValue(describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE"), "PlanRef"));

        getUsagePlan(planId)
            .statusCode(200)
            .body("name", equalTo("apigw-usageplan-rollback-original"))
            .body("$", not(hasKey("description")))
            .body("throttle.burstLimit", equalTo(10))
            .body("throttle.rateLimit", equalTo(5.0f))
            .body("quota.limit", equalTo(1000))
            .body("quota.offset", equalTo(0))
            .body("quota.period", equalTo("MONTH"))
            .body("apiStages", hasSize(1))
            .body("apiStages[0].stage", equalTo("v1"))
            .body("apiStages[0].throttle['/items/GET'].burstLimit", equalTo(3))
            .body("tags.stack", equalTo("v1"));

        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
        getUsagePlan(planId).statusCode(404);
    }

    @Test
    void aFailedUpdateDeletesThePlanItCreatedInPlaceOfOneDeletedOutOfBand() {
        String stack = "apigw-usageplan-cfn-recreate-it";
        String name = "apigw-usageplan-cfn-recreate-it-plan";
        String properties = "\"UsagePlanName\": \"" + name + "\"";
        cloudFormation(stack, "CreateStack", PLAN_TEMPLATE.formatted(properties, ""));
        String planId = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "PlanRef");
        given().when().delete("/usageplans/" + planId).then().statusCode(202);

        // The description makes the update re-provision the plan, which creates one in place of the
        // deleted one before the secret fails the update.
        cloudFormation(stack, "UpdateStack", PLAN_TEMPLATE.formatted(properties + ", \"Description\": \"changed\"",
                FAILING_RESOURCE.formatted("Plan", stack)));
        describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
        given().when().get("/usageplans").then()
            .statusCode(200)
            .body("item.name", not(hasItem(name)));

        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    // The plan's own write fails after changing it, and the provisioner's undo fails once too, so the stack
    // stops in UPDATE_ROLLBACK_FAILED with the plan holding the failed update's values. The retry must put
    // them back rather than report a rollback that restored nothing.
    @Test
    void continueUpdateRollbackPutsBackAPlanWhoseFailedUpdateCouldNotBeUndoneAtOnce() {
        String stack = "apigw-usageplan-cfn-retry-it";
        String original = """
            "UsagePlanName": "apigw-usageplan-retry-original",
            "Throttle": {"BurstLimit": 10, "RateLimit": 5},
            "Quota": {"Limit": 1000, "Period": "MONTH"}""";
        cloudFormation(stack, "CreateStack", PLAN_TEMPLATE.formatted(original, ""));
        String planId = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "PlanRef");

        Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated write failure");
        }).doCallRealMethod().when(apiGatewayService).replaceUsagePlanLimits(anyString(), eq(planId), any());
        Mockito.doThrow(new IllegalStateException("simulated restore failure")).doCallRealMethod()
                .when(apiGatewayService).restoreUsagePlan(anyString(), eq(planId), any(), any(), any(), anyMap());

        String changed = """
            "UsagePlanName": "apigw-usageplan-retry-changed",
            "Throttle": {"BurstLimit": 20, "RateLimit": 8},
            "Quota": {"Limit": 50, "Period": "WEEK"}""";
        cloudFormation(stack, "UpdateStack", PLAN_TEMPLATE.formatted(changed, ""));
        describeStacks(stack, "UPDATE_ROLLBACK_FAILED");
        getUsagePlan(planId)
            .statusCode(200)
            .body("name", equalTo("apigw-usageplan-retry-changed"))
            .body("quota.limit", equalTo(50));

        cloudFormation(stack, "ContinueUpdateRollback", null);
        assertEquals(planId, outputValue(describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE"), "PlanRef"));
        getUsagePlan(planId)
            .statusCode(200)
            .body("name", equalTo("apigw-usageplan-retry-original"))
            .body("throttle.burstLimit", equalTo(10))
            .body("throttle.rateLimit", equalTo(5.0f))
            .body("quota.limit", equalTo(1000))
            .body("quota.period", equalTo("MONTH"));

        // An update that only changes an output commits afterwards, and leaves the plan as it is.
        cloudFormation(stack, "UpdateStack", PLAN_TEMPLATE.formatted(original, "")
                .replace("\"PlanRef\": {\"Value\": {\"Ref\": \"Plan\"}}",
                        "\"PlanRef\": {\"Value\": {\"Ref\": \"Plan\"}}, \"Extra\": {\"Value\": \"x\"}"));
        describeStacks(stack, "UPDATE_COMPLETE");
        getUsagePlan(planId)
            .statusCode(200)
            .body("name", equalTo("apigw-usageplan-retry-original"))
            .body("quota.limit", equalTo(1000));

        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static void cloudFormation(String stack, String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals(expectedStatus, state.status(), state.reason());
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse getUsagePlan(String planId) {
        return given().when().get("/usageplans/" + planId).then();
    }
}
