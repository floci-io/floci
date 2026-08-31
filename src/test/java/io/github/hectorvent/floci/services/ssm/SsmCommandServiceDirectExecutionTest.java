package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
    void cancelAndConcurrentRollup_areMutuallyExclusiveForTheSameCommand() throws Exception {
        // Regression: even with the self-consistency fix above, cancelCommand's own status +
        // statusDetails write could still land entirely inside updateCommandStatus's window between
        // ITS two field writes - leaving status=Cancelled but statusDetails reflecting the rollup's
        // stale completion status, or the reverse. Both methods now synchronize on the shared Command
        // object (commandStore's get() always returns the same instance for a key) before touching
        // its status fields, so the two calls can't interleave with each other at all, closing this
        // regardless of which field either one happens to write first.
        //
        // Proving this directly rather than by timing: commandStore.put() is called from inside the
        // synchronized block in both methods and is a real interception point (unlike the two setter
        // calls themselves), so a spy can force updateCommandStatus's finalize write to block while
        // it still holds the lock, and assert a concurrent cancelCommand call blocks too rather than
        // racing past it - the same style already used to prove the code-volume lock in
        // ContainerLauncherTest's cleanupAndAConcurrentRollback... test.
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        Instant start = Instant.parse("2026-06-07T00:00:00Z");
        Instant end = Instant.parse("2026-06-07T00:00:01Z");
        when(executor.supports(eq("i-race"), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(eq("i-race"), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenReturn(Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                        "Success", "done\n", "", 0, start, end)));

        CountDownLatch rollupHoldingLock = new CountDownLatch(1);
        CountDownLatch releaseRollup = new CountDownLatch(1);
        AccountAwareStorageBackend<Command> realCommandStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Command> commandStore = spy(realCommandStore);
        doAnswer(invocation -> {
            Command value = invocation.getArgument(1);
            // Only the finalize write (status=Success) blocks - the initial creation put (status=
            // InProgress) must go through immediately, or sendCommand itself would never return.
            if ("Success".equals(value.getStatus())) {
                rollupHoldingLock.countDown();
                assertTrue(releaseRollup.await(5, TimeUnit.SECONDS),
                        "test did not release the rollup in time");
            }
            return invocation.callRealMethod();
        }).when(commandStore).put(any(), any());

        SsmCommandService service = new SsmCommandService(
                new SingleCommandStoreFactory(commandStore), objectMapper, regionResolver, executor);

        Command command = service.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-race"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["echo hi"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertTrue(rollupHoldingLock.await(5, TimeUnit.SECONDS), "rollup never reached its blocking put()");

        AtomicBoolean cancelReturned = new AtomicBoolean(false);
        Thread cancelThread = new Thread(() -> {
            service.cancelCommand(command.getCommandId(), null, "us-west-2");
            cancelReturned.set(true);
        });
        cancelThread.start();

        // Give cancelCommand every chance to (wrongly) proceed if the lock weren't shared.
        Thread.sleep(200);
        assertFalse(cancelReturned.get(),
                "cancelCommand must block while the rollup holds the command's lock, not race past it");

        releaseRollup.countDown();
        cancelThread.join(5000);
        assertTrue(cancelReturned.get(), "cancelCommand should complete once the rollup releases the lock");

        Command finalCommand = service.listCommands(command.getCommandId(), null, "us-west-2").getFirst();
        assertEquals("Cancelled", finalCommand.getStatus());
        assertEquals("Cancelled", finalCommand.getStatusDetails());
    }

    @Test
    void listCommandsBlocksWhileARollupHoldsTheCommandLock() throws Exception {
        // Regression flagged in review on PR #2477: even with writers now synchronized against each
        // other, SsmJsonHandler.commandToNode() (the ListCommands serializer) used to read Status and
        // StatusDetails from the same live, shared Command object without taking that same lock. A
        // ListCommands call whose two field reads straddled a concurrent writer's update could observe
        // a torn pair - e.g. Status read before the writer's change, StatusDetails read after it
        // completes - reporting a combination that was never actually true at any single instant. This
        // is plausibly the actual path #2264 was originally observed through, since a customer polls
        // status via ListCommands, not the internal rollup directly.
        //
        // Proven the same way as the writer-vs-writer case: commandStore.put() is called from inside
        // the synchronized block that guards the write, so a spy can force the rollup to block while
        // still holding the lock, and this asserts a concurrent ListCommands call (which now also
        // synchronizes on the same object before reading) blocks too rather than observing a torn read.
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        Instant start = Instant.parse("2026-06-07T00:00:00Z");
        Instant end = Instant.parse("2026-06-07T00:00:01Z");
        when(executor.supports(eq("i-race"), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(eq("i-race"), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenReturn(Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                        "Success", "done\n", "", 0, start, end)));

        CountDownLatch rollupHoldingLock = new CountDownLatch(1);
        CountDownLatch releaseRollup = new CountDownLatch(1);
        AccountAwareStorageBackend<Command> realCommandStore = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Command> commandStore = spy(realCommandStore);
        doAnswer(invocation -> {
            Command value = invocation.getArgument(1);
            if ("Success".equals(value.getStatus())) {
                rollupHoldingLock.countDown();
                assertTrue(releaseRollup.await(5, TimeUnit.SECONDS),
                        "test did not release the rollup in time");
            }
            return invocation.callRealMethod();
        }).when(commandStore).put(any(), any());

        SsmCommandService commandService = new SsmCommandService(
                new SingleCommandStoreFactory(commandStore), objectMapper, regionResolver, executor);
        SsmJsonHandler jsonHandler = new SsmJsonHandler(mock(SsmService.class), commandService, objectMapper);

        Command command = commandService.sendCommand(objectMapper.readTree("""
                {
                  "InstanceIds": ["i-race"],
                  "DocumentName": "AWS-RunShellScript",
                  "Parameters": {
                    "commands": ["echo hi"]
                  },
                  "TimeoutSeconds": 60
                }
                """), "us-west-2");

        assertTrue(rollupHoldingLock.await(5, TimeUnit.SECONDS), "rollup never reached its blocking put()");

        ObjectNode listRequest = objectMapper.createObjectNode().put("CommandId", command.getCommandId());
        AtomicBoolean listReturned = new AtomicBoolean(false);
        Thread listThread = new Thread(() -> {
            jsonHandler.handle("ListCommands", listRequest, "us-west-2");
            listReturned.set(true);
        });
        listThread.start();

        // Give ListCommands every chance to (wrongly) proceed if the read weren't guarded by the
        // same lock.
        Thread.sleep(200);
        assertFalse(listReturned.get(),
                "ListCommands must block while the rollup holds the command's lock, not observe a torn read");

        releaseRollup.countDown();
        listThread.join(5000);
        assertTrue(listReturned.get(), "ListCommands should complete once the rollup releases the lock");
    }

    @Test
    void concurrentInstanceCompletions_neverLeaveStatusAndStatusDetailsInconsistent() throws Exception {
        // Regression for #2264: commandStore is a plain ConcurrentHashMap-backed InMemoryStorage
        // whose get() returns the SAME shared Command object on every call, not a defensive copy. A
        // multi-instance command's last two instances completing at (nearly) the same moment can both
        // observe "all done" and both enter updateCommandStatus's finalize branch concurrently on that
        // one shared object. The finalize branch used to re-read command.getStatus() instead of the
        // local variable it had just computed, so whichever thread's write landed last could leave
        // statusDetails reflecting a DIFFERENT status than the one actually stored - exactly the
        // reported symptom (status=TimedOut, statusDetails=In Progress).
        //
        // A CyclicBarrier forces every instance's mocked completion to release as close to
        // simultaneously as Java threads allow, with many instances (not just two) and many trials
        // to make several concurrently reaching the finalize branch as likely as this test can make
        // it. In practice even 1200 such attempts (12 instances x 100 trials) never landed a thread
        // exactly between the old code's setStatus() and its re-read of getStatus() - that gap is a
        // few nanoseconds of adjacent bytecode, far narrower than normal thread-scheduling
        // granularity in a clean, uncontended test JVM. That matches the issue's own report (passes
        // locally, only flakes under real CI load, where GC pauses and dozens of other tests
        // competing for CPU create far more scheduling noise than this test can reproduce alone).
        // This test therefore isn't a fails-before/passes-after reproduction of the historical bug;
        // it's a standing invariant check that concurrent completions never leave status and
        // statusDetails disagreeing, which the fix satisfies by construction: statusDetails is now
        // computed from a local variable no other thread can touch, so the specific TOCTOU window
        // is closed regardless of scheduling, not merely made statistically unlikely to hit.
        int instanceCount = 12;
        int trials = 100;
        for (int trial = 0; trial < trials; trial++) {
            SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
            Instant start = Instant.parse("2026-06-07T00:00:00Z");
            Instant end = Instant.parse("2026-06-07T00:00:01Z");
            CyclicBarrier barrier = new CyclicBarrier(instanceCount);
            List<String> instanceIds = new ArrayList<>();
            for (int i = 0; i < instanceCount; i++) {
                String instanceId = "i-" + i;
                instanceIds.add(instanceId);
                when(executor.supports(eq(instanceId), eq("AWS-RunShellScript"))).thenReturn(true);
                when(executor.executeIfSupported(eq(instanceId), eq("AWS-RunShellScript"), any(), eq(60)))
                        .thenAnswer(invocation -> {
                            barrier.await(5, TimeUnit.SECONDS);
                            return Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                                    "Success", "done\n", "", 0, start, end));
                        });
            }

            SsmCommandService service = new SsmCommandService(
                    new InMemoryStorageFactory(), objectMapper, regionResolver, executor);

            ObjectNode request = objectMapper.createObjectNode();
            ArrayNode instanceIdsNode = request.putArray("InstanceIds");
            instanceIds.forEach(instanceIdsNode::add);
            request.put("DocumentName", "AWS-RunShellScript");
            request.putObject("Parameters").putArray("commands").add("echo hi");
            request.put("TimeoutSeconds", 60);

            Command command = service.sendCommand(request, "us-west-2");

            String finalStatus = waitForCommandStatus(service, command.getCommandId(), "us-west-2");
            Command updated = service.listCommands(command.getCommandId(), null, "us-west-2").getFirst();
            assertEquals("Success", finalStatus, "trial " + trial + ": command status");
            assertEquals("Success", updated.getStatus(), "trial " + trial + ": command status on re-fetch");
            assertEquals("Success", updated.getStatusDetails(), "trial " + trial + ": statusDetails must match status");
        }
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
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }

    /** Like InMemoryStorageFactory, but hands out a caller-supplied backend for ssm-commands.json
     *  specifically, so a test can wrap it (e.g. via Mockito spy) to control its timing. */
    private static final class SingleCommandStoreFactory extends StorageFactory {
        private final AccountAwareStorageBackend<Command> commandStore;

        private SingleCommandStoreFactory(AccountAwareStorageBackend<Command> commandStore) {
            super(null, null);
            this.commandStore = commandStore;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            if ("ssm-commands.json".equals(fileName)) {
                return (AccountAwareStorageBackend<V>) commandStore;
            }
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
