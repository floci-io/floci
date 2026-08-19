package io.github.hectorvent.floci.services.ses.model;

/**
 * The SES V2 {@code SendEmail} {@code ListManagementOptions}: the contact list a send is scoped to
 * and an optional topic. When present, opted-out contacts on the named list are suppressed from the
 * send. This is a request-only transport value (never persisted or serialized), so it carries no
 * Jackson or reflection annotations.
 */
public record ListManagementOptions(String contactListName, String topicName) {
}
