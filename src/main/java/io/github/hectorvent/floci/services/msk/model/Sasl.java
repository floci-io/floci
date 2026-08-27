package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Sasl {

    @JsonProperty("scram")
    private Scram scram;

    @JsonProperty("iam")
    private Iam iam;

    public Sasl() {}

    public Scram getScram() { return scram; }
    public void setScram(Scram scram) { this.scram = scram; }

    public Iam getIam() { return iam; }
    public void setIam(Iam iam) { this.iam = iam; }
}