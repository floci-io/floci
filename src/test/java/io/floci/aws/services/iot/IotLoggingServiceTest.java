package io.floci.aws.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.iot.model.IotV2LoggingOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IotLoggingServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/iot-logging";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IotLoggingService service = new IotLoggingService(new InMemoryStorage<>());

    private void set(String body, String region) throws Exception {
        service.setV2LoggingOptions(objectMapper.readTree(body), region);
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
    void setReplacesTheWholeConfiguration() throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "DEBUG", "disableAllLogs": true}
            """.formatted(ROLE_ARN), REGION);
        set("""
            {"defaultLogLevel": "ERROR"}
            """, REGION);

        assertEquals(new IotV2LoggingOptions(null, "ERROR", false), service.getV2LoggingOptions(REGION));
    }

    @Test
    void eachRegionKeepsItsOwnOptions() throws Exception {
        set("""
            {"roleArn": "%s", "defaultLogLevel": "WARN"}
            """.formatted(ROLE_ARN), REGION);

        assertEquals("NotConfiguredException",
                assertThrows(AwsException.class, () -> service.getV2LoggingOptions("eu-west-1")).getErrorCode());
    }
}
