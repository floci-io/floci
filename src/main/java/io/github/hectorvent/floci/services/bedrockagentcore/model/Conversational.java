package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** One conversational turn: a role and its content. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Conversational {
    @JsonProperty("content")
    private Content content;
    @JsonProperty("role")
    private String role;

    public Conversational() {}

    public Content getContent() { return content; }
    public void setContent(Content content) { this.content = content; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
