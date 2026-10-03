package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.AppSpecContent;
import software.amazon.awssdk.services.codedeploy.model.BatchGetDeploymentTargetsResponse;
import software.amazon.awssdk.services.codedeploy.model.ComputePlatform;
import software.amazon.awssdk.services.codedeploy.model.CreateApplicationResponse;
import software.amazon.awssdk.services.codedeploy.model.CreateDeploymentGroupResponse;
import software.amazon.awssdk.services.codedeploy.model.CreateDeploymentResponse;
import software.amazon.awssdk.services.codedeploy.model.DeploymentOption;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStatus;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStyle;
import software.amazon.awssdk.services.codedeploy.model.DeploymentTarget;
import software.amazon.awssdk.services.codedeploy.model.DeploymentType;
import software.amazon.awssdk.services.codedeploy.model.ECSService;
import software.amazon.awssdk.services.codedeploy.model.GetDeploymentResponse;
import software.amazon.awssdk.services.codedeploy.model.ListDeploymentTargetsResponse;
import software.amazon.awssdk.services.codedeploy.model.LoadBalancerInfo;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocation;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocationType;
import software.amazon.awssdk.services.codedeploy.model.TargetGroupInfo;
import software.amazon.awssdk.services.codedeploy.model.TargetGroupPairInfo;
import software.amazon.awssdk.services.codedeploy.model.TrafficRoute;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.CreateClusterResponse;
import software.amazon.awssdk.services.ecs.model.CreateServiceResponse;
import software.amazon.awssdk.services.ecs.model.DeploymentControllerType;
import software.amazon.awssdk.services.ecs.model.DescribeTaskSetsResponse;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateListenerResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateLoadBalancerResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.IpAddressType;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compatibility tests for CodeDeploy ECS blue/green deployments.
 *
 * Verifies the full lifecycle: ALB + blue/green TG setup → ECS cluster/service setup →
 * CodeDeploy ECS app/group → deployment → traffic shifts to green TG.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeDeployEcsTest {

    static CodeDeployClient codedeploy;
    static EcsClient ecs;
    static ElasticLoadBalancingV2Client elb;

    static final String CLUSTER = "cd-ecs-cluster";
    static final String SERVICE = "cd-ecs-service";
    static final String TASK_FAMILY = "cd-ecs-task";
    static final String LB_NAME = "cd-ecs-alb";
    static final String BLUE_TG = "cd-blue-tg";
    static final String GREEN_TG = "cd-green-tg";
    static final String APP_NAME = "cd-ecs-app";
    static final String DG_NAME = "cd-ecs-dg";
    static final String ROLE = "arn:aws:iam::000000000000:role/codedeploy-role";

    static String lbArn;
    static String blueTgArn;
    static String greenTgArn;
    static String listenerArn;
    static String taskDefArn;
    static String deploymentId;

    @BeforeAll
    static void setup() {
        codedeploy = TestFixtures.codeDeployClient();
        ecs = TestFixtures.ecsClient();
        elb = TestFixtures.elbV2Client();
    }

    @AfterAll
    static void teardown() {
        try { codedeploy.deleteDeploymentGroup(r -> r.applicationName(APP_NAME).deploymentGroupName(DG_NAME)); } catch (Exception ignored) {}
        try { codedeploy.deleteApplication(r -> r.applicationName(APP_NAME)); } catch (Exception ignored) {}
        try { ecs.deleteService(r -> r.cluster(CLUSTER).service(SERVICE).force(true)); } catch (Exception ignored) {}
        try { ecs.deleteCluster(r -> r.cluster(CLUSTER)); } catch (Exception ignored) {}
        try {
            if (listenerArn != null) { elb.deleteListener(r -> r.listenerArn(listenerArn)); }
            if (blueTgArn != null)   { elb.deleteTargetGroup(r -> r.targetGroupArn(blueTgArn)); }
            if (greenTgArn != null)  { elb.deleteTargetGroup(r -> r.targetGroupArn(greenTgArn)); }
            if (lbArn != null)       { elb.deleteLoadBalancer(r -> r.loadBalancerArn(lbArn)); }
        } catch (Exception ignored) {}
        codedeploy.close();
        ecs.close();
        elb.close();
    }

    // ── ELB v2 setup ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createLoadBalancer() {
        CreateLoadBalancerResponse resp = elb.createLoadBalancer(r -> r
                .name(LB_NAME)
                .type(LoadBalancerTypeEnum.APPLICATION)
                .ipAddressType(IpAddressType.IPV4));

        assertThat(resp.loadBalancers()).hasSize(1);
        lbArn = resp.loadBalancers().get(0).loadBalancerArn();
        assertThat(lbArn).isNotBlank();
    }

    @Test
    @Order(2)
    void createBlueTargetGroup() {
        CreateTargetGroupResponse resp = elb.createTargetGroup(r -> r
                .name(BLUE_TG)
                .protocol(ProtocolEnum.HTTP)
                .port(80)
                .vpcId("vpc-00000001"));

        assertThat(resp.targetGroups()).hasSize(1);
        blueTgArn = resp.targetGroups().get(0).targetGroupArn();
        assertThat(blueTgArn).isNotBlank();
    }

    @Test
    @Order(3)
    void createGreenTargetGroup() {
        CreateTargetGroupResponse resp = elb.createTargetGroup(r -> r
                .name(GREEN_TG)
                .protocol(ProtocolEnum.HTTP)
                .port(80)
                .vpcId("vpc-00000001"));

        assertThat(resp.targetGroups()).hasSize(1);
        greenTgArn = resp.targetGroups().get(0).targetGroupArn();
        assertThat(greenTgArn).isNotBlank().isNotEqualTo(blueTgArn);
    }

    @Test
    @Order(4)
    void createListenerPointingToBlue() {
        assertThat(lbArn).isNotNull();
        assertThat(blueTgArn).isNotNull();

        CreateListenerResponse resp = elb.createListener(r -> r
                .loadBalancerArn(lbArn)
                .protocol(ProtocolEnum.HTTP)
                .port(18090)
                .defaultActions(software.amazon.awssdk.services.elasticloadbalancingv2.model.Action.builder()
                        .type(ActionTypeEnum.FORWARD)
                        .targetGroupArn(blueTgArn)
                        .build()));

        assertThat(resp.listeners()).hasSize(1);
        listenerArn = resp.listeners().get(0).listenerArn();
        assertThat(listenerArn).isNotBlank();
    }

    @Test
    @Order(5)
    void verifyListenerInitiallyPointsToBlue() {
        assertThat(listenerArn).isNotNull();

        DescribeRulesResponse resp = elb.describeRules(r -> r.listenerArn(listenerArn));
        Optional<Rule> defaultRule = resp.rules().stream()
                .filter(rule -> rule.isDefault())
                .findFirst();
        assertThat(defaultRule).isPresent();
        Optional<Action> forwardAction = defaultRule.get().actions().stream()
                .filter(a -> ActionTypeEnum.FORWARD.equals(a.type()))
                .findFirst();
        assertThat(forwardAction).isPresent();
        assertThat(forwardAction.get().targetGroupArn()).isEqualTo(blueTgArn);
    }

    // ── ECS setup ────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void createEcsCluster() {
        CreateClusterResponse resp = ecs.createCluster(r -> r.clusterName(CLUSTER));
        assertThat(resp.cluster().clusterName()).isEqualTo(CLUSTER);
        assertThat(resp.cluster().status()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(7)
    void registerTaskDefinition() {
        RegisterTaskDefinitionResponse resp = ecs.registerTaskDefinition(r -> r
                .family(TASK_FAMILY)
                .containerDefinitions(cd -> cd
                        .name("app")
                        .image("nginx:latest")
                        .portMappings(pm -> pm.containerPort(80))));

        assertThat(resp.taskDefinition().family()).isEqualTo(TASK_FAMILY);
        taskDefArn = resp.taskDefinition().taskDefinitionArn();
        assertThat(taskDefArn).isNotBlank();
    }

    @Test
    @Order(8)
    void createEcsService() {
        assertThat(taskDefArn).isNotNull();

        CreateServiceResponse resp = ecs.createService(r -> r
                .cluster(CLUSTER)
                .serviceName(SERVICE)
                .taskDefinition(TASK_FAMILY + ":1")
                .desiredCount(1)
                .deploymentController(dc -> dc.type(DeploymentControllerType.EXTERNAL)));

        assertThat(resp.service().serviceName()).isEqualTo(SERVICE);
    }

    // ── CodeDeploy ECS deployment ─────────────────────────────────────────────

    @Test
    @Order(9)
    void createEcsApplication() {
        CreateApplicationResponse resp = codedeploy.createApplication(r -> r
                .applicationName(APP_NAME)
                .computePlatform(ComputePlatform.ECS));

        assertThat(resp.applicationId()).isNotBlank();
    }

    @Test
    @Order(10)
    void createEcsDeploymentGroup() {
        assertThat(listenerArn).isNotNull();
        assertThat(blueTgArn).isNotNull();
        assertThat(greenTgArn).isNotNull();

        CreateDeploymentGroupResponse resp = codedeploy.createDeploymentGroup(r -> r
                .applicationName(APP_NAME)
                .deploymentGroupName(DG_NAME)
                .deploymentConfigName("CodeDeployDefault.ECSAllAtOnce")
                .serviceRoleArn(ROLE)
                .deploymentStyle(DeploymentStyle.builder()
                        .deploymentType(DeploymentType.BLUE_GREEN)
                        .deploymentOption(DeploymentOption.WITH_TRAFFIC_CONTROL)
                        .build())
                .ecsServices(ECSService.builder()
                        .clusterName(CLUSTER)
                        .serviceName(SERVICE)
                        .build())
                .loadBalancerInfo(LoadBalancerInfo.builder()
                        .targetGroupPairInfoList(TargetGroupPairInfo.builder()
                                .targetGroups(
                                        TargetGroupInfo.builder().name(BLUE_TG).build(),
                                        TargetGroupInfo.builder().name(GREEN_TG).build())
                                .prodTrafficRoute(TrafficRoute.builder()
                                        .listenerArns(listenerArn)
                                        .build())
                                .build())
                        .build()));

        assertThat(resp.deploymentGroupId()).isNotBlank();
    }

    @Test
    @Order(11)
    void createEcsDeployment_allAtOnce() {
        assertThat(taskDefArn).isNotNull();

        String appSpec = """
                {
                  "version": 0.0,
                  "Resources": [{
                    "TargetService": {
                      "Type": "AWS::ECS::Service",
                      "Properties": {
                        "TaskDefinition": "%s",
                        "LoadBalancerInfo": {
                          "ContainerName": "app",
                          "ContainerPort": 80
                        }
                      }
                    }
                  }]
                }
                """.formatted(taskDefArn);

        CreateDeploymentResponse resp = codedeploy.createDeployment(r -> r
                .applicationName(APP_NAME)
                .deploymentGroupName(DG_NAME)
                .deploymentConfigName("CodeDeployDefault.ECSAllAtOnce")
                .revision(RevisionLocation.builder()
                        .revisionType(RevisionLocationType.APP_SPEC_CONTENT)
                        .appSpecContent(AppSpecContent.builder()
                                .content(appSpec)
                                .build())
                        .build()));

        assertThat(resp.deploymentId()).startsWith("d-");
        deploymentId = resp.deploymentId();
    }

    @Test
    @Order(12)
    void getDeployment_returnsEcsComputePlatform() {
        assertThat(deploymentId).isNotNull();

        GetDeploymentResponse resp = codedeploy.getDeployment(r -> r.deploymentId(deploymentId));
        assertThat(resp.deploymentInfo().deploymentId()).isEqualTo(deploymentId);
        assertThat(resp.deploymentInfo().applicationName()).isEqualTo(APP_NAME);
        assertThat(resp.deploymentInfo().computePlatformAsString()).isEqualTo("ECS");
    }

    @Test
    @Order(13)
    void deployment_eventuallySucceeds() throws InterruptedException {
        assertThat(deploymentId).isNotNull();

        DeploymentStatus status = DeploymentStatus.QUEUED;
        for (int i = 0; i < 30 && !DeploymentStatus.SUCCEEDED.equals(status)
                && !DeploymentStatus.FAILED.equals(status); i++) {
            Thread.sleep(500);
            GetDeploymentResponse resp = codedeploy.getDeployment(r -> r.deploymentId(deploymentId));
            status = resp.deploymentInfo().status();
        }
        assertThat(status).isEqualTo(DeploymentStatus.SUCCEEDED);
    }

    @Test
    @Order(14)
    void listDeploymentTargets_containsEcsTarget() {
        assertThat(deploymentId).isNotNull();

        ListDeploymentTargetsResponse resp = codedeploy.listDeploymentTargets(r -> r
                .deploymentId(deploymentId));

        assertThat(resp.targetIds()).hasSize(1);
        assertThat(resp.targetIds().get(0)).contains(CLUSTER);
    }

    @Test
    @Order(15)
    void batchGetDeploymentTargets_returnsEcsTargetWithSucceededStatus() {
        assertThat(deploymentId).isNotNull();

        List<String> targetIds = codedeploy.listDeploymentTargets(r -> r
                .deploymentId(deploymentId)).targetIds();

        BatchGetDeploymentTargetsResponse resp = codedeploy.batchGetDeploymentTargets(r -> r
                .deploymentId(deploymentId)
                .targetIds(targetIds));

        assertThat(resp.deploymentTargets()).hasSize(1);
        DeploymentTarget target = resp.deploymentTargets().get(0);
        assertThat(target.deploymentTargetTypeAsString()).isEqualTo("ECSTarget");
        assertThat(target.ecsTarget()).isNotNull();
        assertThat(target.ecsTarget().statusAsString()).isEqualTo("Succeeded");
        assertThat(target.ecsTarget().lifecycleEvents())
                .anyMatch(e -> "Install".equals(e.lifecycleEventName()))
                .anyMatch(e -> "AllowTraffic".equals(e.lifecycleEventName()));
    }

    @Test
    @Order(16)
    void listenerNowPointsToGreen_afterDeployment() {
        assertThat(listenerArn).isNotNull();
        assertThat(greenTgArn).isNotNull();

        DescribeRulesResponse resp = elb.describeRules(r -> r.listenerArn(listenerArn));
        Optional<Rule> defaultRule = resp.rules().stream()
                .filter(rule -> rule.isDefault())
                .findFirst();

        assertThat(defaultRule).isPresent();
        Optional<Action> forwardAction = defaultRule.get().actions().stream()
                .filter(a -> ActionTypeEnum.FORWARD.equals(a.type()))
                .findFirst();
        assertThat(forwardAction).isPresent();
        assertThat(forwardAction.get().targetGroupArn()).isEqualTo(greenTgArn);
    }

    @Test
    @Order(17)
    void ecsTaskSetCreatedForGreenDeployment() {
        // The green task set should exist and be PRIMARY after deployment
        DescribeTaskSetsResponse taskSets = ecs.describeTaskSets(r -> r
                .cluster(CLUSTER)
                .service(SERVICE));

        assertThat(taskSets.taskSets())
                .anyMatch(ts -> "PRIMARY".equals(ts.status()));
    }

    // ── Canary deployment variant ─────────────────────────────────────────────

    @Test
    @Order(20)
    void createCanaryDeployment() {
        String appSpec = """
                {
                  "version": 0.0,
                  "Resources": [{
                    "TargetService": {
                      "Type": "AWS::ECS::Service",
                      "Properties": {
                        "TaskDefinition": "%s",
                        "LoadBalancerInfo": {
                          "ContainerName": "app",
                          "ContainerPort": 80
                        }
                      }
                    }
                  }]
                }
                """.formatted(taskDefArn);

        CreateDeploymentResponse resp = codedeploy.createDeployment(r -> r
                .applicationName(APP_NAME)
                .deploymentGroupName(DG_NAME)
                .deploymentConfigName("CodeDeployDefault.ECSCanary10Percent5Minutes")
                .revision(RevisionLocation.builder()
                        .revisionType(RevisionLocationType.APP_SPEC_CONTENT)
                        .appSpecContent(AppSpecContent.builder().content(appSpec).build())
                        .build()));

        assertThat(resp.deploymentId()).startsWith("d-");
        String canaryDeploymentId = resp.deploymentId();

        // Poll until done (Floci caps wait times so this completes quickly)
        DeploymentStatus status = DeploymentStatus.QUEUED;
        try {
            for (int i = 0; i < 40 && !DeploymentStatus.SUCCEEDED.equals(status)
                    && !DeploymentStatus.FAILED.equals(status); i++) {
                Thread.sleep(500);
                GetDeploymentResponse gr = codedeploy.getDeployment(r -> r.deploymentId(canaryDeploymentId));
                status = gr.deploymentInfo().status();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(status).isEqualTo(DeploymentStatus.SUCCEEDED);
    }
}
