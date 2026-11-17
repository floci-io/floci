package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import static io.floci.aws.services.cloudformation.CfnStackTestSupport.createUpdateChangeSet;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.executeAndAwaitUpdate;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.physicalId;
import static io.floci.aws.services.cloudformation.CfnStackTestSupport.subjectStack;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * End-to-end check that CloudFormation provisions EKS resources for real (into EksService) rather
 * than stubbing them. EKS runs in mock mode under test (floci.services.eks.mock=true), so no k3s
 * container starts — the test stays Docker-free.
 */
@QuarkusTest
class CloudFormationEksIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Test
    void createStackProvisionsEksClusterAndNodegroup() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterName = "cfn-eks-" + suffix;
        String nodegroupName = "ng-" + suffix;
        String stackName = "cfn-eks-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Cluster": {
                      "Type": "AWS::EKS::Cluster",
                      "Properties": {
                        "Name": "%s",
                        "Version": "1.29",
                        "RoleArn": "arn:aws:iam::000000000000:role/eks-cluster-role"
                      }
                    },
                    "Nodes": {
                      "Type": "AWS::EKS::Nodegroup",
                      "Properties": {
                        "ClusterName": {"Ref": "Cluster"},
                        "NodegroupName": "%s",
                        "NodeRole": "arn:aws:iam::000000000000:role/eks-node-role",
                        "Subnets": ["subnet-aaaa1111", "subnet-bbbb2222"]
                      }
                    }
                  },
                  "Outputs": {
                    "ClusterRef": {"Value": {"Ref": "Cluster"}},
                    "ClusterArn": {"Value": {"Fn::GetAtt": ["Cluster", "Arn"]}}
                  }
                }
                """.formatted(clusterName, nodegroupName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString(clusterName))
            // Fn::GetAtt Cluster.Arn resolves to a real EKS arn, not the literal "Cluster.Arn".
            .body(containsString("arn:aws:eks:"));

        // The cluster really exists in EKS (provisioned, not stubbed).
        given()
        .when()
            .get("/clusters/" + clusterName)
        .then()
            .statusCode(200)
            .body(containsString(clusterName));

        // The nodegroup was created under it (ClusterName resolved from Ref(Cluster)).
        given()
        .when()
            .get("/clusters/" + clusterName + "/node-groups/" + nodegroupName)
        .then()
            .statusCode(200)
            .body(containsString(nodegroupName));
    }

    @Test
    void anUnnamedClusterIsUpdatedInPlaceAndReplacedOnlyWhenItsRoleChanges() {
        String stack = "cfn-eks-upd-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(client, stack, cluster("role-a", "one"), "CREATE_COMPLETE");
            String first = physicalId(client, stack, "Subject");
            int count = given().get("/clusters").jsonPath().getList("clusters").size();

            createUpdateChangeSet(client, stack, "tags", cluster("role-a", "two"));
            executeAndAwaitUpdate(client, stack, "tags");
            assertEquals(first, physicalId(client, stack, "Subject"));
            assertEquals(count, given().get("/clusters").jsonPath().getList("clusters").size());
            assertEquals("two", given().get("/clusters/" + first).jsonPath().getString("cluster.tags.team"));

            createUpdateChangeSet(client, stack, "role", cluster("role-b", "two"));
            executeAndAwaitUpdate(client, stack, "role");
            assertNotEquals(first, physicalId(client, stack, "Subject"));
            given().get("/clusters/" + first).then().statusCode(404);
            assertEquals(count, given().get("/clusters").jsonPath().getList("clusters").size());
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String cluster(String role, String team) {
        return subjectStack("", "", "AWS::EKS::Cluster", "\"RoleArn\": \"arn:aws:iam::000000000000:role/" + role
                + "\", \"Tags\": [{\"Key\": \"team\", \"Value\": \"" + team + "\"}]");
    }
}
