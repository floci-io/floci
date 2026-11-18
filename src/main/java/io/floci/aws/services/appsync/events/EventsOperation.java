package io.floci.aws.services.appsync.events;

/** The Events operation a request is authorized for, as a Lambda authorizer receives it. */
public enum EventsOperation {
    EVENT_CONNECT,
    EVENT_SUBSCRIBE,
    EVENT_PUBLISH
}
