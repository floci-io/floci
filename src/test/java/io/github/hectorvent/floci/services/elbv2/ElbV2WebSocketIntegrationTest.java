package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2WebSocketIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260927/us-east-1/elasticloadbalancing/aws4_request";
    private static final int LISTENER_PORT = 7795;
    private static final String INSTANCE_ID = "i-0websocket0000001";

    @Inject
    Vertx vertx;

    @InjectSpy
    Ec2Service ec2Service;

    private HttpServer backend;
    private HttpClient client;
    private String loadBalancerArn;
    private String targetGroupArn;
    private String listenerArn;

    @BeforeEach
    void startBackend() throws Exception {
        backend = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                        request.response().setStatusCode(400).end("not an upgrade");
                    } else if (!request.path().startsWith("/socket.io/")) {
                        request.response().setStatusCode(404).end();
                    } else {
                        String query = request.query();
                        request.toWebSocket().onSuccess(socket -> socket.textMessageHandler(
                                text -> socket.writeTextMessage(query + ":" + text)));
                    }
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        client = vertx.createHttpClient();
        loadBalancerArn = createLoadBalancer();
        targetGroupArn = createTargetGroup("websocket-tg", "ip", backend.actualPort());
        registerTarget(targetGroupArn, "127.0.0.1", backend.actualPort());
        listenerArn = createListener(loadBalancerArn, targetGroupArn);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
            .when()
                .get("/")
            .then()
                .statusCode(400));
    }

    @AfterEach
    void stopBackend() throws Exception {
        deleteListener(listenerArn);
        deleteTargetGroup(targetGroupArn);
        deleteLoadBalancer(loadBalancerArn);
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void tunnelsWebSocketUpgradeToIpTarget() throws Exception {
        WebSocket socket = connect("/socket.io/?EIO=4&transport=websocket");
        try {
            assertEquals("EIO=4&transport=websocket:hello", exchange(socket, "hello"));
            assertEquals("EIO=4&transport=websocket:again", exchange(socket, "again"));
        } finally {
            socket.close();
        }
    }

    @Test
    void tunnelsWebSocketUpgradeToInstanceTarget() throws Exception {
        Instance instance = new Instance();
        instance.setInstanceId(INSTANCE_ID);
        instance.setContainerBridgeIp("127.0.0.1");
        doReturn(instance).when(ec2Service).findInstanceById(anyString(), eq(INSTANCE_ID));
        String instanceTargetGroupArn = createTargetGroup("websocket-instance-tg", "instance", backend.actualPort());
        try {
            registerTarget(instanceTargetGroupArn, INSTANCE_ID, backend.actualPort());
            forwardDefaultActionTo(listenerArn, instanceTargetGroupArn);

            WebSocket socket = connect("/socket.io/?transport=websocket");
            try {
                assertEquals("transport=websocket:hello", exchange(socket, "hello"));
            } finally {
                socket.close();
            }
        } finally {
            deleteListener(listenerArn);
            listenerArn = null;
            deleteTargetGroup(instanceTargetGroupArn);
        }
    }

    @Test
    void relaysTargetRejectionOfUpgrade() {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect("/elsewhere/?transport=websocket"));

        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(404, rejected.getStatus());
    }

    @Test
    void plainRequestStillReachesTargetWithoutUpgrade() {
        given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
            .when()
                .get("/socket.io/?transport=polling")
            .then()
                .statusCode(400)
                .body(equalTo("not an upgrade"));
    }

    @Test
    void closesTunnelAfterLoadBalancerIdleTimeout() throws Exception {
        setIdleTimeout(loadBalancerArn, 1);
        WebSocket socket = connect("/socket.io/?transport=websocket");
        CompletableFuture<Void> closed = new CompletableFuture<>();
        socket.closeHandler(ignored -> closed.complete(null));

        assertEquals("transport=websocket:ping", exchange(socket, "ping"));

        closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectsUpgradeToLambdaTargetWith400() throws Exception {
        String functionArn = createFunction("alb-websocket-fn");
        String lambdaTargetGroupArn = null;
        try {
            lambdaTargetGroupArn = createLambdaTargetGroup(functionArn);
            forwardDefaultActionTo(listenerArn, lambdaTargetGroupArn);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> connect("/socket.io/?transport=websocket"));

            UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
            assertEquals(400, rejected.getStatus());
        } finally {
            deleteListener(listenerArn);
            listenerArn = null;
            deleteTargetGroup(lambdaTargetGroupArn);
            given().when().delete("/2015-03-31/functions/alb-websocket-fn").then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static String createFunction(String name) {
        return given()
                .contentType("application/json")
                .body("""
                        {
                            "FunctionName": "%s",
                            "Runtime": "nodejs20.x",
                            "Role": "arn:aws:iam::000000000000:role/lambda-role",
                            "Handler": "index.handler"
                        }
                        """.formatted(name))
            .when()
                .post("/2015-03-31/functions")
            .then()
                .statusCode(201)
                .extract()
                .path("FunctionArn");
    }

    private static String createLambdaTargetGroup(String functionArn) {
        String targetGroupArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "websocket-lambda-tg")
                .formParam("TargetType", "lambda")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", functionArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        return targetGroupArn;
    }

    private static void forwardDefaultActionTo(String listenerArn, String targetGroupArn) {
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

    private WebSocket connect(String uri) throws Exception {
        return client.webSocket(new WebSocketConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(LISTENER_PORT)
                        .setURI(uri))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    private static String exchange(WebSocket socket, String text) throws Exception {
        CompletableFuture<String> reply = new CompletableFuture<>();
        socket.textMessageHandler(reply::complete);
        socket.writeTextMessage(text);
        return reply.get(5, TimeUnit.SECONDS);
    }

    private static String createLoadBalancer() {
        return given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "websocket-lb")
                .formParam("Type", "application")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    private static String createTargetGroup(String name, String targetType, int backendPort) {
        return given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", name)
                .formParam("Protocol", "HTTP")
                .formParam("Port", backendPort)
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

    private static void registerTarget(String targetGroupArn, String targetId, int backendPort) {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", targetId)
                .formParam("Targets.member.1.Port", backendPort)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static String createListener(String loadBalancerArn, String targetGroupArn) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", LISTENER_PORT)
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

    private static void setIdleTimeout(String loadBalancerArn, int seconds) {
        given()
                .formParam("Action", "ModifyLoadBalancerAttributes")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Attributes.member.1.Key", "idle_timeout.timeout_seconds")
                .formParam("Attributes.member.1.Value", String.valueOf(seconds))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static void deleteListener(String listenerArn) {
        if (listenerArn != null) {
            given()
                    .formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", listenerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteTargetGroup(String targetGroupArn) {
        if (targetGroupArn != null) {
            given()
                    .formParam("Action", "DeleteTargetGroup")
                    .formParam("TargetGroupArn", targetGroupArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteLoadBalancer(String loadBalancerArn) {
        if (loadBalancerArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", loadBalancerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
}
