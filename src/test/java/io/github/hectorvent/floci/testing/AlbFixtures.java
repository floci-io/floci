package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.vertx.core.http.WebSocket;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/**
 * An application load balancer in front of a test's own target, for the classes that send traffic
 * through the real ELBv2 data plane, and the WebSocket exchange they check it with.
 */
public final class AlbFixtures {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261101/us-east-1/elasticloadbalancing/aws4_request";

    private AlbFixtures() {
    }

    public static String createLoadBalancer(String name) {
        return given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", name)
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    /** An HTTP target group with health checks off, so a target serves as soon as it is registered. */
    public static String createTargetGroup(String name, String targetType, int targetPort) {
        return given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", name)
                .formParam("Protocol", "HTTP")
                .formParam("Port", targetPort)
                .formParam("TargetType", targetType)
                .formParam("HealthCheckEnabled", "false")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    public static void registerTarget(String targetGroupArn, String targetId, int targetPort) {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", targetId)
                .formParam("Targets.member.1.Port", targetPort)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    /** An HTTP listener on {@code port} that forwards every request to {@code targetGroupArn}. */
    public static String createListener(String loadBalancerArn, String targetGroupArn, int port) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", port)
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", targetGroupArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    public static void forwardDefaultActionTo(String listenerArn, String targetGroupArn) {
        given()
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listenerArn)
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", targetGroupArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    public static void deleteListener(String listenerArn) {
        delete("DeleteListener", "ListenerArn", listenerArn);
    }

    public static void deleteTargetGroup(String targetGroupArn) {
        delete("DeleteTargetGroup", "TargetGroupArn", targetGroupArn);
    }

    public static void deleteLoadBalancer(String loadBalancerArn) {
        delete("DeleteLoadBalancer", "LoadBalancerArn", loadBalancerArn);
    }

    /** Sends {@code text} on the socket and returns the next text message it receives. */
    public static String exchange(WebSocket socket, String text) throws Exception {
        CompletableFuture<String> reply = new CompletableFuture<>();
        socket.textMessageHandler(reply::complete);
        socket.writeTextMessage(text);
        return reply.get(5, TimeUnit.SECONDS);
    }

    private static void delete(String action, String arnParameter, String arn) {
        if (arn == null) {
            return;
        }
        given()
                .formParam("Action", action)
                .formParam(arnParameter, arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }
}
