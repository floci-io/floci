package io.floci.aws.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.iot.model.IotV2LoggingOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IotLoggingServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/iot-logging";
    private static final String OTHER_ROLE_ARN = "arn:aws:iam::123456789012:role/iot-logging-2";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IotLoggingService service = new IotLoggingService(new InMemoryStorage<>());

    private void set(String body, String region) throws Exception {
        service.setV2LoggingOptions(objectMapper.readTree(body), region);
    }

    private String failure(String body) {
        return assertThrows(AwsException.class, () -> set(body, REGION)).getErrorCode();
    }

    @Test
    void getBeforeAnySetIsNotConfigured() {
        AwsException failure = assertThrows(AwsException.class, () -> service.getV2LoggingOptions(REGION));
        assertEquals("NotConfiguredException", failure.getErrorCode());
        assertEquals(404, failure.getHttpStatus());
    }

    @Test
    void setThenGetReturnsTheOptions() throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "INFO", "disableAllLogs": true}
            """.formatted(ROLE_ARN), REGION);

        assertEquals(new IotV2LoggingOptions(ROLE_ARN, "INFO", true), service.getV2LoggingOptions(REGION));
    }

    @Test
    void initialSetupDefaultsDisableAllLogsToFalse() throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "WARN"}
            """.formatted(ROLE_ARN), REGION);

        assertEquals(new IotV2LoggingOptions(ROLE_ARN, "WARN", false), service.getV2LoggingOptions(REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"defaultLogLevel\": \"ERROR\"}",
        "{\"roleArn\": \"arn:aws:iam::123456789012:role/iot-logging\"}",
        "{\"disableAllLogs\": true}",
        "{}"
    })
    void initialSetupRequiresRoleArnAndDefaultLogLevel(String body) {
        assertEquals("InvalidRequestException", failure(body));
        assertEquals("NotConfiguredException",
                assertThrows(AwsException.class, () -> service.getV2LoggingOptions(REGION)).getErrorCode());
    }

    @Test
    void laterSetsKeepTheOptionsTheyOmit() throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "INFO"}
            """.formatted(ROLE_ARN), REGION);

        set("""
            {"disableAllLogs": true}
            """, REGION);
        assertEquals(new IotV2LoggingOptions(ROLE_ARN, "INFO", true), service.getV2LoggingOptions(REGION));

        set("""
            {"defaultLogLevel": "ERROR"}
            """, REGION);
        assertEquals(new IotV2LoggingOptions(ROLE_ARN, "ERROR", true), service.getV2LoggingOptions(REGION));

        set("""
            {"roleArn": "%s", "disableAllLogs": false}
            """.formatted(OTHER_ROLE_ARN), REGION);
        assertEquals(new IotV2LoggingOptions(OTHER_ROLE_ARN, "ERROR", false), service.getV2LoggingOptions(REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "INFO", "ERROR", "WARN", "DISABLED"})
    void acceptsEveryLogLevel(String level) throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "%s"}
            """.formatted(ROLE_ARN, level), REGION);

        assertEquals(level, service.getV2LoggingOptions(REGION).defaultLogLevel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACE", "info", ""})
    void rejectsAnUnknownLogLevelAndKeepsTheStoredOptions(String level) throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "INFO"}
            """.formatted(ROLE_ARN), REGION);

        assertEquals("InvalidRequestException", failure("""
            {"defaultLogLevel": "%s", "disableAllLogs": true}
            """.formatted(level)));
        assertEquals(new IotV2LoggingOptions(ROLE_ARN, "INFO", false), service.getV2LoggingOptions(REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"roleArn\": 42}",
        "{\"defaultLogLevel\": []}",
        "{\"disableAllLogs\": \"true\"}"
    })
    void rejectsAMemberOfTheWrongTypeAndKeepsTheStoredOptions(String body) throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "INFO"}
            """.formatted(ROLE_ARN), REGION);

        assertEquals("SerializationException", failure(body));
        assertEquals(new IotV2LoggingOptions(ROLE_ARN, "INFO", false), service.getV2LoggingOptions(REGION));
    }

    @Test
    void eachRegionKeepsItsOwnOptions() throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "WARN"}
            """.formatted(ROLE_ARN), REGION);

        assertEquals("NotConfiguredException",
                assertThrows(AwsException.class, () -> service.getV2LoggingOptions("eu-west-1")).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () -> set("""
            {"defaultLogLevel": "ERROR"}
            """, "eu-west-1")).getErrorCode());
    }

    @Test
    void optionsSurviveAReloadOfPersistentStorage(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("iot-v2-logging-options.json");
        TypeReference<Map<String, IotV2LoggingOptions>> type = new TypeReference<>() {};
        new IotLoggingService(new PersistentStorage<>(file, type)).setV2LoggingOptions(objectMapper.readTree("""
            {"roleArn": "%s", "defaultLogLevel": "DEBUG", "disableAllLogs": true}
            """.formatted(ROLE_ARN)), REGION);

        PersistentStorage<String, IotV2LoggingOptions> reloaded = new PersistentStorage<>(file, type);
        reloaded.load();
        IotLoggingService reader = new IotLoggingService(reloaded);

        assertEquals(new IotV2LoggingOptions(ROLE_ARN, "DEBUG", true), reader.getV2LoggingOptions(REGION));
        assertEquals("NotConfiguredException",
                assertThrows(AwsException.class, () -> reader.getV2LoggingOptions("eu-west-1")).getErrorCode());
    }
}
