package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregationAuthorization(
        @JsonProperty("AggregationAuthorizationArn") String aggregationAuthorizationArn,
        @JsonProperty("AuthorizedAccountId") String authorizedAccountId,
        @JsonProperty("AuthorizedAwsRegion") String authorizedAwsRegion,
        @JsonProperty("CreationTime") Long creationTime) {
}
