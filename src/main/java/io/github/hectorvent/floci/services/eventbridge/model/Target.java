package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Target {

    private String id;
    private String arn;
    private String input;
    private String inputPath;
    private InputTransformer inputTransformer;

    public Target() {}

    public Target(String id, String arn, String input, String inputPath) {
        this.id = id;
        this.arn = arn;
        this.input = input;
        this.inputPath = inputPath;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getInputPath() { return inputPath; }
    public void setInputPath(String inputPath) { this.inputPath = inputPath; }

    public InputTransformer getInputTransformer() { return inputTransformer; }
    public void setInputTransformer(InputTransformer inputTransformer) { this.inputTransformer = inputTransformer; }
}
