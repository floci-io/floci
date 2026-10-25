package io.github.hectorvent.floci.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetApplicationResponse {

    // The application is stored with the clientToken CreateApplication's retries are matched on, which
    // GetApplication's Application does not have. It is left out here, not on Application, so storage keeps it.
    @JsonIgnoreProperties("clientToken")
    private Application application;

    public Application getApplication() {
        return application;
    }

    public void setApplication(Application application) {
        this.application = application;
    }
}
