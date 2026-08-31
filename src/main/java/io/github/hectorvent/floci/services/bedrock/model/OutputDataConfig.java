package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutputDataConfig(
        @JsonProperty("s3OutputDataConfig") S3OutputDataConfig s3OutputDataConfig
) {
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record S3OutputDataConfig(
            @JsonProperty("s3Uri") String s3Uri,
            @JsonProperty("s3BucketOwner") String s3BucketOwner,
            @JsonProperty("s3EncryptionKeyId") String s3EncryptionKeyId
    ) {
        public S3OutputDataConfig(String s3Uri) {
            this(s3Uri, null, null);
        }
    }
}
