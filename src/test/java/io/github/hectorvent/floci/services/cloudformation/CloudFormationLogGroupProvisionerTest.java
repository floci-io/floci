package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the "Persisted Name Mode Is Lost" review finding on #1965: a log group persisted
 * by a floci version before FlociLogGroupNameMode existed has no recorded mode, so the fix must
 * infer explicit vs. generated from the physical id's shape rather than default to one or the other.
 */
class CloudFormationLogGroupProvisionerTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STACK_NAME = "test-stack";

    private final ObjectMapper mapper = new ObjectMapper();
    private CloudWatchLogsService logsService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        logsService = mock(CloudWatchLogsService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                mapper,
                null, null, null, null, null, null, null,
                null, null, logsService, null, null, null, null,
                null, null,
                new CloudFormationResourceRegistry(List.of()), null);
    }

    @Test
    void legacyExplicitNameWithNoRecordedMode_replacesWhenNameIsRemoved() {
        // Simulates a log group created before FlociLogGroupNameMode was tracked: no attributes at
        // all, and a physical id that does not match generatePhysicalName's shape, so it must have
        // been an explicit LogGroupName the caller chose.
        String legacyExplicitName = "my-hand-chosen-log-group";
        when(logsService.logGroupExists(legacyExplicitName, REGION)).thenReturn(true);

        StackResource result = provision("{}", legacyExplicitName, Map.of());

        assertEquals("CREATE_COMPLETE", result.getStatus());
        assertNotEquals(legacyExplicitName, result.getPhysicalId());
        assertTrue(result.getPhysicalId().startsWith(STACK_NAME + "-LogGroup-"),
                "expected a freshly generated name, got: " + result.getPhysicalId());
        verify(logsService).createLogGroup(eq(result.getPhysicalId()), isNull(), anyMap(), eq(REGION));
        verify(logsService).deleteLogGroup(legacyExplicitName, REGION);
    }

    @Test
    void legacyGeneratedNameWithNoRecordedMode_reconcilesInPlace() {
        // A physical id matching the exact <stackName>-<logicalId>-<12 hex chars> shape must have
        // been auto-generated, even with no recorded mode, so it should be reconciled, not replaced.
        String legacyGeneratedName = STACK_NAME + "-LogGroup-abc123def456";
        when(logsService.logGroupExists(legacyGeneratedName, REGION)).thenReturn(true);

        StackResource result = provision("{}", legacyGeneratedName, Map.of());

        assertEquals("CREATE_COMPLETE", result.getStatus());
        assertEquals(legacyGeneratedName, result.getPhysicalId());
        verify(logsService, never()).createLogGroup(eq(legacyGeneratedName), isNull(), anyMap(), eq(REGION));
        verify(logsService, never()).deleteLogGroup(legacyGeneratedName, REGION);
        verify(logsService).deleteRetentionPolicy(legacyGeneratedName, REGION);
    }

    private StackResource provision(String propertiesJson, String existingPhysicalId,
                                    Map<String, String> existingAttributes) {
        return provisioner.provision("LogGroup", "AWS::Logs::LogGroup", props(propertiesJson), engine(),
                REGION, ACCOUNT_ID, STACK_NAME, existingPhysicalId, existingAttributes);
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine(
                ACCOUNT_ID, REGION, STACK_NAME, "stack/id",
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private JsonNode props(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
