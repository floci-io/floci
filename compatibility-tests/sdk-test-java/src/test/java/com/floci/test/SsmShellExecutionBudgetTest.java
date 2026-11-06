package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Command;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SSM shell execution budgets")
class SsmShellExecutionBudgetTest {
    @ParameterizedTest
    @CsvSource({"0,3600", "1,1", "5,5", "172800,172800"})
    void publicCommandReportsDeliveryAndTotalBudgets(int supplied, int execution) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses an emulator-only managed-node fixture");
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        parameters.put("commands", List.of("echo budget"));
        if (supplied != 0) {
            parameters.put("executionTimeout", List.of(Integer.toString(supplied)));
        }
        try (SsmClient ssm = TestFixtures.ssmClient()) {
            try {
                Command command = ssm.sendCommand(r -> r.documentName("AWS-RunShellScript")
                        .instanceIds(instance).parameters(parameters).timeoutSeconds(30)).command();
                assertThat(command.timeoutSeconds()).isEqualTo(30);
                assertThat(Duration.between(command.requestedDateTime(), command.expiresAfter()).toMillis())
                        .isCloseTo((30L + execution) * 1000, within(1L));
                assertThat(command.parameters()).isEqualTo(parameters);
                Command stored = ssm.listCommands(r -> r.commandId(command.commandId())).commands().get(0);
                assertThat(stored.timeoutSeconds()).isEqualTo(30);
                assertThat(stored.expiresAfter()).isEqualTo(command.expiresAfter());
                assertThat(ssm.getCommandInvocation(r -> r.commandId(command.commandId()).instanceId(instance))
                        .statusAsString()).isEqualTo("Pending");
            } finally {
                cancelFixture(ssm, instance);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "172801", "not-a-number", "+5", "01"})
    void malformedExecutionBudgetDoesNotCreateACommand(String value) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses an emulator-only managed-node fixture");
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        try (SsmClient ssm = TestFixtures.ssmClient()) {
            try {
                assertThat(ssm.listCommands(r -> r.instanceId(instance)).commands()).isEmpty();
                assertThatThrownBy(() -> ssm.sendCommand(r -> r.documentName("AWS-RunShellScript")
                        .instanceIds(instance).timeoutSeconds(30).parameters(Map.of(
                                "commands", List.of("echo budget"), "executionTimeout", List.of(value)))))
                        .isInstanceOfSatisfying(SsmException.class, error -> {
                            assertThat(error.statusCode()).isEqualTo(400);
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameters");
                        });
                assertThat(ssm.listCommands(r -> r.instanceId(instance)).commands()).isEmpty();
            } finally {
                cancelFixture(ssm, instance);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "only-timeout"})
    void requiredScriptIsCheckedBeforeCreatingACommand(String shape) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses an emulator-only managed-node fixture");
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        try (SsmClient ssm = TestFixtures.ssmClient()) {
            try {
                assertThatThrownBy(() -> ssm.sendCommand(request -> {
                    request.documentName("AWS-RunShellScript").instanceIds(instance).timeoutSeconds(30);
                    if ("empty".equals(shape)) {
                        request.parameters(Map.of("commands", List.of()));
                    }
                    if ("only-timeout".equals(shape)) {
                        request.parameters(Map.of("executionTimeout", List.of("5")));
                    }
                })).isInstanceOfSatisfying(SsmException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameters");
                });
                assertThat(ssm.listCommands(request -> request.instanceId(instance)).commands()).isEmpty();
                Command retry = ssm.sendCommand(request -> request.documentName("AWS-RunShellScript")
                        .instanceIds(instance).timeoutSeconds(30)
                        .parameters(Map.of("commands", List.of("echo retry")))).command();
                assertThat(retry.commandId()).isNotBlank();
                assertThat(retry.timeoutSeconds()).isEqualTo(30);
            } finally {
                cancelFixture(ssm, instance);
            }
        }
    }

    private static void cancelFixture(SsmClient ssm, String instance) {
        // SendCommand has no deletion API: cancel its queued work, retaining normal command history.
        for (Command command : ssm.listCommands(r -> r.instanceId(instance)).commands()) {
            ssm.cancelCommand(r -> r.commandId(command.commandId()).instanceIds(instance));
        }
    }
}
