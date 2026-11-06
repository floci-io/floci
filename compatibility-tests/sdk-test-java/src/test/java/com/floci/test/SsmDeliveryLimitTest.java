package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Command;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("SSM public delivery limits")
class SsmDeliveryLimitTest {
    @ParameterizedTest
    @ValueSource(ints = {2592001, Integer.MAX_VALUE})
    void rejectsOversizedDeliveryWithoutCreatingWork(int timeout) {
        assumeFalse(TestFixtures.isRealAws(), "Uses an emulator-only managed node");
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        try (SsmClient client = TestFixtures.ssmClient()) {
            try {
                SsmException error = assertThrows(SsmException.class,
                        () -> client.sendCommand(request(instance).timeoutSeconds(timeout).build()));
                assertEquals(400, error.statusCode());
                assertEquals("ValidationException", error.awsErrorDetails().errorCode());
                assertTrue(client.listCommands(r -> r.instanceId(instance)).commands().isEmpty());
                Command retry = client.sendCommand(request(instance).timeoutSeconds(30).build()).command();
                assertEquals(30, retry.timeoutSeconds());
                assertEquals(1, client.listCommands(r -> r.instanceId(instance)).commands().size());
            } finally {
                cancelOwned(client, instance);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 2592000})
    void acceptsInclusiveDeliveryBounds(int timeout) {
        assumeFalse(TestFixtures.isRealAws(), "Uses an emulator-only managed node");
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        try (SsmClient client = TestFixtures.ssmClient()) {
            try {
                Command command = client.sendCommand(request(instance).timeoutSeconds(timeout).build()).command();
                assertEquals(timeout, command.timeoutSeconds());
                assertEquals(command.commandId(), client.listCommands(r -> r.instanceId(instance))
                        .commands().get(0).commandId());
            } finally {
                cancelOwned(client, instance);
            }
        }
    }

    @Test
    void preservesOmittedDeliveryDefault() {
        assumeFalse(TestFixtures.isRealAws(), "Uses an emulator-only managed node");
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        try (SsmClient client = TestFixtures.ssmClient()) {
            try {
                assertEquals(3600, client.sendCommand(request(instance).build()).command().timeoutSeconds());
            } finally {
                cancelOwned(client, instance);
            }
        }
    }

    private static SendCommandRequest.Builder request(String instance) {
        return SendCommandRequest.builder().documentName("AWS-RunShellScript").instanceIds(instance)
                .parameters(Map.of("commands", List.of("echo limit")));
    }

    private static void cancelOwned(SsmClient client, String instance) {
        assertAll(client.listCommands(r -> r.instanceId(instance)).commands().stream()
                .map(command -> (Executable) () ->
                        client.cancelCommand(r -> r.commandId(command.commandId()))));
    }
}
