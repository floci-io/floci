package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ECS Elastic Container Service")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcsTests {

    private static EcsClient ecs;
    private static String suffix;
    private static String clusterName;
    private static String family;
    private static String serviceName;
    private static Cluster cluster;
    private static TaskDefinition taskDef;
    private static TaskDefinition taskDefRev2;
    private static Service service;
    private static Task task;

    @BeforeAll
    static void setup() {
        ecs = TestFixtures.ecsClient();
        suffix = String.valueOf(System.currentTimeMillis() % 100000);
        clusterName = "sdk-test-cluster-" + suffix;
        family = "sdk-test-task-" + suffix;
        serviceName = "sdk-test-svc-" + suffix;
    }

    @AfterAll
    static void cleanup() {
        if (ecs != null) {
            // Stop any running tasks
            try {
                List<String> running = ecs.listTasks(ListTasksRequest.builder()
                        .cluster(clusterName)
                        .desiredStatus(DesiredStatus.RUNNING)
                        .build()).taskArns();
                for (String taskArn : running) {
                    ecs.stopTask(StopTaskRequest.builder()
                            .cluster(clusterName)
                            .task(taskArn)
                            .build());
                }
            } catch (Exception ignored) {}

            try {
                if (serviceName != null) {
                    ecs.deleteService(DeleteServiceRequest.builder()
                            .cluster(clusterName)
                            .service(serviceName)
                            .build());
                }
            } catch (Exception ignored) {}

            try {
                if (clusterName != null) {
                    ecs.deleteCluster(DeleteClusterRequest.builder().cluster(clusterName).build());
                }
            } catch (Exception ignored) {}

            ecs.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("CreateCluster - create ECS cluster")
    void createCluster() {
        cluster = ecs.createCluster(CreateClusterRequest.builder()
                .clusterName(clusterName)
                .build()).cluster();

        assertThat(cluster).isNotNull();
        assertThat(cluster.clusterName()).isEqualTo(clusterName);
        assertThat(cluster.clusterArn()).isNotNull().contains(clusterName);
        assertThat(cluster.status()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(2)
    @DisplayName("DescribeClusters - by name")
    void describeClustersByName() {
        List<Cluster> clusters = ecs.describeClusters(DescribeClustersRequest.builder()
                .clusters(clusterName)
                .build()).clusters();

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).clusterName()).isEqualTo(clusterName);
    }

    @Test
    @Order(3)
    @DisplayName("DescribeClusters - by ARN")
    void describeClustersById() {
        List<Cluster> clusters = ecs.describeClusters(DescribeClustersRequest.builder()
                .clusters(cluster.clusterArn())
                .build()).clusters();

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).clusterName()).isEqualTo(clusterName);
    }

    @Test
    @Order(4)
    @DisplayName("ListClusters - cluster in list")
    void listClusters() {
        List<String> arns = ecs.listClusters(ListClustersRequest.builder().build()).clusterArns();
        assertThat(arns).contains(cluster.clusterArn());
    }

    @Test
    @Order(5)
    @DisplayName("UpdateCluster - update cluster settings")
    void updateCluster() {
        Cluster updated = ecs.updateCluster(UpdateClusterRequest.builder()
                .cluster(clusterName)
                .settings(ClusterSetting.builder()
                        .name(ClusterSettingName.CONTAINER_INSIGHTS)
                        .value("enabled")
                        .build())
                .build()).cluster();

        assertThat(updated).isNotNull();
        assertThat(updated.clusterName()).isEqualTo(clusterName);
    }

    @Test
    @Order(6)
    @DisplayName("UpdateClusterSettings - update settings")
    void updateClusterSettings() {
        Cluster updated = ecs.updateClusterSettings(UpdateClusterSettingsRequest.builder()
                .cluster(clusterName)
                .settings(ClusterSetting.builder()
                        .name(ClusterSettingName.CONTAINER_INSIGHTS)
                        .value("disabled")
                        .build())
                .build()).cluster();

        assertThat(updated).isNotNull();
        assertThat(updated.clusterName()).isEqualTo(clusterName);
    }

    @Test
    @Order(7)
    @DisplayName("PutClusterCapacityProviders - add capacity providers")
    void putClusterCapacityProviders() {
        Cluster updated = ecs.putClusterCapacityProviders(PutClusterCapacityProvidersRequest.builder()
                .cluster(clusterName)
                .capacityProviders("FARGATE", "FARGATE_SPOT")
                .defaultCapacityProviderStrategy(
                        CapacityProviderStrategyItem.builder()
                                .capacityProvider("FARGATE")
                                .weight(1)
                                .build())
                .build()).cluster();

        assertThat(updated).isNotNull();
        assertThat(updated.hasCapacityProviders()).isTrue();
        assertThat(updated.capacityProviders()).contains("FARGATE");
    }

    @Test
    @Order(8)
    @DisplayName("RegisterTaskDefinition - create task definition")
    void registerTaskDefinition() {
        taskDef = ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .networkMode(NetworkMode.BRIDGE)
                .cpu("256")
                .memory("512")
                .containerDefinitions(
                        ContainerDefinition.builder()
                                .name("web")
                                .image("nginx:alpine")
                                .cpu(256)
                                .memory(512)
                                .essential(true)
                                .portMappings(PortMapping.builder()
                                        .containerPort(80)
                                        .hostPort(0)
                                        .protocol(TransportProtocol.TCP)
                                        .build())
                                .environment(KeyValuePair.builder()
                                        .name("ENV")
                                        .value("test")
                                        .build())
                                .build())
                .build()).taskDefinition();

        assertThat(taskDef).isNotNull();
        assertThat(taskDef.family()).isEqualTo(family);
        assertThat(taskDef.revision()).isEqualTo(1);
        assertThat(taskDef.statusAsString()).isEqualTo("ACTIVE");
        assertThat(taskDef.taskDefinitionArn()).isNotNull().contains(family + ":1");
        assertThat(taskDef.containerDefinitions()).hasSize(1);
        assertThat(taskDef.containerDefinitions().get(0).name()).isEqualTo("web");
    }

    @Test
    @Order(9)
    @DisplayName("RegisterTaskDefinition - revision 2")
    void registerTaskDefinitionRevision2() {
        taskDefRev2 = ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .containerDefinitions(ContainerDefinition.builder()
                        .name("app")
                        .image("alpine:latest")
                        .essential(true)
                        .build())
                .build()).taskDefinition();

        assertThat(taskDefRev2.revision()).isEqualTo(2);
        assertThat(taskDefRev2.taskDefinitionArn()).contains(family + ":2");
    }

    @Test
    @Order(10)
    @DisplayName("DescribeTaskDefinition - by family:revision")
    void describeTaskDefinitionByFamilyRevision() {
        TaskDefinition described = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(family + ":1")
                .build()).taskDefinition();

        assertThat(described.revision()).isEqualTo(1);
        assertThat(described.family()).isEqualTo(family);
    }

    @Test
    @Order(11)
    @DisplayName("DescribeTaskDefinition - by family (latest)")
    void describeTaskDefinitionByFamily() {
        TaskDefinition latest = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(family)
                .build()).taskDefinition();

        assertThat(latest.revision()).isEqualTo(2);
    }

    @Test
    @Order(12)
    @DisplayName("DescribeTaskDefinition - by ARN")
    void describeTaskDefinitionByArn() {
        TaskDefinition byArn = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(taskDef.taskDefinitionArn())
                .build()).taskDefinition();

        assertThat(byArn.taskDefinitionArn()).isEqualTo(taskDef.taskDefinitionArn());
    }

    @Test
    @Order(13)
    @DisplayName("ListTaskDefinitions - list all revisions")
    void listTaskDefinitions() {
        List<String> arns = ecs.listTaskDefinitions(ListTaskDefinitionsRequest.builder()
                .familyPrefix(family)
                .build()).taskDefinitionArns();

        assertThat(arns).hasSize(2);
        assertThat(arns).allMatch(a -> a.contains(family));
    }

    @Test
    @Order(14)
    @DisplayName("ListTaskDefinitionFamilies - list families")
    void listTaskDefinitionFamilies() {
        List<String> families = ecs.listTaskDefinitionFamilies(
                ListTaskDefinitionFamiliesRequest.builder()
                        .familyPrefix(family)
                        .build()).families();

        assertThat(families).hasSize(1);
        assertThat(families).contains(family);
    }

    @Test
    @Order(15)
    @DisplayName("TagResource - tag cluster")
    void tagResource() {
        ecs.tagResource(TagResourceRequest.builder()
                .resourceArn(cluster.clusterArn())
                .tags(software.amazon.awssdk.services.ecs.model.Tag.builder().key("env").value("test").build(),
                      software.amazon.awssdk.services.ecs.model.Tag.builder().key("team").value("sdk").build())
                .build());
    }

    @Test
    @Order(16)
    @DisplayName("ListTagsForResource - verify tags")
    void listTagsForResource() {
        List<software.amazon.awssdk.services.ecs.model.Tag> tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(cluster.clusterArn())
                .build()).tags();

        assertThat(tags).anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()));
        assertThat(tags).anyMatch(t -> "team".equals(t.key()) && "sdk".equals(t.value()));
    }

    @Test
    @Order(17)
    @DisplayName("UntagResource - remove tag")
    void untagResource() {
        ecs.untagResource(UntagResourceRequest.builder()
                .resourceArn(cluster.clusterArn())
                .tagKeys("team")
                .build());

        List<software.amazon.awssdk.services.ecs.model.Tag> tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(cluster.clusterArn())
                .build()).tags();

        assertThat(tags).noneMatch(t -> "team".equals(t.key()));
        assertThat(tags).anyMatch(t -> "env".equals(t.key()));
    }

    @Test
    @Order(18)
    @DisplayName("PutAccountSetting - set account setting")
    void putAccountSetting() {
        Setting setting = ecs.putAccountSetting(PutAccountSettingRequest.builder()
                .name(SettingName.CONTAINER_INSIGHTS)
                .value("enabled")
                .build()).setting();

        assertThat(setting).isNotNull();
        assertThat(setting.value()).isEqualTo("enabled");
    }

    @Test
    @Order(19)
    @DisplayName("PutAccountSettingDefault - set default setting")
    void putAccountSettingDefault() {
        Setting setting = ecs.putAccountSettingDefault(PutAccountSettingDefaultRequest.builder()
                .name(SettingName.TASK_LONG_ARN_FORMAT)
                .value("enabled")
                .build()).setting();

        assertThat(setting).isNotNull();
        assertThat(setting.value()).isEqualTo("enabled");
    }

    @Test
    @Order(20)
    @DisplayName("ListAccountSettings - list settings")
    void listAccountSettings() {
        List<Setting> settings = ecs.listAccountSettings(ListAccountSettingsRequest.builder()
                .build()).settings();

        assertThat(settings.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(21)
    @DisplayName("DeleteAccountSetting - delete setting")
    void deleteAccountSetting() {
        ecs.deleteAccountSetting(DeleteAccountSettingRequest.builder()
                .name(SettingName.TASK_LONG_ARN_FORMAT)
                .build());
    }

    @Test
    @Order(22)
    @DisplayName("DescribeCapacityProviders - built-in providers")
    void describeCapacityProviders() {
        List<CapacityProvider> providers = ecs.describeCapacityProviders(
                DescribeCapacityProvidersRequest.builder()
                        .capacityProviders("FARGATE", "FARGATE_SPOT")
                        .build()).capacityProviders();

        assertThat(providers).hasSize(2);
        assertThat(providers).anyMatch(p -> "FARGATE".equals(p.name()));
        assertThat(providers).anyMatch(p -> "FARGATE_SPOT".equals(p.name()));
    }

    @Test
    @Order(23)
    @DisplayName("CreateCapacityProvider - create custom provider")
    void createCapacityProvider() {
        String cpName = "cp-" + suffix;
        CapacityProvider capacityProvider = ecs.createCapacityProvider(CreateCapacityProviderRequest.builder()
                .name(cpName)
                .autoScalingGroupProvider(AutoScalingGroupProvider.builder()
                        .autoScalingGroupArn("arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:123:autoScalingGroupName/test-asg")
                        .build())
                .build()).capacityProvider();

        assertThat(capacityProvider).isNotNull();
        assertThat(capacityProvider.name()).isEqualTo(cpName);
        assertThat(capacityProvider.statusAsString()).isEqualTo("ACTIVE");

        // Update and delete
        CapacityProvider updated = ecs.updateCapacityProvider(UpdateCapacityProviderRequest.builder()
                .name(cpName)
                .autoScalingGroupProvider(AutoScalingGroupProviderUpdate.builder()
                        .build())
                .build()).capacityProvider();

        assertThat(updated).isNotNull();
        assertThat(updated.name()).isEqualTo(cpName);

        CapacityProvider deleted = ecs.deleteCapacityProvider(DeleteCapacityProviderRequest.builder()
                .capacityProvider(cpName)
                .build()).capacityProvider();

        assertThat(deleted).isNotNull();
        assertThat(deleted.name()).isEqualTo(cpName);
    }

    @Test
    @Order(24)
    @DisplayName("RegisterContainerInstance - register instance")
    void registerContainerInstance() {
        ContainerInstance containerInstance = ecs.registerContainerInstance(RegisterContainerInstanceRequest.builder()
                .cluster(clusterName)
                .build()).containerInstance();

        assertThat(containerInstance).isNotNull();
        assertThat(containerInstance.containerInstanceArn()).isNotNull();
        assertThat(containerInstance.status()).isEqualTo("ACTIVE");
        assertThat(containerInstance.agentConnected()).isTrue();

        String instanceArn = containerInstance.containerInstanceArn();

        // List and describe
        List<String> arns = ecs.listContainerInstances(ListContainerInstancesRequest.builder()
                .cluster(clusterName)
                .build()).containerInstanceArns();
        assertThat(arns).contains(instanceArn);

        List<ContainerInstance> instances = ecs.describeContainerInstances(
                DescribeContainerInstancesRequest.builder()
                        .cluster(clusterName)
                        .containerInstances(instanceArn)
                        .build()).containerInstances();

        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).containerInstanceArn()).isEqualTo(instanceArn);

        // Update agent
        ContainerInstance updated = ecs.updateContainerAgent(UpdateContainerAgentRequest.builder()
                .cluster(clusterName)
                .containerInstance(instanceArn)
                .build()).containerInstance();

        assertThat(updated).isNotNull();
        assertThat(updated.containerInstanceArn()).isEqualTo(instanceArn);

        // Update state
        List<ContainerInstance> updatedState = ecs.updateContainerInstancesState(
                UpdateContainerInstancesStateRequest.builder()
                        .cluster(clusterName)
                        .containerInstances(instanceArn)
                        .status(ContainerInstanceStatus.DRAINING)
                        .build()).containerInstances();

        assertThat(updatedState).isNotEmpty();
        assertThat(updatedState.get(0).status()).isEqualTo("DRAINING");

        // Set back to ACTIVE for StartTask
        ecs.updateContainerInstancesState(UpdateContainerInstancesStateRequest.builder()
                .cluster(clusterName)
                .containerInstances(instanceArn)
                .status(ContainerInstanceStatus.ACTIVE)
                .build());

        // StartTask
        List<Task> started = ecs.startTask(StartTaskRequest.builder()
                .cluster(clusterName)
                .containerInstances(instanceArn)
                .taskDefinition(family + ":1")
                .build()).tasks();

        assertThat(started).hasSize(1);
        assertThat(started.get(0).taskArn()).isNotNull();
        assertThat(started.get(0).containerInstanceArn()).isEqualTo(instanceArn);

        // Stop the task
        if (!started.isEmpty()) {
            ecs.stopTask(StopTaskRequest.builder()
                    .cluster(clusterName)
                    .task(started.get(0).taskArn())
                    .build());
        }

        // Deregister
        ContainerInstance deregistered = ecs.deregisterContainerInstance(
                DeregisterContainerInstanceRequest.builder()
                        .cluster(clusterName)
                        .containerInstance(instanceArn)
                        .force(true)
                        .build()).containerInstance();

        assertThat(deregistered).isNotNull();
        assertThat(deregistered.status()).isEqualTo("INACTIVE");
    }

    @Test
    @Order(25)
    @DisplayName("PutAttributes - set attributes")
    void putAttributes() {
        String targetId = cluster.clusterArn();
        List<Attribute> stored = ecs.putAttributes(PutAttributesRequest.builder()
                .cluster(clusterName)
                .attributes(
                        Attribute.builder().name("com.example.attr").value("val1")
                                .targetType(TargetType.CONTAINER_INSTANCE).targetId(targetId).build())
                .build()).attributes();

        assertThat(stored).isNotEmpty();

        // List attributes
        List<Attribute> attrs = ecs.listAttributes(ListAttributesRequest.builder()
                .cluster(clusterName)
                .targetType(TargetType.CONTAINER_INSTANCE)
                .build()).attributes();

        assertThat(attrs).anyMatch(a -> "com.example.attr".equals(a.name()));

        // Delete attributes
        List<Attribute> deleted = ecs.deleteAttributes(DeleteAttributesRequest.builder()
                .cluster(clusterName)
                .attributes(Attribute.builder().name("com.example.attr")
                        .targetType(TargetType.CONTAINER_INSTANCE).targetId(targetId).build())
                .build()).attributes();

        assertThat(deleted).isNotEmpty();
    }

    @Test
    @Order(26)
    @DisplayName("DiscoverPollEndpoint - get poll endpoint")
    void discoverPollEndpoint() {
        DiscoverPollEndpointResponse pollResp = ecs.discoverPollEndpoint(
                DiscoverPollEndpointRequest.builder()
                        .cluster(clusterName)
                        .build());

        assertThat(pollResp.endpoint()).isNotNull().isNotEmpty();
    }

    @Test
    @Order(27)
    @DisplayName("SubmitTaskStateChange - submit state change")
    void submitTaskStateChange() {
        String ack = ecs.submitTaskStateChange(SubmitTaskStateChangeRequest.builder()
                .cluster(clusterName)
                .status("RUNNING")
                .build()).acknowledgment();

        assertThat(ack).isNotNull().isNotEmpty();
    }

    @Test
    @Order(28)
    @DisplayName("SubmitContainerStateChange - submit container state")
    void submitContainerStateChange() {
        String ack = ecs.submitContainerStateChange(SubmitContainerStateChangeRequest.builder()
                .cluster(clusterName)
                .status("RUNNING")
                .build()).acknowledgment();

        assertThat(ack).isNotNull().isNotEmpty();
    }

    @Test
    @Order(29)
    @DisplayName("SubmitAttachmentStateChanges - submit attachment state")
    void submitAttachmentStateChanges() {
        String ack = ecs.submitAttachmentStateChanges(SubmitAttachmentStateChangesRequest.builder()
                .cluster(clusterName)
                .attachments(AttachmentStateChange.builder()
                        .attachmentArn("arn:aws:ecs:us-east-1:000000000000:attachment/test")
                        .status("ATTACHED")
                        .build())
                .build()).acknowledgment();

        assertThat(ack).isNotNull().isNotEmpty();
    }

    @Test
    @Order(30)
    @DisplayName("RunTask - run task in cluster")
    void runTask() {
        List<Task> tasks = ecs.runTask(RunTaskRequest.builder()
                .cluster(clusterName)
                .taskDefinition(family + ":1")
                .count(1)
                .launchType(LaunchType.FARGATE)
                .startedBy("sdk-test")
                .build()).tasks();

        assertThat(tasks).hasSize(1);
        task = tasks.get(0);
        assertThat(task.taskArn()).isNotNull();
        assertThat(task.clusterArn()).isEqualTo(cluster.clusterArn());
        assertThat(task.taskDefinitionArn()).isEqualTo(taskDef.taskDefinitionArn());
        assertThat(task.lastStatus()).isEqualTo("RUNNING");
    }

    @Test
    @Order(31)
    @DisplayName("DescribeTasks - describe running task")
    void describeTasks() {
        List<Task> described = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterName)
                .tasks(task.taskArn())
                .build()).tasks();

        assertThat(described).hasSize(1);
        assertThat(described.get(0).taskArn()).isEqualTo(task.taskArn());
        assertThat(described.get(0).lastStatus()).isEqualTo("RUNNING");
        assertThat(described.get(0).startedBy()).isEqualTo("sdk-test");
    }

    @Test
    @Order(32)
    @DisplayName("ListTasks - list tasks in cluster")
    void listTasks() {
        List<String> taskArns = ecs.listTasks(ListTasksRequest.builder()
                .cluster(clusterName)
                .build()).taskArns();

        assertThat(taskArns).contains(task.taskArn());
    }

    @Test
    @Order(33)
    @DisplayName("ListTasks - filter by running status")
    void listTasksRunning() {
        List<String> taskArns = ecs.listTasks(ListTasksRequest.builder()
                .cluster(clusterName)
                .desiredStatus(DesiredStatus.RUNNING)
                .build()).taskArns();

        assertThat(taskArns).contains(task.taskArn());
    }

    @Test
    @Order(34)
    @DisplayName("DescribeClusters - runningTasksCount updated")
    void describeClustersRunningCount() {
        Cluster updated = ecs.describeClusters(DescribeClustersRequest.builder()
                .clusters(clusterName)
                .build()).clusters().get(0);

        assertThat(updated.runningTasksCount()).isEqualTo(1);
    }

    @Test
    @Order(35)
    @DisplayName("UpdateTaskProtection - enable protection")
    void updateTaskProtection() {
        List<ProtectedTask> protectedTasks = ecs.updateTaskProtection(
                UpdateTaskProtectionRequest.builder()
                        .cluster(clusterName)
                        .tasks(task.taskArn())
                        .protectionEnabled(true)
                        .expiresInMinutes(60)
                        .build()).protectedTasks();

        assertThat(protectedTasks).hasSize(1);
        assertThat(protectedTasks.get(0).protectionEnabled()).isTrue();
        assertThat(protectedTasks.get(0).expirationDate()).isNotNull();
    }

    @Test
    @Order(36)
    @DisplayName("GetTaskProtection - verify protection")
    void getTaskProtection() {
        List<ProtectedTask> protectedTasks = ecs.getTaskProtection(GetTaskProtectionRequest.builder()
                .cluster(clusterName)
                .tasks(task.taskArn())
                .build()).protectedTasks();

        assertThat(protectedTasks).hasSize(1);
        assertThat(protectedTasks.get(0).protectionEnabled()).isTrue();

        // Disable protection
        ecs.updateTaskProtection(UpdateTaskProtectionRequest.builder()
                .cluster(clusterName)
                .tasks(task.taskArn())
                .protectionEnabled(false)
                .build());
    }

    @Test
    @Order(37)
    @DisplayName("StopTask - stop running task")
    void stopTask() {
        Task stopped = ecs.stopTask(StopTaskRequest.builder()
                .cluster(clusterName)
                .task(task.taskArn())
                .reason("sdk-test-stop")
                .build()).task();

        assertThat(stopped.lastStatus()).isEqualTo("STOPPED");
        assertThat(stopped.stoppedReason()).isEqualTo("sdk-test-stop");
        assertThat(stopped.stoppedAt()).isNotNull();
    }

    @Test
    @Order(38)
    @DisplayName("DescribeTasks - verify task stopped")
    void describeTasksStopped() {
        Task stoppedTask = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterName)
                .tasks(task.taskArn())
                .build()).tasks().get(0);

        assertThat(stoppedTask.lastStatus()).isEqualTo("STOPPED");
    }

    @Test
    @Order(39)
    @DisplayName("ListTasks - filter by stopped status")
    void listTasksStopped() {
        List<String> taskArns = ecs.listTasks(ListTasksRequest.builder()
                .cluster(clusterName)
                .desiredStatus(DesiredStatus.STOPPED)
                .build()).taskArns();

        assertThat(taskArns).contains(task.taskArn());
    }

    @Test
    @Order(40)
    @DisplayName("CreateService - create ECS service")
    void createService() {
        service = ecs.createService(CreateServiceRequest.builder()
                .cluster(clusterName)
                .serviceName(serviceName)
                .taskDefinition(family + ":1")
                .desiredCount(1)
                .launchType(LaunchType.FARGATE)
                .build()).service();

        assertThat(service).isNotNull();
        assertThat(service.serviceName()).isEqualTo(serviceName);
        assertThat(service.serviceArn()).isNotNull().contains(serviceName);
        assertThat(service.desiredCount()).isEqualTo(1);
        assertThat(service.status()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(41)
    @DisplayName("CreateService - duplicate fails")
    void createServiceDuplicate() {
        assertThatThrownBy(() -> ecs.createService(CreateServiceRequest.builder()
                .cluster(clusterName)
                .serviceName(serviceName)
                .taskDefinition(family + ":1")
                .desiredCount(1)
                .build()))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @Order(42)
    @DisplayName("DescribeServices - describe service")
    void describeServices() {
        List<Service> services = ecs.describeServices(DescribeServicesRequest.builder()
                .cluster(clusterName)
                .services(serviceName)
                .build()).services();

        assertThat(services).hasSize(1);
        assertThat(services.get(0).serviceName()).isEqualTo(serviceName);
        assertThat(services.get(0).desiredCount()).isEqualTo(1);
    }

    @Test
    @Order(43)
    @DisplayName("ListServices - list services")
    void listServices() {
        List<String> serviceArns = ecs.listServices(ListServicesRequest.builder()
                .cluster(clusterName)
                .build()).serviceArns();

        assertThat(serviceArns).contains(service.serviceArn());
    }

    @Test
    @Order(44)
    @DisplayName("Service reconciler - reaches desiredCount")
    void serviceReconciler() throws InterruptedException {
        boolean reconciled = false;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            Service svc = ecs.describeServices(DescribeServicesRequest.builder()
                    .cluster(clusterName)
                    .services(serviceName)
                    .build()).services().get(0);
            if (svc.runningCount() >= 1) {
                reconciled = true;
                break;
            }
        }
        assertThat(reconciled).isTrue();
    }

    @Test
    @Order(45)
    @DisplayName("ListServiceDeployments - list deployments")
    void listServiceDeployments() {
        List<ServiceDeploymentBrief> briefs = ecs.listServiceDeployments(
                ListServiceDeploymentsRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .build()).serviceDeployments();

        assertThat(briefs).isNotEmpty();

        if (!briefs.isEmpty()) {
            String deploymentArn = briefs.get(0).serviceDeploymentArn();
            List<ServiceDeployment> deployments = ecs.describeServiceDeployments(
                    DescribeServiceDeploymentsRequest.builder()
                            .serviceDeploymentArns(deploymentArn)
                            .build()).serviceDeployments();

            assertThat(deployments).hasSize(1);
            assertThat(deployments.get(0).serviceArn()).isEqualTo(service.serviceArn());
        }
    }

    @Test
    @Order(46)
    @DisplayName("UpdateService - update desiredCount to 0")
    void updateServiceDesiredCount() {
        Service updated = ecs.updateService(UpdateServiceRequest.builder()
                .cluster(clusterName)
                .service(serviceName)
                .desiredCount(0)
                .build()).service();

        assertThat(updated.desiredCount()).isEqualTo(0);
    }

    @Test
    @Order(47)
    @DisplayName("UpdateService - update taskDefinition")
    void updateServiceTaskDefinition() {
        Service updated = ecs.updateService(UpdateServiceRequest.builder()
                .cluster(clusterName)
                .service(serviceName)
                .taskDefinition(family + ":2")
                .build()).service();

        assertThat(updated.taskDefinition()).isNotNull().contains(family + ":2");
    }

    @Test
    @Order(48)
    @DisplayName("CreateTaskSet - create task set")
    void createTaskSet() {
        software.amazon.awssdk.services.ecs.model.TaskSet ts =
                ecs.createTaskSet(CreateTaskSetRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .taskDefinition(family + ":1")
                        .launchType(LaunchType.FARGATE)
                        .scale(Scale.builder().value(50.0).unit(ScaleUnit.PERCENT).build())
                        .build()).taskSet();

        assertThat(ts).isNotNull();
        assertThat(ts.taskSetArn()).isNotNull();
        assertThat(ts.status()).isEqualTo("ACTIVE");

        String taskSetArn = ts.taskSetArn();

        // Describe task sets
        List<software.amazon.awssdk.services.ecs.model.TaskSet> sets =
                ecs.describeTaskSets(DescribeTaskSetsRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .taskSets(taskSetArn)
                        .build()).taskSets();

        assertThat(sets).hasSize(1);
        assertThat(sets.get(0).taskSetArn()).isEqualTo(taskSetArn);

        // Update task set
        software.amazon.awssdk.services.ecs.model.TaskSet updated =
                ecs.updateTaskSet(UpdateTaskSetRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .taskSet(taskSetArn)
                        .scale(Scale.builder().value(100.0).unit(ScaleUnit.PERCENT).build())
                        .build()).taskSet();

        assertThat(updated).isNotNull();
        assertThat(updated.scale().value()).isEqualTo(100.0);

        // Update primary task set
        software.amazon.awssdk.services.ecs.model.TaskSet primary =
                ecs.updateServicePrimaryTaskSet(UpdateServicePrimaryTaskSetRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .primaryTaskSet(taskSetArn)
                        .build()).taskSet();

        assertThat(primary).isNotNull();
        assertThat(primary.status()).isEqualTo("PRIMARY");

        // Delete task set
        software.amazon.awssdk.services.ecs.model.TaskSet deleted =
                ecs.deleteTaskSet(DeleteTaskSetRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .taskSet(taskSetArn)
                        .build()).taskSet();

        assertThat(deleted).isNotNull();
    }

    @Test
    @Order(49)
    @DisplayName("DeleteService - delete service")
    void deleteService() {
        Service deleted = ecs.deleteService(DeleteServiceRequest.builder()
                .cluster(clusterName)
                .service(serviceName)
                .build()).service();

        assertThat(deleted.status()).isEqualTo("INACTIVE");
    }

    @Test
    @Order(50)
    @DisplayName("ListServices - service no longer in list")
    void listServicesAfterDelete() {
        List<String> serviceArns = ecs.listServices(ListServicesRequest.builder()
                .cluster(clusterName)
                .build()).serviceArns();

        assertThat(serviceArns).doesNotContain(service.serviceArn());
    }

    @Test
    @Order(51)
    @DisplayName("DeregisterTaskDefinition - deregister revision 1")
    void deregisterTaskDefinition() {
        TaskDefinition deregistered = ecs.deregisterTaskDefinition(
                DeregisterTaskDefinitionRequest.builder()
                        .taskDefinition(family + ":1")
                        .build()).taskDefinition();

        assertThat(deregistered.statusAsString()).isEqualTo("INACTIVE");
    }

    @Test
    @Order(52)
    @DisplayName("ListTaskDefinitions - filter by ACTIVE")
    void listTaskDefinitionsActive() {
        List<String> activeArns = ecs.listTaskDefinitions(ListTaskDefinitionsRequest.builder()
                .familyPrefix(family)
                .status(TaskDefinitionStatus.ACTIVE)
                .build()).taskDefinitionArns();

        assertThat(activeArns).hasSize(1);
        assertThat(activeArns.get(0)).contains(family + ":2");
    }

    @Test
    @Order(53)
    @DisplayName("DeleteTaskDefinitions - delete INACTIVE")
    void deleteTaskDefinitions() {
        List<TaskDefinition> deletedDefs = ecs.deleteTaskDefinitions(
                DeleteTaskDefinitionsRequest.builder()
                        .taskDefinitions(family + ":1")
                        .build()).taskDefinitions();

        assertThat(deletedDefs).hasSize(1);
        assertThat(deletedDefs.get(0).taskDefinitionArn()).contains(family + ":1");
    }

    @Test
    @Order(54)
    @DisplayName("DeleteCluster - fails with running tasks")
    void deleteClusterFailsWithTasks() {
        ecs.runTask(RunTaskRequest.builder()
                .cluster(clusterName)
                .taskDefinition(family + ":2")
                .count(1)
                .build());

        assertThatThrownBy(() -> ecs.deleteCluster(DeleteClusterRequest.builder()
                .cluster(clusterName).build()))
                .isInstanceOf(ClusterContainsTasksException.class);
    }

    @Test
    @Order(55)
    @DisplayName("DeleteCluster - delete after stopping tasks")
    void deleteCluster() {
        List<String> running = ecs.listTasks(ListTasksRequest.builder()
                .cluster(clusterName)
                .desiredStatus(DesiredStatus.RUNNING)
                .build()).taskArns();

        for (String taskArn : running) {
            ecs.stopTask(StopTaskRequest.builder()
                    .cluster(clusterName)
                    .task(taskArn)
                    .build());
        }

        Cluster deleted = ecs.deleteCluster(DeleteClusterRequest.builder()
                .cluster(clusterName)
                .build()).cluster();

        assertThat(deleted.status()).isEqualTo("INACTIVE");
    }

    @Test
    @Order(56)
    @DisplayName("ListClusters - cluster no longer in list")
    void listClustersAfterDelete() {
        List<String> arns = ecs.listClusters(ListClustersRequest.builder().build()).clusterArns();
        assertThat(arns).doesNotContain(cluster.clusterArn());
    }
}
