package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** UpdateApplication's answer: "Information about the updated application", as GetApplication reports it. */
@RegisterForReflection
public class UpdateApplicationResponse {

    // Without the clientToken storage keeps, as GetApplicationResponse leaves it out.
    @JsonIgnoreProperties("clientToken")
    private Application application;

    public Application getApplication() {
        return application;
    }

    public void setApplication(Application application) {
        this.application = application;
    }
}
