package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Resources created by a request signed for a non-default region carry that region in their ARN,
 * not the emulator's default region.
 */
@QuarkusTest
class RequestRegionArnIntegrationTest {

    private static final String REGION = "eu-west-1";

    private static final String OTHER_REGION = "us-west-2";

    private static String auth(String service) {
        return auth(REGION, service);
    }

    private static String auth(String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    @Test
    void amazonMqBrokerArnUsesTheRequestRegion() {
        String brokerId = given()
                .header("Authorization", auth("mq"))
                .contentType("application/json")
                .body("""
                    {"brokerName": "region-arn-broker", "engineType": "RABBITMQ",
                     "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                     "publiclyAccessible": false,
                     "users": [{"username": "admin", "password": "AdminPass123", "consoleAccess": true}]}
                    """)
            .when()
                .post("/v1/brokers")
            .then()
                .statusCode(200)
                .body("brokerArn", startsWith("arn:aws:mq:" + REGION + ":"))
                .extract().path("brokerId");

        given().header("Authorization", auth("mq")).delete("/v1/brokers/{id}", brokerId)
                .then().statusCode(200);
    }

    @Test
    void mskConfigurationArnUsesTheRequestRegion() {
        String properties = Base64.getEncoder()
                .encodeToString("auto.create.topics.enable=true".getBytes(StandardCharsets.UTF_8));
        String arn = given()
                .header("Authorization", auth("kafka"))
                .contentType("application/json")
                .body("""
                    {"name": "region-arn-config", "kafkaVersions": ["3.6.0"], "serverProperties": "%s"}
                    """.formatted(properties))
            .when()
                .post("/v1/configurations")
            .then()
                .statusCode(200)
                .body("arn", startsWith("arn:aws:kafka:" + REGION + ":"))
                .extract().path("arn");

        given().header("Authorization", auth("kafka")).delete("/v1/configurations/{arn}", arn)
                .then().statusCode(200);
    }

    @Test
    void eksFargateProfileArnUsesTheClusterRegion() {
        String cluster = "region-arn-cluster";
        given()
                .header("Authorization", auth("eks"))
                .contentType("application/json")
                .body("{\"name\":\"" + cluster + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
            .when()
                .post("/clusters")
            .then()
                .statusCode(200)
                .body("cluster.arn", startsWith("arn:aws:eks:" + REGION + ":"));

        given()
                .header("Authorization", auth("eks"))
                .contentType("application/json")
                .body("{\"fargateProfileName\":\"fp\",\"podExecutionRoleArn\":\"arn:aws:iam::000000000000:role/fp\"}")
            .when()
                .post("/clusters/{name}/fargate-profiles", cluster)
            .then()
                .statusCode(200)
                .body("fargateProfile.fargateProfileArn", startsWith("arn:aws:eks:" + REGION + ":"));

        given().header("Authorization", auth("eks"))
                .delete("/clusters/{name}/fargate-profiles/{fp}", cluster, "fp").then().statusCode(200);
        given().header("Authorization", auth("eks"))
                .delete("/clusters/{name}", cluster).then().statusCode(200);
    }

    @Test
    void appSyncDomainNameUsesTheRequestRegionAndChannelNamespaceItsApisRegion() {
        String domain = "region-arn-" + System.nanoTime() + ".example.com";
        given()
                .header("Authorization", auth("appsync"))
                .contentType("application/json")
                .body("""
                    {"domainName": "%s", "certificateArn": "arn:aws:acm:%s:000000000000:certificate/1"}
                    """.formatted(domain, REGION))
            .when()
                .post("/v1/domainnames")
            .then()
                .statusCode(200)
                .body("domainNameConfig.domainNameArn", startsWith("arn:aws:appsync:" + REGION + ":"))
                .body("domainNameConfig.appsyncDomainName", containsString(REGION));
        given().header("Authorization", auth("appsync")).delete("/v1/domainnames/" + domain)
                .then().statusCode(204);

        String apiId = given()
                .header("Authorization", auth("appsync"))
                .contentType("application/json")
                .body("{\"name\": \"region-arn-api\", \"authenticationType\": \"API_KEY\"}")
            .when()
                .post("/v1/apis")
            .then()
                .statusCode(200)
                .extract().path("graphqlApi.apiId");
        // A namespace lives in its API's region, even when the request is signed for another one.
        given()
                .header("Authorization", auth(OTHER_REGION, "appsync"))
                .contentType("application/json")
                .body("{\"name\": \"region-arn-ns\"}")
            .when()
                .post("/v2/apis/" + apiId + "/channelNamespaces")
            .then()
                .statusCode(200)
                .body("channelNamespace.channelNamespaceArn", startsWith("arn:aws:appsync:" + REGION + ":"));
        given().header("Authorization", auth("appsync"))
                .delete("/v2/apis/" + apiId + "/channelNamespaces/region-arn-ns").then().statusCode(204);
        given().header("Authorization", auth("appsync")).delete("/v1/apis/" + apiId).then().statusCode(204);
    }

    @Test
    void elastiCacheUserArnIsMintedOnceInTheCreateRegion() {
        given()
                .header("Authorization", auth("elasticache"))
                .formParam("Action", "CreateUser")
                .formParam("UserId", "region-arn-user")
                .formParam("UserName", "region-arn-user")
                .formParam("Engine", "valkey")
                .formParam("AuthenticationMode.Type", "no-password-required")
                .formParam("AccessString", "on ~* +@all")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateUserResponse.CreateUserResult.ARN", startsWith("arn:aws:elasticache:" + REGION + ":"));

        // Users are not stored per region, so another region can read this one; its ARN must not change.
        given()
                .header("Authorization", auth(OTHER_REGION, "elasticache"))
                .formParam("Action", "DescribeUsers")
                .formParam("UserId", "region-arn-user")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeUsersResponse.DescribeUsersResult.Users.member.ARN",
                        startsWith("arn:aws:elasticache:" + REGION + ":"));

        given()
                .header("Authorization", auth("elasticache"))
                .formParam("Action", "DeleteUser")
                .formParam("UserId", "region-arn-user")
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }
}
