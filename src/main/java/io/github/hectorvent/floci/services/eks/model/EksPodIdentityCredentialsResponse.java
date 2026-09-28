package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EksPodIdentityCredentialsResponse(
        @JsonProperty("AccessKeyId") String accessKeyId,
        @JsonProperty("SecretAccessKey") String secretAccessKey,
        @JsonProperty("Token") String token,
        @JsonProperty("AccountId") String accountId,
        @JsonProperty("Expiration") String expiration
) {}
