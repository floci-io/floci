package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Provisions an {@code AWS::ApiGateway::UsagePlan} over a deployed REST API stage and an
 * {@code AWS::ApiGateway::UsagePlanKey} attaching an API key to it, the way a template guards an
 * API behind a key. The API and the key are created outside the stack, since the stack only owns
 * the plan and the association. Asserts that {@code Ref} and {@code Fn::GetAtt Id} resolve to the
 * plan id and to {@code <keyId>:<usagePlanId>}, that the plan reports its stage with the stage's
 * method throttle, its throttle and its quota, that an update renames the plan, moves its stage and
 * changes the stage throttle in place and drops a quota the template omits through
 * {@code AWS::NoValue}, and that deleting the stack removes the plan and
 * the association but not the key.
 */
@QuarkusTest
class ApiGatewayUsagePlanCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "apigw-usageplan-cfn-it";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "ApiId": {"Type": "String"},
            "StageName": {"Type": "String"},
            "KeyId": {"Type": "String"},
            "PlanName": {"Type": "String"},
            "Description": {"Type": "String"},
            "TagValue": {"Type": "String"},
            "QuotaLimit": {"Type": "Number"},
            "BurstLimit": {"Type": "Number"},
            "WithQuota": {"Type": "String"}
          },
          "Conditions": {
            "HasQuota": {"Fn::Equals": [{"Ref": "WithQuota"}, "true"]}
          },
          "Resources": {
            "Plan": {
              "Type": "AWS::ApiGateway::UsagePlan",
              "Properties": {
                "UsagePlanName": {"Ref": "PlanName"},
                "Description": {"Ref": "Description"},
                "ApiStages": [{"ApiId": {"Ref": "ApiId"}, "Stage": {"Ref": "StageName"},
                               "Throttle": {"/items/GET": {"BurstLimit": {"Ref": "BurstLimit"}, "RateLimit": 2}}}],
                "Throttle": {"BurstLimit": 10, "RateLimit": 5},
                "Quota": {"Fn::If": ["HasQuota", {"Limit": {"Ref": "QuotaLimit"}, "Period": "MONTH"},
                                     {"Ref": "AWS::NoValue"}]},
                "Tags": [{"Key": "stack", "Value": {"Ref": "TagValue"}}]
              }
            },
            "PlanKey": {
              "Type": "AWS::ApiGateway::UsagePlanKey",
              "Properties": {
                "UsagePlanId": {"Ref": "Plan"},
                "KeyId": {"Ref": "KeyId"},
                "KeyType": "API_KEY"
              }
            }
          },
          "Outputs": {
            "PlanRef": {"Value": {"Ref": "Plan"}},
            "PlanId": {"Value": {"Fn::GetAtt": ["Plan", "Id"]}},
            "KeyRef": {"Value": {"Ref": "PlanKey"}},
            "KeyAttId": {"Value": {"Fn::GetAtt": ["PlanKey", "Id"]}}
          }
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateAndDeleteAUsagePlanWithAKey() {
        String apiId = createMockApi(STACK);
        String deploymentId = createDeployment(apiId);
        createStage(apiId, "dev", deploymentId);
        createStage(apiId, "prod", deploymentId);
        String keyId = given().contentType(ContentType.JSON).body("{\"name\":\"" + STACK + "-key\"}")
            .when().post("/apikeys").then().statusCode(201).extract().path("id");
        try {
            cloudFormation("CreateStack", parameters(apiId, "dev", keyId, "gold", "first", "v1", "1000", "4", "true"));
            String created = describeStacks("CREATE_COMPLETE");
            String planId = outputValue(created, "PlanRef");

            // Fn::GetAtt Id is the plan id, and the key resource reports <keyId>:<usagePlanId> as on AWS.
            assertEquals(planId, outputValue(created, "PlanId"));
            assertEquals(keyId + ":" + planId, outputValue(created, "KeyRef"));
            assertEquals(keyId + ":" + planId, outputValue(created, "KeyAttId"));

            getUsagePlan(planId)
                .statusCode(200)
                .body("name", equalTo("gold"))
                .body("description", equalTo("first"))
                .body("tags.stack", equalTo("v1"));
            getUsagePlan(planId)
                .statusCode(200)
                .body("apiStages", hasSize(1))
                .body("apiStages[0].apiId", equalTo(apiId))
                .body("apiStages[0].stage", equalTo("dev"))
                .body("apiStages[0].throttle['/items/GET'].burstLimit", equalTo(4))
                .body("apiStages[0].throttle['/items/GET'].rateLimit", equalTo(2.0f))
                .body("throttle.burstLimit", equalTo(10))
                .body("throttle.rateLimit", equalTo(5.0f))
                .body("quota.limit", equalTo(1000))
                .body("quota.offset", equalTo(0))
                .body("quota.period", equalTo("MONTH"));
            getUsagePlanKey(planId, keyId).statusCode(200).body("type", equalTo("API_KEY"));

            cloudFormation("UpdateStack", parameters(apiId, "prod", keyId, "platinum", "second", "v2", "2000", "6", "false"));
            String updated = describeStacks("UPDATE_COMPLETE");

            // The plan is renamed and moved to the other stage in place; the association survives.
            assertEquals(planId, outputValue(updated, "PlanRef"));
            assertEquals(keyId + ":" + planId, outputValue(updated, "KeyRef"));
            getUsagePlan(planId)
                .statusCode(200)
                .body("name", equalTo("platinum"))
                .body("description", equalTo("second"))
                .body("tags.stack", equalTo("v2"));
            getUsagePlan(planId)
                .statusCode(200)
                .body("apiStages", hasSize(1))
                .body("apiStages[0].apiId", equalTo(apiId))
                .body("apiStages[0].stage", equalTo("prod"))
                .body("apiStages[0].throttle['/items/GET'].burstLimit", equalTo(6))
                .body("apiStages[0].throttle['/items/GET'].rateLimit", equalTo(2.0f))
                .body("throttle.burstLimit", equalTo(10))
                .body("throttle.rateLimit", equalTo(5.0f))
                // AWS::NoValue leaves the quota out of the template, so the update takes it off the plan.
                .body("quota", nullValue());
            getUsagePlanKey(planId, keyId).statusCode(200);

            cloudFormation("DeleteStack", Map.of());
            CfnStackWaits.awaitStackDeleted(STACK);

            // The stack owned the plan and the association, not the key.
            getUsagePlan(planId).statusCode(404);
            getUsagePlanKey(planId, keyId).statusCode(404);
            given().when().get("/apikeys/" + keyId).then().statusCode(200);
        } finally {
            given().when().delete("/apikeys/" + keyId);
            given().when().delete("/restapis/" + apiId);
        }
    }

    private static Map<String, String> parameters(String apiId, String stage, String keyId, String planName,
                                                  String description, String tagValue, String quotaLimit,
                                                  String burstLimit, String withQuota) {
        return Map.of("ApiId", apiId, "StageName", stage, "KeyId", keyId, "PlanName", planName,
                "Description", description, "TagValue", tagValue, "QuotaLimit", quotaLimit,
                "BurstLimit", burstLimit, "WithQuota", withQuota);
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse getUsagePlan(String planId) {
        return given().when().get("/usageplans/" + planId).then();
    }

    private static ValidatableResponse getUsagePlanKey(String planId, String keyId) {
        return given().when().get("/usageplans/" + planId + "/keys/" + keyId).then();
    }

    /** A REST API whose GET /items is a MOCK integration, enough for a deployment and two stages. */
    private static String createMockApi(String name) {
        String apiId = given().contentType(ContentType.JSON).body("{\"name\":\"" + name + "\"}")
            .when().post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources")
            .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON).body("{\"pathPart\":\"items\"}")
            .when().post("/restapis/" + apiId + "/resources/" + rootId).then().statusCode(201).extract().path("id");
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
            .when().put(method).then().statusCode(201);
        given().contentType(ContentType.JSON).body("{\"responseParameters\":{}}")
            .when().put(method + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
            .when().put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{}\"}}")
            .when().put(method + "/integration/responses/200").then().statusCode(201);
        return apiId;
    }

    private static String createDeployment(String apiId) {
        return given().contentType(ContentType.JSON).body("{\"description\":\"v1\"}")
            .when().post("/restapis/" + apiId + "/deployments").then().statusCode(201).extract().path("id");
    }

    private static void createStage(String apiId, String stage, String deploymentId) {
        given().contentType(ContentType.JSON)
            .body("{\"stageName\":\"" + stage + "\",\"deploymentId\":\"" + deploymentId + "\"}")
            .when().post("/restapis/" + apiId + "/stages").then().statusCode(201);
    }
}
