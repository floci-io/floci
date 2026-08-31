package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InputDataConfig(
        @JsonProperty("s3InputDataConfig") S3InputDataConfig s3InputDataConfig
) {
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record S3InputDataConfig(
            @JsonProperty("s3Uri") String s3Uri,
            @JsonProperty("s3InputFormat") String s3InputFormat,
            @JsonProperty("s3BucketOwner") String s3BucketOwner
    ) {
        public S3InputDataConfig(String s3Uri) {
            this(s3Uri, "JSONL", null);
        }
    }
}
