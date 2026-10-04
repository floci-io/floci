package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void refReturnsTheArnsAndAnUpdateKeepsTheConnector() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-microvm-ref-" + suffix;
        String connectorName = "cfn-microvm-ref-connector-" + suffix;

        cloudFormation(stackName, "CreateStack", refTemplate(connectorName, "subnet-0cfn000000000001"));
        Map<String, String> created = outputs(describeStacks(stackName, "CREATE_COMPLETE"));
        String connectorArn = created.get("ConnectorArn");
        assertTrue(connectorArn.contains(":network-connector:nc-"), connectorArn);
        assertEquals(connectorArn, created.get("ConnectorRef"), "Ref is the connector ARN, its primaryIdentifier");
        assertEquals(created.get("ImageArn"), created.get("ImageRef"), "Ref is the image ARN, its primaryIdentifier");

        cloudFormation(stackName, "UpdateStack", refTemplate(connectorName, "subnet-0cfn000000000002"));
        Map<String, String> updated = outputs(describeStacks(stackName, "UPDATE_COMPLETE"));
        assertEquals(connectorArn, updated.get("ConnectorRef"), "a subnet change updates the connector in place");
        given()
            .when()
            .get("/2026-04-04/network-connectors/" + connectorArn)
            .then()
            .statusCode(200)
            .body("Name", equalTo(connectorName))
            .body("Configuration.VpcEgressConfiguration.SubnetIds[0]", equalTo("subnet-0cfn000000000002"));

        cloudFormation(stackName, "DeleteStack", null);
        await().untilAsserted(() ->
                given()
                        .when()
                        .get("/2026-04-04/network-connectors/" + connectorArn)
                        .then()
                        .statusCode(404));
    }

    private static String refTemplate(String connectorName, String subnetId) {
        return """
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
                            "SubnetIds": ["%s"],
                            "SecurityGroupIds": ["sg-0cfn000000000001"],
                            "NetworkProtocol": "IPv4"
                          }
                        }
                      }
                    },
                    "Image": {
                      "Type": "AWS::Lambda::MicrovmImage",
                      "Properties": {
                        "BaseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                        "BuildRoleArn": "arn:aws:iam::000000000000:role/cfn-microvm-build",
                        "CodeArtifact": { "Uri": "s3://cfn-bucket/context.zip" },
                        "Description": "cfn ref image"
                      }
                    }
                  },
                  "Outputs": {
                    "ImageRef": {"Value": {"Ref": "Image"}},
                    "ImageArn": {"Value": {"Fn::GetAtt": ["Image", "ImageArn"]}},
                    "ConnectorRef": {"Value": {"Ref": "Connector"}},
                    "ConnectorArn": {"Value": {"Fn::GetAtt": ["Connector", "Arn"]}}
                  }
                }
                """.formatted(connectorName, subnetId);
    }

    private static void cloudFormation(String stackName, String action, String templateBody) {
        if (templateBody == null) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName)
            .when().post("/").then().statusCode(200);
            return;
        }
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateBody)
        .when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stackName, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static Map<String, String> outputs(String describeStacksXml) {
        return XmlParser.extractPairs(describeStacksXml, "Outputs", "OutputKey", "OutputValue");
    }
}
