package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A new PathPart replaces an AWS::ApiGateway::Resource on an API outside the stack. The displaced
 * resource is deleted in the cleanup after the update commits, kept under UpdateReplacePolicy:
 * Retain, and still there when a later resource fails the update and it rolls back. A resource the
 * update kept has nothing to roll back.
 */
@QuarkusTest
class ApiGatewayResourceCfnReplacementIntegrationTest {

    /** The API, its root resource, the PathPart, the UpdateReplacePolicy and any extra resources. */
    private static final String TEMPLATE = """
            {
              "Resources": {
                "Res": {"Type":"AWS::ApiGateway::Resource", "UpdateReplacePolicy":"%4$s", "Properties":{
                  "RestApiId":"%1$s", "ParentId":"%2$s", "PathPart":"%3$s"}}%5$s
              },
              "Outputs":{"ResourceId":{"Value":{"Ref":"Res"}}}
            }
            """;
    /** Fails after Res has been replaced: its parent does not exist. */
    private static final String FAILING_RESOURCE = """
            ,
                "Broken": {"Type":"AWS::ApiGateway::Resource", "DependsOn":"Res", "Properties":{
                  "RestApiId":"%1$s", "ParentId":"missing0", "PathPart":"broken"}}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void displacedResourceIsDeletedOnceTheUpdateCommits() {
        String stack = "apigw-cfn-resource-replace-it";
        String[] api = createApi("cfn-resource-replace");
        try {
            stackAction(stack, "CreateStack", TEMPLATE.formatted(api[0], api[1], "infrastructures", "Delete", ""));
            String displaced = resourceIdOnceStatusIs(stack, "CREATE_COMPLETE");

            stackAction(stack, "UpdateStack", TEMPLATE.formatted(api[0], api[1], "renamed", "Delete", ""));
            assertNotEquals(displaced, resourceIdOnceStatusIs(stack, "UPDATE_COMPLETE"));

            assertPaths(api[0], "/", "/renamed");
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DescribeStackEvents")
                    .formParam("StackName", stack)
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<ResourceStatus>UPDATE_COMPLETE_CLEANUP_IN_PROGRESS</ResourceStatus>"))
                    .body(containsString("<PhysicalResourceId>" + displaced + "</PhysicalResourceId>"))
                    .body(containsString("<ResourceStatus>DELETE_COMPLETE</ResourceStatus>"));
        } finally {
            deleteStack(stack);
            deleteApi(api[0]);
        }
    }

    @Test
    void displacedResourceSurvivesAnUpdateThatRollsBack() {
        String stack = "apigw-cfn-resource-replace-rollback-it";
        String[] api = createApi("cfn-resource-replace-rollback");
        try {
            stackAction(stack, "CreateStack", TEMPLATE.formatted(api[0], api[1], "infrastructures", "Delete", ""));
            String displaced = resourceIdOnceStatusIs(stack, "CREATE_COMPLETE");

            stackAction(stack, "UpdateStack", TEMPLATE.formatted(api[0], api[1], "renamed", "Delete",
                    FAILING_RESOURCE.formatted(api[0])));
            assertEquals(displaced, resourceIdOnceStatusIs(stack, "UPDATE_ROLLBACK_COMPLETE"));

            assertPaths(api[0], "/", "/infrastructures");

            deleteStack(stack);
            assertPaths(api[0], "/");
        } finally {
            deleteStack(stack);
            deleteApi(api[0]);
        }
    }

    @Test
    void keptResourceHasNothingToRollBack() {
        String stack = "apigw-cfn-resource-kept-rollback-it";
        String[] api = createApi("cfn-resource-kept-rollback");
        try {
            stackAction(stack, "CreateStack", TEMPLATE.formatted(api[0], api[1], "infrastructures", "Delete", ""));
            String kept = resourceIdOnceStatusIs(stack, "CREATE_COMPLETE");

            stackAction(stack, "UpdateStack", TEMPLATE.formatted(api[0], api[1], "infrastructures", "Delete",
                    FAILING_RESOURCE.formatted(api[0])));
            assertEquals(kept, resourceIdOnceStatusIs(stack, "UPDATE_ROLLBACK_COMPLETE"));

            assertPaths(api[0], "/", "/infrastructures");
        } finally {
            deleteStack(stack);
            deleteApi(api[0]);
        }
    }

    @Test
    void retainKeepsTheDisplacedResource() {
        String stack = "apigw-cfn-resource-replace-retain-it";
        String[] api = createApi("cfn-resource-replace-retain");
        try {
            stackAction(stack, "CreateStack", TEMPLATE.formatted(api[0], api[1], "infrastructures", "Retain", ""));
            resourceIdOnceStatusIs(stack, "CREATE_COMPLETE");

            stackAction(stack, "UpdateStack", TEMPLATE.formatted(api[0], api[1], "renamed", "Retain", ""));
            resourceIdOnceStatusIs(stack, "UPDATE_COMPLETE");
            assertPaths(api[0], "/", "/infrastructures", "/renamed");

            deleteStack(stack);
            assertPaths(api[0], "/", "/infrastructures");
        } finally {
            deleteStack(stack);
            deleteApi(api[0]);
        }
    }

    /** Creates a REST API outside any stack and returns its id and root resource id. */
    private static String[] createApi(String name) {
        ValidatableResponse api = given().contentType("application/json")
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/restapis").then().statusCode(201);
        return new String[]{api.extract().path("id"), api.extract().path("rootResourceId")};
    }

    private static void deleteApi(String apiId) {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    private static void assertPaths(String apiId, String... paths) {
        given().when().get("/restapis/" + apiId + "/resources").then().statusCode(200)
                .body("item.path", containsInAnyOrder(paths));
    }

    private static void stackAction(String stack, String action, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stack)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    private static String resourceIdOnceStatusIs(String stack, String status) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals(status, state.status(), state.reason());
        String xml = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200)
                .extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get("ResourceId");
    }

    private static void deleteStack(String stack) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }
}
