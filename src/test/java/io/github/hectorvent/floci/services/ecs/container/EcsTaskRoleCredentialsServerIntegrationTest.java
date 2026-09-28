package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the real wire contract end to end: a role minted through the running app's IAM API,
 * a real Vert.x server bound to a free port, and a plain {@link HttpClient} reading the response
 * the way an AWS SDK's {@code ContainerMetadataFetcher} does. Modeled on
 * {@code Ec2InstanceCredentialsIntegrationTest}.
 */
@QuarkusTest
class EcsTaskRoleCredentialsServerIntegrationTest {

    @Inject
    IamService iam;

    @Test
    void credentialsEndpointServesAnIssuedSessionAndStopsAfterRevoke() throws Exception {
        String account = "356813579012";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String role = "ecs-task-role-" + suffix;
        String taskArn = "arn:aws:ecs:us-east-1:" + account + ":task/cluster/" + suffix;
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260916/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":"
                        + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"},"
                        + "\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200);
        String roleArn = "arn:aws:iam::" + account + ":role/" + role;

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.services().ecs().taskRoleCredentials().port()).thenReturn(port);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(21600L);

        Vertx vertx = Vertx.vertx();
        EcsTaskRoleCredentials credentials = new EcsTaskRoleCredentials(iam, config);
        EcsTaskRoleCredentialsServer server = new EcsTaskRoleCredentialsServer(vertx, config, credentials);
        String endpoint = "http://127.0.0.1:" + port;
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start().get(10, TimeUnit.SECONDS);
            String path = credentials.issue(taskArn, roleArn, account, Instant.now()).orElseThrow();
            assertTrue(path.matches("^/v2/credentials/[0-9a-f-]{36}$"), path);

            assertEquals(404, get(client, endpoint + "/v2/credentials/" + UUID.randomUUID()).statusCode());

            HttpResponse<String> response = get(client, endpoint + path);
            assertEquals(200, response.statusCode());
            JsonObject body = new JsonObject(response.body());
            assertEquals(roleArn, body.getString("RoleArn"));
            assertTrue(Instant.parse(body.getString("Expiration")).isAfter(Instant.now()));
            String key = body.getString("AccessKeyId");
            String token = body.getString("Token");
            assertTrue(key.startsWith("ASIA"));
            assertEquals(body.getString("SecretAccessKey"), iam.findSecretKey(key, token).orElseThrow());
            assertEquals(account, iam.resolveAccountId(key).orElseThrow());

            credentials.revoke(taskArn);
            assertNotEquals(200, get(client, endpoint + path).statusCode());
            assertTrue(iam.findSecretKey(key, token).isEmpty());
        } finally {
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteRole").formParam("RoleName", role)
                    .post("/").then().statusCode(200);
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
