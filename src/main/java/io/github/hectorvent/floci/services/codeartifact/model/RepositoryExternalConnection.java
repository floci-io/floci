package io.github.hectorvent.floci.services.codeartifact.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** An external connection from a CodeArtifact repository to a public package repository. */
@RegisterForReflection
public class RepositoryExternalConnection {

    private String externalConnectionName;
    private String packageFormat;
    private String status;

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
