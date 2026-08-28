package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The await budget is an IDLE timeout, not a total one.
 *
 * <p>A CDK provider-framework waiter drives {@code framework.isComplete} on a fixed cadence and only
 * PUTs to the ResponseURL once the underlying work finishes. How long that takes is a function of
 * the work, not of the emulator: {@code Custom::CreateOrganizationAccounts} creates exactly one
 * account per poll, so a 15-account org needs ~15 polls where a 6-account org needs ~3. A total-time
 * budget therefore has to be re-tuned every time a config set grows, and when it is exceeded the
 * Lambda is guillotined mid-success — the accounts exist, but the resource reports CREATE_FAILED and
 * the stack rolls back.
 *
 * <p>Measuring idleness instead makes the budget proportional to the work without the CloudFormation
 * layer needing to know what the work IS: every poll is liveness, so progress buys unbounded
 * wall-clock while a genuinely hung resource still dies on schedule.
 */
class CustomResourceResponseStoreTest {

    private final CustomResourceResponseStore store = new CustomResourceResponseStore(
            new ProviderFrameworkDetector(mock(LambdaService.class)));
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode success() {
        ObjectNode node = mapper.createObjectNode();
        node.put("Status", "SUCCESS");
        return node;
    }

    /** Polls {@code count} times at 100ms, then optionally PUTs. Mirrors the waiter's cadence. */
    private Thread waiterPolling(String token, int count, boolean thenComplete) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    Thread.sleep(100);
                    store.touch(token);
                }
                if (thenComplete) {
                    store.complete(token, success());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void awaitOutlivesItsBudgetWhileTouchesKeepArriving() throws Exception {
        String token = store.register();
        // ~900ms of polling against a 300ms budget: three times over, the way 15 accounts run three
        // times past a budget sized for 6.
        Thread waiter = waiterPolling(token, 9, true);

        JsonNode response = store.await(token, Duration.ofMillis(300));

        assertEquals("SUCCESS", response.get("Status").asText());
        waiter.join(2_000);
    }

    @Test
    void awaitStillTimesOutOnceTheTouchesStop() throws Exception {
        String token = store.register();
        // Three polls, then the resource hangs. The budget must still fire from the LAST poll.
        Thread waiter = waiterPolling(token, 3, false);

        assertThrows(TimeoutException.class, () -> store.await(token, Duration.ofMillis(300)));
        waiter.join(2_000);
    }

    @Test
    void awaitTimesOutWhenNothingEverHappens() {
        String token = store.register();

        assertThrows(TimeoutException.class, () -> store.await(token, Duration.ofMillis(150)));
    }

    @Test
    void awaitReturnsAResponseThatArrivedBeforeTheBudgetWasChecked() throws Exception {
        // A synchronous handler PUTs from inside the invoke the provisioner is blocked on, so its
        // response can already be here by the time await is entered. Whatever the clock says, a
        // response that arrived is a response — the future outranks the budget.
        String token = store.register();
        store.complete(token, success());

        JsonNode response = store.await(token, Duration.ZERO);

        assertEquals("SUCCESS", response.get("Status").asText());
    }

    @Test
    void awaitBudgetsIdlenessFromWhenTheCallerStartsWaitingNotFromRegistration() throws Exception {
        String token = store.register();
        // The token is registered before the invoke, and a slow handler — a cold container start, or
        // two of them nested — can burn longer than the whole budget before await is ever reached.
        // That is the handler working, not the resource going idle, so it must not be charged here.
        Thread.sleep(400);

        Thread waiter = new Thread(() -> {
            try {
                Thread.sleep(100);
                store.complete(token, success());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.setDaemon(true);
        waiter.start();

        JsonNode response = store.await(token, Duration.ofMillis(300));

        assertEquals("SUCCESS", response.get("Status").asText());
        waiter.join(2_000);
    }

    @Test
    void touchForAnUnknownTokenIsIgnored() {
        // Polls can outlive their await (timeout, rollback). Liveness for a token nobody is waiting
        // on is not an error — it is the normal tail of a resource that already failed.
        assertDoesNotThrow(() -> store.touch("no-such-token"));
    }

    @Test
    void toleratedWaitSliceTimeoutIsLogged() throws Exception {
        // Every slice of the poll loop below the outer budget hits an internal TimeoutException that
        // is intentionally swallowed and retried. Per the repo's logging convention, a caught
        // exception that is deliberately tolerated must still be logged (at trace level, since this
        // is the normal, expected tail of every wait) rather than disappearing silently.
        java.util.logging.Logger julLogger =
                java.util.logging.Logger.getLogger(CustomResourceResponseStore.class.getName());
        julLogger.setLevel(java.util.logging.Level.ALL);
        java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(java.util.logging.Level.ALL);
        julLogger.addHandler(handler);
        try {
            String token = store.register();
            Thread waiter = waiterPolling(token, 9, true);

            store.await(token, Duration.ofMillis(300));
            waiter.join(2_000);
        } finally {
            julLogger.removeHandler(handler);
        }

        assertTrue(records.stream().anyMatch(r -> r.getMessage() != null
                        && r.getMessage().toLowerCase().contains("wait slice")),
                "expected a log record for a tolerated wait-slice timeout, got: " + records);
    }
}
