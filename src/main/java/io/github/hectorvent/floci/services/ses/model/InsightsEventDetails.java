package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code Details} member of a {@code GetMessageInsights} event. Only the member matching the
 * event type is populated, and an event with neither omits {@code Details} entirely.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InsightsEventDetails(
        @JsonProperty("Bounce") InsightsBounce bounce,
        @JsonProperty("Complaint") InsightsComplaint complaint) {

    public static InsightsEventDetails ofBounce(InsightsBounce bounce) {
        return new InsightsEventDetails(bounce, null);
    }

    public static InsightsEventDetails ofComplaint(InsightsComplaint complaint) {
        return new InsightsEventDetails(null, complaint);
    }
}
