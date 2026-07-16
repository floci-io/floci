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
}
