package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * One parameter of a delegation request's policy template, the {@code PolicyParameter} shape.
 *
 * <p>Every member is optional in the model, including the name, so nothing here is required for a
 * request to be accepted.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyParameter {

    /** {@code policyParameterTypeType}: the two value shapes a parameter may carry. */
    public static final String TYPE_STRING = "string";
    public static final String TYPE_STRING_LIST = "stringList";

    private String name;
    private String type;
    private List<String> values = new ArrayList<>();

    public PolicyParameter() {}

    public PolicyParameter(String name, String type, List<String> values) {
        this.name = name;
        this.type = type;
        setValues(values);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<String> getValues() { return values; }

    public void setValues(List<String> values) {
        this.values = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
