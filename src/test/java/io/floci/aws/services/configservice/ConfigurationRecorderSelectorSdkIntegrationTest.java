package io.floci.aws.services.configservice;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.ConfigurationRecorder;
import software.amazon.awssdk.services.config.model.ConfigurationRecorderStatus;
import software.amazon.awssdk.services.config.model.NoSuchConfigurationRecorderException;
import software.amazon.awssdk.services.config.model.ValidationException;
import software.amazon.awssdk.services.iam.IamClient;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ConfigurationRecorderSelectorSdkIntegrationTest {

    private static final String ACCOUNT = "000000097531";

    @TestHTTPResource("/")
    URI endpoint;

    private ConfigClient config;
    private IamClient iam;
    private String name;
    private boolean roleCreated;
    private boolean recorderCreated;

    @BeforeEach
    void createOwnedRecorder() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCOUNT, "test"));
        config = ConfigClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        name = "recorder-selector-" + Long.toString(System.nanoTime(), 36);
        String roleArn = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": {"Service": "config.amazonaws.com"},
                    "Action": "sts:AssumeRole"
                  }]
                }
                """)).role().arn();
        roleCreated = true;
        config.putConfigurationRecorder(request -> request.configurationRecorder(
                ConfigurationRecorder.builder().name(name).roleARN(roleArn)
                        .recordingGroup(group -> group.allSupported(false)
                                .includeGlobalResourceTypes(false).resourceTypesWithStrings("AWS::S3::Bucket"))
                        .build()));
        recorderCreated = true;
    }

    @AfterEach
    void deleteOwnedResources() {
        try {
            if (recorderCreated) {
                config.deleteConfigurationRecorder(request -> request.configurationRecorderName(name));
            }
        } finally {
            try {
                if (roleCreated) {
                    iam.deleteRole(request -> request.roleName(name));
                }
            } finally {
                if (config != null) {
                    config.close();
                }
                if (iam != null) {
                    iam.close();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RecorderOperation.class)
    void omittedEmptyAndSingleNamesSelectTheOwnedRecorder(RecorderOperation operation) {
        assertEquals(List.of(name), operation.read(config, null));
        assertEquals(List.of(name), operation.read(config, List.of()));
        assertEquals(List.of(name), operation.read(config, List.of(name)));
    }

    @ParameterizedTest
    @EnumSource(RecorderOperation.class)
    void multipleNamesAreValidationErrorsAndLeaveTheRecorderUnchanged(RecorderOperation operation) {
        ConfigurationRecorder before = config.describeConfigurationRecorders(request ->
                request.configurationRecorderNames(name)).configurationRecorders().getFirst();
        ValidationException error = assertThrows(ValidationException.class,
                () -> operation.read(config, List.of(name, name + "-missing")));
        assertEquals(400, error.statusCode());
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        ValidationException repeatedName = assertThrows(ValidationException.class,
                () -> operation.read(config, List.of(name, name)));
        assertEquals(400, repeatedName.statusCode());
        assertEquals(List.of(name), operation.read(config, List.of(name)));
        assertEquals(before, config.describeConfigurationRecorders(request ->
                request.configurationRecorderNames(name)).configurationRecorders().getFirst());
    }

    @ParameterizedTest
    @EnumSource(RecorderOperation.class)
    void multipleNamesAreValidationErrorsWhenNoRecorderExists(RecorderOperation operation) {
        config.deleteConfigurationRecorder(request -> request.configurationRecorderName(name));
        recorderCreated = false;
        assertEquals(List.of(), operation.read(config, null));
        ValidationException error = assertThrows(ValidationException.class,
                () -> operation.read(config, List.of(name, name + "-missing")));
        assertEquals(400, error.statusCode());
        assertEquals(List.of(), operation.read(config, null));
    }

    @ParameterizedTest
    @EnumSource(RecorderOperation.class)
    void oneMissingNameKeepsTheMissingRecorderError(RecorderOperation operation) {
        NoSuchConfigurationRecorderException error = assertThrows(NoSuchConfigurationRecorderException.class,
                () -> operation.read(config, List.of(name + "-missing")));
        assertEquals(400, error.statusCode());
        assertEquals(List.of(name), operation.read(config, List.of(name)));
    }

    enum RecorderOperation {
        DESCRIPTIONS,
        STATUS;

        List<String> read(ConfigClient client, List<String> names) {
            return switch (this) {
                case DESCRIPTIONS -> client.describeConfigurationRecorders(request ->
                        request.configurationRecorderNames(names)).configurationRecorders().stream()
                        .map(ConfigurationRecorder::name).toList();
                case STATUS -> client.describeConfigurationRecorderStatus(request ->
                        request.configurationRecorderNames(names)).configurationRecordersStatus().stream()
                        .map(ConfigurationRecorderStatus::name).toList();
            };
        }
    }
}
