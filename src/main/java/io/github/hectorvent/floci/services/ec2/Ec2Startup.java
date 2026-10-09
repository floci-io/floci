package io.github.hectorvent.floci.services.ec2;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

@ApplicationScoped
public class Ec2Startup {

    private final Ec2Service service;

    @Inject
    public Ec2Startup(Ec2Service service) {
        this.service = service;
    }

    // Run after the lifecycle's boot hooks and persistent-path validation.
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 600) StartupEvent ignored) {
        service.seedDefaultRegionIfEnabled();
    }
}
