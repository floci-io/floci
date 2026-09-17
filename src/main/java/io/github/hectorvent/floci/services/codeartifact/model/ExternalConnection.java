package io.github.hectorvent.floci.services.codeartifact.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalConnection {
    private String externalConnectionName;
    private String packageFormat;
    private String status;

    public ExternalConnection() {
    }

    public ExternalConnection(String externalConnectionName, String packageFormat, String status) {
        this.externalConnectionName = externalConnectionName;
        this.packageFormat = packageFormat;
        this.status = status;
    }

    public String getExternalConnectionName() {
        return externalConnectionName;
    }

    public void setExternalConnectionName(String externalConnectionName) {
        this.externalConnectionName = externalConnectionName;
    }

    public String getPackageFormat() {
        return packageFormat;
    }

    public void setPackageFormat(String packageFormat) {
        this.packageFormat = packageFormat;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
