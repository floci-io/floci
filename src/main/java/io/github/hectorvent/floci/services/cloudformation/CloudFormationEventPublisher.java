package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Publishes AWS-shaped {@code aws.cloudformation} "CloudFormation Stack Status Change" events to the
 * default EventBridge bus of the stack's account and Region, one per status recorded for a stack.
 *
 * <p>Unlike the other publishers this one only queues, and one daemon worker delivers in order. A
 * status can be recorded while the service holds the stack's lock in {@code stacks.compute}, and
 * delivery calls a rule's targets before it returns, so delivering in place would hold that lock and
 * the operation for as long as the targets take.
 *
 * <p>The queue is in memory. An event still in it when Floci stops is dropped, and counted in the log:
 * it is not sent on the next start, even where the stack itself is persisted.
 */
@ApplicationScoped
public class CloudFormationEventPublisher {

    private static final Logger LOG = Logger.getLogger(CloudFormationEventPublisher.class);
    static final String SOURCE = "aws.cloudformation";
    static final String DETAIL_TYPE = "CloudFormation Stack Status Change";
    private static final long STATUS_WRITE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private record StatusChange(Stack stack, String accountId, String statusWhenRecorded, String status,
                                String reason, String token) {
    }

    private final BlockingQueue<StatusChange> pending = new LinkedBlockingQueue<>();
    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private Thread worker;

    @Inject
    public CloudFormationEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void start() {
        worker = new Thread(this::deliverInOrder, "cloudformation-event-publisher");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        worker.interrupt();
    }

    /**
     * Queues one event for {@code status} and returns at once, so it is safe under the stack's lock.
     * The token is the one on the stack now, the operation that caused this status.
     */
    public void stackStatusChanged(Stack stack, String accountId, String status, String reason) {
        pending.add(new StatusChange(stack, accountId, stack.getStatus(), status, reason,
                stack.getClientRequestToken()));
    }

    private void deliverInOrder() {
        StatusChange held = null;
        try {
            while (true) {
                held = pending.take();
                awaitStatusWrite(held);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                deliver(held);
                held = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            int dropped = pending.size() + (held != null ? 1 : 0);
            if (dropped > 0) {
                LOG.warnv("Dropping {0} queued CloudFormation Stack Status Change event(s): Floci is stopping", dropped);
            }
        }
    }

    /**
     * A failed path records its event before it writes the status, the volatile publishing write, so
     * that a reader who sees the status also sees the event in DescribeStackEvents. A reader the bus
     * event wakes must see the status too, so delivery waits out the few statements between the two.
     * Bounded, since a status left unwritten must not hold up every later event.
     */
    private static void awaitStatusWrite(StatusChange change) throws InterruptedException {
        if (Objects.equals(change.statusWhenRecorded(), change.status())) {
            return;
        }
        long deadline = System.nanoTime() + STATUS_WRITE_WAIT_NANOS;
        while (Objects.equals(change.statusWhenRecorded(), change.stack().getStatus())
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
    }

    private void deliver(StatusChange change) {
        String stackId = change.stack().getStackId();
        String region = change.stack().getRegion();
        try {
            String detail = objectMapper.writeValueAsString(buildDetail(change));
            RequestScopes.runAs(change.accountId(), region,
                    () -> eventBridgeService.publishServiceEvent(SOURCE, DETAIL_TYPE, detail, stackId, region));
        } catch (Exception e) {
            LOG.warnv("Failed to emit CloudFormation Stack Status Change for {0}: {1}", stackId, e.getMessage());
        }
    }

    // As CloudFormation sent them on 2026-10-30: the key is absent, not empty, where there is no token,
    // and detailed-status and status-reason are present and empty where there is nothing to say.
    private ObjectNode buildDetail(StatusChange change) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("stack-id", change.stack().getStackId());
        if (change.token() != null && !change.token().isEmpty()) {
            detail.put("client-request-token", change.token());
        }
        ObjectNode statusDetails = detail.putObject("status-details");
        statusDetails.put("status", change.status());
        statusDetails.put("detailed-status", "");
        statusDetails.put("status-reason", change.reason() != null ? change.reason() : "");
        return detail;
    }
}
