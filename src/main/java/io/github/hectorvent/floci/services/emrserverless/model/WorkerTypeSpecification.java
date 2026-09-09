package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class WorkerTypeSpecification {
    private ImageConfiguration imageConfiguration;

    public ImageConfiguration getImageConfiguration() { return imageConfiguration; }
    public void setImageConfiguration(ImageConfiguration imageConfiguration) { this.imageConfiguration = imageConfiguration; }
}
