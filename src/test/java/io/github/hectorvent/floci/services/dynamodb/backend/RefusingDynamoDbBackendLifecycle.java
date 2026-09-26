package io.github.hectorvent.floci.services.dynamodb.backend;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** A selected backend that starts nothing and refuses every emulator reset. */
@Alternative
@ApplicationScoped
public class RefusingDynamoDbBackendLifecycle implements DynamoDbBackendLifecycle {

    @Override
    public void start() {
    }

    @Override
    public void checkReset() {
        throw new AwsException("ResourceInUseException", "The DynamoDB backend refuses an emulator reset", 409);
    }

    @Override
    public void beforeReset() {
        throw new IllegalStateException("beforeReset ran after a refused preflight");
    }

    @Override
    public void reset() {
        throw new IllegalStateException("reset ran after a refused preflight");
    }

    @Override
    public void afterReset() {
        throw new IllegalStateException("afterReset ran after a refused preflight");
    }

    @Override
    public void stop() {
    }
}
