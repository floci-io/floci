package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Correlates a CloudFormation custom-resource Lambda invocation with the response the
 * Lambda PUTs back to its {@code ResponseURL}.
 *
 * <p>The AWS custom-resource protocol is callback-based: the backing Lambda does not return
 * its result, it HTTP PUTs a response document to a pre-signed {@code ResponseURL}. Floci hands
 * the Lambda a {@code ResponseURL} pointing at {@link CfnResponseController}, which feeds the
 * body back here keyed by a per-invocation token.
 *
 * <p>Because Floci invokes the Lambda synchronously (RequestResponse) and the handler PUTs
 * before returning, the future is normally already completed by the time the provisioner awaits
 * it — the timeout only guards a PUT that lands just after the container returns.
 */
@ApplicationScoped
public class CustomResourceResponseStore {

    private static final Logger LOG = Logger.getLogger(CustomResourceResponseStore.class);

    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** Registers a fresh token and returns it. Call before invoking the Lambda. */
    public String register() {
        String token = UUID.randomUUID().toString();
        pending.put(token, new CompletableFuture<>());
        return token;
    }

    /** Completes the future for {@code token} with the PUT body. No-op for unknown/expired tokens. */
    public void complete(String token, JsonNode response) {
        CompletableFuture<JsonNode> future = pending.get(token);
        if (future != null) {
            future.complete(response);
        } else {
            LOG.debugv("Received custom-resource response for unknown token {0}", token);
        }
    }

    /**
     * Waits up to {@code timeout} for the Lambda's response, then discards the token.
     *
     * @throws TimeoutException if no response arrives in time
     */
    public JsonNode await(String token, Duration timeout) throws TimeoutException {
        CompletableFuture<JsonNode> future = pending.get(token);
        if (future == null) {
            throw new IllegalStateException("No pending custom-resource token: " + token);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Interrupted awaiting custom-resource response", e);
        } finally {
            pending.remove(token);
        }
    }
}
