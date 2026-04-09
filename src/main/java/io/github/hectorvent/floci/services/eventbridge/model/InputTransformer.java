package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    public Map<String, String> getInputPathsMap() { return inputPathsMap; }
    public void setInputPathsMap(Map<String, String> inputPathsMap) {
        this.inputPathsMap = inputPathsMap != null ? inputPathsMap : new HashMap<>();
    }

    public String getInputTemplate() { return inputTemplate; }
    public void setInputTemplate(String inputTemplate) { this.inputTemplate = inputTemplate; }
}
