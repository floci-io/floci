package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.emrcontainers.EmrContainersClient;
import software.amazon.awssdk.services.emrcontainers.model.ContainerProviderType;
import software.amazon.awssdk.services.emrcontainers.model.CreateVirtualClusterResponse;
import software.amazon.awssdk.services.emrcontainers.model.FailureReason;
import software.amazon.awssdk.services.emrcontainers.model.JobRun;
import software.amazon.awssdk.services.emrcontainers.model.JobRunState;
import software.amazon.awssdk.services.emrcontainers.model.ResourceNotFoundException;
import software.amazon.awssdk.services.emrcontainers.model.TemplateParameterConfiguration;
import software.amazon.awssdk.services.emrcontainers.model.TemplateParameterDataType;
import software.amazon.awssdk.services.emrcontainers.model.VirtualCluster;
import software.amazon.awssdk.services.emrcontainers.model.VirtualClusterState;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("EMR on EKS")
class EmrContainersTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-job";
    private static final String RELEASE = "emr-7.5.0-latest";
    // No EKS cluster of this name exists, so a job run fails the way AWS documents it.
    private static final String EKS_CLUSTER = "no-such-eks-cluster";

    private static EmrContainersClient emr;
    private static String virtualClusterId;
    private static String virtualClusterArn;

    @BeforeAll
    static void setup() {
        emr = TestFixtures.emrContainersClient();
        CreateVirtualClusterResponse created = emr.createVirtualCluster(r -> r
                .name(TestFixtures.uniqueName("vc"))
                .clientToken(UUID.randomUUID().toString())
                .containerProvider(p -> p.type(ContainerProviderType.EKS).id(EKS_CLUSTER)
                        .info(i -> i.eksInfo(e -> e.namespace("spark"))))
                .tags(Map.of("team", "data")));
        virtualClusterId = created.id();
        virtualClusterArn = created.arn();
    }

    @AfterAll
    static void cleanup() {
        if (emr == null) {
            return;
        }
        try {
            emr.deleteVirtualCluster(r -> r.id(virtualClusterId));
        }
        finally {
            emr.close();
        }
    }

    @Test
    @DisplayName("A virtual cluster is RUNNING and its timestamps parse")
    void describeVirtualCluster() {
        VirtualCluster cluster = emr.describeVirtualCluster(r -> r.id(virtualClusterId)).virtualCluster();

        assertThat(cluster.state()).isEqualTo(VirtualClusterState.RUNNING);
        assertThat(cluster.arn()).isEqualTo(virtualClusterArn).contains(":/virtualclusters/" + virtualClusterId);
        assertThat(cluster.containerProvider().info().eksInfo().namespace()).isEqualTo("spark");
        assertThat(cluster.createdAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.HOURS));
        assertThat(emr.listVirtualClusters(r -> r.states(VirtualClusterState.RUNNING)).virtualClusters())
                .extracting(VirtualCluster::id).contains(virtualClusterId);
    }

    @Test
    @DisplayName("A job run on an EKS cluster that does not exist FAILS with CLUSTER_UNAVAILABLE")
    void jobRunOnMissingEksCluster() {
        String runId = emr.startJobRun(r -> r
                .virtualClusterId(virtualClusterId)
                .name("pi")
                .clientToken(UUID.randomUUID().toString())
                .executionRoleArn(ROLE)
                .releaseLabel(RELEASE)
                .jobDriver(d -> d.sparkSubmitJobDriver(s -> s.entryPoint("s3://bucket/pi.py")))).id();

        JobRun run = emr.describeJobRun(r -> r.virtualClusterId(virtualClusterId).id(runId)).jobRun();

        assertThat(run.state()).isEqualTo(JobRunState.FAILED);
        assertThat(run.failureReason()).isEqualTo(FailureReason.CLUSTER_UNAVAILABLE);
        assertThat(run.stateDetails()).isEqualTo("Cluster " + EKS_CLUSTER + " does not exist.");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(emr.listJobRuns(r -> r.virtualClusterId(virtualClusterId).states(JobRunState.FAILED)).jobRuns())
                .extracting(JobRun::id).contains(runId);
    }

    @Test
    @DisplayName("A job template's parameters are filled in on StartJobRun")
    void jobTemplate() {
        String templateId = emr.createJobTemplate(r -> r
                .name(TestFixtures.uniqueName("template"))
                .clientToken(UUID.randomUUID().toString())
                .jobTemplateData(d -> d
                        .executionRoleArn(ROLE)
                        .releaseLabel(RELEASE)
                        .jobDriver(j -> j.sparkSubmitJobDriver(s -> s.entryPoint("${EntryPoint}")))
                        .parameterConfiguration(Map.of("EntryPoint",
                                TemplateParameterConfiguration.builder()
                                        .type(TemplateParameterDataType.STRING).build())))).id();
        try {
            String runId = emr.startJobRun(r -> r
                    .virtualClusterId(virtualClusterId)
                    .clientToken(UUID.randomUUID().toString())
                    .jobTemplateId(templateId)
                    .jobTemplateParameters(Map.of("EntryPoint", "s3://bucket/job.py"))).id();

            JobRun run = emr.describeJobRun(r -> r.virtualClusterId(virtualClusterId).id(runId)).jobRun();
            assertThat(run.jobDriver().sparkSubmitJobDriver().entryPoint()).isEqualTo("s3://bucket/job.py");
            assertThat(run.executionRoleArn()).isEqualTo(ROLE);
        }
        finally {
            emr.deleteJobTemplate(r -> r.id(templateId));
        }
    }

    @Test
    @DisplayName("TagResource, UntagResource and ListTagsForResource round trip on the virtual cluster ARN")
    void tags() {
        emr.tagResource(r -> r.resourceArn(virtualClusterArn).tags(Map.of("env", "dev")));
        assertThat(emr.listTagsForResource(r -> r.resourceArn(virtualClusterArn)).tags())
                .containsOnly(Map.entry("team", "data"), Map.entry("env", "dev"));

        emr.untagResource(r -> r.resourceArn(virtualClusterArn).tagKeys("env"));
        assertThat(emr.listTagsForResource(r -> r.resourceArn(virtualClusterArn)).tags())
                .containsOnly(Map.entry("team", "data"));
    }

    @Test
    @DisplayName("An unknown virtual cluster is ResourceNotFoundException")
    void unknownVirtualCluster() {
        assertThatThrownBy(() -> emr.describeVirtualCluster(r -> r.id("nosuchcluster")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Virtual cluster nosuchcluster doesn't exist.");
    }
}
