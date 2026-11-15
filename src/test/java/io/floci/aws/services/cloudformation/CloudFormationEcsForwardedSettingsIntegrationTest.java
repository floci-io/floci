package io.floci.aws.services.cloudformation;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.floci.aws.testing.CfnRequests.cfn;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The service and task definition settings an ECS stack declares reach ECS, so DescribeServices
 * and DescribeTaskDefinition report them. Measured on AWS (eu-west-1) with this template's
 * shape: a value a {@code String} parameter gives an {@code AWS::ECS::Service} comes back typed,
 * {@code maximumPercent} 150, {@code enable} true and {@code weight} 2.
 */
@QuarkusTest
class CloudFormationEcsForwardedSettingsIntegrationTest {

    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113";

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "MaxPercent": {"Type": "String", "Default": "150"},
                "Flag": {"Type": "String", "Default": "true"},
                "Weight": {"Type": "String", "Default": "2"},
                "Arch": {"Type": "String", "Default": "ARM64"},
                "Os": {"Type": "String", "Default": "LINUX"}
              },
              "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
              "Resources": {
                "Namespace": {
                  "Type": "AWS::ServiceDiscovery::HttpNamespace",
                  "Properties": {"Name": "%1$s"}
                },
                "Registry": {
                  "Type": "AWS::ServiceDiscovery::Service",
                  "Properties": {"Name": "app", "NamespaceId": {"Ref": "Namespace"}}
                },
                "Cluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": {"ClusterName": "%1$s"}
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "%1$s-td",
                    "Cpu": "256",
                    "Memory": "512",
                    "NetworkMode": "awsvpc",
                    "RequiresCompatibilities": ["FARGATE"],
                    "RuntimePlatform": {"CpuArchitecture": {"Ref": "Arch"}, "OperatingSystemFamily": {"Ref": "Os"}},
                    "ContainerDefinitions": [{
                      "Name": "app",
                      "Image": "app:v1",
                      "User": "1000",
                      "HealthCheck": {"Command": ["CMD-SHELL", "true"], "Interval": 30, "Retries": 3}
                    }]
                  }
                },
                "Service": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": "%1$s-svc",
                    "Cluster": {"Ref": "Cluster"},
                    "TaskDefinition": {"Ref": "TaskDef"},
                    "DesiredCount": 1,
                    "CapacityProviderStrategy": [{"CapacityProvider": "FARGATE", "Weight": {"Ref": "Weight"}}],
                    "DeploymentConfiguration": {
                      "MaximumPercent": {"Ref": "MaxPercent"},
                      "MinimumHealthyPercent": 0,
                      "DeploymentCircuitBreaker": {
                        "Enable": {"Ref": "Flag"},
                        "Rollback": true,
                        "ResetOnHealthyTask": false,
                        "ThresholdConfiguration": {"Type": "COUNT", "Value": 3}
                      },
                      "Alarms": {"Fn::Select": [0, [{"AlarmNames": ["high-errors"], "Enable": false, "Rollback": false}]]}
                    },
                    "ServiceRegistries": [{"Fn::Select": [0, [{
                      "RegistryArn": {"Fn::GetAtt": ["Registry", "Arn"]},
                      "Port": {"Fn::If": ["Never", 80, {"Ref": "AWS::NoValue"}]}
                    }]]}],
                    "NetworkConfiguration": {"AwsvpcConfiguration": {"Subnets": ["%2$s"]}}
                  }
                }
              }
            }
            """;

    private static final String REGISTRIES_SETTING =
            "\"ServiceRegistries\": [{\"RegistryArn\": {\"Fn::GetAtt\": [\"Registry\", \"Arn\"]}}]";
    private static final String STRATEGY_SETTING =
            "\"CapacityProviderStrategy\": [{\"CapacityProvider\": \"FARGATE\", \"Weight\": 2}]";
    private static final String DEPLOYMENT_SETTING =
            "\"DeploymentConfiguration\": {\"MaximumPercent\": 150, \"MinimumHealthyPercent\": 0}";
    private static final String NO_VALUE_DEPLOYMENT_SETTING = "\"DeploymentConfiguration\": "
            + "{\"Fn::If\": [\"Never\", {\"MaximumPercent\": 150}, {\"Ref\": \"AWS::NoValue\"}]}";
    private static final String NO_VALUE_REGISTRIES_SETTING = "\"ServiceRegistries\": "
            + "{\"Fn::If\": [\"Never\", [{\"RegistryArn\": {\"Fn::GetAtt\": [\"Registry\", \"Arn\"]}}], {\"Ref\": \"AWS::NoValue\"}]}";
    private static final String NO_VALUE_STRATEGY_SETTING = "\"CapacityProviderStrategy\": "
            + "{\"Fn::If\": [\"Never\", [{\"CapacityProvider\": \"FARGATE\", \"Weight\": 2}], {\"Ref\": \"AWS::NoValue\"}]}";
    private static final String OUTSIDE_REGISTRY_ARN = "arn:aws:servicediscovery:us-east-1:000000000000:service/srv-9";

    /** A service that declares only the settings spliced into %4$s, so a test controls which of the three it declares. */
    private static final String SERVICE_TEMPLATE = """
            {
              "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
              "Resources": {
                "Namespace": {
                  "Type": "AWS::ServiceDiscovery::HttpNamespace",
                  "Properties": {"Name": "%1$s"}
                },
                "Registry": {
                  "Type": "AWS::ServiceDiscovery::Service",
                  "Properties": {"Name": "app", "NamespaceId": {"Ref": "Namespace"}}
                },
                "Cluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": {"ClusterName": "%1$s"}
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "%1$s-td",
                    "Cpu": "256",
                    "Memory": "512",
                    "NetworkMode": "awsvpc",
                    "RequiresCompatibilities": ["FARGATE"],
                    "ContainerDefinitions": [{"Name": "app", "Image": "app:v1"}]
                  }
                },
                "Service": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": "%1$s-svc",
                    "Cluster": {"Ref": "Cluster"},
                    "TaskDefinition": {"Ref": "TaskDef"},
                    "DesiredCount": %3$d,
                    "NetworkConfiguration": {"AwsvpcConfiguration": {"Subnets": ["%2$s"]}}%4$s
                  }
                }
              }
            }
            """;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStack_ecsServiceAndTaskDefinition_reportTheDeclaredSettings() {
        String name = stackName();
        submit("CreateStack", name, null);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        ecs("DescribeTaskDefinition", "{\"taskDefinition\": \"" + name + "-td\"}")
            .body("taskDefinition.runtimePlatform.cpuArchitecture", equalTo("ARM64"))
            .body("taskDefinition.runtimePlatform.operatingSystemFamily", equalTo("LINUX"))
            .body("taskDefinition.containerDefinitions[0].user", equalTo("1000"))
            .body("taskDefinition.containerDefinitions[0].healthCheck.command", equalTo(List.of("CMD-SHELL", "true")))
            .body("taskDefinition.containerDefinitions[0].healthCheck.interval", equalTo(30))
            .body("taskDefinition.containerDefinitions[0].healthCheck.retries", equalTo(3));
        describeService(name)
            .body("services[0].deploymentConfiguration.maximumPercent", equalTo(150))
            .body("services[0].deploymentConfiguration.minimumHealthyPercent", equalTo(0))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.enable", equalTo(true))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.rollback", equalTo(true))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.resetOnHealthyTask", equalTo(false))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.thresholdConfiguration.type",
                    equalTo("COUNT"))
            .body("services[0].deploymentConfiguration.deploymentCircuitBreaker.thresholdConfiguration.value",
                    equalTo(3))
            .body("services[0].deploymentConfiguration.alarms.alarmNames", equalTo(List.of("high-errors")))
            .body("services[0].capacityProviderStrategy[0].capacityProvider", equalTo("FARGATE"))
            .body("services[0].capacityProviderStrategy[0].weight", equalTo(2))
            .body("services[0].capacityProviderStrategy[0].base", equalTo(0))
            .body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:us-east-1:"))
            .body("services[0].serviceRegistries[0].port", nullValue());
    }

    @Test
    void updateStack_ecsService_appliesAChangedSettingAndKeepsTheOthers() {
        String name = stackName();
        submit("CreateStack", name, null);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        submit("UpdateStack", name, "200");
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        describeService(name)
            .body("services[0].deploymentConfiguration.maximumPercent", equalTo(200))
            .body("services[0].capacityProviderStrategy[0].weight", equalTo(2))
            .body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:us-east-1:"));
    }

    @Test
    void updateStack_droppedServiceRegistries_keepTheStoredRegistries() {
        String name = stackName();
        createServiceStack(name, REGISTRIES_SETTING, STRATEGY_SETTING, DEPLOYMENT_SETTING);
        describeService(name).body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:"));

        updateServiceStack(name, 1, STRATEGY_SETTING, DEPLOYMENT_SETTING);

        describeService(name)
            .body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:"))
            .body("services[0].capacityProviderStrategy[0].weight", equalTo(2))
            .body("services[0].deploymentConfiguration.maximumPercent", equalTo(150));
    }

    @Test
    void updateStack_droppedCapacityProviderStrategy_keepTheStoredStrategy() {
        String name = stackName();
        createServiceStack(name, REGISTRIES_SETTING, STRATEGY_SETTING, DEPLOYMENT_SETTING);

        updateServiceStack(name, 1, REGISTRIES_SETTING, DEPLOYMENT_SETTING);

        describeService(name)
            .body("services[0].capacityProviderStrategy[0].capacityProvider", equalTo("FARGATE"))
            .body("services[0].capacityProviderStrategy[0].weight", equalTo(2))
            .body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:"))
            .body("services[0].deploymentConfiguration.maximumPercent", equalTo(150));
    }

    @Test
    void updateStack_droppedDeploymentConfiguration_isReadBackAsNeverSet() {
        String name = stackName();
        createServiceStack(name, REGISTRIES_SETTING, STRATEGY_SETTING, DEPLOYMENT_SETTING);

        updateServiceStack(name, 1, REGISTRIES_SETTING, STRATEGY_SETTING);

        describeService(name)
            .body("services[0]", not(hasKey("deploymentConfiguration")))
            .body("services[0].capacityProviderStrategy[0].weight", equalTo(2))
            .body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:"));
    }

    @Test
    void updateStack_deploymentConfigurationChosenAsNoValue_isReadBackAsNeverSet() {
        String name = stackName();
        createServiceStack(name, DEPLOYMENT_SETTING);

        updateServiceStack(name, 1, NO_VALUE_DEPLOYMENT_SETTING);

        describeService(name).body("services[0]", not(hasKey("deploymentConfiguration")));
    }

    @Test
    void updateStack_serviceRegistriesChosenAsNoValue_keepTheStoredRegistries() {
        String name = stackName();
        createServiceStack(name, REGISTRIES_SETTING);

        updateServiceStack(name, 1, NO_VALUE_REGISTRIES_SETTING);

        describeService(name).body("services[0].serviceRegistries[0].registryArn", containsString(":servicediscovery:"));
    }

    @Test
    void updateStack_capacityProviderStrategyChosenAsNoValue_keepTheStoredStrategy() {
        String name = stackName();
        createServiceStack(name, STRATEGY_SETTING);

        updateServiceStack(name, 1, NO_VALUE_STRATEGY_SETTING);

        describeService(name)
            .body("services[0].capacityProviderStrategy[0].capacityProvider", equalTo("FARGATE"))
            .body("services[0].capacityProviderStrategy[0].weight", equalTo(2));
    }

    @Test
    void updateStack_strategyDeclaredDirectlyAsNoValue_keepsTheStoredStrategy() {
        String name = stackName();
        createServiceStack(name, STRATEGY_SETTING);

        updateServiceStack(name, 1, "\"CapacityProviderStrategy\": {\"Ref\": \"AWS::NoValue\"}");

        describeService(name).body("services[0].capacityProviderStrategy[0].weight", equalTo(2));
    }

    @Test
    void updateStack_emptyStrategyAndEmptyRegistries_clearBoth() {
        String name = stackName();
        createServiceStack(name, REGISTRIES_SETTING, STRATEGY_SETTING);

        updateServiceStack(name, 1, "\"ServiceRegistries\": []", "\"CapacityProviderStrategy\": []");

        describeService(name)
            .body("services[0].serviceRegistries", nullValue())
            .body("services[0].capacityProviderStrategy", nullValue())
            .body("services[0].launchType", equalTo("FARGATE"));
    }

    @Test
    void updateStack_emptyDeploymentConfiguration_isStoredAsGivenAndClearedWhenDroppedLater() {
        String name = stackName();
        createServiceStack(name, DEPLOYMENT_SETTING);

        updateServiceStack(name, 1, "\"DeploymentConfiguration\": {}");
        describeService(name).body("services[0].deploymentConfiguration", equalTo(Map.of()));

        updateServiceStack(name, 1);
        describeService(name).body("services[0]", not(hasKey("deploymentConfiguration")));
    }

    @Test
    void updateStack_settingsSetThroughTheApi_surviveAnUpdateThatStillDoesNotDeclareThem() {
        String name = stackName();
        createServiceStack(name);
        ecs("UpdateService", """
                {"cluster": "%1$s", "service": "%1$s-svc",
                 "deploymentConfiguration": {"maximumPercent": 111},
                 "capacityProviderStrategy": [{"capacityProvider": "FARGATE_SPOT", "weight": 1}],
                 "serviceRegistries": [{"registryArn": "%2$s"}]}
                """.formatted(name, OUTSIDE_REGISTRY_ARN));

        updateServiceStack(name, 2);

        describeService(name)
            .body("services[0].desiredCount", equalTo(2))
            .body("services[0].deploymentConfiguration.maximumPercent", equalTo(111))
            .body("services[0].capacityProviderStrategy[0].capacityProvider", equalTo("FARGATE_SPOT"))
            .body("services[0].serviceRegistries[0].registryArn", equalTo(OUTSIDE_REGISTRY_ARN));
    }

    @Test
    void updateStack_droppingOneSetting_leavesASettingSetThroughTheApiAlone() {
        String name = stackName();
        createServiceStack(name, DEPLOYMENT_SETTING);
        ecs("UpdateService", """
                {"cluster": "%1$s", "service": "%1$s-svc",
                 "capacityProviderStrategy": [{"capacityProvider": "FARGATE_SPOT", "weight": 1}]}
                """.formatted(name));

        updateServiceStack(name, 1);

        describeService(name)
            .body("services[0]", not(hasKey("deploymentConfiguration")))
            .body("services[0].capacityProviderStrategy[0].capacityProvider", equalTo("FARGATE_SPOT"));
    }

    /**
     * One row per case of the AWS::ECS::Service DesiredCount contract: a new service without it gets 1, and an
     * update that leaves it out omits it from UpdateService, so the stored count stays. Columns: the declaration
     * at create (null: none), the count set through the ECS API after create (null: none), the declaration at
     * update (null: none), the count DescribeServices reports at the end.
     */
    private static Stream<Arguments> desiredCountCases() {
        String noValue = "{\"Fn::If\": [\"Never\", 3, {\"Ref\": \"AWS::NoValue\"}]}";
        return Stream.of(
            Arguments.of("createWithoutIt", null, null, null, 1),
            Arguments.of("updateDropsIt", "3", null, null, 3),
            Arguments.of("neverDeclaredButSetThroughTheApi", null, 4, null, 4),
            Arguments.of("updateChoosesNoValue", "3", null, noValue, 3),
            Arguments.of("updateDeclaresZero", "3", null, "0", 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("desiredCountCases")
    void updateStack_desiredCount_followsTheContract(String row, String atCreate, Integer setThroughTheApi,
                                                     String atUpdate, int expected) {
        String name = stackName();
        submitTemplate("CreateStack", name, withDesiredCount(name, atCreate, REGISTRIES_SETTING));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
        if (atCreate == null && setThroughTheApi == null) {
            describeService(name).body("services[0].desiredCount", equalTo(expected));
            return;
        }
        if (setThroughTheApi != null) {
            ecs("UpdateService", "{\"cluster\": \"%1$s\", \"service\": \"%1$s-svc\", \"desiredCount\": %2$d}"
                    .formatted(name, setThroughTheApi));
        }

        submitTemplate("UpdateStack", name, withDesiredCount(name, atUpdate, STRATEGY_SETTING));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        describeService(name).body("services[0].desiredCount", equalTo(expected));
    }

    /** SERVICE_TEMPLATE with DesiredCount set to {@code value} (a JSON value), or without the property when null. */
    private static String withDesiredCount(String stackName, String value, String setting) {
        return serviceTemplate(stackName, 0, setting)
            .replace("\"DesiredCount\": 0,", value == null ? "" : "\"DesiredCount\": " + value + ",");
    }

    private void createServiceStack(String name, String... settings) {
        submitService("CreateStack", name, 1, settings);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
    }

    private void updateServiceStack(String name, int desiredCount, String... settings) {
        submitService("UpdateStack", name, desiredCount, settings);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
    }

    /** Submits SERVICE_TEMPLATE whose service declares exactly {@code settings} besides its fixed properties. */
    private static void submitService(String action, String stackName, int desiredCount, String... settings) {
        submitTemplate(action, stackName, serviceTemplate(stackName, desiredCount, settings));
    }

    private static String serviceTemplate(String stackName, int desiredCount, String... settings) {
        String declared = List.of(settings).stream().map(setting -> ", " + setting).collect(Collectors.joining());
        return SERVICE_TEMPLATE.formatted(stackName, Ec2Service.defaultSubnetId("us-east-1", "a"), desiredCount, declared);
    }

    private static void submitTemplate(String action, String stackName, String template) {
        cfn(action, stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createStack_healthCheckWithoutACommand_failsAsRegisterTaskDefinitionDoes() {
        String name = stackName();
        cfn("CreateStack", name)
            .formParam("TemplateBody", """
                {"Resources": {"TaskDef": {"Type": "AWS::ECS::TaskDefinition", "Properties": {
                  "ContainerDefinitions": [{"Name": "app", "Image": "app:v1", "HealthCheck": {"Interval": 30}}]
                }}}}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
        assertEquals("HealthCheck command is required.", CfnStackWaits.failedResourceReason(name));
    }

    private String stackName() {
        String name = "cfn-ecs-settings-" + Long.toString(System.nanoTime(), 36);
        cleanup.register(() -> CfnStackWaits.deleteStack(name));
        return name;
    }

    /** Submits the template for {@code stackName}, with {@code maxPercent} as its MaxPercent when not null. */
    private static void submit(String action, String stackName, String maxPercent) {
        RequestSpecification request = cfn(action, stackName)
            .formParam("TemplateBody", TEMPLATE.formatted(stackName, Ec2Service.defaultSubnetId("us-east-1", "a")));
        if (maxPercent != null) {
            request.formParam("Parameters.member.1.ParameterKey", "MaxPercent")
                .formParam("Parameters.member.1.ParameterValue", maxPercent);
        }
        request
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static ValidatableResponse describeService(String name) {
        return ecs("DescribeServices", "{\"cluster\": \"" + name + "\", \"services\": [\"" + name + "-svc\"]}");
    }

    private static ValidatableResponse ecs(String action, String body) {
        return RestAssuredJsonUtils.awsAction(ECS_TARGET, action, body)
        .then()
            .statusCode(200);
    }
}
