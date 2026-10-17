package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class SsmDirectCommandAccountIntegrationTest {
    @Inject StorageFactory storageFactory;
    @Inject RegionResolver regionResolver;
    @InjectMock SsmDirectCommandExecutor executor;
    @Inject SsmCommandService commandService;
    @TestHTTPResource URI endpoint;

    @BeforeEach
    void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        reset(executor);
    }

    @ParameterizedTest
    @CsvSource({
            "000000000000,us-east-1,false", "000000000000,us-east-1,true",
            "222222222222,us-west-2,false", "222222222222,us-west-2,true",
            "333333333333,cn-north-1,false", "333333333333,cn-north-1,true"
    })
    void directWorkerRetainsRequestOwnerForResultAndFallback(String account, String region, boolean fallback)
            throws Exception {
        String instance = "i-scope-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<String> workerAccount = new AtomicReference<>();
        AtomicReference<String> workerRegion = new AtomicReference<>();
        AtomicReference<String> workerPartition = new AtomicReference<>();
        when(executor.supports(account, instance, "AWS-RunShellScript")).thenReturn(true);
        when(executor.executeIfSupported(eq(account), eq(instance), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenAnswer(invocation -> {
                    worker.set(Thread.currentThread());
                    workerAccount.set(regionResolver.getAccountId());
                    workerRegion.set(regionResolver.getRegion());
                    workerPartition.set(regionResolver.getPartition());
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return fallback ? Optional.empty() : Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                            "Success", "owned output", "owned stderr", 0, Instant.now(), Instant.now()));
                });
        AccountAwareStorageBackend<Command> commands = storageFactory.create(
                "ssm", "ssm-commands.json", new TypeReference<>() {});
        AccountAwareStorageBackend<CommandInvocation> invocations = storageFactory.create(
                "ssm", "ssm-invocations.json", new TypeReference<>() {});
        String commandId = call(account, region, "AmazonSSM.SendCommand", Map.of(
                "DocumentName", "AWS-RunShellScript", "InstanceIds", List.of(instance),
                "Parameters", Map.of("commands", List.of("echo owned")), "TimeoutSeconds", 60))
                .jsonPath().getString("Command.CommandId");
        assertNotNull(commandId);
        String commandKey = region + "::" + commandId;
        String invocationKey = commandKey + "::" + instance;
        try {
            assertTrue(commands.getForAccount(account, commandKey).isPresent());
            assertTrue(invocations.getForAccount(account, invocationKey).isPresent());
            assertEquals("InProgress", commands.getForAccount(account, commandKey).orElseThrow().getStatus());
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Worker did not resolve its issuing account's invocation");
            assertEquals(account, workerAccount.get());
            assertEquals(region, workerRegion.get());
            assertEquals(AwsRegions.partitionFor(region), workerPartition.get());
            release.countDown();
            worker.get().join(5000);
            assertFalse(worker.get().isAlive(), "Direct worker did not finish");
            Response result = call(account, region, "AmazonSSM.GetCommandInvocation", Map.of(
                    "CommandId", commandId, "InstanceId", instance));
            assertEquals(fallback ? "Pending" : "Success", result.jsonPath().getString("Status"));
            if (fallback) {
                Response poll = call(account, region, "AmazonSSMMessageDeliveryService.GetMessages",
                        Map.of("Destination", instance, "MessagesRequestId", "owned-request"));
                assertEquals(1, poll.jsonPath().getList("Messages").size());
                assertEquals("aws.ssm.sendCommand." + region, poll.jsonPath().getString("Messages[0].Topic"));
            } else {
                assertEquals("owned output", result.jsonPath().getString("StandardOutputContent"));
                assertEquals("owned stderr", result.jsonPath().getString("StandardErrorContent"));
                assertEquals("Success", commands.getForAccount(account, commandKey).orElseThrow().getStatus());
                assertEquals(1, commands.getForAccount(account, commandKey).orElseThrow().getCompletedCount());
            }
            for (String other : List.of("000000000000", "222222222222", "333333333333")) {
                if (!other.equals(account)) {
                    assertTrue(commands.getForAccount(other, commandKey).isEmpty());
                    assertTrue(invocations.getForAccount(other, invocationKey).isEmpty());
                }
            }
        } finally {
            release.countDown();
            assertAll("release worker and remove every owned fixture",
                    () -> joinWorker(worker),
                    () -> deleteCommand(account, region, commandId, instance));
        }
    }

    @ParameterizedTest
    @CsvSource({"333333333333,us-west-2", "222222222222,cn-north-1"})
    void blockedOwnerDoesNotDelayOtherScopeAndCancellationSurvivesCompletion(String otherAccount, String otherRegion)
            throws Exception {
        String account = "222222222222";
        String region = "us-west-2";
        String instance = "i-scope-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch otherEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> blockedWorker = new AtomicReference<>();
        AtomicReference<Thread> otherWorker = new AtomicReference<>();
        when(executor.supports(anyString(), eq(instance), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(anyString(), eq(instance), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenAnswer(invocation -> {
                    assertEquals(invocation.getArgument(0), regionResolver.getAccountId());
                    if (account.equals(regionResolver.getAccountId()) && region.equals(regionResolver.getRegion())) {
                        blockedWorker.set(Thread.currentThread());
                        entered.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } else {
                        assertEquals(otherAccount, regionResolver.getAccountId());
                        assertEquals(otherRegion, regionResolver.getRegion());
                        otherWorker.set(Thread.currentThread());
                        otherEntered.countDown();
                    }
                    return Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                            "Success", "scope output", "", 0, Instant.now(), Instant.now()));
                });
        Map<String, Object> request = Map.of("DocumentName", "AWS-RunShellScript", "InstanceIds", List.of(instance),
                "Parameters", Map.of("commands", List.of("echo scope")), "TimeoutSeconds", 60);
        String firstId = call(account, region, "AmazonSSM.SendCommand", request)
                .jsonPath().getString("Command.CommandId");
        String secondId = null;
        try {
            assertOwnedInvocation(account, region, firstId, instance);
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Owner worker did not resolve its stored invocation");
            secondId = call(otherAccount, otherRegion, "AmazonSSM.SendCommand", request)
                    .jsonPath().getString("Command.CommandId");
            assertOwnedInvocation(otherAccount, otherRegion, secondId, instance);
            assertTrue(otherEntered.await(3, TimeUnit.SECONDS), "Unrelated scope was blocked by direct execution");
            otherWorker.get().join(3000);
            assertFalse(otherWorker.get().isAlive());
            assertEquals("Success", call(otherAccount, otherRegion, "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", secondId, "InstanceId", instance)).jsonPath().getString("Status"));
            call(account, region, "AmazonSSM.CancelCommand", Map.of("CommandId", firstId));
            release.countDown();
            blockedWorker.get().join(3000);
            assertFalse(blockedWorker.get().isAlive());
            assertEquals("Cancelled", call(account, region, "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", firstId, "InstanceId", instance)).jsonPath().getString("Status"));
            assertTrue(call(account, region, "AmazonSSMMessageDeliveryService.GetMessages",
                    Map.of("Destination", instance)).jsonPath().getList("Messages").isEmpty());
        } finally {
            release.countDown();
            String otherId = secondId;
            assertAll("release both workers and remove every owned fixture",
                    () -> joinWorker(blockedWorker),
                    () -> joinWorker(otherWorker),
                    () -> deleteCommand(account, region, firstId, instance),
                    () -> {
                        if (otherId != null) {
                            deleteCommand(otherAccount, otherRegion, otherId, instance);
                        }
                    });
        }
    }

    @ParameterizedTest
    @CsvSource({"333333333333,us-west-2", "222222222222,cn-north-1"})
    void foreignScopeCannotPollDirectFallback(String foreignAccount, String foreignRegion) throws Exception {
        String instance = "i-fallback-" + UUID.randomUUID();
        String command = directFallback(instance);
        try {
            assertTrue(call(foreignAccount, foreignRegion, "AmazonSSMMessageDeliveryService.GetMessages",
                    Map.of("Destination", instance)).jsonPath().getList("Messages").isEmpty());
            Response own = call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.GetMessages",
                    Map.of("Destination", instance));
            assertEquals(1, own.jsonPath().getList("Messages").size());
            assertEquals(command, new ObjectMapper().readTree(Base64.getDecoder()
                    .decode(own.jsonPath().getString("Messages[0].Payload"))).path("CommandId").asText());
        } finally {
            deleteCommand("222222222222", "us-west-2", command, instance);
        }
    }

    @ParameterizedTest
    @CsvSource({"333333333333,us-west-2,AcknowledgeMessage", "333333333333,us-west-2,SendReply",
            "333333333333,us-west-2,FailMessage", "333333333333,us-west-2,DeleteMessage",
            "222222222222,cn-north-1,AcknowledgeMessage", "222222222222,cn-north-1,SendReply",
            "222222222222,cn-north-1,FailMessage", "222222222222,cn-north-1,DeleteMessage"})
    void foreignScopeCannotConsumeFallbackCorrelation(String foreignAccount, String foreignRegion, String operation)
            throws Exception {
        String instance = "i-fallback-" + UUID.randomUUID();
        String command = directFallback(instance);
        try {
            String message = call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.GetMessages",
                    Map.of("Destination", instance)).jsonPath().getString("Messages[0].MessageId");
            assertNotNull(message);
            Map<String, Object> body = Map.of("MessageId", message, "Payload", replyPayload(),
                    "FailureType", "foreign-failure");
            call(foreignAccount, foreignRegion, "AmazonSSMMessageDeliveryService." + operation, body);
            assertEquals("Pending", call("222222222222", "us-west-2", "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", command, "InstanceId", instance)).jsonPath().getString("Status"));
            call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.AcknowledgeMessage", body);
            assertEquals("InProgress", call("222222222222", "us-west-2", "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", command, "InstanceId", instance)).jsonPath().getString("Status"));
            call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.SendReply", body);
            Response result = call("222222222222", "us-west-2", "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", command, "InstanceId", instance));
            assertEquals("Success", result.jsonPath().getString("Status"));
            assertEquals("owned agent output", result.jsonPath().getString("StandardOutputContent"));
        } finally {
            deleteCommand("222222222222", "us-west-2", command, instance);
        }
    }

    @ParameterizedTest
    @CsvSource({"333333333333,us-west-2,false,false", "333333333333,us-west-2,false,true",
            "333333333333,us-west-2,true,false", "333333333333,us-west-2,true,true",
            "222222222222,cn-north-1,false,false", "222222222222,cn-north-1,false,true",
            "222222222222,cn-north-1,true,false", "222222222222,cn-north-1,true,true"})
    void peerCleanupPreservesOwnerQueueAndCorrelation(String peerAccount, String peerRegion,
                                                       boolean polled, boolean cancel) throws Exception {
        String instance = "i-fallback-" + UUID.randomUUID();
        String command = directFallback(instance);
        String peerCommand = null;
        try {
            peerCommand = directFallback(peerAccount, peerRegion, instance);
            String message = polled ? call("222222222222", "us-west-2",
                    "AmazonSSMMessageDeliveryService.GetMessages", Map.of("Destination", instance))
                    .jsonPath().getString("Messages[0].MessageId") : null;
            if (polled) {
                assertNotNull(message);
            }
            if (cancel) {
                call(peerAccount, peerRegion, "AmazonSSM.CancelCommand", Map.of("CommandId", peerCommand));
            } else {
                RequestScopes.runAs(peerAccount, peerRegion, () -> assertEquals(1,
                        commandService.failActiveInvocationsForInstance(peerRegion, instance, "peer stopped")));
            }
            assertTrue(call(peerAccount, peerRegion, "AmazonSSMMessageDeliveryService.GetMessages",
                    Map.of("Destination", instance)).jsonPath().getList("Messages").isEmpty());
            if (!polled) {
                message = call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.GetMessages",
                        Map.of("Destination", instance)).jsonPath().getString("Messages[0].MessageId");
                assertNotNull(message);
            }
            call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.SendReply",
                    Map.of("MessageId", message, "Payload", replyPayload()));
            assertEquals("Success", call("222222222222", "us-west-2", "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", command, "InstanceId", instance)).jsonPath().getString("Status"));
        } finally {
            String peer = peerCommand;
            assertAll(() -> deleteCommand("222222222222", "us-west-2", command, instance),
                    () -> { if (peer != null) { deleteCommand(peerAccount, peerRegion, peer, instance); } });
        }
    }

    @ParameterizedTest
    @CsvSource({"FailMessage,Failed", "DeleteMessage,Pending"})
    void ownerCanFailOrDeleteFallbackCorrelation(String operation, String expected) throws Exception {
        String instance = "i-fallback-" + UUID.randomUUID();
        String command = directFallback(instance);
        try {
            String message = call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.GetMessages",
                    Map.of("Destination", instance)).jsonPath().getString("Messages[0].MessageId");
            assertNotNull(message);
            Map<String, Object> body = Map.of("MessageId", message, "FailureType", "owner failure",
                    "Payload", replyPayload());
            call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService." + operation, body);
            call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.AcknowledgeMessage", body);
            call("222222222222", "us-west-2", "AmazonSSMMessageDeliveryService.SendReply", body);
            Response result = call("222222222222", "us-west-2", "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", command, "InstanceId", instance));
            assertEquals(expected, result.jsonPath().getString("Status"));
            if (operation.equals("FailMessage")) {
                assertEquals("Failed: owner failure", result.jsonPath().getString("StatusDetails"));
            }
        } finally {
            deleteCommand("222222222222", "us-west-2", command, instance);
        }
    }

    private String directFallback(String instance) throws Exception {
        return directFallback("222222222222", "us-west-2", instance);
    }

    private String directFallback(String account, String region, String instance) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(executor.supports(account, instance, "AWS-RunShellScript")).thenReturn(true);
        when(executor.executeIfSupported(eq(account), eq(instance), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenAnswer(invocation -> {
                    worker.set(Thread.currentThread());
                    entered.countDown();
                    return Optional.empty();
                });
        String command = call(account, region, "AmazonSSM.SendCommand", Map.of("DocumentName", "AWS-RunShellScript",
                "InstanceIds", List.of(instance), "Parameters", Map.of("commands", List.of("echo owned")),
                "TimeoutSeconds", 60)).jsonPath().getString("Command.CommandId");
        try {
            assertNotNull(command);
            assertOwnedInvocation(account, region, command, instance);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            joinWorker(worker);
            assertEquals("Pending", call(account, region, "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", command, "InstanceId", instance)).jsonPath().getString("Status"));
            return command;
        } catch (Throwable failure) {
            assertAll(() -> { throw failure; }, () -> joinWorker(worker),
                    () -> deleteCommand(account, region, command, instance));
            throw new AssertionError("unreachable");
        }
    }

    private static String replyPayload() {
        return Base64.getEncoder().encodeToString(("{\"runtimeStatus\":{\"aws:runShellScript\":{"
                + "\"status\":\"Success\",\"code\":0,\"output\":\"owned agent output\"}}}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private void assertOwnedInvocation(String account, String region, String commandId, String instance) {
        AccountAwareStorageBackend<Command> commands = storageFactory.create(
                "ssm", "ssm-commands.json", new TypeReference<>() {});
        AccountAwareStorageBackend<CommandInvocation> invocations = storageFactory.create(
                "ssm", "ssm-invocations.json", new TypeReference<>() {});
        String key = region + "::" + commandId;
        assertTrue(commands.getForAccount(account, key).isPresent());
        assertTrue(invocations.getForAccount(account, key + "::" + instance).isPresent());
    }

    private void deleteCommand(String account, String region, String commandId, String instance) {
        AccountAwareStorageBackend<Command> commands = storageFactory.create(
                "ssm", "ssm-commands.json", new TypeReference<>() {});
        AccountAwareStorageBackend<CommandInvocation> invocations = storageFactory.create(
                "ssm", "ssm-invocations.json", new TypeReference<>() {});
        String key = region + "::" + commandId;
        assertAll("remove owned queue, command and invocation",
                () -> RequestScopes.runAs(account, region, () ->
                        commandService.failActiveInvocationsForInstance(region, instance, "fixture cleanup")),
                () -> {
                    commands.deleteForAccount(account, key);
                    assertTrue(commands.getForAccount(account, key).isEmpty());
                },
                () -> {
                    invocations.deleteForAccount(account, key + "::" + instance);
                    assertTrue(invocations.getForAccount(account, key + "::" + instance).isEmpty());
                });
    }

    private void joinWorker(AtomicReference<Thread> worker) throws InterruptedException {
        if (worker.get() != null) {
            worker.get().join(5000);
            assertFalse(worker.get().isAlive(), "Owned direct worker did not finish");
        }
    }

    private Response call(String account, String region, String action, Map<String, Object> body) {
        return given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .header("Authorization", EnforcementFixtures.auth(account, region, "ssm"))
                .header("X-Amz-Target", action).body(body).post("/")
                .then().statusCode(200).extract().response();
    }
}
