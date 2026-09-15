package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

/**
 * The request mapping template produced a document the data source cannot execute (missing
 * operation, missing key, wrong field type). The runtime reports it as a {@code MappingTemplate}
 * error and does not evaluate the response template, mirroring AWS.
 */
public class InvalidRequestDocumentException extends RuntimeException {

    public InvalidRequestDocumentException(String message) {
        super(message);
    }
}
