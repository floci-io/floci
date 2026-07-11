package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsmCommandServiceDirectExecutionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RegionResolver regionResolver = mock(RegionResolver.class);

    @Test
    void directExecutionCompletesCommandWithoutQueuedAgentMessage() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        Instant start = Instant.parse("2026-06-07T00:00:00Z");
        Instant end = Instant.parse("2026-06-07T00:00:01Z");
        when(executor.supports(eq("i-container"), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(eq("i-container"), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenReturn(Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                        "Success", "hello\n", "", 0, start, end)));

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-container"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["echo hello"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertEquals("InProgress", command.getStatus());
        assertEquals("In Progress", command.getStatusDetails());
        assertEquals(0, command.getCompletedCount());
        assertEquals("Success", waitForCommandStatus(service, command.getCommandId(), "us-west-2"));

        CommandInvocation invocation = service.getCommandInvocation(command.getCommandId(), "i-container", "us-west-2");
        assertEquals("Success", invocation.getStatus());
        assertEquals(0, invocation.getResponseCode());
        assertEquals("hello\n", invocation.getStandardOutputContent());
        assertEquals(start, invocation.getExecutionStartDateTime());
        assertEquals(end, invocation.getExecutionEndDateTime());
        assertTrue(service.getMessages("i-container", "request-id", 30).isEmpty());
    }

    @Test
    void unsupportedDirectExecutionFallsBackToAgentQueue() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        when(executor.supports(eq("i-agent"), eq("AWS-RunShellScript"))).thenReturn(false);

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-agent"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["echo hello"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertEquals("InProgress", command.getStatus());
        assertEquals("In Progress", command.getStatusDetails());
        CommandInvocation invocation = service.getCommandInvocation(command.getCommandId(), "i-agent", "us-west-2");
        assertEquals("Pending", invocation.getStatus());
        assertEquals(1, service.getMessages("i-agent", "request-id", 30).size());
    }

    @Test
    void unavailableInstanceFailsQueuedCommandInvocation() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        when(executor.supports(eq("i-stale"), eq("AWS-RunShellScript"))).thenReturn(false);

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-stale"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["echo hello"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertEquals(1, service.failActiveInvocationsForInstance("us-west-2", "i-stale", "Undeliverable"));

        CommandInvocation invocation = service.getCommandInvocation(command.getCommandId(), "i-stale", "us-west-2");
        assertEquals("Failed", invocation.getStatus());
        assertEquals("Undeliverable", invocation.getStatusDetails());
        assertEquals(-1, invocation.getResponseCode());
        assertEquals("Failed", service.listCommands(command.getCommandId(), null, "us-west-2").getFirst().getStatus());
        assertTrue(service.getMessages("i-stale", "request-id", 30).isEmpty());
    }

    @Test
    void unavailableInstancesFailQueuedCommandInvocationsInBulk() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        when(executor.supports(any(), eq("AWS-RunShellScript"))).thenReturn(false);

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-stale-a", "i-stale-b", "i-active"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["echo hello"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertEquals(2, service.failActiveInvocationsForInstances(
                "us-west-2", Set.of("i-stale-a", "i-stale-b"), "Undeliverable"));

        assertEquals("Failed", service.getCommandInvocation(command.getCommandId(), "i-stale-a", "us-west-2").getStatus());
        assertEquals("Failed", service.getCommandInvocation(command.getCommandId(), "i-stale-b", "us-west-2").getStatus());
        assertEquals("Pending", service.getCommandInvocation(command.getCommandId(), "i-active", "us-west-2").getStatus());
        assertTrue(service.getMessages("i-stale-a", "request-id", 30).isEmpty());
        assertTrue(service.getMessages("i-stale-b", "request-id", 30).isEmpty());
        assertEquals(1, service.getMessages("i-active", "request-id", 30).size());
    }

    @Test
    void directTimeoutMarksCommandTimedOut() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        Instant start = Instant.parse("2026-06-07T00:00:00Z");
        Instant end = Instant.parse("2026-06-07T00:00:30Z");
        when(executor.supports(eq("i-timeout"), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(eq("i-timeout"), eq("AWS-RunShellScript"), any(), eq(30)))
                .thenReturn(Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                        "TimedOut", "", "Timed out after 30s", -1, start, end)));

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-timeout"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["sleep 100"]
                  },
                  "TimeoutSeconds": 30
                }
                """), "us-west-2");

        assertEquals("TimedOut", waitForCommandStatus(service, command.getCommandId(), "us-west-2"));
        Command updatedCommand = service.listCommands(command.getCommandId(), null, "us-west-2").getFirst();
        assertEquals("Execution Timed Out", updatedCommand.getStatusDetails());

        CommandInvocation invocation = service.getCommandInvocation(command.getCommandId(), "i-timeout", "us-west-2");
        assertEquals("TimedOut", invocation.getStatus());
        assertEquals("Execution Timed Out", invocation.getStatusDetails());
        assertEquals(-1, invocation.getResponseCode());
        assertEquals("Timed out after 30s", invocation.getStandardErrorContent());
    }

    @Test
    void mixedDirectAndQueuedTargetsRemainInProgress() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        Instant start = Instant.parse("2026-06-07T00:00:00Z");
        Instant end = Instant.parse("2026-06-07T00:00:01Z");
        when(executor.supports(eq("i-container"), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.supports(eq("i-agent"), eq("AWS-RunShellScript"))).thenReturn(false);
        when(executor.executeIfSupported(eq("i-container"), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenReturn(Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                        "Success", "done\n", "", 0, start, end)));

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-container", "i-agent"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["echo done"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertEquals("InProgress", command.getStatus());
        assertEquals("In Progress", command.getStatusDetails());
        waitForInvocationStatus(service, command.getCommandId(), "i-container", "us-west-2", "Success");
        Command updatedCommand = service.listCommands(command.getCommandId(), null, "us-west-2").getFirst();
        assertEquals(1, updatedCommand.getCompletedCount());
        assertEquals(0, command.getErrorCount());
        assertEquals("Success",
                service.getCommandInvocation(command.getCommandId(), "i-container", "us-west-2").getStatus());
        assertEquals("Pending",
                service.getCommandInvocation(command.getCommandId(), "i-agent", "us-west-2").getStatus());
        assertEquals(1, service.getMessages("i-agent", "request-id", 30).size());
    }

    @Test
    void directExecutionReturnsBeforeContainerCommandCompletes() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Instant start = Instant.parse("2026-06-07T00:00:00Z");
        Instant end = Instant.parse("2026-06-07T00:00:01Z");
        when(executor.supports(eq("i-container"), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(eq("i-container"), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenAnswer(invocation -> {
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                            "Success", "done\n", "", 0, start, end));
                });

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-container"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["sleep 1 && echo done"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertEquals("InProgress", command.getStatus());
        assertEquals("In Progress", command.getStatusDetails());
        CommandInvocation invocation = service.getCommandInvocation(command.getCommandId(), "i-container", "us-west-2");
        assertEquals("InProgress", invocation.getStatus());
        assertEquals("In Progress", invocation.getStatusDetails());
        assertTrue(started.await(5, TimeUnit.SECONDS));

        release.countDown();
        assertEquals("Success", waitForCommandStatus(service, command.getCommandId(), "us-west-2"));
    }

    @Test
    void directExecutionThatBecomesUnsupportedFallsBackToAgentQueue() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        when(executor.supports(eq("i-container"), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(eq("i-container"), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenReturn(Optional.empty());

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-container"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["echo hello"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertEquals("InProgress", command.getStatus());
        assertEquals(1, waitForMessageCount(service, "i-container"));
        CommandInvocation invocation = service.getCommandInvocation(command.getCommandId(), "i-container", "us-west-2");
        assertEquals("Pending", invocation.getStatus());
        assertEquals("Pending", invocation.getStatusDetails());
    }

    @Test
    void directExecutionUsesAwsOutputLimitsFromBeginning() throws Exception {
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        String stdout = "o".repeat(24_010) + "tail";
        String stderr = "e".repeat(8_010) + "tail";
        when(executor.supports(eq("i-container"), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(eq("i-container"), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenReturn(Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                        "Failed", stdout, stderr, 1, Instant.parse("2026-06-07T00:00:00Z"), Instant.parse("2026-06-07T00:00:01Z"))));

        SsmCommandService service = new SsmCommandService(
                new InMemoryStorageFactory(),
                objectMapper,
                regionResolver,
                executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-container"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["printf output"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertEquals("Failed", waitForCommandStatus(service, command.getCommandId(), "us-west-2"));
        CommandInvocation invocation = service.getCommandInvocation(command.getCommandId(), "i-container", "us-west-2");
        assertEquals(24_000, invocation.getStandardOutputContent().length());
        assertEquals(8_000, invocation.getStandardErrorContent().length());
        assertEquals("o".repeat(24_000), invocation.getStandardOutputContent());
        assertEquals("e".repeat(8_000), invocation.getStandardErrorContent());
    }

    private static String waitForCommandStatus(SsmCommandService service, String commandId, String region) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Command command = service.listCommands(commandId, null, region).getFirst();
            if (!"InProgress".equals(command.getStatus())) {
                return command.getStatus();
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return service.listCommands(commandId, null, region).getFirst().getStatus();
    }

    private static void waitForInvocationStatus(SsmCommandService service, String commandId, String instanceId, String region, String status) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (status.equals(service.getCommandInvocation(commandId, instanceId, region).getStatus())) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static int waitForMessageCount(SsmCommandService service, String instanceId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            int messageCount = service.getMessages(instanceId, "request-id", 30).size();
            if (messageCount > 0) {
                return messageCount;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return 0;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return new InMemoryStorage<>();
        }
    }
}
