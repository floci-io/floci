package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end check that CloudFormation provisions ECS capacity providers and
 * cluster associations (issue #1998) into EcsService. Control-plane only.
 */
@QuarkusTest
class CloudFormationEcsCapacityIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String ECS_CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void capacityProviderAndAssociationsProvision() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ecscap-" + suffix;
        String clusterName = "cap-cluster-" + suffix;
        String providerName = "cap-provider-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Cluster": {
                      "Type": "AWS::ECS::Cluster",
                      "Properties": {"ClusterName": "%s"}
                    },
                    "Provider": {
                      "Type": "AWS::ECS::CapacityProvider",
                      "Properties": {
                        "Name": "%s",
                        "AutoScalingGroupProvider": {"AutoScalingGroupArn": "arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:x:autoScalingGroupName/asg-x"}
                      }
                    },
                    "Assoc": {
                      "Type": "AWS::ECS::ClusterCapacityProviderAssociations",
                      "Properties": {
                        "Cluster": {"Ref": "Cluster"},
                        "CapacityProviders": [{"Ref": "Provider"}, "FARGATE"],
                        "DefaultCapacityProviderStrategy": [
                          {"CapacityProvider": {"Ref": "Provider"}, "Weight": 1}
                        ]
                      }
                    }
                  }
                }
                """.formatted(clusterName, providerName);

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
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.DescribeClusters")
            .contentType(ECS_CT)
            .body("{\"clusters\":[\"" + clusterName + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(providerName));
    }
}
