package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The text of a conversational payload part. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Content {
    @JsonProperty("text")
    private String text;

    public Content() {}

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}
