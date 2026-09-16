package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;

// A single-item call reports this as a plain ValidationException. A transaction cancels on it.
public class KeySchemaMismatchException extends AwsException {

    public KeySchemaMismatchException(String message) {
        super("ValidationException", message, 400);
    }
}
