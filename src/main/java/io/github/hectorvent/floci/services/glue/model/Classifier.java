package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Classifier {
    @JsonProperty("GrokClassifier")
    private Map<String, Object> grokClassifier;

    @JsonProperty("XMLClassifier")
    private Map<String, Object> xmlClassifier;

    @JsonProperty("JsonClassifier")
    private Map<String, Object> jsonClassifier;

    @JsonProperty("CsvClassifier")
    private Map<String, Object> csvClassifier;

    public Classifier() {}

    public Map<String, Object> getGrokClassifier() { return grokClassifier; }
    public void setGrokClassifier(Map<String, Object> grokClassifier) { this.grokClassifier = grokClassifier; }

    public Map<String, Object> getXmlClassifier() { return xmlClassifier; }
    public void setXmlClassifier(Map<String, Object> xmlClassifier) { this.xmlClassifier = xmlClassifier; }

    public Map<String, Object> getJsonClassifier() { return jsonClassifier; }
    public void setJsonClassifier(Map<String, Object> jsonClassifier) { this.jsonClassifier = jsonClassifier; }

    public Map<String, Object> getCsvClassifier() { return csvClassifier; }
    public void setCsvClassifier(Map<String, Object> csvClassifier) { this.csvClassifier = csvClassifier; }

    public String selectedKind() {
        if (grokClassifier != null) {
            return "GrokClassifier";
        }
        if (xmlClassifier != null) {
            return "XMLClassifier";
        }
        if (jsonClassifier != null) {
            return "JsonClassifier";
        }
        if (csvClassifier != null) {
            return "CsvClassifier";
        }
        return null;
    }

    public int selectedKindCount() {
        int count = 0;
        count += grokClassifier == null ? 0 : 1;
        count += xmlClassifier == null ? 0 : 1;
        count += jsonClassifier == null ? 0 : 1;
        count += csvClassifier == null ? 0 : 1;
        return count;
    }

    public Map<String, Object> selectedDetails() {
        return switch (selectedKind()) {
            case "GrokClassifier" -> grokClassifier;
            case "XMLClassifier" -> xmlClassifier;
            case "JsonClassifier" -> jsonClassifier;
            case "CsvClassifier" -> csvClassifier;
            case null -> null;
            default -> throw new IllegalStateException("Unknown classifier kind");
        };
    }

    public String name() {
        Map<String, Object> details = selectedDetails();
        Object name = details == null ? null : details.get("Name");
        return name instanceof String value ? value : null;
    }

    public Classifier copyWithDetails(Map<String, Object> details) {
        Classifier copy = new Classifier();
        Map<String, Object> copiedDetails = new LinkedHashMap<>(details);
        switch (selectedKind()) {
            case "GrokClassifier" -> copy.setGrokClassifier(copiedDetails);
            case "XMLClassifier" -> copy.setXmlClassifier(copiedDetails);
            case "JsonClassifier" -> copy.setJsonClassifier(copiedDetails);
            case "CsvClassifier" -> copy.setCsvClassifier(copiedDetails);
            case null -> throw new IllegalStateException("Classifier kind is required");
            default -> throw new IllegalStateException("Unknown classifier kind");
        }
        return copy;
    }
}
