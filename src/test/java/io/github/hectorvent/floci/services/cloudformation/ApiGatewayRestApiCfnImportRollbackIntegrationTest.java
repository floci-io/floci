package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A stack whose AWS::ApiGateway::RestApi declares a Body that PutRestApi rejects fails to create.
 * The REST API was created before the import ran, and the rollback removes it, so neither
 * ROLLBACK_COMPLETE nor DeleteStack leaves it behind. A REST API of the same name that the stack
 * did not create is left alone.
 */
@QuarkusTest
class ApiGatewayRestApiCfnImportRollbackIntegrationTest {

    private static final String API_NAME = "cfn-restapi-import-rollback";
    /** A token authorizer whose cache TTL is above the 3600-second maximum, which the import rejects. */
    private static final String TEMPLATE = """
            {"Resources":{"Api":{"Type":"AWS::ApiGateway::RestApi","Properties":{
              "Name":"cfn-restapi-import-rollback",
              "Body":{"openapi":"3.0.1","info":{"title":"cfn-restapi-import-rollback","version":"1"},
                "components":{"securitySchemes":{"token":{"type":"apiKey","name":"Authorization","in":"header",
                  "x-amazon-apigateway-authtype":"custom",
                  "x-amazon-apigateway-authorizer":{"type":"token","authorizerResultTtlInSeconds":3601,
                    "authorizerUri":{"Fn::Sub":"arn:${AWS::Partition}:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:authorizer/invocations"}}}}},
                "paths":{"/items":{"get":{"security":[{"token":[]}],
                  "responses":{"200":{"description":"ok"}}}}}}}}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void rollbackRemovesTheRestApiWhoseBodyWasRejected() {
        String stack = "apigw-cfn-restapi-import-rollback-it";
        String outsideId = given().contentType("application/json")
                .body("{\"name\":\"" + API_NAME + "\",\"description\":\"outside the stack\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        String createdId = null;
        boolean stackDeleted = false;
        try {
            stackAction(stack, "CreateStack", TEMPLATE);
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
            assertThat(CfnStackWaits.failedResourceReason(stack), containsString("authorizerResultTtlInSeconds"));
            createdId = physicalId(stack, "Api");
            assertNotEquals(outsideId, createdId);

            given().when().get("/restapis/" + createdId).then().statusCode(404);
            assertOutsideApiIntact(outsideId);

            deleteStack(stack);
            stackDeleted = true;
            given().when().get("/restapis/" + createdId).then().statusCode(404);
            assertOutsideApiIntact(outsideId);
        } finally {
            try {
                if (!stackDeleted) {
                    deleteStack(stack);
                }
            } finally {
                if (createdId != null) {
                    given().when().delete("/restapis/" + createdId);
                }
                given().when().delete("/restapis/" + outsideId);
            }
        }
    }

    private static void assertOutsideApiIntact(String apiId) {
        given().when().get("/restapis/" + apiId).then().statusCode(200)
                .body("name", equalTo(API_NAME))
                .body("description", equalTo("outside the stack"));
    }

    private static String physicalId(String stack, String logicalId) {
        String xml = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200)
                .extract().asString();
        return XmlParser.extractGroups(xml, "member").stream()
                .filter(resource -> logicalId.equals(resource.get("LogicalResourceId")))
                .findFirst().orElseThrow().get("PhysicalResourceId");
    }

    private static void stackAction(String stack, String action, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stack)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    private static void deleteStack(String stack) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }
}
