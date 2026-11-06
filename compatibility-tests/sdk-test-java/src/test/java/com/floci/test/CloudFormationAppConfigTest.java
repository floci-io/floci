package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.GetApplicationResponse;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationProfileResponse;
import software.amazon.awssdk.services.appconfig.model.GetDeploymentStrategyResponse;
import software.amazon.awssdk.services.appconfig.model.GetEnvironmentResponse;
import software.amazon.awssdk.services.appconfig.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation AWS::AppConfig::*")
class CloudFormationAppConfigTest {

    private static final String ALARM = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";

    private static CloudFormationClient cfn;
    private static AppConfigClient appConfig;
    private String stackName;
    private boolean stackCreated;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        appConfig = TestFixtures.appConfigClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-appconfig");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        deleteStack();
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        appConfig.close();
    }

    @Test
    void createsTheFourTypesThatReadBackThroughTheAppConfigApiAndDeletesThemWithTheStack()
            throws InterruptedException {
        createStack(template("orders", "hosted"));

        String app = output("AppId");
        String env = output("EnvId");
        String profile = output("ProfileId");
        String strategy = output("StrategyId");
        assertThat(output("AppRef")).as("Ref is the application ID").isEqualTo(app);
        assertThat(output("EnvRef")).isEqualTo(env);
        assertThat(output("ProfileRef")).isEqualTo(profile);
        assertThat(output("StrategyRef")).isEqualTo(strategy);

        GetApplicationResponse application = appConfig.getApplication(r -> r.applicationId(app));
        assertThat(application.name()).isEqualTo("orders");
        assertThat(appConfig.listTagsForResource(r -> r.resourceArn(applicationArn(app))).tags())
                .isEqualTo(Map.of("team", "payments"));
        GetEnvironmentResponse environment = appConfig.getEnvironment(r -> r.applicationId(app).environmentId(env));
        assertThat(environment.name()).isEqualTo("beta");
        assertThat(environment.monitors()).singleElement()
                .satisfies(monitor -> assertThat(monitor.alarmArn()).isEqualTo(ALARM));
        GetConfigurationProfileResponse configurationProfile = appConfig.getConfigurationProfile(
                r -> r.applicationId(app).configurationProfileId(profile));
        assertThat(configurationProfile.name()).isEqualTo("settings");
        assertThat(configurationProfile.locationUri()).isEqualTo("hosted");
        assertThat(configurationProfile.type()).isEqualTo("AWS.Freeform");
        GetDeploymentStrategyResponse deploymentStrategy = appConfig.getDeploymentStrategy(
                r -> r.deploymentStrategyId(strategy));
        assertThat(deploymentStrategy.name()).isEqualTo("gradual");
        assertThat(deploymentStrategy.deploymentDurationInMinutes()).isEqualTo(10);
        assertThat(deploymentStrategy.growthFactor()).isEqualTo(20f);
        assertThat(deploymentStrategy.growthTypeAsString()).isEqualTo("EXPONENTIAL");

        deleteStack();
        assertThatThrownBy(() -> appConfig.getEnvironment(r -> r.applicationId(app).environmentId(env)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> appConfig.getConfigurationProfile(
                r -> r.applicationId(app).configurationProfileId(profile)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> appConfig.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> appConfig.getApplication(r -> r.applicationId(app)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUpdateRenamesTheApplicationInPlaceAndReplacesTheProfileWhoseLocationChanges()
            throws InterruptedException {
        createStack(template("orders", "hosted"));
        String app = output("AppId");
        String profile = output("ProfileId");

        cfn.updateStack(r -> r.stackName(stackName).templateBody(template("orders-v2", "ssm-parameter://settings")));
        awaitStatus("UPDATE_COMPLETE");

        assertThat(output("AppId")).as("the application is changed in place").isEqualTo(app);
        assertThat(appConfig.getApplication(r -> r.applicationId(app)).name()).isEqualTo("orders-v2");
        String replacement = output("ProfileId");
        assertThat(replacement).as("a LocationUri change replaces the profile").isNotEqualTo(profile);
        assertThat(appConfig.getConfigurationProfile(r -> r.applicationId(app).configurationProfileId(replacement))
                .locationUri()).isEqualTo("ssm-parameter://settings");
        assertThatThrownBy(() -> appConfig.getConfigurationProfile(
                r -> r.applicationId(app).configurationProfileId(profile)))
                .as("the displaced profile is deleted once the update commits")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static String template(String applicationName, String locationUri) {
        return """
                {
                  "Resources": {
                    "App": {"Type": "AWS::AppConfig::Application", "Properties": {
                      "Name": "%s", "Tags": [{"Key": "team", "Value": "payments"}]}},
                    "Env": {"Type": "AWS::AppConfig::Environment", "Properties": {
                      "ApplicationId": {"Ref": "App"}, "Name": "beta",
                      "Monitors": [{"AlarmArn": "%s"}]}},
                    "Profile": {"Type": "AWS::AppConfig::ConfigurationProfile", "Properties": {
                      "ApplicationId": {"Ref": "App"}, "Name": "settings", "LocationUri": "%s",
                      "Type": "AWS.Freeform"}},
                    "Strategy": {"Type": "AWS::AppConfig::DeploymentStrategy", "Properties": {
                      "Name": "gradual", "ReplicateTo": "NONE", "DeploymentDurationInMinutes": 10,
                      "GrowthFactor": 20, "GrowthType": "EXPONENTIAL"}}
                  },
                  "Outputs": {
                    "AppRef": {"Value": {"Ref": "App"}},
                    "AppId": {"Value": {"Fn::GetAtt": ["App", "ApplicationId"]}},
                    "EnvRef": {"Value": {"Ref": "Env"}},
                    "EnvId": {"Value": {"Fn::GetAtt": ["Env", "EnvironmentId"]}},
                    "ProfileRef": {"Value": {"Ref": "Profile"}},
                    "ProfileId": {"Value": {"Fn::GetAtt": ["Profile", "ConfigurationProfileId"]}},
                    "StrategyRef": {"Value": {"Ref": "Strategy"}},
                    "StrategyId": {"Value": {"Fn::GetAtt": ["Strategy", "Id"]}}
                  }
                }
                """.formatted(applicationName, ALARM, locationUri);
    }

    private static String applicationArn(String applicationId) {
        return "arn:aws:appconfig:" + TestFixtures.region().id() + ":000000000000:application/" + applicationId;
    }

    private void createStack(String template) throws InterruptedException {
        cfn.createStack(r -> r.stackName(stackName).templateBody(template));
        stackCreated = true;
        awaitStatus("CREATE_COMPLETE");
    }

    private String output(String key) {
        return cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0).outputs().stream()
                .filter(o -> key.equals(o.outputKey())).map(Output::outputValue)
                .findFirst().orElseThrow();
    }

    /** Waits for {@code expected}, failing as soon as the stack settles on any other status. */
    private void awaitStatus(String expected) throws InterruptedException {
        TestFixtures.await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (!expected.equals(status) && !status.endsWith("_IN_PROGRESS")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return expected.equals(status);
        }, stackName, expected, 60_000_000_000L, 100);
    }

    private void deleteStack() throws InterruptedException {
        if (!stackCreated) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackName));
        TestFixtures.await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
                if (stacks.isEmpty()) {
                    return true;
                }
                Stack stack = stacks.get(0);
                if ("DELETE_FAILED".equals(stack.stackStatusAsString())) {
                    throw new AssertionError(stackName + " deletion failed: " + stack.stackStatusReason());
                }
                return "DELETE_COMPLETE".equals(stack.stackStatusAsString());
            } catch (CloudFormationException e) {
                if ("ValidationError".equals(e.awsErrorDetails().errorCode())
                        && e.getMessage().contains("does not exist")) {
                    return true;
                }
                throw e;
            }
        }, stackName, "stack deletion", 60_000_000_000L, 100);
        stackCreated = false;
    }
}
