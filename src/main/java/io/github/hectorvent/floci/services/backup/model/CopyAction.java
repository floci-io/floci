package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CopyAction {

    @JsonProperty("DestinationBackupVaultArn")
    private String destinationBackupVaultArn;

    @JsonProperty("Lifecycle")
    private Lifecycle lifecycle;

    public CopyAction() {}

    public String getDestinationBackupVaultArn() { return destinationBackupVaultArn; }
    public void setDestinationBackupVaultArn(String destinationBackupVaultArn) { this.destinationBackupVaultArn = destinationBackupVaultArn; }

    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
}
