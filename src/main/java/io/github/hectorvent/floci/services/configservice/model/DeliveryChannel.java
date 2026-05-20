package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryChannel(
        @JsonProperty("name") String name,
        @JsonProperty("s3BucketName") String s3BucketName,
        @JsonProperty("s3KeyPrefix") String s3KeyPrefix,
        @JsonProperty("s3KmsKeyArn") String s3KmsKeyArn,
        @JsonProperty("snsTopicARN") String snsTopicARN,
        @JsonProperty("configSnapshotDeliveryProperties") ConfigSnapshotDeliveryProperties configSnapshotDeliveryProperties) {
}
