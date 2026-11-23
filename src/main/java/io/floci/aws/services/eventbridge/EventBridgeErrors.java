package io.floci.aws.services.eventbridge;

import io.floci.aws.core.common.AwsException;

/**
 * The client errors EventBridge words one way for every operation that runs into the same
 * condition, so a missing rule, bus, archive, replay, connection or API destination reads the same
 * whichever call found it.
 */
final class EventBridgeErrors {

    private EventBridgeErrors() {
    }

    static AwsException ruleNotFound(String rule, String bus) {
        return notFound("Rule " + rule + " does not exist on EventBus " + bus + ".");
    }

    static AwsException busNotFound(String bus) {
        return notFound("Event bus " + bus + " does not exist.");
    }

    static AwsException archiveNotFound(String archive) {
        return notFound("Archive " + archive + " does not exist.");
    }

    static AwsException replayNotFound(String replay) {
        return notFound("Replay " + replay + " does not exist.");
    }

    /** {@code verb} is the failing call: create, describe, update, delete or deauthorize. */
    static AwsException connectionNotFound(String verb, String connection) {
        return notFound("Failed to " + verb + " the connection(s). " + connectionMissing(connection));
    }

    static AwsException apiDestinationNotFound(String verb, String name) {
        return notFound("Failed to " + verb + " the api-destination(s). An api-destination '" + name
                + "' does not exist.");
    }

    /** An API destination write that names a connection ARN no connection answers to. */
    static AwsException apiDestinationConnectionNotFound(String verb, String connectionArn) {
        return notFound("Failed to " + verb + " the api-destination(s). " + connectionMissing(connectionArn));
    }

    /** An internal lookup by ARN, whose message reaches the dead-letter queue; unquoted, unlike the API's. */
    static AwsException plainConnectionNotFound(String connectionArn) {
        return notFound("Connection " + connectionArn + " does not exist.");
    }

    static AwsException busAlreadyExists(String bus) {
        return alreadyExists("Event bus " + bus + " already exists.");
    }

    static AwsException archiveAlreadyExists(String archive) {
        return alreadyExists("Archive " + archive + " already exists.");
    }

    static AwsException replayAlreadyExists(String replay) {
        return alreadyExists("Replay " + replay + " already exists.");
    }

    static AwsException connectionAlreadyExists(String connection) {
        return alreadyExists("Failed to create the connection(s). A connection with name '" + connection
                + "' already exists.");
    }

    static AwsException replayNotCancellable(String replay) {
        return new AwsException("IllegalStatusException",
                "Replay " + replay + " is not in a valid state for this operation.", 400);
    }

    static AwsException cannotDeleteDefaultBus() {
        return validation("Cannot delete event bus default.");
    }

    static AwsException busHasRules() {
        return validation("EventBus can't be deleted since it has rules.");
    }

    static AwsException ruleHasTargets() {
        return validation("Rule can't be deleted since it has targets.");
    }

    static AwsException replaySourceArchiveNotFound(String archive) {
        return validation("Parameter EventSourceArn is not valid. Reason: Archive " + archive
                + " does not exist.");
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static String connectionMissing(String connection) {
        return "Connection '" + connection + "' does not exist.";
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException alreadyExists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 400);
    }
}
