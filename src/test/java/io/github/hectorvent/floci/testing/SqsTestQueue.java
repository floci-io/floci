package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * An SQS queue a test creates in one account and Region, typically as a destination, and then waits
 * on for one JSON message. Every call runs in that account, so it works from a thread without a
 * request context.
 */
public final class SqsTestQueue {

    /** Longer than any test, so a received message never comes back and fails a later check. */
    private static final int VISIBILITY_TIMEOUT_SECONDS = 300;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SqsService sqsService;
    private final String account;
    private final String region;
    private final String url;

    private SqsTestQueue(SqsService sqsService, String account, String region, String url) {
        this.sqsService = sqsService;
        this.account = account;
        this.region = region;
        this.url = url;
    }

    /** Creates a queue named {@code namePrefix} plus a random suffix. */
    public static SqsTestQueue create(SqsService sqsService, String account, String region, String namePrefix) {
        String name = namePrefix + UUID.randomUUID().toString().substring(0, 8);
        String url = RequestScopes.callAs(account, () -> sqsService.createQueue(name, Map.of(), region).getQueueUrl());
        return new SqsTestQueue(sqsService, account, region, url);
    }

    public String url() {
        return url;
    }

    public String arn() {
        return RequestScopes.callAs(account, () ->
                sqsService.getQueueAttributes(url, List.of("QueueArn"), region).get("QueueArn"));
    }

    /** Sets the queue's {@code Policy} attribute. */
    public void setPolicy(String policy) {
        RequestScopes.runAs(account, () -> sqsService.setQueueAttributes(url, Map.of("Policy", policy), region));
    }

    /** Grants {@code action} to the root of {@code grantedAccount}, as AddPermission writes it into the policy. */
    public void addPermission(String label, String grantedAccount, String action) {
        RequestScopes.runAs(account, () ->
                sqsService.addPermission(url, label, List.of(grantedAccount), List.of(action), region));
    }

    /** At most one message, received without waiting. */
    public List<Message> receive() {
        return RequestScopes.callAs(account, () ->
                sqsService.receiveMessage(url, 1, VISIBILITY_TIMEOUT_SECONDS, 0, region));
    }

    /** Polls until a message arrives, and returns its body parsed as JSON. */
    public JsonNode awaitJson(Duration timeout) throws Exception {
        List<Message> messages = await("a message in " + url)
                .atMost(timeout)
                .pollInterval(POLL_INTERVAL)
                .until(this::receive, received -> !received.isEmpty());
        return MAPPER.readTree(messages.get(0).getBody());
    }

    public void delete() {
        RequestScopes.runAs(account, () -> sqsService.deleteQueue(url, region));
    }
}
