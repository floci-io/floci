package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.emr.EmrClient;
import software.amazon.awssdk.services.emr.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * EMR validated through the real AWS SDK v2 client: run job flow → describe cluster →
 * add step → instance groups → security config → terminate. Verifies the ActionOnFailure
 * legacy alias and the InvalidRequestException not-found shape.
 */
@DisplayName("EMR")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmrTest {

    private static EmrClient emr;
    private static String clusterId;
    private static String stepId;
    private static String secConfigName;

    @BeforeAll
    static void setup() {
        emr = TestFixtures.emrClient();
        secConfigName = TestFixtures.uniqueName("emr-sec");
    }

    @AfterAll
    static void cleanup() {
        try {
            emr.terminateJobFlows(r -> r.jobFlowIds(clusterId));
        } catch (Exception ignored) {}
        try {
            emr.deleteSecurityConfiguration(r -> r.name(secConfigName));
        } catch (Exception ignored) {}
        emr.close();
    }

    @Test
    @Order(1)
    void runJobFlow() {
        RunJobFlowResponse resp = emr.runJobFlow(r -> r
                .name("floci-emr-sdk")
                .releaseLabel("emr-7.5.0")
                .instances(JobFlowInstancesConfig.builder()
                        .keepJobFlowAliveWhenNoSteps(true)
                        .instanceGroups(
                                InstanceGroupConfig.builder().name("master")
                                        .instanceRole(InstanceRoleType.MASTER)
                                        .instanceType("m5.xlarge").instanceCount(1).build(),
                                InstanceGroupConfig.builder().name("core")
                                        .instanceRole(InstanceRoleType.CORE)
                                        .instanceType("m5.xlarge").instanceCount(2).build())
                        .build())
                .steps(StepConfig.builder()
                        .name("initial")
                        .actionOnFailure(ActionOnFailure.TERMINATE_JOB_FLOW)
                        .hadoopJarStep(HadoopJarStepConfig.builder()
                                .jar("command-runner.jar").args("echo", "hi").build())
                        .build()));
        clusterId = resp.jobFlowId();
        assertThat(clusterId).startsWith("j-");
        assertThat(resp.clusterArn()).contains("cluster/" + clusterId);
    }

    @Test
    @Order(2)
    void describeClusterWaiting() {
        DescribeClusterResponse resp = emr.describeCluster(r -> r.clusterId(clusterId));
        Cluster cluster = resp.cluster();
        assertThat(cluster.status().state()).isEqualTo(ClusterState.WAITING);
        assertThat(cluster.releaseLabel()).isEqualTo("emr-7.5.0");
        assertThat(cluster.instanceCollectionType()).isEqualTo(InstanceCollectionType.INSTANCE_GROUP);
        assertThat(cluster.autoTerminate()).isFalse();
    }

    @Test
    @Order(3)
    void listClustersByState() {
        ListClustersResponse resp = emr.listClusters(r -> r.clusterStates(ClusterState.WAITING));
        assertThat(resp.clusters()).anyMatch(c -> c.id().equals(clusterId));
    }

    @Test
    @Order(4)
    void listInstanceGroups() {
        ListInstanceGroupsResponse resp = emr.listInstanceGroups(r -> r.clusterId(clusterId));
        assertThat(resp.instanceGroups()).hasSize(2);
        assertThat(resp.instanceGroups())
                .anyMatch(g -> g.instanceGroupType() == InstanceGroupType.CORE
                        && g.runningInstanceCount() == 2);
    }

    @Test
    @Order(5)
    void listInstances() {
        ListInstancesResponse resp = emr.listInstances(r -> r.clusterId(clusterId));
        assertThat(resp.instances()).hasSize(3);  // 1 master + 2 core
    }

    @Test
    @Order(6)
    void addStepWithLegacyAlias() {
        AddJobFlowStepsResponse resp = emr.addJobFlowSteps(r -> r
                .jobFlowId(clusterId)
                .steps(StepConfig.builder()
                        .name("added")
                        .actionOnFailure(ActionOnFailure.TERMINATE_CLUSTER)
                        .hadoopJarStep(HadoopJarStepConfig.builder()
                                .jar("command-runner.jar").mainClass("org.Main").args("run").build())
                        .build()));
        assertThat(resp.stepIds()).hasSize(1);
        stepId = resp.stepIds().get(0);
    }

    @Test
    @Order(7)
    void describeStepCompleted() {
        DescribeStepResponse resp = emr.describeStep(r -> r.clusterId(clusterId).stepId(stepId));
        assertThat(resp.step().name()).isEqualTo("added");
        assertThat(resp.step().actionOnFailure()).isEqualTo(ActionOnFailure.TERMINATE_CLUSTER);
        assertThat(resp.step().config().mainClass()).isEqualTo("org.Main");
        assertThat(resp.step().status().state()).isEqualTo(StepState.COMPLETED);
    }

    @Test
    @Order(8)
    void listStepsNewestFirst() {
        ListStepsResponse resp = emr.listSteps(r -> r.clusterId(clusterId));
        List<StepSummary> steps = resp.steps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).name()).isEqualTo("added");
    }

    @Test
    @Order(9)
    void setKeepAliveTogglesAutoTerminate() {
        emr.setKeepJobFlowAliveWhenNoSteps(r -> r.jobFlowIds(clusterId).keepJobFlowAliveWhenNoSteps(false));
        Cluster cluster = emr.describeCluster(r -> r.clusterId(clusterId)).cluster();
        assertThat(cluster.autoTerminate()).isTrue();
    }

    @Test
    @Order(10)
    void securityConfigurationRoundTrip() {
        CreateSecurityConfigurationResponse created = emr.createSecurityConfiguration(r -> r
                .name(secConfigName).securityConfiguration("{\"EncryptionConfiguration\":{}}"));
        assertThat(created.name()).isEqualTo(secConfigName);
        assertThat(created.creationDateTime()).isNotNull();

        DescribeSecurityConfigurationResponse described =
                emr.describeSecurityConfiguration(r -> r.name(secConfigName));
        assertThat(described.securityConfiguration()).contains("EncryptionConfiguration");

        emr.deleteSecurityConfiguration(r -> r.name(secConfigName));
    }

    @Test
    @Order(11)
    void terminate() {
        emr.terminateJobFlows(r -> r.jobFlowIds(clusterId));
        Cluster cluster = emr.describeCluster(r -> r.clusterId(clusterId)).cluster();
        assertThat(cluster.status().state()).isEqualTo(ClusterState.TERMINATED);
        assertThat(cluster.status().stateChangeReason().code())
                .isEqualTo(ClusterStateChangeReasonCode.USER_REQUEST);
    }

    @Test
    @Order(12)
    void describeUnknownClusterThrowsInvalidRequest() {
        assertThatThrownBy(() -> emr.describeCluster(r -> r.clusterId("j-DOESNOTEXIST0")))
                .isInstanceOf(InvalidRequestException.class);
    }
}
