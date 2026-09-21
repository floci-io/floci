package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The structured {@code CreateNodegroup} inputs must reach the wire, not just the Java model.
 * The drift OpenTofu sees comes from the serialized {@code DescribeNodegroup} body, so these
 * assertions parse the raw JSON rather than reading getters.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksNodegroupStructuredInputsIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "it-ng-inputs-cluster";
    private static final String NODE_ROLE = "arn:aws:iam::000000000000:role/eks-node-role";
    private static final String NODEGROUP = "ng-structured";
    private static final String BARE_NODEGROUP = "ng-bare";

    private static final String STRUCTURED_INPUTS = """
            "launchTemplate":{"id":"lt-0a1b2c3d4e5f60718","name":"node-lt","version":"3"},\
            "remoteAccess":{"ec2SshKey":"my-keypair","sourceSecurityGroups":["sg-0123456789abcdef0"]},\
            "taints":[{"key":"dedicated","value":"gpu","effect":"NO_SCHEDULE"},\
            {"key":"spot","value":"true","effect":"PREFER_NO_SCHEDULE"}],\
            "nodeRepairConfig":{"enabled":true,"maxUnhealthyNodeThresholdPercentage":20},\
            "warmPoolConfig":{"enabled":true,"minSize":2,"poolState":"Stopped","reuseOnScaleIn":false}\
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Order(1)
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER));
    }

    @Test
    @Order(2)
    void createNodeGroupEchoesStructuredInputsOnTheWire() {
        given().contentType(JSON)
                .body(structuredNodeGroupBody(NODEGROUP))
                .when().post("/clusters/" + CLUSTER + "/node-groups")
                .then().statusCode(200)
                .body("nodegroup.nodegroupName", equalTo(NODEGROUP))
                .body("nodegroup.launchTemplate.id", equalTo("lt-0a1b2c3d4e5f60718"))
                .body("nodegroup.launchTemplate.name", equalTo("node-lt"))
                .body("nodegroup.launchTemplate.version", equalTo("3"))
                .body("nodegroup.remoteAccess.ec2SshKey", equalTo("my-keypair"))
                .body("nodegroup.remoteAccess.sourceSecurityGroups", hasSize(1))
                .body("nodegroup.taints", hasSize(2))
                .body("nodegroup.taints[0].effect", equalTo("NO_SCHEDULE"))
                .body("nodegroup.nodeRepairConfig.enabled", equalTo(true))
                .body("nodegroup.warmPoolConfig.poolState", equalTo("Stopped"));
    }

    @Test
    @Order(3)
    void describeNodeGroupReturnsStructuredInputsByteIdenticalToWhatWasSupplied() throws Exception {
        String rawBody = given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER + "/node-groups/" + NODEGROUP)
                .then().statusCode(200)
                .extract().asString();

        JsonNode described = MAPPER.readTree(rawBody).path("nodegroup");
        JsonNode supplied = MAPPER.readTree("{" + STRUCTURED_INPUTS + "}");

        assertEquals(supplied.get("launchTemplate"), described.get("launchTemplate"));
        assertEquals(supplied.get("remoteAccess"), described.get("remoteAccess"));
        assertEquals(supplied.get("taints"), described.get("taints"));
        assertEquals(supplied.get("nodeRepairConfig"), described.get("nodeRepairConfig"));
        assertEquals(supplied.get("warmPoolConfig"), described.get("warmPoolConfig"));
    }

    @Test
    @Order(4)
    void createAndDescribeWithoutStructuredInputsOmitTheKeysRatherThanEmittingNulls() throws Exception {
        String createBody = given().contentType(JSON)
                .body("{\"nodegroupName\":\"" + BARE_NODEGROUP + "\",\"subnets\":[\"subnet-abc\"],"
                        + "\"nodeRole\":\"" + NODE_ROLE + "\"}")
                .when().post("/clusters/" + CLUSTER + "/node-groups")
                .then().statusCode(200)
                .extract().asString();
        assertNoStructuredInputKeys(createBody);

        String describeBody = given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER + "/node-groups/" + BARE_NODEGROUP)
                .then().statusCode(200)
                .extract().asString();
        assertNoStructuredInputKeys(describeBody);
    }

    @Test
    @Order(5)
    void deleteNodeGroups() {
        given().contentType(JSON)
                .when().delete("/clusters/" + CLUSTER + "/node-groups/" + NODEGROUP)
                .then().statusCode(200);
        given().contentType(JSON)
                .when().delete("/clusters/" + CLUSTER + "/node-groups/" + BARE_NODEGROUP)
                .then().statusCode(200);
        given().contentType(JSON)
                .when().delete("/clusters/" + CLUSTER)
                .then().statusCode(200);
    }

    private String structuredNodeGroupBody(String nodegroupName) {
        return "{\"nodegroupName\":\"" + nodegroupName + "\",\"subnets\":[\"subnet-abc\"],"
                + "\"nodeRole\":\"" + NODE_ROLE + "\"," + STRUCTURED_INPUTS + "}";
    }

    private void assertNoStructuredInputKeys(String rawBody) throws Exception {
        JsonNode nodegroup = MAPPER.readTree(rawBody).path("nodegroup");
        for (String member : new String[] {
                "launchTemplate", "remoteAccess", "taints", "nodeRepairConfig", "warmPoolConfig" }) {
            assertFalse(nodegroup.has(member),
                    "DescribeNodegroup must omit " + member + " rather than serialize it as null");
        }
    }
}
