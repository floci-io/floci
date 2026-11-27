package io.floci.aws.services.kinesisvideo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record KinesisVideoStream(
        String streamName,
        String streamArn,
        String deviceName,
        String mediaType,
        String kmsKeyId,
        String version,
        String status,
        long creationTimeMillis,
        int dataRetentionInHours,
        Map<String, String> tags) {
}
