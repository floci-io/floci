package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end check that CloudFormation provisions {@code AWS::Lambda::MicrovmImage} and
 * {@code AWS::Lambda::NetworkConnector} into the real MicroVMs service. The template mirrors
 * the shape chant's MicrovmApp composite emits: the image's EgressNetworkConnectors reference
 * the connector's Arn via Fn::GetAtt, and the connector's configuration nests under
 * Configuration.VpcEgressConfiguration.
 */
@QuarkusTest
class CloudFormationLambdaMicrovmsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Test
    void createStackProvisionsImageAndConnector() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String imageName = "cfn-microvm-image-" + suffix;
        String connectorName = "cfn-microvm-connector-" + suffix;
        String stackName = "cfn-microvm-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Connector": {
                      "Type": "AWS::Lambda::NetworkConnector",
                      "Properties": {
                        "Name": "%s",
                        "OperatorRole": "arn:aws:iam::000000000000:role/cfn-microvm-connector-operator",
                        "Configuration": {
                          "VpcEgressConfiguration": {
                            "AssociatedComputeResourceTypes": ["MicroVm"],
                            "SubnetIds": ["subnet-0cfn000000000001"],
                            "SecurityGroupIds": ["sg-0cfn000000000001"],
                            "NetworkProtocol": "IPv4"
                          }
                        }
                      }
                    },
                    "Image": {
                      "Type": "AWS::Lambda::MicrovmImage",
                      "Properties": {
                        "Name": "%s",
                        "BaseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                        "BuildRoleArn": "arn:aws:iam::000000000000:role/cfn-microvm-build",
                        "CodeArtifact": { "Uri": "s3://cfn-bucket/context.zip" },
                        "Description": "cfn e2e image",
                        "EgressNetworkConnectors": [{"Fn::GetAtt": ["Connector", "Arn"]}],
                        "CpuConfigurations": [{"Architecture": "ARM_64"}],
                        "Resources": [{"MinimumMemoryInMiB": 2048}]
                      }
                    }
                  },
                  "Outputs": {
                    "ImageArn": {"Value": {"Fn::GetAtt": ["Image", "ImageArn"]}},
                    "ConnectorArn": {"Value": {"Fn::GetAtt": ["Connector", "Arn"]}}
                  }
                }
                """.formatted(connectorName, imageName);

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
            .body(containsString(":microvm-image:" + imageName))
            .body(containsString(":network-connector:nc-"));

        // Both resources really exist in the MicroVMs service.
        given()
            .when()
            .get("/2025-09-09/microvm-images/" + imageName)
            .then()
            .statusCode(200)
            .body("state", equalTo("CREATED"))
            .body("latestActiveImageVersion", equalTo("1.0"));

        // Delete the stack and both resources go away.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        await().untilAsserted(() ->
                given()
                        .when()
                        .get("/2025-09-09/microvm-images/" + imageName)
                        .then()
                        .statusCode(404));
    }
}
