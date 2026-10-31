package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.MapRun;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineAlias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class StepFunctionsListExecutionsServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ARN = "arn:aws:states:us-east-1:" + ACCOUNT + ":stateMachine:pages";
    private static final Instant TOKEN_TIME = Instant.parse("2026-01-01T00:00:00Z");

    private AccountAwareStorageBackend<Execution> executionStore;
    private MutableClock clock;
    private StepFunctionsService service;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        executionStore = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
        clock = new MutableClock(TOKEN_TIME);
        doReturn(AccountAwareStorageBackend.<StateMachine>inMemory(ACCOUNT))
                .doReturn(executionStore)
                .doReturn(AccountAwareStorageBackend.<Activity>inMemory(ACCOUNT))
                .doReturn(AccountAwareStorageBackend.<MapRun>inMemory(ACCOUNT))
                .doReturn(AccountAwareStorageBackend.<StateMachineAlias>inMemory(ACCOUNT))
                .when(factory).create(anyString(), anyString(), any());
        service = new StepFunctionsService(factory, new RegionResolver("us-east-1", ACCOUNT), null,
                new ObjectMapper(), mock(SfnMockLoader.class), mock(StepFunctionsEventPublisher.class), clock);

        put("new", 3.0, "SUCCEEDED");
        put("running", 2.0, "RUNNING");
        put("old", 1.0, "SUCCEEDED");
    }

    @Test
    void pagesNewestExecutionsAndResumesFromTheOpaqueToken() {
        PaginatedResult<StepFunctionsService.ExecutionSnapshot> first = service.listExecutions(ARN, null, 1, null);
        assertEquals(List.of("new"), first.items().stream().map(snapshot -> snapshot.execution().getName()).toList());
        assertNotNull(first.nextToken());

        PaginatedResult<StepFunctionsService.ExecutionSnapshot> second = service.listExecutions(
                ARN, null, 1, first.nextToken());
        assertEquals(List.of("running"), second.items().stream().map(snapshot -> snapshot.execution().getName())
                .toList());
        assertNotNull(second.nextToken());
    }

    @Test
    void continuationTokenCarriesItsIssueTimeForExpiryValidation() {
        String token = service.listExecutions(ARN, null, 1, null).nextToken();

        String payload = Base64Url.decode(token);
        String scope = "states:executions:" + ACCOUNT + ":" + ARN + ":*";
        String namespace = "states:executions:" + Base64Url.encode(
                Digests.sha256(scope.getBytes(StandardCharsets.UTF_8)));
        assertTrue(payload.startsWith(namespace + ":"), payload);
        assertTrue(payload.substring(namespace.length() + 1)
                .matches("\\d+:\\d{19}:.*\\.[A-Za-z0-9_-]{43}"), payload);
    }

    @Test
    void continuationTokenExpiresAfterTwentyFourHours() {
        String token = service.listExecutions(ARN, null, 1, null).nextToken();

        clock.advance(Duration.ofHours(24));
        PaginatedResult<StepFunctionsService.ExecutionSnapshot> atBoundary = service.listExecutions(
                ARN, null, 1, token);
        assertEquals(List.of("running"), atBoundary.items().stream()
                .map(snapshot -> snapshot.execution().getName()).toList());

        clock.advance(Duration.ofMillis(1));
        AwsException error = assertThrows(AwsException.class,
                () -> service.listExecutions(ARN, null, 1, token));
        assertEquals("InvalidToken", error.getErrorCode());
    }

    @Test
    void longUnicodeNamesStillProduceAUsableTokenWithinTheAwsLengthLimit() {
        String stateMachineName = "界".repeat(80);
        String longArn = "arn:aws:states:us-east-1:" + ACCOUNT + ":stateMachine:" + stateMachineName;
        Execution later = execution(longArn, "a".repeat(80), 1.0, 2.0);
        Execution earlier = execution(longArn, "z".repeat(80), 1.0, 1.0);
        executionStore.put(later.getExecutionArn(), later);
        executionStore.put(earlier.getExecutionArn(), earlier);

        PaginatedResult<StepFunctionsService.ExecutionSnapshot> first = service.listExecutions(
                longArn, null, 1, null);
        assertNotNull(first.nextToken());
        assertTrue(first.nextToken().length() <= 1024,
                "returned token length was " + first.nextToken().length());

        PaginatedResult<StepFunctionsService.ExecutionSnapshot> second = service.listExecutions(
                longArn, null, 1, first.nextToken());
        assertEquals(List.of("z".repeat(80)), second.items().stream()
                .map(snapshot -> snapshot.execution().getName()).toList());
    }

    @Test
    void changingAnExpiredTokensIssueTimeDoesNotExtendItsLifetime() {
        String token = service.listExecutions(ARN, null, 1, null).nextToken();
        String payload = Base64Url.decode(token);
        String scope = "states:executions:" + ACCOUNT + ":" + ARN + ":*";
        String namespace = "states:executions:" + Base64Url.encode(
                Digests.sha256(scope.getBytes(StandardCharsets.UTF_8)));

        clock.advance(Duration.ofHours(24).plusMillis(1));
        int issueTimeEnd = payload.indexOf(':', namespace.length() + 1);
        String edited = Base64Url.encode(namespace + ":" + clock.millis() + payload.substring(issueTimeEnd));

        AwsException error = assertThrows(AwsException.class,
                () -> service.listExecutions(ARN, null, 1, edited));
        assertEquals("InvalidToken", error.getErrorCode());
    }

    @Test
    void changingATokensScopeCannotAuthorizeAnotherStateMachine() {
        String token = service.listExecutions(ARN, null, 1, null).nextToken();
        String otherArn = ARN + "-other";

        AwsException error = assertThrows(AwsException.class,
                () -> service.listExecutions(otherArn, null, 1, token));
        assertEquals("InvalidToken", error.getErrorCode());
    }

    @Test
    void continuationTokenIssuedInTheFutureIsRejected() {
        String token = service.listExecutions(ARN, null, 1, null).nextToken();

        clock.advance(Duration.ofMillis(-1));
        AwsException error = assertThrows(AwsException.class,
                () -> service.listExecutions(ARN, null, 1, token));
        assertEquals("InvalidToken", error.getErrorCode());
    }

    @Test
    void malformedContinuationTokenIsRejected() {
        String scope = "states:executions:" + ACCOUNT + ":" + ARN + ":*:";
        String malformed = Base64Url.encode(scope + "not-a-time:cursor");

        AwsException error = assertThrows(AwsException.class,
                () -> service.listExecutions(ARN, null, 1, malformed));
        assertEquals("InvalidToken", error.getErrorCode());
    }

    @Test
    void listExecutionsAcceptsTokensUpToThreeThousandNinetySixCharacters() {
        for (int length : List.of(1025, 3096)) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.listExecutions(ARN, null, 1, "x".repeat(length)));
            assertEquals("InvalidToken", error.getErrorCode());
        }

        AwsException tooLong = assertThrows(AwsException.class,
                () -> service.listExecutions(ARN, null, 1, "x".repeat(3097)));
        assertEquals("ValidationException", tooLong.getErrorCode());
    }

    @Test
    void appliesStatusFilterBeforePaging() {
        PaginatedResult<StepFunctionsService.ExecutionSnapshot> first = service.listExecutions(
                ARN, "SUCCEEDED", 1, null);
        assertEquals(List.of("new"), first.items().stream().map(snapshot -> snapshot.execution().getName())
                .toList());
        assertNotNull(first.nextToken());

        PaginatedResult<StepFunctionsService.ExecutionSnapshot> second = service.listExecutions(
                ARN, "SUCCEEDED", 1, first.nextToken());
        assertEquals(List.of("old"), second.items().stream().map(snapshot -> snapshot.execution().getName())
                .toList());
    }

    @Test
    void rejectsATokenIssuedForAnotherStateMachineOrFilter() {
        String token = service.listExecutions(ARN, null, 1, null).nextToken();
        assertThrows(AwsException.class, () -> service.listExecutions(
                ARN + "-other", null, 1, token));
        assertThrows(AwsException.class, () -> service.listExecutions(ARN, "SUCCEEDED", 1, token));
    }

    @Test
    void responseStatusIsTheSnapshotUsedByTheFilter() {
        Execution changing = mock(Execution.class);
        when(changing.getExecutionArn()).thenReturn(ARN + ":changing");
        when(changing.getStateMachineArn()).thenReturn(ARN);
        when(changing.getName()).thenReturn("changing");
        when(changing.getStatus()).thenReturn("RUNNING", "SUCCEEDED");
        when(changing.getStartDate()).thenReturn(4.0);
        executionStore.put(ARN + ":changing", changing);

        StepFunctionsJsonHandler handler = new StepFunctionsJsonHandler(service, new ObjectMapper(), Clock.systemUTC());
        ObjectNode request = new ObjectMapper().createObjectNode()
                .put("stateMachineArn", ARN)
                .put("statusFilter", "RUNNING");
        JsonNode response = (JsonNode) handler.handle("ListExecutions", request, "us-east-1").getEntity();

        JsonNode changingResponse = response.path("executions").get(0);
        assertEquals("changing", changingResponse.path("name").asText());
        assertEquals("RUNNING", changingResponse.path("status").asText());
    }

    @Test
    void ordersTerminalExecutionsByStopDateAndResumesStableCursors() {
        String orderedArn = "arn:aws:states:us-east-1:" + ACCOUNT + ":stateMachine:ordered";
        Execution terminalA = execution(orderedArn, "terminal-a", 3.0, 5.0);
        Execution terminalB = execution(orderedArn, "terminal-b", 1.0, 5.0);
        Execution terminalC = execution(orderedArn, "terminal-c", 9.0, 4.0);
        executionStore.put(terminalA.getExecutionArn(), terminalA);
        executionStore.put(terminalB.getExecutionArn(), terminalB);
        executionStore.put(terminalC.getExecutionArn(), terminalC);
        doReturn(List.of(terminalB, terminalA, terminalC)).when(executionStore).scan(any());

        PaginatedResult<StepFunctionsService.ExecutionSnapshot> first = service.listExecutions(
                orderedArn, null, 1, null);
        assertEquals("terminal-a", first.items().get(0).execution().getName());
        assertNotNull(first.nextToken());

        PaginatedResult<StepFunctionsService.ExecutionSnapshot> second = service.listExecutions(
                orderedArn, null, 1, first.nextToken());
        assertEquals("terminal-b", second.items().get(0).execution().getName());
        assertNotNull(second.nextToken());

        PaginatedResult<StepFunctionsService.ExecutionSnapshot> third = service.listExecutions(
                orderedArn, null, 1, second.nextToken());
        assertEquals("terminal-c", third.items().get(0).execution().getName());
        assertNull(third.nextToken());
    }

    private Execution execution(String stateMachineArn, String name, double startDate, double stopDate) {
        Execution execution = new Execution();
        execution.setExecutionArn(stateMachineArn + ":execution:" + name);
        execution.setStateMachineArn(stateMachineArn);
        execution.setName(name);
        execution.setStatus("SUCCEEDED");
        execution.setStartDate(startDate);
        execution.setStopDate(stopDate);
        return execution;
    }

    private void put(String name, double startDate, String status) {
        Execution execution = new Execution();
        execution.setExecutionArn(ARN + ":" + name);
        execution.setStateMachineArn(ARN);
        execution.setName(name);
        execution.setStatus(status);
        execution.setStartDate(startDate);
        executionStore.put(execution.getExecutionArn(), execution);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
