package io.github.hectorvent.floci.services.msk.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class MskScramAssociations {

    private Map<String, ScramSecretAssociation> associations = new LinkedHashMap<>();

    public Map<String, ScramSecretAssociation> getAssociations() {
        return associations;
    }

    public void setAssociations(Map<String, ScramSecretAssociation> associations) {
        this.associations = associations;
    }
}
