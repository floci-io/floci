package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
                    "ClusterRef": {"Value": {"Ref": "Cluster"}}
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
            .body(containsString(clusterName));

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
}
