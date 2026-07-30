package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end check that CloudFormation provisions ECS capacity providers and
 * cluster associations (issue #1998) into EcsService, and unwinds both on stack
 * delete. Control-plane only.
 */
@QuarkusTest
class CloudFormationEcsCapacityIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113.";
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

        createStack(stackName, template(clusterName, providerName));

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
            .header("X-Amz-Target", ECS_TARGET + "DescribeClusters")
            .contentType(ECS_CT)
            .body("{\"clusters\":[\"" + clusterName + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(providerName));

        // Tags declared on the capacity provider reach ECS instead of being replaced by an empty map.
        String providers = describeCapacityProviders(providerName);
        assertThat(providers, containsString(providerName));
        assertThat(providers, containsString("owner"));
        assertThat(providers, containsString("platform"));
    }

    @Test
    void stackDeleteRemovesProviderAndAssociations() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ecscap-del-" + suffix;
        String clusterName = "cap-cluster-del-" + suffix;
        String providerName = "cap-provider-del-" + suffix;

        String stackArn = createStack(stackName, template(clusterName, providerName));
        assertThat(describeCapacityProviders(providerName), containsString(providerName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String settled = awaitStackSettled(stackArn);
        assertThat(settled, containsString("<StackStatus>DELETE_COMPLETE</StackStatus>"));

        // The capacity provider must be deregistered, not left behind by an unsupported delete.
        assertThat(describeCapacityProviders(providerName), not(containsString(providerName)));
    }

    private static String template(String clusterName, String providerName) {
        return """
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
                        "AutoScalingGroupProvider": {
                          "AutoScalingGroupArn": "arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:x:autoScalingGroupName/asg-x",
                          "ManagedTerminationProtection": "ENABLED",
                          "ManagedDraining": "ENABLED",
                          "ManagedScaling": {"Status": "ENABLED", "TargetCapacity": 80}
                        },
                        "Tags": [{"Key": "owner", "Value": "platform"}]
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
    }

    private String createStack(String stackName, String template) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"))
            .extract().asString();
        return xml.substring(xml.indexOf("<StackId>") + "<StackId>".length(), xml.indexOf("</StackId>"));
    }

    private String describeCapacityProviders(String providerName) {
        return given()
            .header("X-Amz-Target", ECS_TARGET + "DescribeCapacityProviders")
            .contentType(ECS_CT)
            .body("{\"capacityProviders\":[\"" + providerName + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private String awaitStackSettled(String stackArn) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String xml = describeStacks(stackArn);
            if (xml.contains("<StackStatus>DELETE_COMPLETE</StackStatus>")
                    || xml.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                return xml;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Stack did not settle into DELETE_COMPLETE/DELETE_FAILED: "
                + describeStacks(stackArn));
    }

    private String describeStacks(String stackArn) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }
}
