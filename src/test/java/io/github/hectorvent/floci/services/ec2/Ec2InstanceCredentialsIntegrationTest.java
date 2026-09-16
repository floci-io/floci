package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class Ec2InstanceCredentialsIntegrationTest {
    @Inject
    IamService iam;

    @Test
    void imdsCredentialsResolveToTheAttachedRoleAndAreRevokedOnTermination() throws Exception {
        String account = "246813579012";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String role = "worker-" + suffix;
        String profile = "profile-" + suffix;
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260916/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200);
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", profile)
                .post("/").then().statusCode(200);
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AddRoleToInstanceProfile").formParam("InstanceProfileName", profile)
                .formParam("RoleName", role).post("/").then().statusCode(200);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(config.services().ec2().imdsPort()).thenReturn(port);
        Vertx vertx = Vertx.vertx();
        Ec2MetadataServer server = new Ec2MetadataServer(vertx, config, iam);
        Instance instance = new Instance();
        instance.setInstanceId("i-credentials");
        instance.setIamInstanceProfileArn("arn:aws:iam::" + account + ":instance-profile/" + profile);
        String endpoint = "http://127.0.0.1:" + port;
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start().get(10, TimeUnit.SECONDS);
            server.registerContainer("127.0.0.1", instance.getInstanceId(), instance);
            HttpResponse<String> token = client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                    .timeout(Duration.ofSeconds(5)).header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, token.statusCode());
            String path = "/latest/meta-data/iam/security-credentials/";
            assertEquals(role, get(client, endpoint + path, token.body()).body());
            assertEquals(404, get(client, endpoint + path + "wrong-role", token.body()).statusCode());
            HttpResponse<String> response = get(client, endpoint + path + role, token.body());
            assertEquals(200, response.statusCode());
            JsonObject credential = new JsonObject(response.body());
            assertEquals("Success", credential.getString("Code"));
            assertEquals("AWS-HMAC", credential.getString("Type"));
            assertTrue(Instant.parse(credential.getString("Expiration")).isAfter(Instant.now()));
            String key = credential.getString("AccessKeyId");
            String sessionToken = credential.getString("Token");
            assertEquals(credential.getString("SecretAccessKey"), iam.findSecretKey(key, sessionToken).orElseThrow());
            assertEquals(account, iam.resolveAccountId(key).orElseThrow());
            assertEquals("arn:aws:sts::" + account + ":assumed-role/" + role + "/i-credentials",
                    iam.resolveCallerArn(key).orElseThrow());
            assertEquals(response.body(), get(client, endpoint + path + role, token.body()).body());
            server.unregisterInstance(instance);
            assertTrue(iam.findSecretKey(key, sessionToken).isEmpty());
            assertNotEquals(200, get(client, endpoint + path + role, token.body()).statusCode());
        } finally {
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", profile)
                    .formParam("RoleName", role).post("/").then().statusCode(200);
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteInstanceProfile").formParam("InstanceProfileName", profile)
                    .post("/").then().statusCode(200);
            given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteRole").formParam("RoleName", role)
                    .post("/").then().statusCode(200);
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("X-aws-ec2-metadata-token", token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
