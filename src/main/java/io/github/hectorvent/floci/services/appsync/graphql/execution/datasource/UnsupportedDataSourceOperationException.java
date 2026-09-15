package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

/**
 * The request document asks for an operation Floci has not implemented yet. This is an emulator
 * capability gap, not a data source error, so the runtime reports it as a terminal
 * {@code UnsupportedOperation} instead of letting the response template ignore it.
 */
public class UnsupportedDataSourceOperationException extends RuntimeException {

    public UnsupportedDataSourceOperationException(String message) {
        super(message);
    }
}
