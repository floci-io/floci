package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import static io.floci.aws.services.cloudformation.CfnStackTestSupport.physicalId;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** An AWS::ApiGateway::Stage updated through a stack: in place, rolled back, moved by a new name or API, deleted. */
@QuarkusTest
class ApiGatewayStageCfnUpdateIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }
    @Test
    void anInPlaceUpdateKeepsOutOfBandVariablesAndRollsBackAndDropsDescription() {
        String stack = "apigw-cfn-stage-inplace-it";
        CloudFormationClient cf = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        try {
            CfnStackWaits.createStack(cf, stack, inStack("D1", "\"Description\":\"a\"", ""), "CREATE_COMPLETE");
            String apiId = physicalId(cf, stack, "Api");
            String d2 = physicalId(cf, stack, "D2");
            given().contentType("application/json")
                    .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/variables/k\",\"value\":\"v\"}]}")
                    .when().patch("/restapis/" + apiId + "/stages/prod").then().statusCode(200);

            CfnStackWaits.updateStack(cf, stack, inStack("D2", "\"Description\":\"b\"", ""), "UPDATE_COMPLETE");
            given().when().get("/restapis/" + apiId + "/stages/prod").then().statusCode(200)
                    .body("deploymentId", equalTo(d2)).body("description", equalTo("b"))
                    .body("variables.k", equalTo("v"));

            CfnStackWaits.updateStack(cf, stack, inStack("D1", "\"Description\":\"c\"",
                    CfnStackWaits.failingResourceAfter("Stage")), "UPDATE_ROLLBACK_COMPLETE");
            given().when().get("/restapis/" + apiId + "/stages/prod").then().statusCode(200)
                    .body("deploymentId", equalTo(d2)).body("description", equalTo("b"));

            CfnStackWaits.updateStack(cf, stack, inStack("D2", "", ""), "UPDATE_COMPLETE");
            given().when().get("/restapis/" + apiId + "/stages/prod").then().statusCode(200)
                    .body("deploymentId", equalTo(d2)).body("description", nullValue());
        } finally {
            CfnStackWaits.deleteStack(cf, stack);
            cf.close();
        }
    }

    @Test
    void aChangedStageNameOrRestApiIdMovesTheStageAndDeleteRemovesIt() {
        String stack = "apigw-cfn-stage-replace-it";
        CloudFormationClient cf = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        String stackId = stack;
        try {
            String[] a = outsideApi("stage-a");
            String[] b = outsideApi("stage-b");
            stackId = CfnStackWaits.createStack(cf, stack, outside(a, "prod"), "CREATE_COMPLETE").stackId();

            CfnStackWaits.updateStack(cf, stack, outside(a, "beta"), "UPDATE_COMPLETE");
            given().when().get("/restapis/" + a[0] + "/stages/beta").then().statusCode(200);
            given().when().get("/restapis/" + a[0] + "/stages/prod").then().statusCode(404);
            CfnStackWaits.updateStack(cf, stack, outside(b, "beta"), "UPDATE_COMPLETE");
            given().when().get("/restapis/" + b[0] + "/stages/beta").then().statusCode(200);
            given().when().get("/restapis/" + a[0] + "/stages/beta").then().statusCode(404);

            CfnStackWaits.deleteStack(cf, stackId);
            given().when().get("/restapis/" + b[0] + "/stages/beta").then().statusCode(404);
        } finally {
            CfnStackWaits.deleteStack(cf, stackId);
            cf.close();
        }
    }

    /** An API made outside any stack with a method and a deployment: its id and the deployment id. */
    private static String[] outsideApi(String name) {
        String apiId = given().contentType("application/json").body("{\"name\":\"" + name + "\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources").then().extract().path("item[0].id");
        String method = "/restapis/" + apiId + "/resources/" + rootId + "/methods/GET";
        given().contentType("application/json").body("{\"authorizationType\":\"NONE\"}")
                .when().put(method).then().statusCode(201);
        given().contentType("application/json").body("{\"type\":\"MOCK\"}")
                .when().put(method + "/integration").then().statusCode(201);
        String deploymentId = given().contentType("application/json").body("{}")
                .when().post("/restapis/" + apiId + "/deployments").then().statusCode(201).extract().path("id");
        return new String[] {apiId, deploymentId};
    }

    private static String outside(String[] api, String stageName) {
        return """
                {"Resources":{"Stage":{"Type":"AWS::ApiGateway::Stage","Properties":{
                  "RestApiId":"%s","StageName":"%s","DeploymentId":"%s"}}}}
                """.formatted(api[0], stageName, api[1]);
    }

    /** One API with a method, deployments D1 and D2 and a stage prod on {@code deployment}; {@code extra} are its properties. */
    private static String inStack(String deployment, String extra, String later) {
        return """
                {"Resources":{
                  "Api":{"Type":"AWS::ApiGateway::RestApi","Properties":{"Name":"cfn-stage"}},
                  "M":{"Type":"AWS::ApiGateway::Method","Properties":{"RestApiId":{"Ref":"Api"},
                    "ResourceId":{"Fn::GetAtt":["Api","RootResourceId"]},"HttpMethod":"GET",
                    "AuthorizationType":"NONE","Integration":{"Type":"MOCK"}}},
                  "D1":{"Type":"AWS::ApiGateway::Deployment","DependsOn":"M","Properties":{"RestApiId":{"Ref":"Api"}}},
                  "D2":{"Type":"AWS::ApiGateway::Deployment","DependsOn":"M","Properties":{"RestApiId":{"Ref":"Api"}}},
                  "Stage":{"Type":"AWS::ApiGateway::Stage","Properties":{"RestApiId":{"Ref":"Api"},
                    "StageName":"prod","DeploymentId":{"Ref":"%s"}%s}}%s}}
                """.formatted(deployment, extra.isEmpty() ? "" : "," + extra, later);
    }
}
