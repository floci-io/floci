package io.github.hectorvent.floci.services.apprunner;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppRunnerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "AppRunner.";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260215/us-east-1/apprunner/aws4_request";

    private static String autoScalingArnRevision1;
    private static String autoScalingArnRevision2;
    private static String vpcConnectorArn;
    private static String connectionArn;
    private static String serviceArn;
    private static String observabilityArn;
    private static String vpcIngressConnectionArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification call(String action, String body) {
        return given()
                .header("Authorization", AUTH)
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body);
    }

    // ─────────────────── Auto scaling configurations ───────────────────

    @Test
    @Order(10)
    void createAutoScalingConfigurationIsActiveWithDocumentedDefaults() {
        autoScalingArnRevision1 = call("CreateAutoScalingConfiguration", """
                {
                  "AutoScalingConfigurationName": "web-scaling",
                  "Tags": [{ "Key": "env", "Value": "test" }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfiguration.AutoScalingConfigurationName", equalTo("web-scaling"))
            .body("AutoScalingConfiguration.AutoScalingConfigurationRevision", equalTo(1))
            .body("AutoScalingConfiguration.Status", equalTo("ACTIVE"))
            .body("AutoScalingConfiguration.Latest", equalTo(true))
            .body("AutoScalingConfiguration.MaxConcurrency", equalTo(100))
            .body("AutoScalingConfiguration.MinSize", equalTo(1))
            .body("AutoScalingConfiguration.MaxSize", equalTo(25))
            .body("AutoScalingConfiguration.IsDefault", equalTo(false))
            .body("AutoScalingConfiguration.HasAssociatedService", equalTo(false))
            .body("AutoScalingConfiguration.CreatedAt", notNullValue())
            .extract().path("AutoScalingConfiguration.AutoScalingConfigurationArn");

        Assertions.assertTrue(
                autoScalingArnRevision1.startsWith(
                        "arn:aws:apprunner:us-east-1:000000000000:autoscalingconfiguration/web-scaling/1/"),
                "Auto scaling configuration ARN carries name and revision, got: " + autoScalingArnRevision1);
    }

    @Test
    @Order(11)
    void reusingTheNameCreatesTheNextRevision() {
        autoScalingArnRevision2 = call("CreateAutoScalingConfiguration", """
                {
                  "AutoScalingConfigurationName": "web-scaling",
                  "MaxConcurrency": 50,
                  "MinSize": 2,
                  "MaxSize": 10
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfiguration.AutoScalingConfigurationRevision", equalTo(2))
            .body("AutoScalingConfiguration.Latest", equalTo(true))
            .body("AutoScalingConfiguration.MaxConcurrency", equalTo(50))
            .body("AutoScalingConfiguration.MinSize", equalTo(2))
            .body("AutoScalingConfiguration.MaxSize", equalTo(10))
            .extract().path("AutoScalingConfiguration.AutoScalingConfigurationArn");

        call("DescribeAutoScalingConfiguration",
                "{\"AutoScalingConfigurationArn\":\"" + autoScalingArnRevision1 + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfiguration.Latest", equalTo(false));
    }

    @Test
    @Order(12)
    void describeByNameOnlyArnResolvesTheLatestRevision() {
        call("DescribeAutoScalingConfiguration",
                "{\"AutoScalingConfigurationArn\":"
                        + "\"arn:aws:apprunner:us-east-1:000000000000:autoscalingconfiguration/web-scaling\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfiguration.AutoScalingConfigurationArn", equalTo(autoScalingArnRevision2))
            .body("AutoScalingConfiguration.AutoScalingConfigurationRevision", equalTo(2));
    }

    @Test
    @Order(13)
    void listAutoScalingConfigurationsIncludesTheAccountDefault() {
        call("ListAutoScalingConfigurations", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfigurationSummaryList.AutoScalingConfigurationName",
                    hasItem("DefaultConfiguration"))
            .body("AutoScalingConfigurationSummaryList.AutoScalingConfigurationArn",
                    hasItem(autoScalingArnRevision1))
            .body("AutoScalingConfigurationSummaryList.AutoScalingConfigurationArn",
                    hasItem(autoScalingArnRevision2));
    }

    @Test
    @Order(14)
    void listAutoScalingConfigurationsFiltersByNameAndLatestOnly() {
        call("ListAutoScalingConfigurations",
                "{\"AutoScalingConfigurationName\":\"web-scaling\",\"LatestOnly\":true}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfigurationSummaryList", hasSize(1))
            .body("AutoScalingConfigurationSummaryList[0].AutoScalingConfigurationArn",
                    equalTo(autoScalingArnRevision2));
    }

    @Test
    @Order(15)
    void autoScalingConfigurationTagsRoundTrip() {
        call("ListTagsForResource", "{\"ResourceArn\":\"" + autoScalingArnRevision1 + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"))
            .body("Tags[0].Value", equalTo("test"));

        call("TagResource", """
                { "ResourceArn": "%s", "Tags": [{ "Key": "team", "Value": "platform" }] }
                """.formatted(autoScalingArnRevision1))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + autoScalingArnRevision1 + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2));

        call("UntagResource", """
                { "ResourceArn": "%s", "TagKeys": ["team"] }
                """.formatted(autoScalingArnRevision1))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + autoScalingArnRevision1 + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"));
    }

    // ──────────────────────── VPC connectors ────────────────────────

    @Test
    @Order(20)
    void createVpcConnectorIsActiveAndEchoesSubnets() {
        vpcConnectorArn = call("CreateVpcConnector", """
                {
                  "VpcConnectorName": "app-connector",
                  "Subnets": ["subnet-0123456789abcdef0", "subnet-0123456789abcdef1"],
                  "SecurityGroups": ["sg-0123456789abcdef0"],
                  "Tags": [{ "Key": "env", "Value": "test" }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcConnector.VpcConnectorName", equalTo("app-connector"))
            .body("VpcConnector.VpcConnectorRevision", equalTo(1))
            .body("VpcConnector.Status", equalTo("ACTIVE"))
            .body("VpcConnector.Subnets", hasSize(2))
            .body("VpcConnector.SecurityGroups", hasSize(1))
            .body("VpcConnector.CreatedAt", notNullValue())
            .extract().path("VpcConnector.VpcConnectorArn");
    }

    @Test
    @Order(21)
    void describeAndListVpcConnectors() {
        call("DescribeVpcConnector", "{\"VpcConnectorArn\":\"" + vpcConnectorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcConnector.VpcConnectorArn", equalTo(vpcConnectorArn))
            .body("VpcConnector.Status", equalTo("ACTIVE"));

        call("ListVpcConnectors", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcConnectors.VpcConnectorArn", hasItem(vpcConnectorArn));
    }

    @Test
    @Order(22)
    void createVpcConnectorWithoutSubnetsIsRejected() {
        call("CreateVpcConnector", "{\"VpcConnectorName\":\"no-subnets\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    // ────────────────────────── Connections ──────────────────────────

    @Test
    @Order(30)
    void createConnectionIsAvailable() {
        connectionArn = call("CreateConnection", """
                {
                  "ConnectionName": "github-connection",
                  "ProviderType": "GITHUB",
                  "Tags": [{ "Key": "env", "Value": "test" }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Connection.ConnectionName", equalTo("github-connection"))
            .body("Connection.ProviderType", equalTo("GITHUB"))
            .body("Connection.Status", equalTo("AVAILABLE"))
            .body("Connection.CreatedAt", notNullValue())
            .extract().path("Connection.ConnectionArn");

        call("ListConnections", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConnectionSummaryList.ConnectionArn", hasItem(connectionArn));
    }

    @Test
    @Order(31)
    void createConnectionWithUnknownProviderIsRejected() {
        call("CreateConnection", "{\"ConnectionName\":\"gitlab\",\"ProviderType\":\"GITLAB\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    // ──────────────────────────── Services ────────────────────────────

    @Test
    @Order(40)
    void createServiceIsRunningWithAServiceUrl() {
        serviceArn = call("CreateService", """
                {
                  "ServiceName": "storefront",
                  "AutoScalingConfigurationArn": "%s",
                  "SourceConfiguration": {
                    "ImageRepository": {
                      "ImageIdentifier": "public.ecr.aws/aws-containers/hello-app-runner:latest",
                      "ImageRepositoryType": "ECR_PUBLIC",
                      "ImageConfiguration": {
                        "Port": "8000",
                        "RuntimeEnvironmentVariables": { "STAGE": "test" }
                      }
                    }
                  },
                  "Tags": [{ "Key": "env", "Value": "test" }]
                }
                """.formatted(autoScalingArnRevision2))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Service.ServiceName", equalTo("storefront"))
            .body("Service.Status", equalTo("RUNNING"))
            .body("Service.ServiceId", notNullValue())
            .body("Service.ServiceUrl", endsWith(".us-east-1.awsapprunner.com"))
            .body("Service.CreatedAt", notNullValue())
            .body("Service.SourceConfiguration.ImageRepository.ImageIdentifier",
                    equalTo("public.ecr.aws/aws-containers/hello-app-runner:latest"))
            .body("Service.SourceConfiguration.ImageRepository.ImageConfiguration.Port", equalTo("8000"))
            .body("Service.SourceConfiguration.ImageRepository.ImageConfiguration"
                    + ".RuntimeEnvironmentVariables.STAGE", equalTo("test"))
            .body("Service.SourceConfiguration.AutoDeploymentsEnabled", equalTo(false))
            .body("Service.InstanceConfiguration.Cpu", equalTo("1024"))
            .body("Service.InstanceConfiguration.Memory", equalTo("2048"))
            .body("Service.HealthCheckConfiguration.Protocol", equalTo("TCP"))
            .body("Service.HealthCheckConfiguration.Interval", equalTo(5))
            .body("Service.HealthCheckConfiguration.Timeout", equalTo(2))
            .body("Service.HealthCheckConfiguration.HealthyThreshold", equalTo(1))
            .body("Service.HealthCheckConfiguration.UnhealthyThreshold", equalTo(5))
            .body("Service.NetworkConfiguration.EgressConfiguration.EgressType", equalTo("DEFAULT"))
            .body("Service.NetworkConfiguration.IngressConfiguration.IsPubliclyAccessible", equalTo(true))
            .body("Service.NetworkConfiguration.IpAddressType", equalTo("IPV4"))
            .body("Service.ObservabilityConfiguration.ObservabilityEnabled", equalTo(false))
            .body("Service.AutoScalingConfigurationSummary.AutoScalingConfigurationArn",
                    equalTo(autoScalingArnRevision2))
            .body("Service.AutoScalingConfigurationSummary.MaxConcurrency", nullValue())
            .body("OperationId", notNullValue())
            .extract().path("Service.ServiceArn");

        Assertions.assertTrue(
                serviceArn.startsWith("arn:aws:apprunner:us-east-1:000000000000:service/storefront/"),
                "Service ARN carries the service name, got: " + serviceArn);
    }

    @Test
    @Order(41)
    void describeServiceReturnsTerminalStatusOnTheFirstRead() {
        call("DescribeService", "{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Service.ServiceArn", equalTo(serviceArn))
            .body("Service.Status", equalTo("RUNNING"))
            .body("Service.ServiceUrl", notNullValue());
    }

    @Test
    @Order(42)
    void listServicesReturnsASummaryForTheService() {
        call("ListServices", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ServiceSummaryList.ServiceArn", hasItem(serviceArn))
            .body("ServiceSummaryList.Status", hasItem("RUNNING"))
            .body("ServiceSummaryList.ServiceUrl", hasItem(endsWith(".awsapprunner.com")));
    }

    @Test
    @Order(43)
    void autoScalingConfigurationReportsTheAssociatedService() {
        call("DescribeAutoScalingConfiguration",
                "{\"AutoScalingConfigurationArn\":\"" + autoScalingArnRevision2 + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfiguration.HasAssociatedService", equalTo(true));
    }

    @Test
    @Order(44)
    void deletingAnAutoScalingConfigurationInUseIsRejected() {
        call("DeleteAutoScalingConfiguration",
                "{\"AutoScalingConfigurationArn\":\"" + autoScalingArnRevision2 + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(45)
    void updateServiceChangesInstanceConfigurationAndStaysRunning() {
        call("UpdateService", """
                {
                  "ServiceArn": "%s",
                  "InstanceConfiguration": { "Cpu": "2048", "Memory": "4096" }
                }
                """.formatted(serviceArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Service.InstanceConfiguration.Cpu", equalTo("2048"))
            .body("Service.InstanceConfiguration.Memory", equalTo("4096"))
            .body("Service.Status", equalTo("RUNNING"))
            .body("OperationId", notNullValue());
    }

    @Test
    @Order(46)
    void serviceTagsRoundTrip() {
        call("ListTagsForResource", "{\"ResourceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags[0].Key", equalTo("env"));

        call("TagResource", """
                { "ResourceArn": "%s", "Tags": [{ "Key": "owner", "Value": "web" }] }
                """.formatted(serviceArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.Key", hasItem("owner"));
    }

    @Test
    @Order(47)
    void pauseAndResumeService() {
        call("PauseService", "{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Service.Status", equalTo("PAUSED"))
            .body("OperationId", notNullValue());

        call("ResumeService", "{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Service.Status", equalTo("RUNNING"));
    }

    @Test
    @Order(48)
    void startDeploymentRecordsASucceededOperation() {
        call("StartDeployment", "{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OperationId", notNullValue());

        call("ListOperations", "{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OperationSummaryList.Type", hasItem("CREATE_SERVICE"))
            .body("OperationSummaryList.Type", hasItem("UPDATE_SERVICE"))
            .body("OperationSummaryList.Type", hasItem("START_DEPLOYMENT"))
            .body("OperationSummaryList[0].Status", equalTo("SUCCEEDED"))
            .body("OperationSummaryList[0].TargetArn", equalTo(serviceArn));
    }

    @Test
    @Order(49)
    void createServiceWithoutSourceConfigurationIsRejected() {
        call("CreateService", "{\"ServiceName\":\"no-source\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(50)
    void describeUnknownServiceReturnsResourceNotFound() {
        call("DescribeService",
                "{\"ServiceArn\":\"arn:aws:apprunner:us-east-1:000000000000:service/missing/deadbeef\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ──────────────────────────── Teardown ────────────────────────────

    @Test
    @Order(60)
    void deleteServiceReachesTheDeletedTerminalStateAndLeavesTheListing() {
        call("DeleteService", "{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Service.Status", equalTo("DELETED"))
            .body("Service.DeletedAt", notNullValue())
            .body("OperationId", notNullValue());

        call("DescribeService", "{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Service.Status", equalTo("DELETED"));

        call("ListServices", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ServiceSummaryList.ServiceArn", not(hasItem(serviceArn)));
    }

    @Test
    @Order(61)
    void deleteAutoScalingConfigurationGoesInactiveAndLeavesTheListing() {
        call("DeleteAutoScalingConfiguration",
                "{\"AutoScalingConfigurationArn\":\"" + autoScalingArnRevision2 + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfiguration.Status", equalTo("INACTIVE"))
            .body("AutoScalingConfiguration.DeletedAt", notNullValue());

        call("ListAutoScalingConfigurations", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AutoScalingConfigurationSummaryList.AutoScalingConfigurationArn",
                    not(hasItem(autoScalingArnRevision2)));
    }

    @Test
    @Order(62)
    void deletingTheAccountDefaultAutoScalingConfigurationIsRejected() {
        call("DeleteAutoScalingConfiguration",
                "{\"AutoScalingConfigurationArn\":"
                        + "\"arn:aws:apprunner:us-east-1:000000000000:autoscalingconfiguration"
                        + "/DefaultConfiguration/1/00000000000000000000000000000001\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(63)
    void deleteVpcConnectorGoesInactiveAndLeavesTheListing() {
        call("DeleteVpcConnector", "{\"VpcConnectorArn\":\"" + vpcConnectorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcConnector.Status", equalTo("INACTIVE"))
            .body("VpcConnector.DeletedAt", notNullValue());

        call("ListVpcConnectors", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcConnectors.VpcConnectorArn", not(hasItem(vpcConnectorArn)));
    }

    @Test
    @Order(64)
    void deleteConnectionGoesDeletedAndLeavesTheListing() {
        call("DeleteConnection", "{\"ConnectionArn\":\"" + connectionArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Connection.Status", equalTo("DELETED"));

        call("ListConnections", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConnectionSummaryList.ConnectionArn", not(hasItem(connectionArn)));
    }

    // ────────────────── Observability configurations ──────────────────

    @Test
    @Order(70)
    void createObservabilityConfigurationIsActiveWithTraceConfiguration() {
        observabilityArn = call("CreateObservabilityConfiguration", """
                {
                  "ObservabilityConfigurationName": "tracing",
                  "TraceConfiguration": { "Vendor": "AWSXRAY" },
                  "Tags": [{ "Key": "env", "Value": "test" }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ObservabilityConfiguration.ObservabilityConfigurationName", equalTo("tracing"))
            .body("ObservabilityConfiguration.ObservabilityConfigurationRevision", equalTo(1))
            .body("ObservabilityConfiguration.Status", equalTo("ACTIVE"))
            .body("ObservabilityConfiguration.Latest", equalTo(true))
            .body("ObservabilityConfiguration.TraceConfiguration.Vendor", equalTo("AWSXRAY"))
            .body("ObservabilityConfiguration.CreatedAt", notNullValue())
            .extract().path("ObservabilityConfiguration.ObservabilityConfigurationArn");

        Assertions.assertTrue(
                observabilityArn.startsWith(
                        "arn:aws:apprunner:us-east-1:000000000000:observabilityconfiguration/tracing/1/"),
                "Observability configuration ARN carries name and revision, got: " + observabilityArn);
    }

    @Test
    @Order(71)
    void describeAndListObservabilityConfigurations() {
        call("DescribeObservabilityConfiguration",
                "{\"ObservabilityConfigurationArn\":\"" + observabilityArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ObservabilityConfiguration.ObservabilityConfigurationArn", equalTo(observabilityArn))
            .body("ObservabilityConfiguration.Status", equalTo("ACTIVE"));

        call("ListObservabilityConfigurations", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ObservabilityConfigurationSummaryList.ObservabilityConfigurationArn",
                    hasItem(observabilityArn));
    }

    @Test
    @Order(72)
    void deleteObservabilityConfigurationGoesInactiveAndLeavesTheListing() {
        call("DeleteObservabilityConfiguration",
                "{\"ObservabilityConfigurationArn\":\"" + observabilityArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ObservabilityConfiguration.Status", equalTo("INACTIVE"))
            .body("ObservabilityConfiguration.DeletedAt", notNullValue());

        call("ListObservabilityConfigurations", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ObservabilityConfigurationSummaryList.ObservabilityConfigurationArn",
                    not(hasItem(observabilityArn)));
    }

    // ──────────────────── VPC ingress connections ────────────────────

    @Test
    @Order(80)
    void createVpcIngressConnectionIsAvailable() {
        vpcIngressConnectionArn = call("CreateVpcIngressConnection", """
                {
                  "VpcIngressConnectionName": "ingress-connector",
                  "ServiceArn": "arn:aws:apprunner:us-east-1:000000000000:service/storefront/deadbeef",
                  "IngressVpcConfiguration": {
                    "VpcId": "vpc-0123456789abcdef0",
                    "VpcEndpointId": "vpce-0123456789abcdef0"
                  },
                  "Tags": [{ "Key": "env", "Value": "test" }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcIngressConnection.VpcIngressConnectionName", equalTo("ingress-connector"))
            .body("VpcIngressConnection.Status", equalTo("AVAILABLE"))
            .body("VpcIngressConnection.DomainName", notNullValue())
            .body("VpcIngressConnection.IngressVpcConfiguration.VpcId", equalTo("vpc-0123456789abcdef0"))
            .body("VpcIngressConnection.IngressVpcConfiguration.VpcEndpointId",
                    equalTo("vpce-0123456789abcdef0"))
            .body("VpcIngressConnection.CreatedAt", notNullValue())
            .body("OperationId", notNullValue())
            .extract().path("VpcIngressConnection.VpcIngressConnectionArn");

        Assertions.assertTrue(
                vpcIngressConnectionArn.startsWith(
                        "arn:aws:apprunner:us-east-1:000000000000:vpcingressconnection/ingress-connector/"),
                "VPC ingress connection ARN carries the name, got: " + vpcIngressConnectionArn);
    }

    @Test
    @Order(81)
    void describeAndListVpcIngressConnections() {
        call("DescribeVpcIngressConnection",
                "{\"VpcIngressConnectionArn\":\"" + vpcIngressConnectionArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcIngressConnection.VpcIngressConnectionArn", equalTo(vpcIngressConnectionArn))
            .body("VpcIngressConnection.Status", equalTo("AVAILABLE"));

        call("ListVpcIngressConnections", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcIngressConnectionSummaryList.VpcIngressConnectionArn", hasItem(vpcIngressConnectionArn));
    }

    @Test
    @Order(82)
    void deleteVpcIngressConnectionGoesDeletedAndLeavesTheListing() {
        call("DeleteVpcIngressConnection",
                "{\"VpcIngressConnectionArn\":\"" + vpcIngressConnectionArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcIngressConnection.Status", equalTo("DELETED"))
            .body("OperationId", notNullValue());

        call("ListVpcIngressConnections", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VpcIngressConnectionSummaryList.VpcIngressConnectionArn",
                    not(hasItem(vpcIngressConnectionArn)));
    }

    @Test
    @Order(90)
    void unsupportedOperationFailsFast() {
        call("AssociateCustomDomain",
                "{\"ServiceArn\":\"arn:aws:apprunner:us-east-1:000000000000:service/storefront/deadbeef\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
