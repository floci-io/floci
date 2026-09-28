package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code Complaint} member of a {@code GetMessageInsights} event's {@code Details}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InsightsComplaint(
        @JsonProperty("ComplaintSubType") String complaintSubType,
        @JsonProperty("ComplaintFeedbackType") String complaintFeedbackType) {
}
