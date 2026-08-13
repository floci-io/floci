package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.*;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.BadRequestException;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("AppConfig")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppConfigTest {

    private static AppConfigClient appConfig;
    private static AppConfigDataClient appConfigData;

    private static String applicationId;
    private static String environmentId;
    private static String configurationProfileId;
    private static String deploymentStrategyId;
    private static String configurationToken;
    private static String secondConfigurationToken;
    private static String intervalSessionToken;
    private static String emptyAppId;
    private static String emptyEnvId;
    private static String emptyProfileId;

    @BeforeAll
    static void setup() {
        appConfig = TestFixtures.appConfigClient();
        appConfigData = TestFixtures.appConfigDataClient();
    }

    @AfterAll
    static void cleanup() {
        if (appConfig != null) {
            appConfig.close();
        }
        if (appConfigData != null) {
            appConfigData.close();
        }
    }

    @Test
    @Order(1)
    void createApplication() {
        CreateApplicationResponse response = appConfig.createApplication(CreateApplicationRequest.builder()
                .name(TestFixtures.uniqueName("app"))
                .description("Test Application")
                .build());

        applicationId = response.id();
        assertThat(applicationId).isNotNull();
    }

    @Test
    @Order(2)
    void createEnvironment() {
        CreateEnvironmentResponse response = appConfig.createEnvironment(CreateEnvironmentRequest.builder()
                .applicationId(applicationId)
                .name("dev")
                .build());

        environmentId = response.id();
        assertThat(environmentId).isNotNull();
    }

    @Test
    @Order(3)
    void createConfigurationProfile() {
        CreateConfigurationProfileResponse response = appConfig.createConfigurationProfile(CreateConfigurationProfileRequest.builder()
                .applicationId(applicationId)
                .name("main-config")
                .locationUri("hosted")
                .type("AWS.Freeform")
                .build());

        configurationProfileId = response.id();
        assertThat(configurationProfileId).isNotNull();
    }

    @Test
    @Order(4)
    void createHostedConfigurationVersion() {
        CreateHostedConfigurationVersionResponse response = appConfig.createHostedConfigurationVersion(
                CreateHostedConfigurationVersionRequest.builder()
                        .applicationId(applicationId)
                        .configurationProfileId(configurationProfileId)
                        .content(SdkBytes.fromString("{\"key\": \"value\"}", StandardCharsets.UTF_8))
                        .contentType("application/json")
                        .build());

        assertThat(response.versionNumber()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void createDeploymentStrategy() {
        CreateDeploymentStrategyResponse response = appConfig.createDeploymentStrategy(CreateDeploymentStrategyRequest.builder()
                .name("immediate")
                .deploymentDurationInMinutes(0)
                .finalBakeTimeInMinutes(0)
                .growthFactor(100.0f)
                .build());

        deploymentStrategyId = response.id();
        assertThat(deploymentStrategyId).isNotNull();
    }

    @Test
    @Order(6)
    void startDeployment() {
        StartDeploymentResponse response = appConfig.startDeployment(StartDeploymentRequest.builder()
                .applicationId(applicationId)
                .environmentId(environmentId)
                .configurationProfileId(configurationProfileId)
                .configurationVersion("1")
                .deploymentStrategyId(deploymentStrategyId)
                .build());

        assertThat(response.deploymentNumber()).isNotNull();
    }

    @Test
    @Order(7)
    void startConfigurationSession() {
        var response = appConfigData.startConfigurationSession(StartConfigurationSessionRequest.builder()
                .applicationIdentifier(applicationId)
                .environmentIdentifier(environmentId)
                .configurationProfileIdentifier(configurationProfileId)
                .build());

        configurationToken = response.initialConfigurationToken();
        assertThat(configurationToken).isNotNull();
    }

    @Test
    @Order(8)
    void getLatestConfiguration() {
        GetLatestConfigurationResponse response = appConfigData.getLatestConfiguration(GetLatestConfigurationRequest.builder()
                .configurationToken(configurationToken)
                .build());

        assertThat(response.configuration().asString(StandardCharsets.UTF_8)).isEqualTo("{\"key\": \"value\"}");
        assertThat(response.contentType()).startsWith("application/json");
        assertThat(response.versionLabel()).isEqualTo("1");
        assertThat(response.nextPollConfigurationToken()).isNotNull();
        secondConfigurationToken = response.nextPollConfigurationToken();
    }

    @Test
    @Order(9)
    void staleConfigurationTokenIsRejected() {
        assertThrows(BadRequestException.class, () -> appConfigData.getLatestConfiguration(
                GetLatestConfigurationRequest.builder()
                        .configurationToken(configurationToken)
                        .build()));
    }

    @Test
    @Order(10)
    void invalidConfigurationTokenIsRejected() {
        assertThrows(BadRequestException.class, () -> appConfigData.getLatestConfiguration(
                GetLatestConfigurationRequest.builder()
                        .configurationToken("not-a-real-token")
                        .build()));
    }

    @Test
    @Order(11)
    void updatedDeploymentIsVisibleOnNextPollToken() {
        CreateHostedConfigurationVersionResponse versionResponse = appConfig.createHostedConfigurationVersion(
                CreateHostedConfigurationVersionRequest.builder()
                        .applicationId(applicationId)
                        .configurationProfileId(configurationProfileId)
                        .content(SdkBytes.fromString("{\"key\": \"value-2\"}", StandardCharsets.UTF_8))
                        .contentType("application/json")
                        .build());
        assertThat(versionResponse.versionNumber()).isEqualTo(2);

        appConfig.startDeployment(StartDeploymentRequest.builder()
                .applicationId(applicationId)
                .environmentId(environmentId)
                .configurationProfileId(configurationProfileId)
                .configurationVersion("2")
                .deploymentStrategyId(deploymentStrategyId)
                .build());

        GetLatestConfigurationResponse response = appConfigData.getLatestConfiguration(GetLatestConfigurationRequest.builder()
                .configurationToken(secondConfigurationToken)
                .build());

        assertThat(response.configuration().asString(StandardCharsets.UTF_8)).isEqualTo("{\"key\": \"value-2\"}");
        assertThat(response.versionLabel()).isEqualTo("2");
    }

    @Test
    @Order(12)
    @DisplayName("Poll interval: requested 60s but emulator returns 15s (known deviation from AWS)")
    void requiredMinimumPollIntervalIsAcceptedButNotEnforced() {
        var sessionResponse = appConfigData.startConfigurationSession(StartConfigurationSessionRequest.builder()
                .applicationIdentifier(applicationId)
                .environmentIdentifier(environmentId)
                .configurationProfileIdentifier(configurationProfileId)
                .requiredMinimumPollIntervalInSeconds(60)
                .build());

        intervalSessionToken = sessionResponse.initialConfigurationToken();
        GetLatestConfigurationResponse firstResponse = appConfigData.getLatestConfiguration(GetLatestConfigurationRequest.builder()
                .configurationToken(intervalSessionToken)
                .build());

        // Emulator always returns 15s regardless of the requested interval.
        // AWS would return the requested 60s. Pinning current emulator behavior.
        assertThat(firstResponse.nextPollIntervalInSeconds()).isEqualTo(15);
        assertThat(firstResponse.nextPollConfigurationToken()).isNotNull();

        GetLatestConfigurationResponse secondResponse = appConfigData.getLatestConfiguration(GetLatestConfigurationRequest.builder()
                .configurationToken(firstResponse.nextPollConfigurationToken())
                .build());

        assertThat(secondResponse.nextPollIntervalInSeconds()).isEqualTo(15);
        assertThat(secondResponse.nextPollConfigurationToken()).isNotNull();
    }

    @Test
    @Order(13)
    void emptyConfigurationReturnsEmptyPayload() {
        emptyAppId = appConfig.createApplication(CreateApplicationRequest.builder()
                .name(TestFixtures.uniqueName("empty-app"))
                .build()).id();

        emptyEnvId = appConfig.createEnvironment(CreateEnvironmentRequest.builder()
                .applicationId(emptyAppId)
                .name("empty-env")
                .build()).id();

        emptyProfileId = appConfig.createConfigurationProfile(CreateConfigurationProfileRequest.builder()
                .applicationId(emptyAppId)
                .name("empty-config")
                .locationUri("hosted")
                .type("AWS.Freeform")
                .build()).id();

        String emptyToken = appConfigData.startConfigurationSession(StartConfigurationSessionRequest.builder()
                .applicationIdentifier(emptyAppId)
                .environmentIdentifier(emptyEnvId)
                .configurationProfileIdentifier(emptyProfileId)
                .build()).initialConfigurationToken();

        GetLatestConfigurationResponse response = appConfigData.getLatestConfiguration(GetLatestConfigurationRequest.builder()
                .configurationToken(emptyToken)
                .build());

        assertThat(response.configuration().asByteArray()).isEmpty();
        assertThat(response.contentType()).isEqualTo("application/octet-stream");
        // SDK deserializes the empty Version-Label header as null.
        // The RestAssured internal test sees "" (raw HTTP header value).
        assertThat(response.versionLabel()).isNull();
    }

    @Test
    @Order(50)
    @DisplayName("TagResource / ListTagsForResource - SDK round-trip")
    void tagAndListViaSdk() {
        // Reproducer for the wire-format mismatch that previously made AWS SDK callers
        // silently get an empty tag set: SDK serializes as {"Tags": {...}} (capital),
        // floci must accept and echo back that exact shape.
        String tagAppId = appConfig.createApplication(CreateApplicationRequest.builder()
                .name("tag-roundtrip-app")
                .build()).id();
        String arn = "arn:aws:appconfig:us-east-1:000000000000:application/" + tagAppId;
        try {
            appConfig.tagResource(TagResourceRequest.builder()
                    .resourceArn(arn)
                    .tags(java.util.Map.of("env", "prod", "owner", "Alice"))
                    .build());

            ListTagsForResourceResponse listed = appConfig.listTagsForResource(
                    ListTagsForResourceRequest.builder().resourceArn(arn).build());
            assertThat(listed.tags())
                    .containsEntry("env", "prod")
                    .containsEntry("owner", "Alice");

            appConfig.untagResource(UntagResourceRequest.builder()
                    .resourceArn(arn)
                    .tagKeys("env")
                    .build());

            assertThat(appConfig.listTagsForResource(
                    ListTagsForResourceRequest.builder().resourceArn(arn).build()).tags())
                    .doesNotContainKey("env")
                    .containsEntry("owner", "Alice");
        } finally {
            try {
                appConfig.deleteApplication(DeleteApplicationRequest.builder()
                        .applicationId(tagAppId).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(51)
    @DisplayName("TagResource / ListTagsForResource - deploymentstrategy ARN (top-level, not nested under application/)")
    void tagDeploymentStrategyViaSdk() {
        // deploymentstrategy/extension/extensionassociation ARNs don't nest under application/
        // like every other AppConfig resource tested above - a naive ARN parser can end up
        // hardcoding the application/ prefix and rejecting these as invalid, even though AWS
        // documents all three as taggable (see e.g. AWS::AppConfig::DeploymentStrategy's Tags
        // property). This asserts the SDK call succeeds end-to-end, not just that some ARN shape
        // is accepted internally.
        String tagStrategyId = appConfig.createDeploymentStrategy(CreateDeploymentStrategyRequest.builder()
                .name("tag-roundtrip-strategy")
                .deploymentDurationInMinutes(0)
                .growthFactor(100f)
                .finalBakeTimeInMinutes(0)
                .build()).id();
        String arn = "arn:aws:appconfig:us-east-1:000000000000:deploymentstrategy/" + tagStrategyId;

        appConfig.tagResource(TagResourceRequest.builder()
                .resourceArn(arn)
                .tags(java.util.Map.of("env", "prod"))
                .build());

        ListTagsForResourceResponse listed = appConfig.listTagsForResource(
                ListTagsForResourceRequest.builder().resourceArn(arn).build());
        assertThat(listed.tags()).isNotNull();
    }

    @Test
    @Order(52)
    @DisplayName("DeleteConfigurationProfile / DeleteHostedConfigurationVersion / ListDeploymentStrategies / DeleteDeploymentStrategy")
    void deleteAndListOperationsPreviouslyReturnedNoSuchBucket() {
        // These four operations had no route at all, so the request fell through to S3's
        // generic path-style catch-all and returned a raw S3 NoSuchBucket 404 instead of a real
        // AppConfig response. Exercised via the SDK (not raw HTTP) so a regression here surfaces
        // as an SDK-level exception, matching how a real caller would hit it.
        String delAppId = appConfig.createApplication(CreateApplicationRequest.builder()
                .name("delete-ops-app")
                .build()).id();
        try {
            String delProfileId = appConfig.createConfigurationProfile(CreateConfigurationProfileRequest.builder()
                    .applicationId(delAppId)
                    .name("delete-ops-profile")
                    .locationUri("hosted")
                    .type("AWS.Freeform")
                    .build()).id();

            appConfig.createHostedConfigurationVersion(CreateHostedConfigurationVersionRequest.builder()
                    .applicationId(delAppId)
                    .configurationProfileId(delProfileId)
                    .contentType("application/json")
                    .content(SdkBytes.fromUtf8String("{\"throwaway\": true}"))
                    .build());

            appConfig.deleteHostedConfigurationVersion(DeleteHostedConfigurationVersionRequest.builder()
                    .applicationId(delAppId)
                    .configurationProfileId(delProfileId)
                    .versionNumber(1)
                    .build());
            assertThrows(ResourceNotFoundException.class, () -> appConfig.getHostedConfigurationVersion(
                    GetHostedConfigurationVersionRequest.builder()
                            .applicationId(delAppId).configurationProfileId(delProfileId).versionNumber(1).build()));

            appConfig.deleteConfigurationProfile(DeleteConfigurationProfileRequest.builder()
                    .applicationId(delAppId)
                    .configurationProfileId(delProfileId)
                    .build());
            assertThrows(ResourceNotFoundException.class, () -> appConfig.getConfigurationProfile(
                    GetConfigurationProfileRequest.builder()
                            .applicationId(delAppId).configurationProfileId(delProfileId).build()));

            String delStrategyId = appConfig.createDeploymentStrategy(CreateDeploymentStrategyRequest.builder()
                    .name("delete-ops-strategy")
                    .deploymentDurationInMinutes(0)
                    .growthFactor(100f)
                    .finalBakeTimeInMinutes(0)
                    .build()).id();

            ListDeploymentStrategiesResponse listed = appConfig.listDeploymentStrategies(
                    ListDeploymentStrategiesRequest.builder().build());
            assertThat(listed.items())
                    .extracting(strategy -> strategy.id())
                    .contains("AppConfig.AllAtOnce", "AppConfig.Linear50PercentEvery30Seconds",
                            "AppConfig.Canary10Percent20Minutes", delStrategyId);

            appConfig.deleteDeploymentStrategy(DeleteDeploymentStrategyRequest.builder()
                    .deploymentStrategyId(delStrategyId).build());
            assertThrows(ResourceNotFoundException.class, () -> appConfig.getDeploymentStrategy(
                    GetDeploymentStrategyRequest.builder().deploymentStrategyId(delStrategyId).build()));
        } finally {
            try {
                appConfig.deleteApplication(DeleteApplicationRequest.builder()
                        .applicationId(delAppId).build());
            } catch (Exception e) {
                System.err.println("Best-effort cleanup for delete-ops-app failed: " + e.getMessage());
            }
        }
    }

    @Test
    @Order(53)
    @DisplayName("DeleteConfigurationProfile rejects a profile that belongs to a different application")
    void deleteConfigurationProfileRejectsWrongApplication() {
        String ownerAppId = appConfig.createApplication(CreateApplicationRequest.builder()
                .name("profile-owner-app").build()).id();
        String otherAppId = appConfig.createApplication(CreateApplicationRequest.builder()
                .name("profile-other-app").build()).id();
        try {
            String profileId = appConfig.createConfigurationProfile(CreateConfigurationProfileRequest.builder()
                    .applicationId(ownerAppId)
                    .name("owned-profile")
                    .locationUri("hosted")
                    .type("AWS.Freeform")
                    .build()).id();

            // Addressing the owner's profile through a DIFFERENT application's path must not
            // delete it - only "not found", never a real cross-application deletion.
            assertThrows(ResourceNotFoundException.class, () -> appConfig.deleteConfigurationProfile(
                    DeleteConfigurationProfileRequest.builder()
                            .applicationId(otherAppId).configurationProfileId(profileId).build()));

            assertThat(appConfig.getConfigurationProfile(GetConfigurationProfileRequest.builder()
                    .applicationId(ownerAppId).configurationProfileId(profileId).build()).id())
                    .isEqualTo(profileId);
        } finally {
            try {
                appConfig.deleteApplication(DeleteApplicationRequest.builder().applicationId(ownerAppId).build());
            } catch (Exception e) {
                System.err.println("Best-effort cleanup for profile-owner-app failed: " + e.getMessage());
            }
            try {
                appConfig.deleteApplication(DeleteApplicationRequest.builder().applicationId(otherAppId).build());
            } catch (Exception e) {
                System.err.println("Best-effort cleanup for profile-other-app failed: " + e.getMessage());
            }
        }
    }

    @Test
    @Order(54)
    @DisplayName("DeleteDeploymentStrategy rejects a predefined strategy")
    void deleteDeploymentStrategyRejectsPredefined() {
        assertThrows(software.amazon.awssdk.services.appconfig.model.BadRequestException.class, () -> appConfig.deleteDeploymentStrategy(
                DeleteDeploymentStrategyRequest.builder().deploymentStrategyId("AppConfig.AllAtOnce").build()));

        assertThat(appConfig.getDeploymentStrategy(GetDeploymentStrategyRequest.builder()
                .deploymentStrategyId("AppConfig.AllAtOnce").build()).id())
                .isEqualTo("AppConfig.AllAtOnce");
    }
}
