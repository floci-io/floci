package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExperimentTemplateOptions {
    private String accountTargeting;
    private String emptyTargetResolutionMode;

    public ExperimentTemplateOptions() {
    }

    public String getAccountTargeting() {
        return accountTargeting;
    }

    public void setAccountTargeting(String accountTargeting) {
        this.accountTargeting = accountTargeting;
    }

    public String getEmptyTargetResolutionMode() {
        return emptyTargetResolutionMode;
    }

    public void setEmptyTargetResolutionMode(String emptyTargetResolutionMode) {
        this.emptyTargetResolutionMode = emptyTargetResolutionMode;
    }
}
