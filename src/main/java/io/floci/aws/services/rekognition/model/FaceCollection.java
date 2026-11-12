package io.floci.aws.services.rekognition.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public record FaceCollection(String id, String arn, String modelVersion, double creationTimestamp,
                             Map<String, String> tags) {

    @RegisterForReflection
    public record IndexedFace(String imageKey, String position, ObjectNode face, ObjectNode detail) {}
}
