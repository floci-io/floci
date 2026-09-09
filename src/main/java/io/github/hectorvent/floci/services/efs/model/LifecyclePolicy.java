package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class LifecyclePolicy {
    private String transitionToIA;
    private String transitionToPrimaryStorageClass;
    private String transitionToArchive;
    public String getTransitionToIA() { return transitionToIA; }
    public void setTransitionToIA(String transitionToIA) { this.transitionToIA = transitionToIA; }
    public String getTransitionToPrimaryStorageClass() { return transitionToPrimaryStorageClass; }
    public void setTransitionToPrimaryStorageClass(String transitionToPrimaryStorageClass) { this.transitionToPrimaryStorageClass = transitionToPrimaryStorageClass; }
    public String getTransitionToArchive() { return transitionToArchive; }
    public void setTransitionToArchive(String transitionToArchive) { this.transitionToArchive = transitionToArchive; }
}
