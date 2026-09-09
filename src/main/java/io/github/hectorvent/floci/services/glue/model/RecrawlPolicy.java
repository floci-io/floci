package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class RecrawlPolicy {
    @JsonProperty("RecrawlBehavior")
    private String recrawlBehavior;

    public RecrawlPolicy() {}

    public String getRecrawlBehavior() { return recrawlBehavior; }
    public void setRecrawlBehavior(String recrawlBehavior) { this.recrawlBehavior = recrawlBehavior; }
}
