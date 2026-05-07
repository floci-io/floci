package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for ALB with Lambda target type.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2LambdaTargetIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";

    private static String lbArn;
    private static String tgArn;
    private static String listenerArn;
    private static String functionArn;

    @Test
    @Order(1)
    void createLambdaFunction() {
        functionArn = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "alb-lambda-target-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler"
                }
                """)
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo("alb-lambda-target-fn"))
            .extract()
            .path("FunctionArn");
    }

    @Test
    @Order(2)
    void createLoadBalancer() {
        lbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "lambda-target-lb")
                .formParam("Type", "application")
                .formParam("Scheme", "internet-facing")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerName",
                        equalTo("lambda-target-lb"))
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    @Test
    @Order(3)
    void createLambdaTargetGroup() {
        tgArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "lambda-tg")
                .formParam("TargetType", "lambda")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupName",
                        equalTo("lambda-tg"))
                .body("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetType",
                        equalTo("lambda"))
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    @Test
    @Order(4)
    void registerLambdaTarget() {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Targets.member.1.Id", functionArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(5)
    void describeLambdaTargetHealth() {
        given()
                .formParam("Action", "DescribeTargetHealth")
                .formParam("TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.Target.Id",
                        equalTo(functionArn));
    }

    @Test
    @Order(6)
    void createListenerWithLambdaForwardAction() {
        listenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", "7780")
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateListenerResponse.CreateListenerResult.Listeners.member.Protocol", equalTo("HTTP"))
                .body("CreateListenerResponse.CreateListenerResult.Listeners.member.Port", equalTo("7780"))
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    @Test
    @Order(7)
    void describeListenerShowsLambdaTarget() {
        given()
                .formParam("Action", "DescribeListeners")
                .formParam("ListenerArns.member.1", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.ListenerArn",
                        equalTo(listenerArn));
    }

    @Test
    @Order(8)
    void deregisterLambdaTarget() {
        given()
                .formParam("Action", "DeregisterTargets")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Targets.member.1.Id", functionArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(90)
    void cleanup() {
        given()
                .formParam("Action", "DeleteListener")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
            .delete("/2015-03-31/functions/alb-lambda-target-fn")
        .then()
            .statusCode(anyOf(equalTo(204), equalTo(200)));
    }
}
