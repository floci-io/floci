package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InputTransformer {

    private Map<String, String> inputPathsMap = new HashMap<>();
    private String inputTemplate;

    public InputTransformer() {}

    public InputTransformer(Map<String, String> inputPathsMap, String inputTemplate) {
        this.inputPathsMap = inputPathsMap != null ? inputPathsMap : new HashMap<>();
        this.inputTemplate = inputTemplate;
    }

    public static InputTransformer fromJson(JsonNode transformerNode) {
        if (transformerNode == null || transformerNode.isMissingNode() || !transformerNode.isObject()) {
            return null;
        }
        Map<String, String> pathsMap = new HashMap<>();
        JsonNode pathsNode = transformerNode.path("InputPathsMap");
        if (pathsNode.isObject()) {
            pathsNode.fields().forEachRemaining(e -> pathsMap.put(e.getKey(), e.getValue().asText()));
        }
        return new InputTransformer(pathsMap, transformerNode.path("InputTemplate").asText(null));
    }

    public Map<String, String> getInputPathsMap() { return inputPathsMap; }
    public void setInputPathsMap(Map<String, String> inputPathsMap) {
        this.inputPathsMap = inputPathsMap != null ? inputPathsMap : new HashMap<>();
    }

    public String getInputTemplate() { return inputTemplate; }
    public void setInputTemplate(String inputTemplate) { this.inputTemplate = inputTemplate; }
}
