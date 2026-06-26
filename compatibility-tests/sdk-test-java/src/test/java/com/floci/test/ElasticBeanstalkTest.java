package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.elasticbeanstalk.ElasticBeanstalkClient;
import software.amazon.awssdk.services.elasticbeanstalk.model.ConfigurationOptionSetting;
import software.amazon.awssdk.services.elasticbeanstalk.model.CreateApplicationRequest;
import software.amazon.awssdk.services.elasticbeanstalk.model.CreateApplicationVersionRequest;
import software.amazon.awssdk.services.elasticbeanstalk.model.CreateEnvironmentRequest;
import software.amazon.awssdk.services.elasticbeanstalk.model.DescribeApplicationsRequest;
import software.amazon.awssdk.services.elasticbeanstalk.model.DescribeApplicationVersionsRequest;
import software.amazon.awssdk.services.elasticbeanstalk.model.DescribeConfigurationSettingsRequest;
import software.amazon.awssdk.services.elasticbeanstalk.model.DescribeEnvironmentsRequest;
import software.amazon.awssdk.services.elasticbeanstalk.model.S3Location;
import software.amazon.awssdk.services.elasticbeanstalk.model.TerminateEnvironmentRequest;
import software.amazon.awssdk.services.elasticbeanstalk.model.UpdateEnvironmentRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Elastic Beanstalk")
class ElasticBeanstalkTest {

    private static ElasticBeanstalkClient elasticBeanstalk;
    private static String applicationName;
    private static String environmentName;

    @BeforeAll
    static void setup() {
        elasticBeanstalk = TestFixtures.elasticBeanstalkClient();
        applicationName = TestFixtures.uniqueName("sdk-eb-app");
        environmentName = TestFixtures.uniqueName("sdk-eb-env");
    }

    @AfterAll
    static void cleanup() {
        if (elasticBeanstalk != null) {
            try {
                elasticBeanstalk.terminateEnvironment(TerminateEnvironmentRequest.builder()
                        .environmentName(environmentName)
                        .build());
            } catch (Exception ignored) {
            }
            elasticBeanstalk.close();
        }
    }

    @Test
    @DisplayName("SDK unmarshals Query responses for application, version, and environment lifecycle")
    void sdkLifecycleFlow() {
        String versionLabel = "v1";
        String cnamePrefix = TestFixtures.uniqueName("sdk-eb-cname");

        var application = elasticBeanstalk.createApplication(CreateApplicationRequest.builder()
                .applicationName(applicationName)
                .description("Elastic Beanstalk SDK compatibility app")
                .build());

        assertThat(application.application().applicationName()).isEqualTo(applicationName);
        assertThat(application.application().applicationArn())
                .endsWith(":application/" + applicationName);
        assertThat(application.application().configurationTemplates()).contains("default");

        var version = elasticBeanstalk.createApplicationVersion(CreateApplicationVersionRequest.builder()
                .applicationName(applicationName)
                .versionLabel(versionLabel)
                .description("Initial SDK version")
                .sourceBundle(S3Location.builder()
                        .s3Bucket("source-bucket")
                        .s3Key("app-v1.zip")
                        .build())
                .build());

        assertThat(version.applicationVersion().applicationName()).isEqualTo(applicationName);
        assertThat(version.applicationVersion().versionLabel()).isEqualTo(versionLabel);
        assertThat(version.applicationVersion().sourceBundle().s3Bucket()).isEqualTo("source-bucket");

        var environment = elasticBeanstalk.createEnvironment(CreateEnvironmentRequest.builder()
                .applicationName(applicationName)
                .environmentName(environmentName)
                .versionLabel(versionLabel)
                .cnamePrefix(cnamePrefix)
                .solutionStackName("64bit Amazon Linux 2023 v4.3.0 running Docker")
                .optionSettings(ConfigurationOptionSetting.builder()
                        .namespace("aws:elasticbeanstalk:application:environment")
                        .optionName("SPRING_PROFILES_ACTIVE")
                        .value("test")
                        .build())
                .build());

        assertThat(environment.environmentName()).isEqualTo(environmentName);
        assertThat(environment.applicationName()).isEqualTo(applicationName);
        assertThat(environment.statusAsString()).isEqualTo("Ready");
        assertThat(environment.healthAsString()).isEqualTo("Green");
        assertThat(environment.cname()).isEqualTo(cnamePrefix + ".elasticbeanstalk.local");

        var applications = elasticBeanstalk.describeApplications(DescribeApplicationsRequest.builder()
                .applicationNames(applicationName)
                .build());
        assertThat(applications.applications()).singleElement().satisfies(app -> {
            assertThat(app.applicationName()).isEqualTo(applicationName);
            assertThat(app.applicationArn()).endsWith(":application/" + applicationName);
            assertThat(app.versions()).contains(versionLabel);
        });

        var versions = elasticBeanstalk.describeApplicationVersions(DescribeApplicationVersionsRequest.builder()
                .applicationName(applicationName)
                .versionLabels(versionLabel)
                .build());
        assertThat(versions.applicationVersions()).singleElement().satisfies(appVersion -> {
            assertThat(appVersion.applicationName()).isEqualTo(applicationName);
            assertThat(appVersion.versionLabel()).isEqualTo(versionLabel);
            assertThat(appVersion.sourceBundle().s3Key()).isEqualTo("app-v1.zip");
        });

        var described = elasticBeanstalk.describeEnvironments(DescribeEnvironmentsRequest.builder()
                .applicationName(applicationName)
                .environmentNames(environmentName)
                .build());
        assertThat(described.environments()).singleElement().satisfies(env -> {
            assertThat(env.environmentName()).isEqualTo(environmentName);
            assertThat(env.statusAsString()).isEqualTo("Ready");
            assertThat(env.endpointURL()).startsWith("awseb-");
        });

        var updated = elasticBeanstalk.updateEnvironment(UpdateEnvironmentRequest.builder()
                .environmentName(environmentName)
                .description("Updated SDK environment")
                .optionSettings(ConfigurationOptionSetting.builder()
                        .namespace("aws:elasticbeanstalk:application:environment")
                        .optionName("SPRING_PROFILES_ACTIVE")
                        .value("prod")
                        .build())
                .build());

        assertThat(updated.environmentName()).isEqualTo(environmentName);
        assertThat(updated.description()).isEqualTo("Updated SDK environment");

        var settings = elasticBeanstalk.describeConfigurationSettings(DescribeConfigurationSettingsRequest.builder()
                .applicationName(applicationName)
                .environmentName(environmentName)
                .build());
        assertThat(settings.configurationSettings()).singleElement()
                .satisfies(configuration -> assertThat(configuration.optionSettings())
                        .anySatisfy(option -> {
                            assertThat(option.optionName()).isEqualTo("SPRING_PROFILES_ACTIVE");
                            assertThat(option.value()).isEqualTo("prod");
                        }));

        var terminated = elasticBeanstalk.terminateEnvironment(TerminateEnvironmentRequest.builder()
                .environmentName(environmentName)
                .build());
        assertThat(terminated.environmentName()).isEqualTo(environmentName);
        assertThat(terminated.statusAsString()).isEqualTo("Terminated");

        var deletedEnvironments = elasticBeanstalk.describeEnvironments(DescribeEnvironmentsRequest.builder()
                .applicationName(applicationName)
                .environmentNames(environmentName)
                .includeDeleted(true)
                .build());
        assertThat(deletedEnvironments.environments()).singleElement()
                .satisfies(env -> assertThat(env.statusAsString()).isEqualTo("Terminated"));
    }
}
