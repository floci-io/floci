package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.LaunchConfiguration;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationLaunchConfigurationReplacementSdkIntegrationTest {
    @Test
    void changedConfigurationReplacesAndReattachesGroupWhileUnchangedConfigurationKeepsIdentity() {
        try (Scenario scenario = new Scenario()) {
            scenario.create(template(null, "t3.micro", 1));
            String original = scenario.physical("Launch");
            LaunchConfiguration launch = scenario.launch(original);
            AutoScalingException inUse = assertThrows(AutoScalingException.class, () ->
                    scenario.scaling.deleteLaunchConfiguration(request -> request.launchConfigurationName(original)));
            assertEquals("ResourceInUse", inUse.awsErrorDetails().errorCode());
            assertEquals(original, scenario.launch(original).launchConfigurationName());
            assertEquals("required", launch.metadataOptions().httpTokensAsString());
            assertEquals("/dev/xvda", launch.blockDeviceMappings().getFirst().deviceName());
            assertEquals(150, launch.blockDeviceMappings().getFirst().ebs().volumeSize());
            assertEquals("gp3", launch.blockDeviceMappings().getFirst().ebs().volumeType());
            assertTrue(launch.blockDeviceMappings().getFirst().ebs().encrypted());
            assertTrue(launch.blockDeviceMappings().getFirst().ebs().deleteOnTermination());

            scenario.update(template(null, "t3.micro", 2), "UPDATE_COMPLETE");
            assertEquals(original, scenario.physical("Launch"));
            scenario.update(template(null, "t3.small", 2), "UPDATE_COMPLETE");
            String replacement = scenario.physical("Launch");
            assertNotEquals(original, replacement);
            assertEquals("t3.small", scenario.launch(replacement).instanceType());
            assertFalse(scenario.scaling.describeLaunchConfigurations().launchConfigurations().stream()
                    .anyMatch(item -> original.equals(item.launchConfigurationName())));
            assertEquals(replacement, scenario.scaling.describeAutoScalingGroups(request ->
                    request.autoScalingGroupNames(scenario.physical("Group")))
                    .autoScalingGroups().getFirst().launchConfigurationName());
        }
    }

    @Test
    void explicitNameReplacementRequiresNewNameAndPreservesOriginalOnRefusal() {
        try (Scenario scenario = new Scenario()) {
            String original = scenario.name + "-named";
            scenario.create(template(original, "t3.micro", 1));
            scenario.update(template(original, "t3.small", 1), "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, scenario.physical("Launch"));
            assertEquals("t3.micro", scenario.launch(original).instanceType());
            assertEquals(original, scenario.scaling.describeAutoScalingGroups(request ->
                    request.autoScalingGroupNames(scenario.physical("Group")))
                    .autoScalingGroups().getFirst().launchConfigurationName());
            String replacement = original + "-next";
            scenario.update(template(replacement, "t3.small", 1), "UPDATE_COMPLETE");
            assertEquals(replacement, scenario.physical("Launch"));
            assertEquals("t3.small", scenario.launch(replacement).instanceType());
            assertFalse(scenario.scaling.describeLaunchConfigurations().launchConfigurations().stream()
                    .anyMatch(item -> original.equals(item.launchConfigurationName())));
        }
    }

    @Test
    void parameterChangesReplaceConfigurationsUsingThePreviousStackValues() {
        try (Scenario scenario = new Scenario()) {
            String initial = parameterTemplate(1);
            scenario.create(initial);
            String original = scenario.physical("Launch");
            scenario.update(parameterTemplate(2), "UPDATE_COMPLETE");
            assertEquals(original, scenario.physical("Launch"));

            scenario.cfn.updateStack(request -> request.stackName(scenario.name).templateBody(parameterTemplate(2))
                    .parameters(parameter -> parameter.parameterKey("InstanceType").parameterValue("t3.small")));
            CfnStackWaits.awaitStatus(scenario.cfn, scenario.name, "UPDATE_COMPLETE");
            String replacement = scenario.physical("Launch");
            assertNotEquals(original, replacement);
            assertEquals("t3.small", scenario.launch(replacement).instanceType());
            assertTrue(scenario.scaling.describeLaunchConfigurations(request ->
                    request.launchConfigurationNames(original)).launchConfigurations().isEmpty());
            assertEquals(replacement, scenario.scaling.describeAutoScalingGroups(request ->
                    request.autoScalingGroupNames(scenario.physical("Group")))
                    .autoScalingGroups().getFirst().launchConfigurationName());

            scenario.cfn.updateStack(request -> request.stackName(scenario.name).templateBody(parameterTemplate(3))
                    .parameters(parameter -> parameter.parameterKey("InstanceType").usePreviousValue(true)));
            CfnStackWaits.awaitStatus(scenario.cfn, scenario.name, "UPDATE_COMPLETE");
            assertEquals(replacement, scenario.physical("Launch"));
            assertEquals("t3.small", scenario.launch(replacement).instanceType());
        }
    }

    @Test
    void addingUserDataReplacesAndReattachesTheGroup() {
        try (Scenario scenario = new Scenario()) {
            scenario.create(template(null, "t3.micro", 1));
            String original = scenario.physical("Launch");
            String userData = "IyEvYmluL3NoCmVjaG8gcmVhZHkK";
            String updated = template(null, "t3.micro", 2)
                    .replace("\"ImageId\":", "\"UserData\": \"" + userData + "\",\n        \"ImageId\":");

            scenario.update(updated, "UPDATE_COMPLETE");

            String replacement = scenario.physical("Launch");
            assertNotEquals(original, replacement);
            assertEquals(userData, scenario.launch(replacement).userData());
            assertEquals(replacement, scenario.scaling.describeAutoScalingGroups(request ->
                    request.autoScalingGroupNames(scenario.physical("Group")))
                    .autoScalingGroups().getFirst().launchConfigurationName());
            assertTrue(scenario.scaling.describeLaunchConfigurations(request ->
                    request.launchConfigurationNames(original)).launchConfigurations().isEmpty());

            scenario.update(updated.replace("\"MaxSize\": \"2\"", "\"MaxSize\": \"3\""), "UPDATE_COMPLETE");
            assertEquals(replacement, scenario.physical("Launch"));
            assertEquals(userData, scenario.launch(replacement).userData());
        }
    }

    private static String parameterTemplate(int max) {
        return template(null, "t3.micro", max)
                .replace("\"Resources\": {", """
                        "Parameters": {"InstanceType": {"Type": "String", "Default": "t3.micro"}},
                          "Resources": {""")
                .replace("\"InstanceType\": \"t3.micro\"", "\"InstanceType\": {\"Ref\": \"InstanceType\"}");
    }

    private static String template(String name, String instanceType, int max) {
        return """
                {
                  "Resources": {
                    "Launch": {
                      "Type": "AWS::AutoScaling::LaunchConfiguration",
                      "Properties": {
                        %s
                        "ImageId": "ami-12345678",
                        "InstanceType": "%s",
                        "MetadataOptions": {
                          "HttpTokens": "required",
                          "HttpEndpoint": "enabled"
                        },
                        "BlockDeviceMappings": [
                          {
                            "DeviceName": "/dev/xvda",
                            "Ebs": {
                              "VolumeSize": 150,
                              "VolumeType": "gp3",
                              "Encrypted": true,
                              "DeleteOnTermination": true
                            }
                          }
                        ]
                      }
                    },
                    "Group": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "LaunchConfigurationName": {"Ref": "Launch"},
                        "MinSize": "0",
                        "MaxSize": "%s",
                        "DesiredCapacity": "0",
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  }
                }
                """.formatted(name == null ? "" : "\"LaunchConfigurationName\": \"" + name + "\",", instanceType, max);
    }

    private static final class Scenario implements AutoCloseable {
        private final String name = "lc-replacement-" + UUID.randomUUID().toString().substring(0, 8);
        private final CloudFormationClient cfn;
        private final AutoScalingClient scaling;
        private boolean created;

        private Scenario() {
            URI endpoint = URI.create("http://127.0.0.1:" + RestAssured.port);
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("846023460246", "test"));
            cfn = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            scaling = AutoScalingClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        }

        private void create(String template) {
            created = true;
            CfnStackWaits.createStack(cfn, name, template, "CREATE_COMPLETE");
        }

        private void update(String template, String expected) {
            CfnStackWaits.updateStack(cfn, name, template, expected);
        }

        private String physical(String logicalId) {
            return cfn.describeStackResource(request -> request.stackName(name).logicalResourceId(logicalId))
                    .stackResourceDetail().physicalResourceId();
        }

        private LaunchConfiguration launch(String physicalId) {
            return scaling.describeLaunchConfigurations(request -> request.launchConfigurationNames(physicalId))
                    .launchConfigurations().getFirst();
        }

        @Override
        public void close() {
            try {
                if (created) {
                    CfnStackWaits.deleteStack(cfn, name);
                    assertTrue(scaling.describeAutoScalingGroups().autoScalingGroups().stream()
                            .noneMatch(group -> group.autoScalingGroupName().startsWith(name + "-")));
                    assertTrue(scaling.describeLaunchConfigurations().launchConfigurations().stream()
                            .noneMatch(launch -> launch.launchConfigurationName().startsWith(name + "-")));
                }
            } finally {
                scaling.close();
                cfn.close();
            }
        }
    }
}
