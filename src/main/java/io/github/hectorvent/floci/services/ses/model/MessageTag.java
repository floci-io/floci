package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Represents one entry of an AWS SES {@code EmailTags} / {@code DefaultEmailTags} /
 * {@code ReplacementTags} list — also known as a {@code MessageTag} in the SES API.
 * Used to attach name/value pairs to a single sent message for analytics and event
 * publishing (the values surface in {@code mail.tags} on published events).
 *
 * <p>Distinct from {@link Tag}, which models the resource tags applied to a
 * {@code ConfigurationSet} / {@code EmailTemplate} / {@code Identity} via the
 * {@code TagResource} / {@code UntagResource} APIs — those use {@code Key}/{@code Value},
 * not {@code Name}/{@code Value}.
 */
@RegisterForReflection
public record MessageTag(
        @JsonProperty("Name") String name,
        @JsonProperty("Value") String value) {
}
