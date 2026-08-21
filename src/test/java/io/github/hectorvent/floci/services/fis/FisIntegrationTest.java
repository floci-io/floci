package io.github.hectorvent.floci.services.fis;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FisIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000310";
    private static final String TARGET_ACCOUNT_ID = "000000000311";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/fis-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void allAwsFisOperationsUseTheRestJsonWireContract() {
        String token = UUID.randomUUID().toString();
        String createBody = templateBody(token, "FIS integration template");

        Response created = jsonRequest(createBody)
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplate.description", equalTo("FIS integration template"))
                .body("experimentTemplate.experimentOptions.accountTargeting", equalTo("multi-account"))
                .body("experimentTemplate.creationTime", notNullValue())
                .extract().response();
        String templateId = created.path("experimentTemplate.id");
        String templateArn = created.path("experimentTemplate.arn");
        assertTrue(templateArn.endsWith("experiment-template/" + templateId));

        String idempotentId = jsonRequest(createBody)
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .extract().path("experimentTemplate.id");
        assertEquals(templateId, idempotentId);

        jsonRequest(templateBody(token, "conflicting payload"))
                .post("/experimentTemplates")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        awsRequest()
                .get("/experimentTemplates/" + templateId)
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", equalTo(templateId));

        awsRequest()
                .queryParam("maxResults", 1)
                .get("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplates.id", hasItem(templateId));

        jsonRequest("{\"description\":\"updated template\"}")
                .patch("/experimentTemplates/" + templateId)
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", equalTo(templateId))
                .body("experimentTemplate.description", equalTo("updated template"));

        jsonRequest("""
                {"clientToken":"%s","roleArn":"arn:aws:iam::%s:role/target-role","description":"target account"}
                """.formatted(UUID.randomUUID(), TARGET_ACCOUNT_ID))
                .post("/experimentTemplates/" + templateId
                        + "/targetAccountConfigurations/" + TARGET_ACCOUNT_ID)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.accountId", equalTo(TARGET_ACCOUNT_ID));

        awsRequest()
                .get("/experimentTemplates/" + templateId
                        + "/targetAccountConfigurations/" + TARGET_ACCOUNT_ID)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.description", equalTo("target account"));

        awsRequest()
                .queryParam("maxResults", 1)
                .get("/experimentTemplates/" + templateId + "/targetAccountConfigurations")
                .then()
                .statusCode(200)
                .body("targetAccountConfigurations", hasSize(1))
                .body("targetAccountConfigurations[0].accountId", equalTo(TARGET_ACCOUNT_ID));

        jsonRequest("{\"description\":\"updated target account\"}")
                .patch("/experimentTemplates/" + templateId
                        + "/targetAccountConfigurations/" + TARGET_ACCOUNT_ID)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.description", equalTo("updated target account"));

        awsRequest()
                .queryParam("maxResults", 1)
                .get("/actions")
                .then()
                .statusCode(200)
                .body("actions", hasSize(1))
                .body("nextToken", notNullValue());

        awsRequest()
                .get("/actions/aws:fis:wait")
                .then()
                .statusCode(200)
                .body("action.id", equalTo("aws:fis:wait"))
                .body("action.parameters.duration.required", equalTo(true));

        Response resourceTypes = awsRequest()
                .queryParam("maxResults", 1)
                .get("/targetResourceTypes")
                .then()
                .statusCode(200)
                .body("targetResourceTypes", hasSize(1))
                .body("nextToken", notNullValue())
                .extract().response();
        String resourceType = resourceTypes.path("targetResourceTypes[0].resourceType");

        awsRequest()
                .get("/targetResourceTypes/" + resourceType)
                .then()
                .statusCode(200)
                .body("targetResourceType.resourceType", equalTo(resourceType));

        jsonRequest("{\"tags\":{\"team\":\"resilience\"}}")
                .post("/tags/" + templateArn)
                .then()
                .statusCode(200);

        jsonRequest("{\"tags\":{\"invalid\":1}}")
                .post("/tags/" + templateArn)
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", "ValidationException")
                .body("__type", equalTo("ValidationException"));

        jsonRequest("{\"tags\":{\"aws:reserved\":\"value\"}}")
                .post("/tags/" + templateArn)
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", "ValidationException");

        jsonRequest("{\"tags\":{\"invalid#key\":\"value\"}}")
                .post("/tags/" + templateArn)
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", "ValidationException");

        awsRequest()
                .get("/tags/" + templateArn)
                .then()
                .statusCode(200)
                .body("tags.team", equalTo("resilience"));

        awsRequest()
                .delete("/tags/" + templateArn)
                .then()
                .statusCode(200);

        awsRequest()
                .get("/tags/" + templateArn)
                .then()
                .statusCode(200)
                .body("tags.team", equalTo("resilience"));

        awsRequest()
                .queryParam("tagKeys", "team")
                .delete("/tags/" + templateArn)
                .then()
                .statusCode(200);

        awsRequest()
                .get("/tags/" + templateArn)
                .then()
                .statusCode(200)
                .body("tags.team", equalTo(null));

        String experimentId = jsonRequest("""
                {"clientToken":"%s","experimentTemplateId":"%s","experimentOptions":{"actionsMode":"run-all"},"tags":{"purpose":"compat"}}
                """.formatted(UUID.randomUUID(), templateId))
                .post("/experiments")
                .then()
                .statusCode(200)
                .body("experiment.experimentTemplateId", equalTo(templateId))
                .body("experiment.state.status", equalTo("running"))
                .extract().path("experiment.id");

        awsRequest()
                .get("/experiments/" + experimentId)
                .then()
                .statusCode(200)
                .body("experiment.id", equalTo(experimentId));

        awsRequest()
                .queryParam("experimentTemplateId", templateId)
                .queryParam("maxResults", 100)
                .get("/experiments")
                .then()
                .statusCode(200)
                .body("experiments.id", hasItem(experimentId));

        awsRequest()
                .queryParam("maxResults", 100)
                .get("/experiments/" + experimentId + "/resolvedTargets")
                .then()
                .statusCode(200)
                .body("resolvedTargets", hasSize(0));

        awsRequest()
                .get("/experiments/" + experimentId + "/targetAccountConfigurations")
                .then()
                .statusCode(200)
                .body("targetAccountConfigurations.accountId", hasItem(TARGET_ACCOUNT_ID));

        awsRequest()
                .get("/experiments/" + experimentId
                        + "/targetAccountConfigurations/" + TARGET_ACCOUNT_ID)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.accountId", equalTo(TARGET_ACCOUNT_ID));

        awsRequest()
                .delete("/experiments/" + experimentId)
                .then()
                .statusCode(200)
                .body("experiment.state.status", equalTo("stopped"));

        awsRequest()
                .get("/safetyLevers/default")
                .then()
                .statusCode(200)
                .body("safetyLever.id", equalTo("default"))
                .body("safetyLever.state.status", equalTo("disengaged"));

        jsonRequest("{\"state\":{\"status\":\"engaged\",\"reason\":\"integration test\"}}")
                .patch("/safetyLevers/default/state")
                .then()
                .statusCode(200)
                .body("safetyLever.state.status", equalTo("engaged"));

        String cancelledId = jsonRequest("""
                {"clientToken":"%s","experimentTemplateId":"%s","experimentOptions":{"actionsMode":"skip-all"}}
                """.formatted(UUID.randomUUID(), templateId))
                .post("/experiments")
                .then()
                .statusCode(200)
                .body("experiment.state.status", equalTo("cancelled"))
                .extract().path("experiment.id");
        assertNotEquals(experimentId, cancelledId);

        jsonRequest("{\"state\":{\"status\":\"disengaged\",\"reason\":\"integration complete\"}}")
                .patch("/safetyLevers/default/state")
                .then()
                .statusCode(200)
                .body("safetyLever.state.status", equalTo("disengaged"));

        awsRequest()
                .delete("/experimentTemplates/" + templateId
                        + "/targetAccountConfigurations/" + TARGET_ACCOUNT_ID)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.accountId", equalTo(TARGET_ACCOUNT_ID));

        awsRequest()
                .delete("/experimentTemplates/" + templateId)
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", equalTo(templateId));

        awsRequest()
                .get("/experimentTemplates/" + templateId)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", "ResourceNotFoundException")
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", containsString(templateId));
    }

    @Test
    void paginationAndValidationFailuresUseAwsErrors() {
        Response first = awsRequest()
                .queryParam("maxResults", 1)
                .get("/actions")
                .then()
                .statusCode(200)
                .body("actions", hasSize(1))
                .extract().response();
        String nextToken = first.path("nextToken");
        assertFalse(nextToken.isBlank());

        Response second = awsRequest()
                .queryParam("maxResults", 1)
                .queryParam("nextToken", nextToken)
                .get("/actions")
                .then()
                .statusCode(200)
                .body("actions", hasSize(1))
                .extract().response();
        assertNotEquals(
                first.<String>path("actions[0].id"),
                second.<String>path("actions[0].id"));

        awsRequest()
                .queryParam("maxResults", 0)
                .get("/actions")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        awsRequest()
                .queryParam("nextToken", "not-a-token")
                .get("/actions")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        jsonRequest("[]")
                .post("/experimentTemplates")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        jsonRequest(templateBody(UUID.randomUUID().toString(), "trailing JSON value") + "\n{}")
                .post("/experimentTemplates")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", "ValidationException")
                .body("__type", equalTo("ValidationException"));

        jsonRequest("{}")
                .post("/experimentTemplates")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        String filterOnlyTemplateId = jsonRequest("""
                {
                  "clientToken":"%s",
                  "description":"filter-only target",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{
                    "instances":{
                      "resourceType":"aws:ec2:instance",
                      "filters":[{"path":"State.Name","values":["running"]}],
                      "selectionMode":"ALL"
                    }
                  },
                  "actions":{
                    "stop":{
                      "actionId":"aws:ec2:stop-instances",
                      "targets":{"Instances":"instances"}
                    }
                  }
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN))
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplate.targets.instances.filters[0].path", equalTo("State.Name"))
                .extract().path("experimentTemplate.id");
        awsRequest()
                .delete("/experimentTemplates/" + filterOnlyTemplateId)
                .then()
                .statusCode(200);

        jsonRequest("""
                {
                  "clientToken":"%s",
                  "description":"invalid logging template",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{},
                  "actions":{"wait":{"actionId":"aws:fis:wait","parameters":{"duration":"PT1M"}}},
                  "logConfiguration":{}
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN))
                .post("/experimentTemplates")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", "ValidationException")
                .body("__type", equalTo("ValidationException"));

        awsRequest()
                .get("/actions/aws:fis:not-real")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        String missingTemplateArn = "arn:aws:fis:" + REGION + ":" + ACCOUNT_ID
                + ":experiment-template/EXTdoesnotexist";
        awsRequest()
                .get("/tags/" + missingTemplateArn)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", "ResourceNotFoundException")
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void templatesAreIsolatedByAccountAndRegion() {
        String token = UUID.randomUUID().toString();
        String body = """
                {
                  "clientToken":"%s",
                  "description":"isolation template",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{},
                  "actions":{"wait":{"actionId":"aws:fis:wait","parameters":{"duration":"PT1M"}}}
                }
                """.formatted(token, ROLE_ARN);
        String templateId = jsonRequest(body)
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .extract().path("experimentTemplate.id");

        awsRequest(ACCOUNT_ID, "us-west-2")
                .get("/experimentTemplates/" + templateId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        awsRequest("000000000399", REGION)
                .get("/experimentTemplates/" + templateId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        awsRequest()
                .delete("/experimentTemplates/" + templateId)
                .then()
                .statusCode(200);
    }

    @Test
    void experimentsDoNotMutateEmulatedTargetServices() {
        String instanceId = null;
        String templateId = null;
        String experimentId = null;
        try {
            instanceId = ec2Request()
                    .formParam("Action", "RunInstances")
                    .formParam("ImageId", "ami-fis-safety-test")
                    .formParam("InstanceType", "t3.micro")
                    .formParam("MinCount", "1")
                    .formParam("MaxCount", "1")
                    .post("/")
                    .then()
                    .statusCode(200)
                    .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

            ec2Request()
                    .formParam("Action", "DescribeInstances")
                    .formParam("InstanceId.1", instanceId)
                    .post("/")
                    .then()
                    .statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                            equalTo("running"));

            templateId = jsonRequest("""
                    {
                      "clientToken":"%s",
                      "description":"cross-service safety proof",
                      "roleArn":"%s",
                      "stopConditions":[{"source":"none"}],
                      "targets":{
                        "instances":{
                          "resourceType":"aws:ec2:instance",
                          "resourceArns":["arn:aws:ec2:%s:%s:instance/%s"],
                          "selectionMode":"ALL"
                        }
                      },
                      "actions":{
                        "stop":{
                          "actionId":"aws:ec2:stop-instances",
                          "targets":{"Instances":"instances"}
                        }
                      }
                    }
                    """.formatted(UUID.randomUUID(), ROLE_ARN, REGION, ACCOUNT_ID, instanceId))
                    .post("/experimentTemplates")
                    .then()
                    .statusCode(200)
                    .extract().path("experimentTemplate.id");

            experimentId = jsonRequest("""
                    {"clientToken":"%s","experimentTemplateId":"%s","experimentOptions":{"actionsMode":"run-all"}}
                    """.formatted(UUID.randomUUID(), templateId))
                    .post("/experiments")
                    .then()
                    .statusCode(200)
                    .body("experiment.state.status", equalTo("running"))
                    .body("experiment.state.reason", containsString("safe emulation mode"))
                    .body("experiment.actions.stop.state.status", equalTo("running"))
                    .body("experiment.actions.stop.state.reason",
                            equalTo("The action is running in safe emulation mode."))
                    .extract().path("experiment.id");

            ec2Request()
                    .formParam("Action", "DescribeInstances")
                    .formParam("InstanceId.1", instanceId)
                    .post("/")
                    .then()
                    .statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                            equalTo("running"));
        } finally {
            if (experimentId != null) {
                awsRequest().delete("/experiments/" + experimentId);
            }
            if (templateId != null) {
                awsRequest().delete("/experimentTemplates/" + templateId);
            }
            if (instanceId != null) {
                ec2Request()
                        .formParam("Action", "TerminateInstances")
                        .formParam("InstanceId.1", instanceId)
                        .post("/");
            }
        }
    }

    private static io.restassured.specification.RequestSpecification awsRequest() {
        return awsRequest(ACCOUNT_ID, REGION);
    }

    private static io.restassured.specification.RequestSpecification awsRequest(String accountId, String region) {
        return given().header("Authorization", authorization(accountId, region));
    }

    private static io.restassured.specification.RequestSpecification ec2Request() {
        return given().header("Authorization", authorization(ACCOUNT_ID, REGION, "ec2"));
    }

    private static io.restassured.specification.RequestSpecification jsonRequest(String body) {
        return awsRequest().contentType("application/json").body(body);
    }

    private static String templateBody(String token, String description) {
        return """
                {
                  "clientToken":"%s",
                  "description":"%s",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{},
                  "actions":{"wait":{"actionId":"aws:fis:wait","parameters":{"duration":"PT1M"}}},
                  "tags":{"environment":"test"},
                  "experimentOptions":{"accountTargeting":"multi-account","emptyTargetResolutionMode":"skip"}
                }
                """.formatted(token, description, ROLE_ARN);
    }

    private static String authorization() {
        return authorization(ACCOUNT_ID, REGION);
    }

    private static String authorization(String accountId, String region) {
        return authorization(accountId, region, "fis");
    }

    private static String authorization(String accountId, String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260820/" + region
                + "/" + service + "/aws4_request";
    }
}
