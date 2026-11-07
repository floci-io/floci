package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class Ec2InstanceCredentialsIntegrationTest {
    @Inject
    IamService iam;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void imdsCredentialsResolveToTheAttachedRoleAndAreRevokedOnCleanup(boolean stopServer) throws Exception {
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
        Instance instance = new Instance();
        instance.setInstanceId("i-credentials");
        instance.setIamInstanceProfileArn("arn:aws:iam::" + account + ":instance-profile/" + profile);
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start(iam, config, instance);
            Ec2MetadataServer server = fixture.server();
            HttpResponse<String> token = fixture.putToken("60");
            assertEquals(200, token.statusCode());
            String path = "/latest/meta-data/iam/security-credentials/";
            assertEquals(role, fixture.get(path, token.body()).body());
            assertEquals(404, fixture.get(path + "wrong-role", token.body()).statusCode());
            HttpResponse<String> response = fixture.get(path + role, token.body());
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
            assertEquals(response.body(), fixture.get(path + role, token.body()).body());
            if (stopServer) {
                server.stop();
            } else {
                server.unregisterInstance(instance);
                assertNotEquals(200, fixture.get(path + role, token.body()).statusCode());
            }
            assertTrue(iam.findSecretKey(key, sessionToken).isEmpty());
            assertTrue(server.registeredContainer("127.0.0.1").isEmpty());
        } finally {
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

    @Test
    void identityCredentialsIdentifyTheLaunchingAccountWithoutAnInstanceProfile() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("123456789012");
        Instance instance = new Instance();
        instance.setInstanceId("i-identity");
        instance.setRegion("us-east-1");
        instance.setOwnerId("246813579012");
        String base = "/latest/meta-data/identity-credentials";
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start(iam, config, instance);
            Ec2MetadataServer server = fixture.server();
            HttpResponse<String> token = fixture.putToken("60");
            assertEquals("ec2/", fixture.get(base, token.body()).body());
            assertEquals("ec2/", fixture.get(base + "/", token.body()).body());
            assertEquals("info\nsecurity-credentials/", fixture.get(base + "/ec2/", token.body()).body());
            assertEquals("ec2-instance", fixture.get(base + "/ec2/security-credentials/", token.body()).body());

            JsonObject info = new JsonObject(fixture.get(base + "/ec2/info", token.body()).body());
            assertEquals("Success", info.getString("Code"));
            assertEquals("246813579012", info.getString("AccountId"));
            assertNotNull(Instant.parse(info.getString("LastUpdated")));

            String path = base + "/ec2/security-credentials/ec2-instance";
            HttpResponse<String> response = fixture.get(path, token.body());
            assertEquals(200, response.statusCode());
            JsonObject credential = new JsonObject(response.body());
            assertEquals("Success", credential.getString("Code"));
            assertEquals("AWS-HMAC", credential.getString("Type"));
            assertTrue(Instant.parse(credential.getString("Expiration")).isAfter(Instant.now()));
            assertNotNull(Instant.parse(credential.getString("LastUpdated")));
            String key = credential.getString("AccessKeyId");
            String sessionToken = credential.getString("Token");
            assertEquals(credential.getString("SecretAccessKey"), iam.findSecretKey(key, sessionToken).orElseThrow());
            assertEquals("246813579012", iam.resolveAccountId(key).orElseThrow());
            assertEquals("arn:aws:sts::246813579012:assumed-role/aws:ec2-instance/i-identity",
                    iam.resolveCallerArn(key).orElseThrow());
            assertEquals("246813579012:aws:ec2-instance:i-identity", iam.resolveCallerUserId(key, sessionToken).orElseThrow());
            List<String> grants = iam.resolveCallerContext(key).identityPolicies();
            assertTrue(grants == null || grants.isEmpty());
            assertEquals(response.body(), fixture.get(path, token.body()).body());

            assertEquals(401, fixture.get(path, "not-a-token").statusCode());

            server.unregisterInstance(instance);
            assertTrue(iam.findSecretKey(key, sessionToken).isEmpty());
        }
    }

}
