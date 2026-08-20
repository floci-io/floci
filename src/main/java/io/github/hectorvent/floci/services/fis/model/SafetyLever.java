package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SafetyLever {
    private String id;
    private String arn;
    private SafetyLeverState state;

    public SafetyLever() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public SafetyLeverState getState() {
        return state;
    }

    public void setState(SafetyLeverState state) {
        this.state = state;
    }
}
