package io.github.hectorvent.floci.services.kms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The wrapping material GetParametersForImport issued for one KMS key, held until
 * ImportKeyMaterial consumes it.
 *
 * <p>It lives on the key rather than in a store of its own because KMS scopes an import
 * token to a single key and lets only the most recent one work: a second
 * GetParametersForImport call replaces these parameters, which invalidates the token the
 * first call handed out.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KmsImportParameters {

    private String wrappingPrivateKeyEncoded;
    private String wrappingAlgorithm;
    private String importToken;
    private long parametersValidTo;

    public String getWrappingPrivateKeyEncoded() { return wrappingPrivateKeyEncoded; }
    public void setWrappingPrivateKeyEncoded(String wrappingPrivateKeyEncoded) {
        this.wrappingPrivateKeyEncoded = wrappingPrivateKeyEncoded;
    }

    public String getWrappingAlgorithm() { return wrappingAlgorithm; }
    public void setWrappingAlgorithm(String wrappingAlgorithm) { this.wrappingAlgorithm = wrappingAlgorithm; }

    public String getImportToken() { return importToken; }
    public void setImportToken(String importToken) { this.importToken = importToken; }

    public long getParametersValidTo() { return parametersValidTo; }
    public void setParametersValidTo(long parametersValidTo) { this.parametersValidTo = parametersValidTo; }
}
