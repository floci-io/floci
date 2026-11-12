package io.floci.aws.services.cloudformation;

import io.floci.aws.services.apigatewayv2.ApiGatewayV2Service;
import io.floci.aws.services.apigatewayv2.model.Authorizer;
import io.floci.aws.services.apigatewayv2.model.Integration;
import io.floci.aws.services.apigatewayv2.model.Route;
import io.floci.aws.services.apigatewayv2.model.Stage;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * What the AWS::ApiGatewayV2 stack integration tests share: driving a stack through the query API,
 * and reading back the stack and its {@code Integration}, {@code Route}, {@code Authorizer} and
 * {@code Stage} resources.
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

    static Route route(ApiGatewayV2Service apiGatewayV2Service, Stack stack) {
        StackResource api = stack.getResources().get("HttpApi");
        StackResource route = stack.getResources().get("Route");
        return apiGatewayV2Service.getRoute(REGION, api.getPhysicalId(), route.getPhysicalId());
    }

    static Authorizer authorizer(ApiGatewayV2Service apiGatewayV2Service, Stack stack) {
        StackResource api = stack.getResources().get("HttpApi");
        StackResource authorizer = stack.getResources().get("Authorizer");
        return apiGatewayV2Service.getAuthorizer(REGION, api.getPhysicalId(), authorizer.getPhysicalId());
    }

    static Stage stage(ApiGatewayV2Service apiGatewayV2Service, Stack stack) {
        StackResource api = stack.getResources().get("HttpApi");
        StackResource stage = stack.getResources().get("Stage");
        return apiGatewayV2Service.getStage(REGION, api.getPhysicalId(), stage.getPhysicalId());
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
