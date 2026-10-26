package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * An in-place update of an AWS::ApiGateway::RestApi whose own undo cannot complete leaves the stack
 * in UPDATE_ROLLBACK_FAILED, and ContinueUpdateRollback takes that undo up again: a restore that
 * failed is tried again, and an update whose OpenAPI document replaced the API's definition fails
 * again until the API is skipped. Each test injects one failure into ApiGatewayService, at the call
 * it is about.
 */
@QuarkusTest
class ApiGatewayRestApiCfnUpdateRollbackRetryIntegrationTest {

    /** The stack's template: the API's name, its description and endpoint type, then its Body. */
    private static final String TEMPLATE = """
            {"Resources":{"Api":{"Type":"AWS::ApiGateway::RestApi","Properties":{
              "Name":"%s","Description":"%s","EndpointConfiguration":{"Types":["%s"]}%s}}}}
            """;
    private static final String ACCEPTED_BODY = """
            ,"Body":{"openapi":"3.0.1","info":{"title":"replacing-document","version":"1"},
              "paths":{"/items":{"get":{"responses":{"200":{"description":"ok"}}}}}}""";
    /** A token authorizer whose cache TTL is above the 3600-second maximum, which the import rejects. */
    private static final String REJECTED_BODY = """
            ,"Body":{"openapi":"3.0.1","info":{"title":"rejected-document","version":"1"},
              "components":{"securitySchemes":{"token":{"type":"apiKey","name":"Authorization","in":"header",
                "x-amazon-apigateway-authtype":"custom",
                "x-amazon-apigateway-authorizer":{"type":"token","authorizerResultTtlInSeconds":3601,
                  "authorizerUri":{"Fn::Sub":"arn:${AWS::Partition}:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:authorizer/invocations"}}}}},
              "paths":{"/items":{"get":{"security":[{"token":[]}],"responses":{"200":{"description":"ok"}}}}}}""";

    @InjectSpy
    ApiGatewayService apiGatewayService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRetryFailsAgainOnceTheUpdatesDocumentReplacedTheApi() {
        String stack = "apigw-cfn-restapi-retry-document-it";
        String name = "cfn-restapi-retry-document";
        try {
            stackAction(stack, "CreateStack", TEMPLATE.formatted(name, "first", "REGIONAL", ""));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
            String apiId = physicalId(stack);
            // After putRestApi accepted the document, applying the declared name and description
            // again (just those two operations) fails once.
            Mockito.doThrow(new AwsException("TooManyRequestsException", "Too Many Requests", 429))
                    .doCallRealMethod()
                    .when(apiGatewayService).updateRestApi(anyString(), eq(apiId),
                            argThat(onlyNameAndDescription(name, "second")));

            stackAction(stack, "UpdateStack", TEMPLATE.formatted(name, "second", "EDGE", ACCEPTED_BODY));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_FAILED", state.status(), state.reason());
            assertThat(apiResourceReason(stack), containsString("OpenAPI document was already applied"));
            // The patch is put back; the definition the document replaced is not.
            given().when().get("/restapis/" + apiId).then().statusCode(200)
                    .body("name", equalTo(name))
                    .body("description", equalTo("first"))
                    .body("endpointConfiguration.types", contains("REGIONAL"));
            assertPaths(apiId, "/", "/items");

            cfn("ContinueUpdateRollback", stack).when().post("/").then().statusCode(200);
            state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_FAILED", state.status(), state.reason());
            assertEquals("Rollback is not implemented for AWS::ApiGateway::RestApi", apiResourceReason(stack));

            cfn("ContinueUpdateRollback", stack).formParam("ResourcesToSkip.member.1", "Api")
                    .when().post("/").then().statusCode(200);
            state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        } finally {
            deleteStack(stack);
        }
    }

    @Test
    void aRetryPutsBackThePatchWhoseFirstRestoreFailed() {
        String stack = "apigw-cfn-restapi-retry-restore-it";
        String name = "cfn-restapi-retry-restore";
        try {
            stackAction(stack, "CreateStack", TEMPLATE.formatted(name, "first", "REGIONAL", ""));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
            String apiId = physicalId(stack);
            // The import rejects the document, and the first restore of the patch fails.
            ArgumentMatcher<List<Map<String, String>>> restore =
                    operations -> operations != null && operations.contains(replace("/description", "first"));
            Mockito.doThrow(new AwsException("TooManyRequestsException", "Too Many Requests", 429))
                    .doCallRealMethod()
                    .when(apiGatewayService).updateRestApi(anyString(), eq(apiId), argThat(restore));

            stackAction(stack, "UpdateStack", TEMPLATE.formatted(name, "second", "EDGE", REJECTED_BODY));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_FAILED", state.status(), state.reason());
            given().when().get("/restapis/" + apiId).then().statusCode(200)
                    .body("description", equalTo("second"))
                    .body("endpointConfiguration.types", contains("EDGE"));

            cfn("ContinueUpdateRollback", stack).when().post("/").then().statusCode(200);
            state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
            given().when().get("/restapis/" + apiId).then().statusCode(200)
                    .body("name", equalTo(name))
                    .body("description", equalTo("first"))
                    .body("endpointConfiguration.types", contains("REGIONAL"));
            assertPaths(apiId, "/");
        } finally {
            deleteStack(stack);
        }
    }

    /** The re-apply of the declared name and description: exactly those two operations. */
    private static ArgumentMatcher<List<Map<String, String>>> onlyNameAndDescription(String name, String description) {
        return operations -> operations != null && operations.size() == 2
                && operations.contains(replace("/name", name))
                && operations.contains(replace("/description", description));
    }

    private static Map<String, String> replace(String path, String value) {
        return Map.of("op", "replace", "path", path, "value", value);
    }

    private static void assertPaths(String apiId, String... paths) {
        given().when().get("/restapis/" + apiId + "/resources").then().statusCode(200)
                .body("item.path", containsInAnyOrder(paths));
    }

    private static String physicalId(String stack) {
        return XmlParser.extractFirst(apiResource(stack), "PhysicalResourceId", null);
    }

    private static String apiResourceReason(String stack) {
        return XmlParser.extractFirst(apiResource(stack), "ResourceStatusReason", null);
    }

    private static String apiResource(String stack) {
        return cfn("DescribeStackResource", stack).formParam("LogicalResourceId", "Api")
                .when().post("/").then().statusCode(200).extract().asString();
    }

    private static void stackAction(String stack, String action, String template) {
        cfn(action, stack).formParam("TemplateBody", template).when().post("/").then().statusCode(200);
    }

    private static void deleteStack(String stack) {
        cfn("DeleteStack", stack).when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }
}
