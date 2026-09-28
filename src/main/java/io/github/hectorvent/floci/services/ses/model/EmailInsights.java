package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The per-recipient timeline returned by {@code GetMessageInsights}. One entry per envelope
 * recipient, carrying the events Floci derived for that address at send time.
 *
 * <p>{@code isp} is always {@code UNKNOWN_ISP}. Real SES reports the receiving provider it observed
 * and falls back to that literal when it cannot identify one (probe-confirmed 2026-09-21), which is
 * permanently Floci's situation, so the AWS-faithful value is also the honest one here.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmailInsights(
        @JsonProperty("Destination") String destination,
        @JsonProperty("Isp") String isp,
        @JsonProperty("Events") List<InsightsEvent> events) {
}
