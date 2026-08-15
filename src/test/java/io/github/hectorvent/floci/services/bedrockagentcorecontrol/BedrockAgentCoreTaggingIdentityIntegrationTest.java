package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreTaggingIdentityIntegrationTest {

    private static final String CREATE_RUNTIME = """
            {
              "agentRuntimeName": "tagAgent",
              "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "x:latest"}},
              "networkConfiguration": {"networkMode": "PUBLIC"},
              "roleArn": "arn:aws:iam::000000000000:role/agent"
            }""";

    private static String runtimeArn;
    private static String workloadIdentityArn;

    @Test
    @Order(1)
    void createRuntimeCapturesArns() {
        var resp = given().contentType("application/json").body(CREATE_RUNTIME)
                .when().put("/runtimes/")
                .then().statusCode(202)
                .body("workloadIdentityDetails.workloadIdentityArn", notNullValue())
                .extract();
        runtimeArn = resp.path("agentRuntimeArn");
        workloadIdentityArn = resp.path("workloadIdentityDetails.workloadIdentityArn");
    }

    @Test
    @Order(2)
    void tagUntagRoundTrip() {
        given().contentType("application/json").body("{\"tags\":{\"env\":\"prod\"}}")
                .when().post("/tags/" + runtimeArn)
                .then().statusCode(204);

        given().when().get("/tags/" + runtimeArn)
                .then().statusCode(200)
                .body("tags.env", equalTo("prod"));

        given().when().delete("/tags/" + runtimeArn + "?tagKeys=env")
                .then().statusCode(204);

        given().when().get("/tags/" + runtimeArn)
                .then().statusCode(200)
                .body("tags.env", nullValue());
    }

    @Test
    @Order(2)
    void aForeignAccountOrRegionInTheArnDoesNotReachTheLocalResource() {
        // Both services resolve from the id suffix alone, so without an identity check these
        // reach the same local runtime and expose its tags through someone else's ARN.
        String foreignAccount = runtimeArn.replace(":000000000000:", ":111111111111:");
        String foreignRegion = runtimeArn.replace(":us-east-1:", ":eu-west-1:");

        given().when().get("/tags/" + foreignAccount).then().statusCode(404);
        given().when().get("/tags/" + foreignRegion).then().statusCode(404);

        given().contentType("application/json").body("{\"tags\":{\"env\":\"stolen\"}}")
                .when().post("/tags/" + foreignAccount)
                .then().statusCode(404);
    }

    @Test
    @Order(3)
    void workloadIdentityCrud() {
        given().contentType("application/json").body("{\"name\":\"wid_test_1\"}")
                .when().post("/identities/CreateWorkloadIdentity")
                .then().statusCode(201)
                .body("name", equalTo("wid_test_1"))
                .body("workloadIdentityArn", notNullValue());

        given().contentType("application/json").body("{\"name\":\"wid_test_1\"}")
                .when().post("/identities/GetWorkloadIdentity")
                .then().statusCode(200)
                .body("name", equalTo("wid_test_1"));

        given().contentType("application/json").body("{}")
                .when().post("/identities/ListWorkloadIdentities")
                .then().statusCode(200)
                .body("workloadIdentities.name", hasItem("wid_test_1"));

        given().contentType("application/json").body("{\"name\":\"wid_test_1\"}")
                .when().post("/identities/DeleteWorkloadIdentity")
                .then().statusCode(204);
    }

    @Test
    @Order(4)
    void runtimeWorkloadIdentityResolves() {
        String name = workloadIdentityArn.substring(workloadIdentityArn.lastIndexOf('/') + 1);
        given().contentType("application/json").body("{\"name\":\"" + name + "\"}")
                .when().post("/identities/GetWorkloadIdentity")
                .then().statusCode(200)
                .body("name", equalTo(name));
    }

    @Test
    @Order(5)
    void gatewayTagRoundTrip() {
        // Regression for issue #9: gateways are AWS-taggable.
        String gwArn = given().contentType("application/json")
                .body("{\"name\":\"tagGw\",\"authorizerType\":\"AWS_IAM\","
                        + "\"roleArn\":\"arn:aws:iam::000000000000:role/gw\",\"tags\":{\"team\":\"core\"}}")
                .when().post("/gateways/")
                .then().statusCode(202)
                .extract().path("gatewayArn");

        // tags provided at create time are retained
        given().when().get("/tags/" + gwArn)
                .then().statusCode(200).body("tags.team", equalTo("core"));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"prod\"}}")
                .when().post("/tags/" + gwArn).then().statusCode(204);
        given().when().get("/tags/" + gwArn)
                .then().statusCode(200)
                .body("tags.env", equalTo("prod"))
                .body("tags.team", equalTo("core"));

        given().when().delete("/tags/" + gwArn + "?tagKeys=env").then().statusCode(204);
        given().when().get("/tags/" + gwArn)
                .then().statusCode(200)
                .body("tags.env", nullValue())
                .body("tags.team", equalTo("core"));
    }

    @Test
    @Order(6)
    void taggingUnsupportedResourceReturns400() {
        // Memory/endpoint ARNs are not taggable (AWS: runtime + gateway only).
        given().contentType("application/json").body("{\"tags\":{\"x\":\"y\"}}")
                .when().post("/tags/arn:aws:bedrock-agentcore:us-east-1:000000000000:memory/mymem-abc1234567")
                .then().statusCode(400);
    }
}
