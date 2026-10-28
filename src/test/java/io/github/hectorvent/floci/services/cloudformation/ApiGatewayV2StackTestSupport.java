package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * What the AWS::ApiGatewayV2 stack integration tests share: driving a stack through the query API,
 * and reading back the stack and its {@code Integration} resource.
 */
final class ApiGatewayV2StackTestSupport {

    static final String REGION = "us-east-1";

    /** A resource that fails to create, so an update that adds it rolls back. */
    static final String FAILING_RESOURCE =
            ",\"Broken\":{\"Type\":\"AWS::ApiGatewayV2::VpcLink\",\"DependsOn\":\"Integration\","
                    + "\"Properties\":{\"Name\":\"broken\"}}";

    private ApiGatewayV2StackTestSupport() {
    }

    static Integration integration(ApiGatewayV2Service apiGatewayV2Service, Stack stack) {
        StackResource api = stack.getResources().get("HttpApi");
        StackResource integration = stack.getResources().get("Integration");
        return apiGatewayV2Service.getIntegration(REGION, api.getPhysicalId(), integration.getPhysicalId());
    }

    static Stack stack(CloudFormationService cloudFormationService, String stackName) {
        return cloudFormationService.describeStacks(stackName, REGION).getFirst();
    }

    static void stackAction(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }
}
