package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ssm.model.Command;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SsmShellExecutionBudgetTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"0,3600", "5,5", "1,1", "172800,172800"})
    void directWorkerUsesDocumentExecutionBudget(int supplied, int expected) throws Exception {
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn("000000000000");
        when(resolver.getRegion()).thenReturn("us-east-1");
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        AtomicInteger captured = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(executor.supports(any(), anyString(), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(any(), anyString(), eq("AWS-RunShellScript"), any(), anyInt()))
                .thenAnswer(call -> {
                    worker.set(Thread.currentThread());
                    captured.set(call.getArgument(4));
                    return Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                            "Success", "ok", "", 0, Instant.now(), Instant.now()));
                });
        SsmCommandService service = new SsmCommandService(new InMemoryStorageFactory("000000000000"),
                mapper, resolver, executor);
        try {
            Command command = service.sendCommand(request(supplied), "us-east-1");
            await().atMost(Duration.ofSeconds(5)).until(() -> worker.get() != null);
            worker.get().join(5000);
            assertFalse(worker.get().isAlive());
            assertAll("delivery and execution are separate",
                    () -> assertEquals(expected, captured.get()),
                    () -> assertEquals(30, command.getTimeoutSeconds()),
                    () -> assertEquals(30L + expected, Duration.between(
                            command.getRequestedDateTime(), command.getExpiresAfter()).toSeconds()),
                    () -> assertEquals("Success", service.getCommandInvocation(
                            command.getCommandId(), "i-budget", "us-east-1").getStatus()));
        } finally {
            service.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,0,3600", "false,5,5", "true,0,3600", "true,5,5"})
    void queuedAndDirectFallbackKeepThePublicDocumentDefault(boolean fallback, int supplied, int expected)
            throws Exception {
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn("000000000000");
        when(resolver.getRegion()).thenReturn("us-east-1");
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger captured = new AtomicInteger();
        when(executor.supports(any(), anyString(), eq("AWS-RunShellScript"))).thenReturn(fallback);
        when(executor.executeIfSupported(any(), anyString(), eq("AWS-RunShellScript"), any(), anyInt()))
                .thenAnswer(call -> {
                    worker.set(Thread.currentThread());
                    captured.set(call.getArgument(4));
                    return Optional.empty();
                });
        SsmCommandService service = new SsmCommandService(new InMemoryStorageFactory("000000000000"),
                mapper, resolver, executor);
        try {
            Command command = service.sendCommand(request(supplied), "us-east-1");
            if (fallback) {
                await().atMost(Duration.ofSeconds(5)).until(() -> worker.get() != null);
                worker.get().join(5000);
                assertFalse(worker.get().isAlive());
            }
            List<Map<String, Object>> messages = service.getMessages("i-budget", "budget", 30);
            assertEquals(1, messages.size());
            JsonNode payload = mapper.readTree(new String(Base64.getDecoder().decode(
                    (String) messages.getFirst().get("Payload")), StandardCharsets.UTF_8));
            assertAll("fallback retains the effective budget without changing the document default",
                    () -> assertEquals("3600", payload.at("/DocumentContent/parameters/executionTimeout/default")
                            .asText()),
                    () -> assertEquals(30L + expected, Duration.between(
                            command.getRequestedDateTime(), command.getExpiresAfter()).toSeconds()),
                    () -> {
                        if (fallback) {
                            assertEquals(expected, captured.get());
                        }
                    },
                    () -> {
                        if (supplied != 0) {
                            assertEquals(Integer.toString(supplied),
                                    payload.at("/Parameters/executionTimeout/0").asText());
                        }
                    });
        } finally {
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 30})
    void internalHookKeepsItsSuppliedExecutionBudget(int timeout) throws Exception {
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn("000000000000");
        when(resolver.getRegion()).thenReturn("us-east-1");
        SsmDirectCommandExecutor executor = mock(SsmDirectCommandExecutor.class);
        SsmCommandService service = new SsmCommandService(new InMemoryStorageFactory("000000000000"),
                mapper, resolver, executor);
        try {
            String id = service.sendCommandToInstance("i-budget", "AWS-RunShellScript",
                    Map.of("commands", List.of("echo hook")), timeout, "us-east-1");
            Command command = service.listCommands(id, null, "us-east-1").getFirst();
            JsonNode payload = mapper.readTree(Base64.getDecoder().decode((String)
                    service.getMessages("i-budget", "hook", 30).getFirst().get("Payload")));
            assertEquals(timeout, command.getTimeoutSeconds());
            assertEquals(timeout, Duration.between(command.getRequestedDateTime(), command.getExpiresAfter())
                    .toSeconds());
            assertEquals(Integer.toString(timeout),
                    payload.at("/DocumentContent/parameters/executionTimeout/default").asText());
            verifyNoInteractions(executor);
        } finally {
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWS-RunPowerShellScript", "CustomDocument"})
    void nonShellBudgetHandlingRemainsUnchanged(String document) throws Exception {
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn("000000000000");
        when(resolver.getRegion()).thenReturn("us-east-1");
        SsmCommandService service = new SsmCommandService(new InMemoryStorageFactory("000000000000"),
                mapper, resolver, mock(SsmDirectCommandExecutor.class));
        try {
            ObjectNode request = request(5);
            request.put("DocumentName", document);
            Command command = service.sendCommand(request, "us-east-1");
            JsonNode payload = mapper.readTree(Base64.getDecoder().decode((String)
                    service.getMessages("i-budget", "other", 30).getFirst().get("Payload")));
            assertEquals(30, Duration.between(command.getRequestedDateTime(), command.getExpiresAfter())
                    .toSeconds());
            assertEquals("30", payload.at("/DocumentContent/parameters/executionTimeout/default").asText());
            assertEquals("5", payload.at("/Parameters/executionTimeout/0").asText());
        } finally {
            service.stop();
        }
    }

    @Test
    void omittedDeliveryAndExecutionProduceTheDefaultTotal() {
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn("000000000000");
        SsmCommandService service = new SsmCommandService(new InMemoryStorageFactory("000000000000"),
                mapper, resolver, mock(SsmDirectCommandExecutor.class));
        try {
            ObjectNode request = request(0);
            request.remove("TimeoutSeconds");
            Command command = service.sendCommand(request, "us-east-1");
            assertEquals(3600, command.getTimeoutSeconds());
            assertEquals(7200, Duration.between(command.getRequestedDateTime(), command.getExpiresAfter())
                    .toSeconds());
        } finally {
            service.stop();
        }
    }

    private ObjectNode request(int supplied) {
        ObjectNode request = mapper.createObjectNode();
        request.put("DocumentName", "AWS-RunShellScript");
        request.putArray("InstanceIds").add("i-budget");
        request.put("TimeoutSeconds", 30);
        ObjectNode parameters = request.putObject("Parameters");
        parameters.putArray("commands").add("echo budget");
        if (supplied != 0) {
            parameters.putArray("executionTimeout").add(Integer.toString(supplied));
        }
        return request;
    }
}
