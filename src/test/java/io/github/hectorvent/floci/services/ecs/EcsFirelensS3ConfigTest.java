package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FireLens config that comes from S3, on both sides of the split ECS draws:
 *
 * <p>{@code RegisterTaskDefinition} rejects the combination for Fargate (the platform cannot pull
 * the object) and rejects a {@code config-file-value} that is not an S3 object ARN, while an
 * EC2-compatible task definition keeps it and the agent pulls it at launch. A failed pull becomes
 * the task's stopped reason in the agent's own wording.
 *
 * <p>A Fargate task can still take its config from S3 the way AWS documents, by giving the
 * aws-for-fluent-bit init process its {@code aws_fluent_bit_init_s3_*} environment variables,
 * which ECS never inspects; the last test pins that Floci does not over-reach and reject it.
 */
class EcsFirelensS3ConfigTest {

    private static final String S3_CONFIG_ARN = "arn:aws:s3:::firelens-configs/extra.conf";

    @Test
    void registerTaskDefinitionRejectsS3FirelensConfigForFargate() {
        AwsException failure = assertThrows(AwsException.class, () -> service("us-east-1")
                .registerTaskDefinition("fluentbit-s3", List.of(firelensRouter("fluentbit", S3_CONFIG_ARN)),
                        NetworkMode.awsvpc, "256", "512", null, null, List.of("FARGATE"), "us-east-1"));

        assertEquals("ClientException", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertEquals("Fargate launch type does not support FirelensConfiguration config file from 's3'",
                failure.getMessage());
    }

    @Test
    void registerTaskDefinitionRejectsS3FirelensConfigForFargateWithAFluentdRouter() {
        AwsException failure = assertThrows(AwsException.class, () -> service("us-east-1")
                .registerTaskDefinition("fluentd-s3", List.of(firelensRouter("fluentd", S3_CONFIG_ARN)),
                        NetworkMode.awsvpc, "256", "512", null, null, List.of("FARGATE"), "us-east-1"));

        assertEquals("Fargate launch type does not support FirelensConfiguration config file from 's3'",
                failure.getMessage());
    }

    @Test
    void registerTaskDefinitionRejectsAConfigFileValueThatIsNotAnS3ObjectArn() {
        AwsException failure = assertThrows(AwsException.class, () -> service("us-east-1")
                .registerTaskDefinition("bad-arn", List.of(firelensRouter("fluentbit", "s3://logs/extra.conf")),
                        NetworkMode.bridge, null, null, null, null, List.of("EC2"), "us-east-1"));

        assertEquals("ClientException", failure.getErrorCode());
        assertEquals("Invalid arn syntax", failure.getMessage());
    }

    @Test
    void ec2TaskDefinitionKeepsItsS3FirelensConfig() {
        TaskDefinition td = service("us-east-1").registerTaskDefinition("ec2-s3",
                List.of(firelensRouter("fluentbit", S3_CONFIG_ARN)),
                NetworkMode.bridge, null, null, null, null, List.of("EC2"), "us-east-1");

        FirelensConfiguration firelens = td.getContainerDefinitions().getFirst().getFirelensConfiguration();
        assertEquals("s3", firelens.options().get("config-file-type"));
        assertEquals(S3_CONFIG_ARN, firelens.options().get("config-file-value"));
    }

    @Test
    void fargateTaskDefinitionAcceptsS3ConfigThroughTheInitProcessEnvironment() {
        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("public.ecr.aws/aws-observability/aws-for-fluent-bit:init-latest");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of()));
        router.setEnvironment(List.of(new KeyValuePair("aws_fluent_bit_init_s3_1", S3_CONFIG_ARN)));

        TaskDefinition td = service("us-east-1").registerTaskDefinition("init-s3", List.of(router),
                NetworkMode.awsvpc, "256", "512", null, null, List.of("FARGATE"), "us-east-1");

        assertEquals("init-s3", td.getFamily());
    }

    @Test
    void runTaskStopsWithTheAgentReasonWhenTheS3ConfigCannotBeDownloaded() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        // EcsContainerManager reads the object before creating any container and reports the
        // failure with the agent's wording under a ResourceInitializationError code; EcsService
        // keys off that code to use the message as the stopped reason verbatim.
        when(containerManager.startTask(any(), any(), any(), eq("us-east-1")))
                .thenThrow(new AwsException("ResourceInitializationError",
                        "Unable to download firelens s3 config file: unable to download s3 config "
                                + "extra.conf from bucket firelens-configs: The specified key does not exist.",
                        404));

        EcsService service = service(containerManager, false);
        service.createCluster("test-cluster", "us-east-1");
        service.registerTaskDefinition("ec2-s3", List.of(firelensRouter("fluentbit", S3_CONFIG_ARN)),
                NetworkMode.bridge, null, null, null, null, List.of("EC2"), "us-east-1");

        List<EcsTask> tasks = service.runTask("test-cluster", "ec2-s3", 1,
                LaunchType.EC2, null, null, List.of(), null, "us-east-1");

        EcsTask task = tasks.getFirst();
        assertEquals(TaskStatus.STOPPED.name(), task.getLastStatus());
        assertEquals("Unable to download firelens s3 config file: unable to download s3 config "
                        + "extra.conf from bucket firelens-configs: The specified key does not exist.",
                task.getStoppedReason());
    }

    private static ContainerDefinition firelensRouter(String type, String configFileValue) {
        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("public.ecr.aws/aws-observability/aws-for-fluent-bit:stable");
        router.setFirelensConfiguration(new FirelensConfiguration(type, Map.of(
                "config-file-type", "s3",
                "config-file-value", configFileValue)));
        return router;
    }

    private static EcsService service(String region) {
        return service(mock(EcsContainerManager.class), true);
    }

    private static EcsService service(EcsContainerManager containerManager, boolean mockMode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(mockMode);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        return new EcsService(new RegionResolver("us-east-1", "000000000000"),
                containerManager, config, mock(EcsLoadBalancerRegistrar.class), null, null);
    }
}
