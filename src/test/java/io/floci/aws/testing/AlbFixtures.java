package io.floci.aws.testing;

import io.floci.aws.services.ec2.Ec2Service;
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

    private static final String DEFAULT_ACCOUNT = "test";

    private AlbFixtures() {
    }

    public static String createLoadBalancer(String name) {
        return createLoadBalancer(DEFAULT_ACCOUNT, name);
    }

    public static String createLoadBalancer(String account, String name) {
        return given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", name)
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", auth(account))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    public static String loadBalancerDnsName(String loadBalancerArn) {
        return given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", loadBalancerArn)
                .header("Authorization", auth(DEFAULT_ACCOUNT))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.DNSName");
    }

    /** An HTTP target group with health checks off, so a target serves as soon as it is registered. */
    public static String createTargetGroup(String name, String targetType, int targetPort) {
        return createTargetGroup(DEFAULT_ACCOUNT, name, targetType, targetPort);
    }

    public static String createTargetGroup(String account, String name, String targetType, int targetPort) {
        return given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", name)
                .formParam("Protocol", "HTTP")
                .formParam("Port", targetPort)
                .formParam("TargetType", targetType)
                .formParam("HealthCheckEnabled", "false")
                .header("Authorization", auth(account))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    public static void registerTarget(String targetGroupArn, String targetId, int targetPort) {
        registerTarget(DEFAULT_ACCOUNT, targetGroupArn, targetId, targetPort);
    }

    public static void registerTarget(String account, String targetGroupArn, String targetId, int targetPort) {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", targetId)
                .formParam("Targets.member.1.Port", targetPort)
                .header("Authorization", auth(account))
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    /** An HTTP listener on {@code port} that forwards every request to {@code targetGroupArn}. */
    public static String createListener(String loadBalancerArn, String targetGroupArn, int port) {
        return createListener(DEFAULT_ACCOUNT, loadBalancerArn, targetGroupArn, port);
    }

    public static String createListener(String account, String loadBalancerArn, String targetGroupArn, int port) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", port)
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", targetGroupArn)
                .header("Authorization", auth(account))
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
                .header("Authorization", auth(DEFAULT_ACCOUNT))
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    public static void deleteListener(String listenerArn) {
        deleteListener(DEFAULT_ACCOUNT, listenerArn);
    }

    public static void deleteListener(String account, String listenerArn) {
        delete(account, "DeleteListener", "ListenerArn", listenerArn);
    }

    public static void deleteTargetGroup(String targetGroupArn) {
        deleteTargetGroup(DEFAULT_ACCOUNT, targetGroupArn);
    }

    public static void deleteTargetGroup(String account, String targetGroupArn) {
        delete(account, "DeleteTargetGroup", "TargetGroupArn", targetGroupArn);
    }

    public static void deleteLoadBalancer(String loadBalancerArn) {
        deleteLoadBalancer(DEFAULT_ACCOUNT, loadBalancerArn);
    }

    public static void deleteLoadBalancer(String account, String loadBalancerArn) {
        delete(account, "DeleteLoadBalancer", "LoadBalancerArn", loadBalancerArn);
    }

    /** Sends {@code text} on the socket and returns the next text message it receives. */
    public static String exchange(WebSocket socket, String text) throws Exception {
        CompletableFuture<String> reply = new CompletableFuture<>();
        socket.textMessageHandler(reply::complete);
        socket.writeTextMessage(text);
        return reply.get(5, TimeUnit.SECONDS);
    }

    private static void delete(String account, String action, String arnParameter, String arn) {
        if (arn == null) {
            return;
        }
        given()
                .formParam("Action", action)
                .formParam(arnParameter, arn)
                .header("Authorization", auth(account))
            .when()
                .post("/")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }

    private static String auth(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261101/us-east-1/elasticloadbalancing/aws4_request";
    }
}
