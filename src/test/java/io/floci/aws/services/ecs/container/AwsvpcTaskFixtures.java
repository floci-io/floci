package io.floci.aws.services.ecs.container;

import io.floci.aws.services.ecs.model.AwsVpcConfiguration;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.NetworkConfiguration;
import io.floci.aws.services.ecs.model.NetworkMode;
import io.floci.aws.services.ecs.model.PortMapping;
import io.floci.aws.services.ecs.model.TaskDefinition;

import java.util.List;

/** The awsvpc task, containers and task definition the {@link EcsContainerManager} network tests launch. */
final class AwsvpcTaskFixtures {

    static final String TASK_ARN = "arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123";

    private AwsvpcTaskFixtures() {
    }

    /** Task {@code abc123} in {@code subnet-1} behind {@code sg-1}, before it has an ENI. */
    static EcsTask awsvpcTaskWithoutEni() {
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of("subnet-1"));
        awsvpc.setSecurityGroups(List.of("sg-1"));
        NetworkConfiguration network = new NetworkConfiguration();
        network.setAwsvpcConfiguration(awsvpc);
        EcsTask task = new EcsTask();
        task.setTaskArn(TASK_ARN);
        task.setNetworkConfiguration(network);
        return task;
    }

    /** {@link #awsvpcTaskWithoutEni()} as attachTaskNetwork leaves it for its containers: ENI {@code eni-1}. */
    static EcsTask awsvpcTask() {
        EcsTask task = awsvpcTaskWithoutEni();
        task.setNetworkInterfaceId("eni-1");
        task.setPrivateIpAddress("10.0.0.10");
        return task;
    }

    /** Container {@code name} running {@code name:latest}, mapping TCP {@code port} unless it is 0. */
    static ContainerDefinition container(String name, int port) {
        ContainerDefinition container = new ContainerDefinition();
        container.setName(name);
        container.setImage(name + ":latest");
        if (port > 0) {
            container.setPortMappings(List.of(new PortMapping(port, port, "tcp")));
        }
        return container;
    }

    /** Revision 1 of an awsvpc task definition of {@code containers}, with {@code taskRoleArn} or none when null. */
    static TaskDefinition taskDef(String taskRoleArn, ContainerDefinition... containers) {
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("awsvpc-family");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setTaskRoleArn(taskRoleArn);
        taskDef.setContainerDefinitions(List.of(containers));
        return taskDef;
    }
}
