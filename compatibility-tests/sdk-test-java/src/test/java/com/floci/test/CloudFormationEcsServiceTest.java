package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DeploymentRolloutState;
import software.amazon.awssdk.services.ecs.model.Service;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CloudFormation reports an {@code AWS::ECS::Service} CREATE_COMPLETE only once the service has
 * reached a steady state, so DescribeServices right after the stack completes shows the desired
 * tasks running.
 */
@DisplayName("CloudFormation AWS::ECS::Service steady state")
class CloudFormationEcsServiceTest {

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "Name": {"Type": "String"},
                "Subnet": {"Type": "String"}
              },
              "Resources": {
                "Cluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": {"ClusterName": {"Ref": "Name"}}
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": {"Ref": "Name"},
                    "NetworkMode": "awsvpc",
                    "RequiresCompatibilities": ["FARGATE"],
                    "Cpu": "256",
                    "Memory": "512",
                    "ContainerDefinitions": [
                      {"Name": "web", "Image": "nginx:alpine", "Essential": true}
                    ]
                  }
                },
                "Service": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": {"Ref": "Name"},
                    "Cluster": {"Ref": "Cluster"},
                    "TaskDefinition": {"Ref": "TaskDef"},
                    "DesiredCount": 1,
                    "LaunchType": "FARGATE",
                    "NetworkConfiguration": {
                      "AwsvpcConfiguration": {"Subnets": [{"Ref": "Subnet"}], "AssignPublicIp": "ENABLED"}
                    }
                  }
                }
              }
            }
            """;

    private static CloudFormationClient cfn;
    private static EcsClient ecs;
    private static Ec2Client ec2;
    private String stackName;
    private boolean stackCreated;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        ecs = TestFixtures.ecsClient();
        ec2 = TestFixtures.ec2Client();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-ecs-svc");
    }

    @AfterEach
    void cleanup() {
        if (stackCreated) {
            cfn.deleteStack(r -> r.stackName(stackName));
            cfn.waiter().waitUntilStackDeleteComplete(r -> r.stackName(stackName),
                    w -> w.waitTimeout(Duration.ofMinutes(5)));
        }
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        ecs.close();
        ec2.close();
    }

    @Test
    @DisplayName("DescribeServices right after CREATE_COMPLETE shows the desired task running")
    void createCompleteMeansTheDesiredTaskIsRunning() {
        cfn.createStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(
                parameter("Name", stackName), parameter("Subnet", defaultSubnet())));
        stackCreated = true;
        StackStatus status = cfn.waiter().waitUntilStackCreateComplete(r -> r.stackName(stackName),
                        w -> w.waitTimeout(Duration.ofMinutes(10)))
                .matched().response().orElseThrow().stacks().get(0).stackStatus();
        assertThat(status).isEqualTo(StackStatus.CREATE_COMPLETE);

        Service service = ecs.describeServices(r -> r.cluster(stackName).services(stackName))
                .services().get(0);

        assertThat(service.desiredCount()).isEqualTo(1);
        assertThat(service.runningCount()).as("runningCount right after CREATE_COMPLETE").isEqualTo(1);
        assertThat(service.deployments()).hasSize(1);
        assertThat(service.deployments().get(0).rolloutState()).isEqualTo(DeploymentRolloutState.COMPLETED);
    }

    private static Parameter parameter(String key, String value) {
        return Parameter.builder().parameterKey(key).parameterValue(value).build();
    }

    /** A subnet of the default VPC, which the task's network interface goes into. */
    private static String defaultSubnet() {
        List<Subnet> subnets = ec2.describeSubnets().subnets();
        return subnets.stream()
                .filter(subnet -> Boolean.TRUE.equals(subnet.defaultForAz()))
                .map(Subnet::subnetId)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no default-VPC subnet to place the task in"));
    }
}
