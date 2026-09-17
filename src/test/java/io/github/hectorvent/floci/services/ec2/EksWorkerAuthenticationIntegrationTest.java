package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksWorkerAuthenticationIntegrationTest {
    @Inject IamService iam;
    @Inject Ec2Service ec2;
    @Inject EksService eks;

    @Test
    void instanceCredentialsAuthenticateOnlyWithMatchingLiveAccessEntry() throws Exception {
        String account = "246813579012";
        String name = "worker-auth-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::" + account + ":role/path/" + name;
        String profileArn = "arn:aws:iam::" + account + ":instance-profile/" + name;
        given().header("Authorization", auth(account, "iam"))
                .formParam("Action", "CreateRole").formParam("RoleName", name).formParam("Path", "/path/")
                .formParam("AssumeRolePolicyDocument", "{}").post("/").then().statusCode(200);
        given().header("Authorization", auth(account, "iam"))
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200);
        given().header("Authorization", auth(account, "iam"))
                .formParam("Action", "AddRoleToInstanceProfile").formParam("InstanceProfileName", name)
                .formParam("RoleName", name).post("/").then().statusCode(200);
        String instanceId = given().header("Authorization", auth(account, "ec2"))
                .formParam("Action", "RunInstances").formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Arn", profileArn).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        given().header("Authorization", auth(account, "eks")).contentType("application/json")
                .body(Map.of("name", name, "roleArn", roleArn, "accessConfig",
                        Map.of("authenticationMode", "API", "bootstrapClusterCreatorAdminPermissions", false)))
                .post("/clusters").then().statusCode(200);
        Ec2InstanceCredentials credentials = new Ec2InstanceCredentials(iam);
        try {
            Instance instance = ec2.findInstanceForAccount(account, "us-east-1", instanceId).orElseThrow();
            // Mock EC2 launches remain pending because no container manager runs in this profile.
            instance.setState(InstanceState.running());
            credentials.register(instance);
            SessionCredential session = credentials.get(instance, name, Instant.now()).orElseThrow();
            String token = SigV4TokenTestHelper.createEksToken(name, session.getAccessKeyId(),
                    session.getSecretAccessKey(), Instant.now(), 60, session.getSessionToken());
            String incarnation = eks.findAuthenticationCluster(account, name).orElseThrow().getCreatedAt().toString();
            String path = "/_floci/eks/clusters/" + name + "/token-webhook";
            Map<String, Object> review = Map.of("apiVersion", "authentication.k8s.io/v1", "kind", "TokenReview",
                    "spec", Map.of("token", token));
            given().queryParam("accountId", account).queryParam("region", "us-east-1")
                    .queryParam("createdAt", incarnation).contentType("application/json").body(review)
                    .post(path).then().statusCode(200).body("status.authenticated", equalTo(false));
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .body(Map.of("principalArn", roleArn, "type", "EC2_LINUX"))
                    .post("/clusters/" + name + "/access-entries").then().statusCode(200);
            given().queryParam("accountId", account).queryParam("region", "us-east-1")
                    .queryParam("createdAt", incarnation).contentType("application/json").body(review)
                    .post(path).then().statusCode(200).body("status.authenticated", equalTo(true))
                    .body("status.user.username", equalTo("system:node:" + instance.getPrivateDnsName()))
                    .body("status.user.groups", contains("system:bootstrappers", "system:nodes"));
            given().queryParam("accountId", "999999999999").queryParam("region", "us-east-1")
                    .queryParam("createdAt", incarnation).contentType("application/json").body(review)
                    .post(path).then().statusCode(200).body("status.authenticated", equalTo(false));
            credentials.unregister(instance);
            given().queryParam("accountId", account).queryParam("region", "us-east-1")
                    .queryParam("createdAt", incarnation).contentType("application/json").body(review)
                    .post(path).then().statusCode(200).body("status.authenticated", equalTo(false));
        } finally {
            credentials.clear();
            given().header("Authorization", auth(account, "eks")).delete("/clusters/" + name).then().statusCode(200);
            given().header("Authorization", auth(account, "ec2")).formParam("Action", "TerminateInstances")
                    .formParam("InstanceId.1", instanceId).post("/").then().statusCode(200);
            given().header("Authorization", auth(account, "iam")).formParam("Action", "RemoveRoleFromInstanceProfile")
                    .formParam("InstanceProfileName", name).formParam("RoleName", name).post("/").then().statusCode(200);
            given().header("Authorization", auth(account, "iam")).formParam("Action", "DeleteInstanceProfile")
                    .formParam("InstanceProfileName", name).post("/").then().statusCode(200);
            given().header("Authorization", auth(account, "iam")).formParam("Action", "DeleteRole")
                    .formParam("RoleName", name).post("/").then().statusCode(200);
        }
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260917/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
