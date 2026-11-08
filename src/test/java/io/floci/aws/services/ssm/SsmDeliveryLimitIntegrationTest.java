package io.floci.aws.services.ssm;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ssm.model.Command;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@QuarkusTest
class SsmDeliveryLimitIntegrationTest extends SsmCommandIntegrationFixture {
    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-west-2";

    @ParameterizedTest
    @CsvSource({"AWS-RunShellScript,2592001", "AWS-RunShellScript,2147483647",
            "CustomDocument,2592001", "CustomDocument,2147483647"})
    void oversizedDeliveryIsRejectedBeforeAllocation(String document, int timeout) {
        String instance = "i-limit-" + UUID.randomUUID();
        try {
            Response rejected = send(document, instance, timeout);
            assertEquals(400, rejected.statusCode());
            assertEquals("ValidationException", rejected.jsonPath().getString("__type"));
            assertEquals("1 validation error detected: Value '" + timeout
                    + "' at 'timeoutSeconds' failed to satisfy constraint: "
                    + "Member must have value less than or equal to 2592000",
                    rejected.jsonPath().getString("message"));
            assertTrue(ownedCommands(instance).isEmpty());
            verifyNoInteractions(executor);
            Response retry = send(document, instance, 30);
            assertEquals(200, retry.statusCode());
            assertEquals(30, retry.jsonPath().getInt("Command.TimeoutSeconds"));
            assertEquals(1, ownedCommands(instance).size());
        } finally {
            cleanup(instance);
        }
    }

    @ParameterizedTest
    @CsvSource({"AWS-RunShellScript,30", "AWS-RunShellScript,2592000",
            "CustomDocument,30", "CustomDocument,2592000"})
    void inclusiveDeliveryBoundsRemainAccepted(String document, int timeout) {
        String instance = "i-limit-" + UUID.randomUUID();
        try {
            Response response = send(document, instance, timeout);
            assertEquals(200, response.statusCode());
            assertEquals(timeout, response.jsonPath().getInt("Command.TimeoutSeconds"));
            assertEquals(1, ownedCommands(instance).size());
        } finally {
            cleanup(instance);
        }
    }

    @ParameterizedTest
    @CsvSource({"AWS-RunShellScript", "CustomDocument"})
    void omissionAndExistingMinimumRemainUnchanged(String document) {
        String instance = "i-limit-" + UUID.randomUUID();
        try {
            Response rejected = send(document, instance, 29);
            assertEquals(400, rejected.statusCode());
            assertEquals("ValidationException", rejected.jsonPath().getString("__type"));
            assertEquals("1 validation error detected: Value '29' at 'timeoutSeconds' failed to satisfy constraint: "
                    + "Member must have value greater than or equal to 30", rejected.jsonPath().getString("message"));
            assertTrue(ownedCommands(instance).isEmpty());
            verifyNoInteractions(executor);
            Response response = send(document, instance, null);
            assertEquals(200, response.statusCode());
            assertEquals(3600, response.jsonPath().getInt("Command.TimeoutSeconds"));
        } finally {
            cleanup(instance);
        }
    }

    @Test
    void internalHookKeepsItsOneSecondBudget() {
        String instance = "i-limit-" + UUID.randomUUID();
        try {
            RequestScopes.runAs(ACCOUNT, REGION, () -> commandService.sendCommandToInstance(instance,
                    "AWS-RunShellScript", Map.of("commands", List.of("echo hook")), 1, REGION));
            assertEquals(1, ownedCommands(instance).size());
            assertEquals(1, ownedCommands(instance).getFirst().getTimeoutSeconds());
            verifyNoInteractions(executor);
        } finally {
            cleanup(instance);
        }
    }

    private Response send(String document, String instance, Integer timeout) {
        Map<String, Object> request = new HashMap<>(Map.of("DocumentName", document,
                "InstanceIds", List.of(instance), "Parameters", Map.of("commands", List.of("echo limit"))));
        if (timeout != null) {
            request.put("TimeoutSeconds", timeout);
        }
        return call(ACCOUNT, REGION, "AmazonSSM.SendCommand", request);
    }

    private List<Command> ownedCommands(String instance) {
        return RequestScopes.callAs(ACCOUNT, REGION,
                () -> commandService.listCommands(null, instance, REGION));
    }

    private void cleanup(String instance) {
        cleanupCommands(ACCOUNT, REGION, instance);
    }
}
