package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The Data Catalog's security configuration, one per catalog. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataCatalogEncryptionSettings {
    @JsonProperty("EncryptionAtRest")
    private EncryptionAtRest encryptionAtRest;

    @JsonProperty("ConnectionPasswordEncryption")
    private ConnectionPasswordEncryption connectionPasswordEncryption;

    /** What a catalog reports before anything was ever put: both blocks present, both off. */
    public static DataCatalogEncryptionSettings defaults() {
        DataCatalogEncryptionSettings settings = new DataCatalogEncryptionSettings();
        EncryptionAtRest atRest = new EncryptionAtRest();
        atRest.setCatalogEncryptionMode("DISABLED");
        settings.encryptionAtRest = atRest;
        ConnectionPasswordEncryption passwords = new ConnectionPasswordEncryption();
        passwords.setReturnConnectionPasswordEncrypted(false);
        settings.connectionPasswordEncryption = passwords;
        return settings;
    }

    public EncryptionAtRest getEncryptionAtRest() { return encryptionAtRest; }
    public void setEncryptionAtRest(EncryptionAtRest encryptionAtRest) { this.encryptionAtRest = encryptionAtRest; }

    public ConnectionPasswordEncryption getConnectionPasswordEncryption() { return connectionPasswordEncryption; }
    public void setConnectionPasswordEncryption(ConnectionPasswordEncryption connectionPasswordEncryption) {
        this.connectionPasswordEncryption = connectionPasswordEncryption;
    }
}
