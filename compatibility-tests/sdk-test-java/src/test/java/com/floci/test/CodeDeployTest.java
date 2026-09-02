package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.AppSpecContent;
import software.amazon.awssdk.services.codedeploy.model.BatchGetApplicationsResponse;
import software.amazon.awssdk.services.codedeploy.model.BatchGetDeploymentGroupsResponse;
import software.amazon.awssdk.services.codedeploy.model.BatchGetDeploymentsResponse;
import software.amazon.awssdk.services.codedeploy.model.BatchGetDeploymentTargetsResponse;
import software.amazon.awssdk.services.codedeploy.model.ComputePlatform;
import software.amazon.awssdk.services.codedeploy.model.CreateApplicationResponse;
import software.amazon.awssdk.services.codedeploy.model.CreateDeploymentConfigResponse;
import software.amazon.awssdk.services.codedeploy.model.CreateDeploymentGroupResponse;
import software.amazon.awssdk.services.codedeploy.model.CreateDeploymentResponse;
import software.amazon.awssdk.services.codedeploy.model.DeploymentOption;
import software.amazon.awssdk.services.codedeploy.model.DeploymentReadyAction;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStatus;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStyle;
import software.amazon.awssdk.services.codedeploy.model.DeploymentType;
import software.amazon.awssdk.services.codedeploy.model.GetDeploymentConfigResponse;
import software.amazon.awssdk.services.codedeploy.model.GetDeploymentGroupResponse;
import software.amazon.awssdk.services.codedeploy.model.GetDeploymentResponse;
import software.amazon.awssdk.services.codedeploy.model.ListApplicationsResponse;
import software.amazon.awssdk.services.codedeploy.model.ListDeploymentConfigsResponse;
import software.amazon.awssdk.services.codedeploy.model.ListDeploymentGroupsResponse;
import software.amazon.awssdk.services.codedeploy.model.ListDeploymentTargetsResponse;
import software.amazon.awssdk.services.codedeploy.model.ListDeploymentsResponse;
import software.amazon.awssdk.services.codedeploy.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.codedeploy.model.MinimumHealthyHosts;
import software.amazon.awssdk.services.codedeploy.model.MinimumHealthyHostsType;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocation;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocationType;
import software.amazon.awssdk.services.codedeploy.model.Tag;
import software.amazon.awssdk.services.codedeploy.model.TrafficRoutingType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateAliasResponse;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetAliasResponse;
import software.amazon.awssdk.services.lambda.model.PublishVersionResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.UpdateAliasRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeDeployTest {

    static CodeDeployClient codedeploy;
    static LambdaClient lambda;
    static String appId;
    static String dgId;

    static final String DEPLOY_FUNCTION = "cd-deploy-fn";
    static final String DEPLOY_ALIAS = "live";
    static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    static String deploymentId;
    static String v1;
    static String v2;

    @BeforeAll
    static void setup() {
        codedeploy = TestFixtures.codeDeployClient();
        lambda = TestFixtures.lambdaClient();
    }

    @AfterAll
    static void teardown() {
        codedeploy.close();
        lambda.close();
    }

    @Test
    @Order(1)
    void builtInDeploymentConfigsExist() {
        ListDeploymentConfigsResponse resp = codedeploy.listDeploymentConfigs(r -> r.build());
        assertThat(resp.deploymentConfigsList())
                .contains("CodeDeployDefault.AllAtOnce",
                           "CodeDeployDefault.HalfAtATime",
                           "CodeDeployDefault.OneAtATime",
                           "CodeDeployDefault.LambdaAllAtOnce",
                           "CodeDeployDefault.ECSAllAtOnce");
    }

    @Test
    @Order(2)
    void getLambdaDeploymentConfig() {
        GetDeploymentConfigResponse resp = codedeploy.getDeploymentConfig(r -> r
                .deploymentConfigName("CodeDeployDefault.LambdaAllAtOnce"));

        assertThat(resp.deploymentConfigInfo().deploymentConfigName())
                .isEqualTo("CodeDeployDefault.LambdaAllAtOnce");
        assertThat(resp.deploymentConfigInfo().computePlatform()).isEqualTo(ComputePlatform.LAMBDA);
        assertThat(resp.deploymentConfigInfo().trafficRoutingConfig().type())
                .isEqualTo(TrafficRoutingType.ALL_AT_ONCE);
    }

    @Test
    @Order(3)
    void getLambdaCanaryConfig() {
        GetDeploymentConfigResponse resp = codedeploy.getDeploymentConfig(r -> r
                .deploymentConfigName("CodeDeployDefault.LambdaCanary10Percent5Minutes"));

        assertThat(resp.deploymentConfigInfo().computePlatform()).isEqualTo(ComputePlatform.LAMBDA);
        assertThat(resp.deploymentConfigInfo().trafficRoutingConfig().type())
                .isEqualTo(TrafficRoutingType.TIME_BASED_CANARY);
        assertThat(resp.deploymentConfigInfo().trafficRoutingConfig().timeBasedCanary().canaryPercentage())
                .isEqualTo(10);
    }

    @Test
    @Order(4)
    void createApplication() {
        CreateApplicationResponse resp = codedeploy.createApplication(r -> r
                .applicationName("sdk-lambda-app")
                .computePlatform(ComputePlatform.LAMBDA)
                .tags(Tag.builder().key("env").value("test").build()));

        assertThat(resp.applicationId()).isNotBlank();
        appId = resp.applicationId();
    }

    @Test
    @Order(5)
    void getApplication() {
        var resp = codedeploy.getApplication(r -> r.applicationName("sdk-lambda-app"));
        assertThat(resp.application().applicationName()).isEqualTo("sdk-lambda-app");
        assertThat(resp.application().computePlatform()).isEqualTo(ComputePlatform.LAMBDA);
        assertThat(resp.application().linkedToGitHub()).isFalse();
    }

    @Test
    @Order(6)
    void listApplications() {
        ListApplicationsResponse resp = codedeploy.listApplications(r -> r.build());
        assertThat(resp.applications()).contains("sdk-lambda-app");
    }

    @Test
    @Order(7)
    void batchGetApplications() {
        BatchGetApplicationsResponse resp = codedeploy.batchGetApplications(r -> r
                .applicationNames("sdk-lambda-app"));

        assertThat(resp.applicationsInfo()).hasSize(1);
        assertThat(resp.applicationsInfo().get(0).applicationName()).isEqualTo("sdk-lambda-app");
    }

    @Test
    @Order(8)
    void createDeploymentGroup() {
        CreateDeploymentGroupResponse resp = codedeploy.createDeploymentGroup(r -> r
                .applicationName("sdk-lambda-app")
                .deploymentGroupName("sdk-lambda-dg")
                .deploymentConfigName("CodeDeployDefault.LambdaAllAtOnce")
                .serviceRoleArn("arn:aws:iam::000000000000:role/codedeploy-role")
                .deploymentStyle(DeploymentStyle.builder()
                        .deploymentType(DeploymentType.BLUE_GREEN)
                        .deploymentOption(DeploymentOption.WITH_TRAFFIC_CONTROL)
                        .build()));

        assertThat(resp.deploymentGroupId()).isNotBlank();
        dgId = resp.deploymentGroupId();
    }

    @Test
    @Order(9)
    void getDeploymentGroup() {
        GetDeploymentGroupResponse resp = codedeploy.getDeploymentGroup(r -> r
                .applicationName("sdk-lambda-app")
                .deploymentGroupName("sdk-lambda-dg"));

        assertThat(resp.deploymentGroupInfo().deploymentGroupName()).isEqualTo("sdk-lambda-dg");
        assertThat(resp.deploymentGroupInfo().deploymentConfigName())
                .isEqualTo("CodeDeployDefault.LambdaAllAtOnce");
    }

    @Test
    @Order(10)
    void listDeploymentGroups() {
        ListDeploymentGroupsResponse resp = codedeploy.listDeploymentGroups(r -> r
                .applicationName("sdk-lambda-app"));

        assertThat(resp.applicationName()).isEqualTo("sdk-lambda-app");
        assertThat(resp.deploymentGroups()).contains("sdk-lambda-dg");
    }

    @Test
    @Order(11)
    void batchGetDeploymentGroups() {
        BatchGetDeploymentGroupsResponse resp = codedeploy.batchGetDeploymentGroups(r -> r
                .applicationName("sdk-lambda-app")
                .deploymentGroupNames("sdk-lambda-dg"));

        assertThat(resp.deploymentGroupsInfo()).hasSize(1);
        assertThat(resp.deploymentGroupsInfo().get(0).deploymentGroupName()).isEqualTo("sdk-lambda-dg");
    }

    @Test
    @Order(12)
    void createCustomDeploymentConfig() {
        CreateDeploymentConfigResponse resp = codedeploy.createDeploymentConfig(r -> r
                .deploymentConfigName("SdkTestConfig")
                .minimumHealthyHosts(MinimumHealthyHosts.builder()
                        .type(MinimumHealthyHostsType.FLEET_PERCENT)
                        .value(75)
                        .build())
                .computePlatform(ComputePlatform.SERVER));

        assertThat(resp.deploymentConfigId()).isNotBlank();
    }

    @Test
    @Order(13)
    void getCustomDeploymentConfig() {
        GetDeploymentConfigResponse resp = codedeploy.getDeploymentConfig(r -> r
                .deploymentConfigName("SdkTestConfig"));

        assertThat(resp.deploymentConfigInfo().deploymentConfigName()).isEqualTo("SdkTestConfig");
        assertThat(resp.deploymentConfigInfo().computePlatform()).isEqualTo(ComputePlatform.SERVER);
        assertThat(resp.deploymentConfigInfo().minimumHealthyHosts().type())
                .isEqualTo(MinimumHealthyHostsType.FLEET_PERCENT);
        assertThat(resp.deploymentConfigInfo().minimumHealthyHosts().value()).isEqualTo(75);
    }

    @Test
    @Order(14)
    void tagAndListTags() {
        String arn = "arn:aws:codedeploy:us-east-1:000000000000:application:sdk-lambda-app";
        codedeploy.tagResource(r -> r
                .resourceArn(arn)
                .tags(Tag.builder().key("team").value("platform").build(),
                      Tag.builder().key("project").value("floci").build()));

        ListTagsForResourceResponse resp = codedeploy.listTagsForResource(r -> r.resourceArn(arn));
        // env=test was added during createApplication, plus team and project here
        assertThat(resp.tags().stream().map(Tag::key)).contains("team", "project", "env");
    }

    @Test
    @Order(15)
    void untagResource() {
        String arn = "arn:aws:codedeploy:us-east-1:000000000000:application:sdk-lambda-app";
        codedeploy.untagResource(r -> r.resourceArn(arn).tagKeys("project"));

        ListTagsForResourceResponse resp = codedeploy.listTagsForResource(r -> r.resourceArn(arn));
        assertThat(resp.tags().stream().map(Tag::key)).doesNotContain("project");
        assertThat(resp.tags().stream().map(Tag::key)).contains("team", "env");
    }

    @Test
    @Order(16)
    void cannotDeleteBuiltInConfig() {
        assertThatThrownBy(() ->
                codedeploy.deleteDeploymentConfig(r -> r.deploymentConfigName("CodeDeployDefault.AllAtOnce")))
                .isInstanceOf(software.amazon.awssdk.services.codedeploy.model.InvalidDeploymentConfigNameException.class);
    }

    @Test
    @Order(17)
    void cleanup() {
        codedeploy.deleteDeploymentGroup(r -> r
                .applicationName("sdk-lambda-app")
                .deploymentGroupName("sdk-lambda-dg"));

        codedeploy.deleteDeploymentConfig(r -> r.deploymentConfigName("SdkTestConfig"));

        codedeploy.deleteApplication(r -> r.applicationName("sdk-lambda-app"));

        ListApplicationsResponse after = codedeploy.listApplications(r -> r.build());
        assertThat(after.applications()).doesNotContain("sdk-lambda-app");
    }

    // ---- Phase 2: Lambda deployment via CodeDeploy ----

    @Test
    @Order(20)
    void setupLambdaFunctionForDeployment() {
        // Create function and publish two versions, alias pointing to v1
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(DEPLOY_FUNCTION)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .build());

        PublishVersionResponse pv1 = lambda.publishVersion(r -> r.functionName(DEPLOY_FUNCTION));
        v1 = pv1.version();
        assertThat(v1).isNotBlank();

        // A second version needs a real change behind it. AWS does not publish a version when the
        // code and configuration have not moved since the last one, so two publishes in a row
        // return the same version. A blue/green deployment shifts between two different builds
        // anyway, so deploy one here rather than publishing the same bytes twice.
        lambda.updateFunctionCode(r -> r
                .functionName(DEPLOY_FUNCTION)
                .zipFile(SdkBytes.fromByteArray(LambdaUtils.handlerZip())));

        PublishVersionResponse pv2 = lambda.publishVersion(r -> r.functionName(DEPLOY_FUNCTION));
        v2 = pv2.version();
        assertThat(v2).isNotBlank().isNotEqualTo(v1);

        CreateAliasResponse alias = lambda.createAlias(r -> r
                .functionName(DEPLOY_FUNCTION)
                .name(DEPLOY_ALIAS)
                .functionVersion(v1));
        assertThat(alias.aliasArn()).contains(DEPLOY_FUNCTION);
    }

    @Test
    @Order(21)
    void setupCodeDeployAppAndGroupForDeployment() {
        codedeploy.createApplication(r -> r
                .applicationName("cd-lambda-app")
                .computePlatform(ComputePlatform.LAMBDA));

        codedeploy.createDeploymentGroup(r -> r
                .applicationName("cd-lambda-app")
                .deploymentGroupName("cd-lambda-dg")
                .deploymentConfigName("CodeDeployDefault.LambdaAllAtOnce")
                .serviceRoleArn("arn:aws:iam::000000000000:role/codedeploy-role"));
    }

    @Test
    @Order(22)
    void createDeployment_allAtOnce() {
        String appSpec = """
                {
                  "version": "0.0",
                  "Resources": [{
                    "myFunction": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Name": "%s",
                        "Alias": "%s",
                        "CurrentVersion": "%s",
                        "TargetVersion": "%s"
                      }
                    }
                  }]
                }
                """.formatted(DEPLOY_FUNCTION, DEPLOY_ALIAS, v1, v2);

        CreateDeploymentResponse resp = codedeploy.createDeployment(r -> r
                .applicationName("cd-lambda-app")
                .deploymentGroupName("cd-lambda-dg")
                .deploymentConfigName("CodeDeployDefault.LambdaAllAtOnce")
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
    @Order(23)
    void getDeployment_returnsInfo() {
        assertThat(deploymentId).isNotNull();

        GetDeploymentResponse resp = codedeploy.getDeployment(r -> r.deploymentId(deploymentId));
        assertThat(resp.deploymentInfo().deploymentId()).isEqualTo(deploymentId);
        assertThat(resp.deploymentInfo().applicationName()).isEqualTo("cd-lambda-app");
        assertThat(resp.deploymentInfo().deploymentGroupName()).isEqualTo("cd-lambda-dg");
        assertThat(resp.deploymentInfo().status()).isIn(
                DeploymentStatus.QUEUED, DeploymentStatus.IN_PROGRESS, DeploymentStatus.SUCCEEDED);
    }

    @Test
    @Order(24)
    void getDeployment_eventuallySucceeds() throws InterruptedException {
        assertThat(deploymentId).isNotNull();

        DeploymentStatus status = DeploymentStatus.QUEUED;
        for (int i = 0; i < 20 && !DeploymentStatus.SUCCEEDED.equals(status); i++) {
            Thread.sleep(500);
            GetDeploymentResponse resp = codedeploy.getDeployment(r -> r.deploymentId(deploymentId));
            status = resp.deploymentInfo().status();
        }
        assertThat(status).isEqualTo(DeploymentStatus.SUCCEEDED);
    }

    @Test
    @Order(25)
    void aliasPointsToTargetVersionAfterDeployment() {
        GetAliasResponse alias = lambda.getAlias(r -> r
                .functionName(DEPLOY_FUNCTION)
                .name(DEPLOY_ALIAS));
        assertThat(alias.functionVersion()).isEqualTo(v2);
        assertThat(alias.routingConfig()).isNull();
    }

    @Test
    @Order(26)
    void listDeployments_containsDeploymentId() {
        assertThat(deploymentId).isNotNull();

        ListDeploymentsResponse resp = codedeploy.listDeployments(r -> r
                .applicationName("cd-lambda-app")
                .deploymentGroupName("cd-lambda-dg"));
        assertThat(resp.deployments()).contains(deploymentId);
    }

    @Test
    @Order(27)
    void batchGetDeployments_returnsDeploymentInfo() {
        assertThat(deploymentId).isNotNull();

        BatchGetDeploymentsResponse resp = codedeploy.batchGetDeployments(r -> r
                .deploymentIds(deploymentId));
        assertThat(resp.deploymentsInfo()).hasSize(1);
        assertThat(resp.deploymentsInfo().get(0).deploymentId()).isEqualTo(deploymentId);
        assertThat(resp.deploymentsInfo().get(0).status()).isEqualTo(DeploymentStatus.SUCCEEDED);
    }

    @Test
    @Order(28)
    void listDeploymentTargets_returnsSingleTarget() {
        assertThat(deploymentId).isNotNull();

        ListDeploymentTargetsResponse resp = codedeploy.listDeploymentTargets(r -> r
                .deploymentId(deploymentId));
        assertThat(resp.targetIds()).hasSize(1);
        assertThat(resp.targetIds().get(0)).contains(DEPLOY_FUNCTION);
    }

    @Test
    @Order(29)
    void batchGetDeploymentTargets_returnsLambdaTarget() {
        assertThat(deploymentId).isNotNull();

        ListDeploymentTargetsResponse listResp = codedeploy.listDeploymentTargets(r -> r
                .deploymentId(deploymentId));
        List<String> targetIds = listResp.targetIds();

        BatchGetDeploymentTargetsResponse resp = codedeploy.batchGetDeploymentTargets(r -> r
                .deploymentId(deploymentId)
                .targetIds(targetIds));
        assertThat(resp.deploymentTargets()).hasSize(1);
        assertThat(resp.deploymentTargets().get(0).deploymentTargetTypeAsString())
                .isEqualTo("LambdaFunction");
        assertThat(resp.deploymentTargets().get(0).lambdaTarget()).isNotNull();
        assertThat(resp.deploymentTargets().get(0).lambdaTarget().statusAsString())
                .isEqualTo("Succeeded");
        assertThat(resp.deploymentTargets().get(0).lambdaTarget().lifecycleEvents())
                .anyMatch(e -> "AllowTraffic".equals(e.lifecycleEventName()));
    }

    @Test
    @Order(30)
    void cleanupDeployment() {
        lambda.deleteFunction(r -> r.functionName(DEPLOY_FUNCTION));
        codedeploy.deleteDeploymentGroup(r -> r
                .applicationName("cd-lambda-app")
                .deploymentGroupName("cd-lambda-dg"));
        codedeploy.deleteApplication(r -> r.applicationName("cd-lambda-app"));
    }
}
